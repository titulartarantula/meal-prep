"""Cart drafts: build in the app (building → ready), the user edits, then send to PC Express (→ sent).

Only `send_draft` touches PC Express carts (one create + one add). Matching runs product searches and AI
picks concurrently in a bounded thread pool; all DB writes stay on the calling thread's connection."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import logging
import math

import httpx
from psycopg.types.json import Jsonb

from . import bulk, db, planner
from .ingredients import item_key, split_prep
from .matcher import OUT_OF_STOCK, _choose
from .models import ListItem, Product

log = logging.getLogger(__name__)
MAX_ALTERNATIVES = 5
CHOICES_PAGE = 20          # chooser results per page (Loblaws adds its sponsored results to the first page)
HINT_SAVING = 1.0          # a remembered pick's line mentions a bigger pack that saves at least this much ($)
MAX_BOUGHT = 5             # products sent before for a line, fetched for the chooser when the search lacks them
BETTER_BY = 0.97           # a better value is at least 3 % cheaper per unit


class DraftNotFound(Exception):
    pass


class DraftStateError(Exception):
    pass


class UnknownProduct(Exception):
    pass


class DraftFailed(Exception):
    pass


class DraftStale(DraftStateError):
    """The week's recipes changed since the draft was built: build a new cart instead of sending this one."""


def _pj(p: Product | None):
    return Jsonb(p.model_dump()) if p else None


def _get(product, field):
    return product.get(field) if isinstance(product, dict) else getattr(product, field, None)


def order_quantity(product, q):
    """A quantity within the product's order limits: at least min, at most max, a multiple of the interval
    (products sold by weight count grams: min 100, interval 100). 0 / None (remove, unmatched) pass through."""
    if not q or q <= 0 or not product:
        return q
    lo, hi, step = _get(product, "min_qty"), _get(product, "max_qty"), _get(product, "interval")
    if step and step > 1:
        q = math.ceil(q / step) * step
    if lo and q < lo:
        q = lo
    if hi and q > hi:
        q = hi - (hi % step if step and step > 1 else 0)
    return int(q)


def weighed_quantity(it, product, last=None) -> int:
    """Grams to order of a product sold by weight: the need when it is a weight (or a volume with a known density),
    else the last purchase's grams, else the minimum."""
    g = planner.need_grams(it)
    if g is None and last and last[1] and last[1] >= (_get(product, "min_qty") or 0):
        g = last[1]
    return order_quantity(product, math.ceil(g) if g else (_get(product, "min_qty") or 100))


def fit_quantity(it, product, qty, last=None):
    """The build's quantity for a product: grams for one sold by weight, else packs within its order limits."""
    if product is None or qty is None:
        return qty
    if _get(product, "weighted"):
        return weighed_quantity(it, product, last)
    return order_quantity(product, qty)


def line_cost(product, quantity) -> float:
    """Price × quantity; a price per 100 g (sold by weight) counts the quantity in grams."""
    price = _get(product, "price") or 0
    per = _get(product, "price_quantity") or 1
    return price * (quantity or 0) / per


def create_draft(conn, items, weeks, store_id: str, ai_provider: str | None) -> int:
    """Record a building draft with one line per needed item (in list order) and the fingerprint of its weeks'
    plan (to tell later whether the week changed). No PC Express calls."""
    needed = [it for it in items if it.needed]
    wks = sorted({db.week_start(w) for w in weeks})
    with conn.transaction():
        fp = db.plan_fingerprint(conn, wks) if wks else None
        did = conn.execute("INSERT INTO carts(store_id, ai_provider, status, weeks, progress_total, plan_fingerprint) "
                           "VALUES(%s,%s,'building',%s,%s,%s) RETURNING id",
                           (store_id, ai_provider, wks, len(needed), fp)).fetchone()[0]
        for pos, it in enumerate(needed):
            conn.execute("INSERT INTO cart_lines(cart_id, position, item, item_key, item_name, need_qty, need_unit, "
                         "status, source, extra_id) VALUES(%s,%s,%s,%s,%s,%s,%s,'pending','none',%s)",
                         (did, pos, Jsonb(it.model_dump(mode="json")), it.key, it.name, it.qty, it.unit, it.extra_id))
    return did


