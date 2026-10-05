"""Cart drafts: build in the app (building → ready), the user edits, then send to PC Express (→ sent).

Only `send_draft` touches PC Express carts (one create + one add). Matching runs product searches and AI
picks concurrently in a bounded thread pool; all DB writes stay on the calling thread's connection."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import logging

import httpx
from psycopg.types.json import Jsonb

from . import db, planner
from .ingredients import item_key, split_prep
from .matcher import OUT_OF_STOCK, _choose
from .models import ListItem, Product

log = logging.getLogger(__name__)
MAX_ALTERNATIVES = 5


class DraftNotFound(Exception):
    pass


class DraftStateError(Exception):
    pass


class UnknownProduct(Exception):
    pass


class DraftFailed(Exception):
    pass


def _pj(p: Product | None):
    return Jsonb(p.model_dump()) if p else None


def create_draft(conn, items, weeks, store_id: str, ai_provider: str | None) -> int:
    """Record a building draft with one line per needed item (in list order). No PC Express calls."""
    needed = [it for it in items if it.needed]
    wks = sorted({db.week_start(w) for w in weeks})
    with conn.transaction():
        did = conn.execute("INSERT INTO carts(store_id, ai_provider, status, weeks, progress_total) "
                           "VALUES(%s,%s,'building',%s,%s) RETURNING id",
                           (store_id, ai_provider, wks, len(needed))).fetchone()[0]
        for pos, it in enumerate(needed):
            conn.execute("INSERT INTO cart_lines(cart_id, position, item, item_key, item_name, need_qty, need_unit, "
                         "status, source) VALUES(%s,%s,%s,%s,%s,%s,%s,'pending','none')",
                         (did, pos, Jsonb(it.model_dump(mode="json")), it.key, it.name, it.qty, it.unit))
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


def _match(pcx, provider, it, pick, last):
    """Worker (no DB): search, then remembered pick or AI choice. Returns (found, cands, code, qty, source).

    `last` is the remembered pick's last purchase (`db.last_purchase`). Either way the quantity is the planner's
    floor (the fewest packs that cover the need) when it has one; see `planner.default_quantity`."""
    found = pcx.search(search_term(it.name))
    cands = [p for p in found if p.stock not in OUT_OF_STOCK]
    if not cands:
        return found, cands, None, None, "none"
    by_code = {p.code: p for p in cands}
    if pick and pick[0] in by_code:
        return found, cands, pick[0], planner.remembered_quantity(it, by_code[pick[0]], last), "memory"
    code, qty = _choose(provider, it, cands)
    if code in by_code:
        qty = planner.default_quantity(it, by_code[code], qty)
    return found, cands, code, qty, "ai"


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
        found = find_pick(conn, it.key)
        pick = found[:2] if found else None
        if found and found[2] != it.key:
            db.copy_pick(conn, found[2], it.key)
        jobs.append((lid, it, pick, db.last_purchase(conn, found[2], pick[0]) if pick else None))
    errors, last_error = 0, None
    with ThreadPoolExecutor(max_workers=max(1, workers), thread_name_prefix=f"draft{draft_id}") as pool:
        futs = {pool.submit(_match, pcx, provider, it, pick, last): (lid, it, pick) for lid, it, pick, last in jobs}
        for fut in as_completed(futs):
            lid, it, pick = futs[fut]
            try:
                found, cands, code, qty, source = fut.result()
            except httpx.HTTPError as e:
                errors, last_error = errors + 1, e
                found, cands, code, qty, source = [], [], None, None, "none"
            except Exception:
                log.exception("draft %s: matching %r failed", draft_id, it.name)
                found, cands, code, qty, source = [], [], None, None, "none"
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
                             "alternatives=%s WHERE id=%s",
                             (_pj(chosen), chosen.code if chosen else None, qty, source,
                              "matched" if chosen else "unmatched", Jsonb([p.model_dump() for p in alts]), lid))
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
    lid, item, key, name, qty, unit, product, code, pname, pbrand, psize, quantity, source, alts, removed, status = row
    item = item or {}
    if product is None and code:   # carts from before drafts only kept the product code
        product = {"code": code, "name": pname, "brand": pbrand, "package_size": psize, "price": None, "stock": None}
    plan = planner.plan(ListItem(key=key or "", name=name or "", qty=qty, unit=unit, prep=item.get("prep")), product)
    return {"id": lid, "item_key": key, "name": name, "qty": qty, "unit": unit, "prep": item.get("prep"),
            "recipes": item.get("recipes", []), "product": product, "quantity": quantity, "source": source,
            "alternatives": alts or [], "removed": removed, "status": status,
            "packs_min": plan.packs_min if plan else None, "needs_check": bool(plan and plan.needs_check),
            "why": plan.why(quantity) if plan else None}


_LINE_SQL = ("SELECT l.id, l.item, l.item_key, l.item_name, l.need_qty, l.need_unit, l.product, l.product_code, "
             "p.name, p.brand, p.package_size, l.quantity, l.source, l.alternatives, l.removed, l.status "
             "FROM cart_lines l LEFT JOIN products p ON p.code = l.product_code ")


def get_draft(conn, draft_id: int) -> dict | None:
    row = conn.execute("SELECT status, weeks, pcx_cart_id, progress_done, progress_total, error, created_at, sent_at "
                       "FROM carts WHERE id=%s", (draft_id,)).fetchone()
    if row is None:
        return None
    status, weeks, pcx_id, done, total, error, created, sent = row
    lines = [_line_out(r) for r in conn.execute(_LINE_SQL + "WHERE l.cart_id=%s ORDER BY l.position NULLS LAST, l.id",
                                                (draft_id,))]
    est = round(sum(((l["product"] or {}).get("price") or 0) * (l["quantity"] or 0)
                    for l in lines if l["product"] and not l["removed"]), 2)
    return {"id": draft_id, "status": status, "error": error, "progress": {"done": done, "total": total},
            "weeks": [w.isoformat() for w in weeks or []], "lines": lines, "pcx_cart_id": pcx_id,
            "estimated_total": est, "created_at": created.isoformat(), "sent_at": sent.isoformat() if sent else None}


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
    """Product details plus its latest observed price/stock."""
    row = conn.execute("SELECT p.code, p.name, p.brand, p.package_size, o.price::float, o.stock FROM products p "
                       "LEFT JOIN LATERAL (SELECT price, stock FROM price_observations WHERE code=p.code "
                       "ORDER BY id DESC LIMIT 1) o ON true WHERE p.code=%s", (code,)).fetchone()
    if row is None:
        return None
    return Product(code=row[0], name=row[1] or "", brand=row[2], package_size=row[3], price=row[4], stock=row[5])


_UNSET = object()


def _line_item(line: dict) -> ListItem:
    return ListItem(key=line["item_key"] or "", name=line["name"] or "", qty=line["qty"], unit=line["unit"],
                    prep=line["prep"])


def update_line(conn, draft_id: int, line_id: int, product_code=_UNSET, quantity=_UNSET, removed=_UNSET) -> dict:
    """Swap product (remembered as the household's pick), change quantity (0 = remove), or remove/restore a line."""
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
                if not qty or not old or qty == planner.default_quantity(it, old, qty):
                    qty = planner.default_quantity(it, new, qty)   # the build's number → the new product's
                else:
                    qty = planner.floor_quantity(it, new, qty)     # the user's own number, raised to the floor
            sets += ["product=%s", "product_code=%s", "source='user'", "status='matched'", "alternatives=%s"]
            vals += [_pj(new), new.code, Jsonb(alts)]
            db.set_pick(conn, line["item_key"], new.code, chosen_by="user")
        if quantity is not _UNSET and quantity is not None:
            if quantity < 0:
                raise ValueError("quantity must be ≥ 0")
            qty = quantity
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
        return _get_line(conn, draft_id, line_id)


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
    """Create the anonymous PC Express cart from the draft (once). Only now does the week count as carted."""
    with conn.transaction():
        row = conn.execute("SELECT status, pcx_cart_id, weeks FROM carts WHERE id=%s FOR UPDATE", (draft_id,)).fetchone()
        if row is None:
            raise DraftNotFound(f"draft {draft_id}")
        status, pcx_id, weeks = row
        if status == "sent":
            return pcx_id
        if status != "ready":
            raise DraftStateError(f"draft {draft_id} is {status}, not ready")
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