def find_pick(conn, key: str):
    """(product_code, chosen_by, key it was stored under) for a list key, or None.

    Keys made before ingredient-name clean-up ("parsnip, chopped|each") still count for the cleaned key
    ("parsnip|each"): the first old key that cleans to this one is used (user picks first, then newest)."""
    row = db.get_pick_full(conn, key)
    if row:
        return row[0], row[1], key
    for old, code, by in db.all_picks(conn):
        name, _, dim = old.rpartition("|")
        if name and item_key(split_prep(name)[0], dim) == key:
            return code, by, old
    return None


def search_term(name: str) -> str:
    """Product search uses the name without prep words, even for a list made before the clean-up."""
    return split_prep(name)[0] or name


def _match(pcx, provider, it, pick, last, pinned=False, usual=None):
    """Worker (no DB): search, then the pinned or remembered pick, or an AI choice.
    Returns (found, cands, code, qty, source, note).

    `last` is the remembered pick's last purchase (`db.last_purchase`). Either way the quantity is the planner's
    floor (the fewest packs that cover the need) when it has one; see `planner.default_quantity`. A pinned pick
    ("Always buy this") is fetched by its code when the search doesn't list it; out of stock or no longer sold,
    the line is matched as usual and `note` says why (so does an out-of-stock remembered pick). `usual` = the
    pick's name as last seen, for the note."""
    found = pcx.search(search_term(it.name))
    note = None
    if pick and pinned:
        pin = next((p for p in found if p.code == pick[0]), None)
        if pin is None:
            try:
                pin = pcx.product(pick[0])
            except httpx.HTTPError:
                pin = None
            if pin is not None:
                found = found + [pin]
        if pin is not None and pin.stock not in OUT_OF_STOCK:
            return (found, [p for p in found if p.stock not in OUT_OF_STOCK], pin.code,
                    fit_quantity(it, pin, planner.remembered_quantity(it, pin, last), last), "memory", None)
        name = (pin.name if pin else None) or usual or "product"
        note = (f"Your usual {name} is out of stock this week" if pin is not None
                else f"Your usual {name} isn't sold at Loblaws any more") + " — matched another."
        pick = None
    found = _with_bulk(pcx, it, found)
    cands = [p for p in found if p.stock not in OUT_OF_STOCK]
    if pick and note is None:
        gone = next((p for p in found if p.code == pick[0] and p.stock in OUT_OF_STOCK), None)
        if gone is not None:
            note = f"Your usual {gone.name or usual or 'product'} is out of stock this week — matched another."
    if not cands:
        return found, cands, None, None, "none", note
    by_code = {p.code: p for p in cands}
    if pick and pick[0] in by_code:
        p = by_code[pick[0]]
        hint = bulk.better_cover(it, p, cands)
        note = bulk.hint_note(hint[0], bulk.cover(it, hint[0])[0], hint[1]) if hint and hint[1] >= HINT_SAVING else None
        return found, cands, pick[0], fit_quantity(it, p, planner.remembered_quantity(it, p, last), last), "memory", note
    code, qty = _choose(provider, it, cands)
    if code in by_code:
        chosen = by_code[code]
        bigger = bulk.better_cover(it, chosen, cands)
        if bigger:   # the same thing in a bigger pack covers the need for no more: take it
            big, saving = bigger
            n_chosen, n_big = bulk.cover(it, chosen)[0], bulk.cover(it, big)[0]
            why = bulk.swap_note(chosen, n_chosen, big, n_big, saving)
            note = f"{note} {why}" if note else why
            code, chosen, qty = big.code, big, n_big
        qty = fit_quantity(it, chosen, planner.default_quantity(it, chosen, qty))
    return found, cands, code, qty, "ai", note


def _with_bulk(pcx, it, found):
    """The search results plus the same item's club packs when bigger packs could matter (`bulk.wanted`). A failed
    club-pack search just leaves them out."""
    if not bulk.wanted(it, [p for p in found if p.stock not in OUT_OF_STOCK]):
        return found
    term = search_term(it.name)
    try:
        more = pcx.search(f"{term} {bulk.BULK_WORDS}")
    except httpx.HTTPError:
        return found
    return bulk.merge(term, found, more)


def _match_extra(pcx, provider, it):
    """Worker for an item added by search: its chosen product (fetched by code, this week's price and stock) in the
    chosen quantity. Out of stock or no longer sold: the item's search term is matched as usual and `note` says
    why. Returns what `_match` returns."""
    p = pcx.product(it.product_code) if it.product_code else None
    if p is not None and p.stock not in OUT_OF_STOCK:
        return [p], [p], p.code, order_quantity(p, it.quantity or 1), "user", None
    name = (p.name if p else None) or it.name
    note = (f"{name} is out of stock this week" if p is not None else f"{name} isn't sold at Loblaws any more") \
        + " — matched another."
    found = pcx.search(search_term(it.name)) + ([p] if p is not None else [])
    cands = [c for c in found if c.stock not in OUT_OF_STOCK]
    if not cands:
        return found, cands, None, None, "none", note
    code, _ = _choose(provider, it, cands)
    by_code = {c.code: c for c in cands}
    if code not in by_code:
        return found, cands, None, None, "none", note
    return found, cands, code, _substitute_quantity(by_code[code], it.quantity or 1), "ai", note


def _substitute_quantity(p, q: int) -> int:
    """An added item's quantity on its substitute: grams chosen for a weighed product don't become packs, and the
    other way round."""
    if _get(p, "weighted"):
        return order_quantity(p, q if q >= (_get(p, "min_qty") or 100) else (_get(p, "min_qty") or 100))
    return order_quantity(p, q if q < 100 else 1)


def build_draft(conn, draft_id: int, pcx, provider, store_id: str = "1092", workers: int = 6) -> None:
    """Match every pending line of a building draft, then mark it ready (or failed on a total search outage)."""
    try:
        _build(conn, draft_id, pcx, provider, store_id, workers)
    except Exception as e:   # never leave a draft stuck in 'building'
        log.exception("draft %s build failed", draft_id)
        conn.execute("UPDATE carts SET status='failed', error=%s WHERE id=%s AND status='building'",
                     (f"build failed: {type(e).__name__}: {str(e)[:200]}", draft_id))


def _build(conn, draft_id, pcx, provider, store_id, workers):
    rows = conn.execute("SELECT id, item FROM cart_lines WHERE cart_id=%s AND status='pending' ORDER BY position",
                        (draft_id,)).fetchall()
    jobs = []
    for lid, item in rows:
        it = ListItem(**item)
        if it.extra_id is not None:   # added by search: its own product; a substitute never becomes the pick
            jobs.append((lid, it, (it.product_code, "user") if it.product_code else None, None, False, None))
            continue
        found = find_pick(conn, it.key)
        pick = found[:2] if found else None
        if found and found[2] != it.key:
            db.copy_pick(conn, found[2], it.key)
        pinned = bool(pick) and db.pinned_code(conn, it.key) == pick[0]
        usual = _current_product(conn, pick[0]) if pick else None
        jobs.append((lid, it, pick, db.last_purchase(conn, found[2], pick[0]) if pick else None, pinned,
                     usual.name if usual else None))
    errors, last_error = 0, None
    with ThreadPoolExecutor(max_workers=max(1, workers), thread_name_prefix=f"draft{draft_id}") as pool:
        futs = {(pool.submit(_match_extra, pcx, provider, it) if it.extra_id is not None else
                 pool.submit(_match, pcx, provider, it, pick, last, pinned, usual)): (lid, it, pick)
                for lid, it, pick, last, pinned, usual in jobs}
        for fut in as_completed(futs):
            lid, it, pick = futs[fut]
            try:
                found, cands, code, qty, source, note = fut.result()
            except httpx.HTTPError as e:
                errors, last_error = errors + 1, e
                found, cands, code, qty, source, note = [], [], None, None, "none", None
            except Exception:
                log.exception("draft %s: matching %r failed", draft_id, it.name)
                found, cands, code, qty, source, note = [], [], None, None, "none", None
            db.record_products(conn, store_id, found)
            by_code = {p.code: p for p in cands}
            chosen = by_code.get(code)
            if chosen is None:
                source, qty = "none", None
            elif source == "ai":
                # Keep a user's pick, and an AI pick that is merely out of stock this week; replace only
                # an AI pick that no longer shows up in search at all.
                if pick is None or (pick[1] != "user" and pick[0] not in {p.code for p in found}):
                    db.set_pick(conn, it.key, chosen.code, chosen_by="ai")
            alts = [p for p in cands if chosen is None or p.code != chosen.code][:MAX_ALTERNATIVES]
            with conn.transaction():
                conn.execute("UPDATE cart_lines SET product=%s, product_code=%s, quantity=%s, source=%s, status=%s, "
                             "alternatives=%s, note=%s WHERE id=%s",
                             (_pj(chosen), chosen.code if chosen else None, qty, source,
                              "matched" if chosen else "unmatched", Jsonb([p.model_dump() for p in alts]), note, lid))
                conn.execute("UPDATE carts SET progress_done = progress_done + 1 WHERE id=%s", (draft_id,))
    if jobs and errors == len(jobs):
        conn.execute("UPDATE carts SET status='failed', error=%s WHERE id=%s",
                     (f"PC Express product search failed for every item ({type(last_error).__name__}: "
                      f"{str(last_error)[:150]}) — likely an outage; try again later", draft_id))
    else:
        conn.execute("UPDATE carts SET status='ready' WHERE id=%s AND status='building'", (draft_id,))


def fail_interrupted(conn) -> None:
    """At startup: builds that were running when the process stopped will never finish."""
    conn.execute("UPDATE carts SET status='failed', error='interrupted by a server restart; build a new draft' "
                 "WHERE status='building'")


def _line_out(row) -> dict:
    (lid, item, key, name, qty, unit, product, code, pname, pbrand, psize, quantity, source, alts, removed, status,
     note, pimage, pinned, extra_id) = row
    item = item or {}
    if product is None and code:   # carts from before drafts only kept the product code
        product = {"code": code, "name": pname, "brand": pbrand, "package_size": psize, "price": None, "stock": None}
    if product and not product.get("image") and pimage:   # a snapshot from before 0.12.0: the photo seen since
        product = {**product, "image": pimage}
    plan = planner.plan(ListItem(key=key or "", name=name or "", qty=qty, unit=unit, prep=item.get("prep"),
                                 staple_packs=item.get("staple_packs")), product)
    return {"id": lid, "item_key": key, "name": name, "qty": qty, "unit": unit, "prep": item.get("prep"),
            "recipes": item.get("recipes", []), "staple": bool(item.get("staple")),
            "staple_packs": item.get("staple_packs"), "product": product, "quantity": quantity, "source": source,
            "alternatives": alts or [], "removed": removed, "status": status,
            "packs_min": plan.packs_min if plan else None, "needs_check": bool(plan and plan.needs_check),
            "why": plan.why(quantity) if plan and not (product or {}).get("weighted") else None,
            "pinned": bool(pinned), "note": note, "extra_id": extra_id}


_LINE_SQL = ("SELECT l.id, l.item, l.item_key, l.item_name, l.need_qty, l.need_unit, l.product, l.product_code, "
             "p.name, p.brand, p.package_size, l.quantity, l.source, l.alternatives, l.removed, l.status, l.note, "
             "p.detail->>'image', EXISTS(SELECT 1 FROM picks k WHERE k.key = l.item_key "
             "AND k.product_code = l.product_code AND k.pinned), l.extra_id "
             "FROM cart_lines l LEFT JOIN products p ON p.code = l.product_code ")


def get_draft(conn, draft_id: int) -> dict | None:
    """The draft and its lines; stale = the plan of its weeks changed since it was made (a newer cart is needed)."""
    row = conn.execute("SELECT status, weeks, pcx_cart_id, progress_done, progress_total, error, created_at, sent_at, "
                       "plan_fingerprint FROM carts WHERE id=%s", (draft_id,)).fetchone()
    if row is None:
        return None
    status, weeks, pcx_id, done, total, error, created, sent, fp = row
    lines = [_line_out(r) for r in conn.execute(_LINE_SQL + "WHERE l.cart_id=%s ORDER BY l.position NULLS LAST, l.id",
                                                (draft_id,))]
    est = round(sum(line_cost(l["product"], l["quantity"]) for l in lines if l["product"] and not l["removed"]), 2)
    return {"id": draft_id, "status": status, "error": error, "progress": {"done": done, "total": total},
            "weeks": [w.isoformat() for w in weeks or []], "lines": lines, "pcx_cart_id": pcx_id,
            "estimated_total": est, "created_at": created.isoformat(), "sent_at": sent.isoformat() if sent else None,
            "stale": db.cart_is_stale(conn, weeks or [], fp)}


def latest_for_week(conn, week) -> dict | None:
    """Newest draft (any status) covering the week, so either phone can find this week's cart."""
    row = conn.execute("SELECT id FROM carts WHERE %s = ANY(weeks) ORDER BY id DESC LIMIT 1",
                       (db.week_start(week),)).fetchone()
    return get_draft(conn, row[0]) if row else None


def _lock_ready(conn, draft_id: int) -> None:
    """Inside a transaction: lock the draft row; it must exist and be ready for edits."""
    row = conn.execute("SELECT status FROM carts WHERE id=%s FOR UPDATE", (draft_id,)).fetchone()
    if row is None:
        raise DraftNotFound(f"draft {draft_id}")
    if row[0] != "ready":
        raise DraftStateError(f"draft {draft_id} is {row[0]}, not ready")


def _get_line(conn, draft_id, line_id):
    row = conn.execute(_LINE_SQL + "WHERE l.id=%s AND l.cart_id=%s", (line_id, draft_id)).fetchone()
    if row is None:
        raise DraftNotFound(f"line {line_id} in draft {draft_id}")
    return _line_out(row)


def _current_product(conn, code: str) -> Product | None:
    """Product details (the latest search snapshot: photos, offers, order limits) plus its latest observed
    price/stock."""
    row = conn.execute("SELECT p.code, p.name, p.brand, p.package_size, o.price::float, o.stock, p.detail "
                       "FROM products p LEFT JOIN LATERAL (SELECT price, stock FROM price_observations WHERE code=p.code "
                       "ORDER BY id DESC LIMIT 1) o ON true WHERE p.code=%s", (code,)).fetchone()
    if row is None:
        return None
    detail = {k: v for k, v in (row[6] or {}).items() if k in Product.model_fields}
    return Product(**{**detail, "code": row[0], "name": row[1] or "", "brand": row[2], "package_size": row[3],
                      "price": row[4], "stock": row[5]})


_UNSET = object()


def _line_item(line: dict) -> ListItem:
    return ListItem(key=line["item_key"] or "", name=line["name"] or "", qty=line["qty"], unit=line["unit"],
                    prep=line["prep"], staple_packs=line.get("staple_packs"), staple=bool(line.get("staple")))


def update_line(conn, draft_id: int, line_id: int, product_code=_UNSET, quantity=_UNSET, removed=_UNSET,
                pinned=_UNSET) -> dict:
    """Swap product (remembered as the household's pick), change quantity (0 = remove), remove/restore a line, or pin
    / unpin its product ("Always buy this" for the item; pinning makes the product the household's pick). A positive
    quantity is kept within the product's order limits (grams for one sold by weight)."""
    with conn.transaction():
        _lock_ready(conn, draft_id)
        line = _get_line(conn, draft_id, line_id)
        sets, vals = [], []
        qty = line["quantity"]
        if product_code is not _UNSET and product_code is not None:
            new = _current_product(conn, product_code)
            if new is None:
                raise UnknownProduct(product_code)
            seen = next((a for a in line["alternatives"] if a["code"] == new.code), None)
            if seen and seen.get("sold_by"):   # the pricing type is only in search snapshots, not in products
                new.sold_by = seen["sold_by"]
            old = line["product"]
            alts = [a for a in line["alternatives"] if a["code"] != new.code]
            if old and old["code"] != new.code:
                alts = [old] + alts
            if quantity is _UNSET or quantity is None:   # cover the need with the new pack
                it = _line_item(line)
                if new.weighted:
                    qty = weighed_quantity(it, new)
                elif not qty or not old or old.get("weighted") or qty == planner.default_quantity(it, old, qty):
                    qty = order_quantity(new, planner.default_quantity(it, new, qty))   # the build's number → the new product's
                else:
                    qty = order_quantity(new, planner.floor_quantity(it, new, qty))     # the user's own number, raised to the floor
            sets += ["product=%s", "product_code=%s", "source='user'", "status='matched'", "alternatives=%s", "note=NULL"]
            vals += [_pj(new), new.code, Jsonb(alts)]
            db.set_pick(conn, line["item_key"], new.code, chosen_by="user")
            product = new
        else:
            product = line["product"]
        if quantity is not _UNSET and quantity is not None:
            if quantity < 0:
                raise ValueError("quantity must be ≥ 0")
            qty = order_quantity(product, quantity)
            if removed is _UNSET or removed is None:   # qty 0 removes the line; a positive qty restores it
                removed = quantity == 0
        if removed is not _UNSET and removed is not None:
            sets.append("removed=%s"); vals.append(bool(removed))
            if not removed and not qty:
                qty = 1
        if qty != line["quantity"]:
            sets.append("quantity=%s"); vals.append(qty)
        if sets:
            conn.execute(f"UPDATE cart_lines SET {', '.join(sets)} WHERE id=%s", (*vals, line_id))
        if line["extra_id"] is not None:
            _mirror_extra(conn, draft_id, line, product, qty,
                          line["removed"] if removed is _UNSET or removed is None else bool(removed))
        if pinned is not _UNSET and pinned is not None:
            code = _get(product, "code")
            if pinned and not code:
                raise ValueError("this line has no product to pin")
            if pinned and (db.get_pick_full(conn, line["item_key"]) or (None,))[0] != code:
                db.set_pick(conn, line["item_key"], code, chosen_by="user")
            db.set_pinned(conn, line["item_key"], bool(pinned))
        return _get_line(conn, draft_id, line_id)


def _mirror_extra(conn, draft_id, line, product, qty, removed: bool) -> None:
    """Edits of an added item's line are the item's (`extras`): its product and quantity follow; removing the line
    deletes the item, restoring the line adds it back (same id, the draft's first week)."""
    eid = line["extra_id"]
    if removed:
        conn.execute("DELETE FROM extras WHERE id=%s", (eid,))
        return
    code = _get(product, "code")
    if not code or not qty:
        return
    conn.execute("INSERT INTO extras(id, week, term, key, product_code, quantity) "
                 "SELECT %s, c.weeks[1], %s, %s, %s, %s FROM carts c WHERE c.id=%s AND c.weeks[1] IS NOT NULL "
                 "ON CONFLICT (id) DO UPDATE SET product_code=EXCLUDED.product_code, quantity=EXCLUDED.quantity",
                 (eid, line["name"], line["item_key"], code, qty, draft_id))


def search_line(conn, draft_id: int, line_id: int, term: str, pcx, store_id: str = "1092") -> list[dict]:
    """Free-text search for a swap: records price observations; in-stock results become the line's alternatives."""
    with conn.transaction():
        _lock_ready(conn, draft_id)
        _get_line(conn, draft_id, line_id)
    found = pcx.search(term)
    db.record_products(conn, store_id, found)
    cands = [p.model_dump() for p in found if p.stock not in OUT_OF_STOCK]
    with conn.transaction():
        _lock_ready(conn, draft_id)
        line = _get_line(conn, draft_id, line_id)
        chosen = (line["product"] or {}).get("code")
        conn.execute("UPDATE cart_lines SET alternatives=%s WHERE id=%s",
                     (Jsonb([c for c in cands if c["code"] != chosen]), line_id))
    return cands


def send_draft(conn, draft_id: int, pcx) -> str:
    """Create the anonymous PC Express cart from the draft (once). Only now does the week count as carted.
    A ready draft whose weeks' recipes changed since it was built is refused (DraftStale): build a new one."""
    with conn.transaction():
        row = conn.execute("SELECT status, pcx_cart_id, weeks, plan_fingerprint FROM carts WHERE id=%s FOR UPDATE",
                           (draft_id,)).fetchone()
        if row is None:
            raise DraftNotFound(f"draft {draft_id}")
        status, pcx_id, weeks, fp = row
        if status == "sent":
            return pcx_id
        if status != "ready":
            raise DraftStateError(f"draft {draft_id} is {status}, not ready")
        if db.cart_is_stale(conn, weeks or [], fp):
            raise DraftStale(f"the week's recipes changed since draft {draft_id} was built; build a new cart")
        lines = conn.execute("SELECT id, product_code, quantity FROM cart_lines WHERE cart_id=%s AND NOT removed "
                             "AND product_code IS NOT NULL AND quantity > 0 ORDER BY position", (draft_id,)).fetchall()
        entries = {}
        for _, code, qty in lines:
            entries[code] = entries.get(code, 0) + qty
        pcx_id = pcx.create_cart()
        if entries:
            pcx.add(pcx_id, entries)
        for lid, code, _ in lines:
            conn.execute("UPDATE cart_lines SET status='added', price_at_add = COALESCE("
                         "(SELECT price FROM price_observations WHERE code=%s ORDER BY id DESC LIMIT 1), "
                         "(product->>'price')::numeric) WHERE id=%s", (code, lid))
        for wk in weeks or []:
            conn.execute("INSERT INTO cart_weeks(cart_id, week) VALUES(%s,%s) ON CONFLICT DO NOTHING", (draft_id, wk))
        conn.execute("UPDATE carts SET status='sent', pcx_cart_id=%s, sent_at=now() WHERE id=%s", (pcx_id, draft_id))
    return pcx_id


# --- the product chooser (0.12.0) -----------------------------------------------------------------------------

GROUPS = ("current", "bought", "deal", "other", "out")
_kind = bulk.kind


def _out(p) -> bool:
    return _get(p, "stock") in OUT_OF_STOCK


def _on_offer(p) -> bool:
    return bool(_get(p, "sale_ends") or _get(p, "deal_text") or _get(p, "optimum_text")
                or (_get(p, "was_price") and _get(p, "price") is not None and _get(p, "price") < _get(p, "was_price")))


def better_value(it, p: Product, others: list[Product]) -> Product | None:
    """The best other candidate of the same kind (same name words, pack words aside) that costs at least 3 % less
    per unit (same comparison unit) and would be used: its pack is no bigger, or the line is a weekly staple, or
    covering this week's need with it costs no more than with `p`. A suggestion only; nothing is swapped."""
    if _out(p) or p.unit_price is None or not p.unit_price_unit:
        return None
    kind, (fam, size) = _kind(p.name), planner.pack_amount(p)
    plan_p = planner.plan(it, p)
    best = None
    for o in others:
        if (o.code == p.code or _out(o) or o.unit_price is None or o.unit_price_unit != p.unit_price_unit
                or o.unit_price > p.unit_price * BETTER_BY or _kind(o.name) != kind or not kind):
            continue
        ofam, osize = planner.pack_amount(o)
        used = it.staple or (fam and fam == ofam and size and osize and osize <= size * (1 + planner.SLACK))
        if not used and plan_p and plan_p.enforce and p.price is not None and o.price is not None:
            plan_o = planner.plan(it, o)
            used = bool(plan_o and plan_o.enforce and
                        o.price * plan_o.packs_min <= p.price * plan_p.packs_min + 1e-9)
        if used and (best is None or o.unit_price < best.unit_price):
            best = o
    return best


def suggested_quantity(it, p, last=None) -> int:
    """Packs (or grams, sold by weight) to start the detail view's stepper at."""
    if _get(p, "weighted"):
        return weighed_quantity(it, p, last)
    return order_quantity(p, planner.default_quantity(it, p))


def _fetch_many(pcx, codes) -> list[Product]:
    if not codes:
        return []
    def one(code):
        try:
            return pcx.product(code)
        except httpx.HTTPError:
            return None
    with ThreadPoolExecutor(max_workers=min(4, len(codes)), thread_name_prefix="choices") as pool:
        return [p for p in pool.map(one, codes) if p is not None]


def choices(conn, draft_id: int, line_id: int, pcx, term: str | None = None, page: int = 0,
            size: int = CHOICES_PAGE, store_id: str = "1092") -> dict:
    """One page of products to choose from for a line (any draft status; read-only apart from price observations).

    The term defaults to the line's item name; pages are Loblaws' pages. Order within the page: the line's current
    product, then products sent in a cart before (most recent first), on sale or with a PC Optimum offer, the rest in
    Loblaws' order, and out-of-stock products last (shown, not selectable). With the default term, page 0 also
    brings the current, the pinned and up to MAX_BOUGHT products bought for this item before when the search lacks
    them (fetched by code, so their price and stock are this week's), and the item's club packs when bigger packs
    could matter (`bulk.wanted`)."""
    line = _get_line(conn, draft_id, line_id)
    return _choices(conn, pcx, _line_item(line), line["item_key"] or "", search_term(line["name"] or ""), term, page,
                    size, store_id, current=(line["product"] or {}).get("code"), quantity=line["quantity"])


def search_choices(conn, pcx, term: str, page: int = 0, size: int = CHOICES_PAGE, store_id: str = "1092") -> dict:
    """The chooser for an item added by search (no list line): the same order and hints as a line's, with the
    products bought before under this search's item key (`extras.extra_key`) on page 0."""
    from .extras import extra_key
    t = " ".join(term.split())
    it = ListItem(key=extra_key(t), name=t)
    return _choices(conn, pcx, it, it.key, t, t, page, size, store_id)


def _choices(conn, pcx, it, key, default, term, page, size, store_id, current=None, quantity=None) -> dict:
    term = (term or "").strip() or default
    found, total = pcx.search_page(term, page, size)
    pin = db.pinned_code(conn, key)
    seen = {p.code for p in found}
    extra: list[Product] = []
    if page == 0 and term == default:
        want = [c for c in dict.fromkeys([pin, *db.bought_codes(conn, key, MAX_BOUGHT)])
                if c and c not in seen and c != current]
        extra = _fetch_many(pcx, want)
        seen |= {p.code for p in extra}
        if bulk.wanted(it, [p for p in [*found, *extra] if not _out(p)]):
            try:
                more = pcx.search(f"{search_term(it.name)} {bulk.BULK_WORDS}")
            except httpx.HTTPError:
                more = []
            extra += [p for p in bulk.merge(search_term(it.name), [], more) if p.code not in seen and p.code != current]
            seen |= {p.code for p in extra}
    if page == 0 and current and current not in seen:
        cur = _current_product(conn, current)
        if cur is not None:
            extra.insert(0, cur)
    db.record_products(conn, store_id, [*found, *(p for p in extra if p.code != current)])
    cands = list({p.code: p for p in [*extra, *found]}.values())
    bought = db.purchases(conn, [p.code for p in cands])
    last = db.last_purchase(conn, key, current) if current else None
    cur_p = next((p for p in cands if p.code == current), None)
    cur_cover = bulk.cover(it, cur_p) if cur_p is not None else None

    def group(p):
        if _out(p):
            return "out"
        if p.code == current:
            return "current"
        if p.code in bought:
            return "bought"
        return "deal" if _on_offer(p) else "other"

    def recency(p):
        sent = bought.get(p.code, (None,))[0]
        return -sent.timestamp() if sent and group(p) == "bought" else 0

    order = sorted(range(len(cands)), key=lambda i: (GROUPS.index(group(cands[i])), recency(cands[i]), i))
    out = []
    for i in order:
        p = cands[i]
        b = bought.get(p.code)
        lb = {"date": b[0].isoformat() if b[0] else None, "price": b[1], "quantity": b[2]} if b else None
        bv = better_value(it, p, cands)
        cov = bulk.cover(it, p)
        out.append({
            "product": p.model_dump(), "group": group(p), "current": p.code == current, "pinned": p.code == pin,
            "last_bought": lb,
            "price_change": round(p.price - b[1], 2) if b and b[1] is not None and p.price is not None else None,
            "better_value": bv.model_dump(include={"code", "name", "brand", "package_size", "price", "unit_price",
                                                   "unit_price_unit"}) if bv else None,
            "suggested_quantity": (quantity if p.code == current and quantity
                                   else suggested_quantity(it, p, last if p.code == current else None)),
            "cover": {"packs": cov[0], "cost": cov[1], "size": bulk.size_text(p)} if cov else None,
            "bulk": bulk.is_bulk(p, cov, cur_cover) and p.code != current,
        })
    return {"term": term, "page": page, "total": total, "has_more": bool(found) and (page + 1) * size < total,
            "choices": out}
