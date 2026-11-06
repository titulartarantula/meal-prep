"""Quantity check: one AI pass over a built draft before it is ready.

The planner works from free-text pack sizes, and some of them hide what a pack really holds ("1 ea" of Green Onion
is a bunch of several). After every line is matched, the AI reviews all of them together — the recipe need, the
product and the quantity — and corrects only quantities that are clearly wrong. A corrected line says so in its
`note` (shown on the cart row), so the user sees the change and can undo it. When the fix is that one pack holds
several pieces (a bunch, a bag), that is saved for the product (`packs`), so later carts plan it right from the start
and the check is only a backstop. Fails open: any AI or data problem leaves the draft exactly as built."""
import json
import logging

from psycopg.types.json import Jsonb

from . import packs, planner
from .ai.base import AIError
from .models import ListItem

log = logging.getLogger(__name__)
MAX_DESC = 240               # characters of a product description sent to the AI
MAX_RAISE = 4                # a correction may at most quadruple a line (a typo-sized jump is likely a mistake)

MARKER = "You are checking a grocery cart"   # how the prompt starts (test fakes tell it from a pick)
PROMPT = MARKER + """ just before it is sent to the store. Each line is a shopping-list need
(from this week's recipes, or a weekly staple) and the product and quantity picked for it. Quantities were worked out
from the package-size text, which can mislead: "1 ea" may be a single piece or a whole bunch, bag or bundle
(a bunch of green onions has several onions; leeks and radishes are often sold by the bunch; a "1 ea" head of celery
has many stalks).

Also check that each product IS what the need asks for. A product named after the ingredient or flavoured with it
is wrong: a boxed dinner, mix, kit, snack, sauce or seasoning (e.g. need "sharp cheddar", product "Sharp Cheddar"
whose description says it is a macaroni & cheese dinner). Use the description. Flag such a line with
"wrong_item": true and a reason instead of a quantity. Only flag clear cases; a different brand, size or cut of the
same food is fine.

For each line decide whether the quantity is reasonable: it covers the need without buying a lot more than needed.
A little extra is fine (packs come in fixed sizes); weekly staples are bought as listed. Change ONLY quantities that
are clearly wrong, e.g. several bunches where one bunch covers it, or far too little for the need. When unsure, leave
the line alone. Lines marked "weighed" are in grams. "pack_holds" is what one pack was already found to hold.

Lines (JSON): {lines}

Return ONLY JSON: {{"changes": [{{"id": line id, "quantity": corrected integer (omit for a wrong item),
"wrong_item": true only for a product that is not the needed food, "reason": "short plain reason for the
shopper, e.g. one bunch has about 6–8 green onions", "pieces_per_pack": integer or null, "pack_word": "bunch"}}]}}
— an empty list when every quantity is reasonable. Give pieces_per_pack (a typical, slightly low count of the need's
pieces in one pack) and pack_word ("bunch", "bag", ...) only when the fix is that one pack holds several pieces of
what the need counts; otherwise null."""


def _line(lid, item, product, qty):
    it = ListItem(**item)
    need = planner.need_text(it) if it.qty is not None else "no amount given"
    out = {"id": lid, "need": f"{need} {it.name}".strip(), "product": product.get("name"),
           "package_size": product.get("package_size") or None, "price": product.get("price"),
           "quantity": qty}
    if it.prep:
        out["prep"] = it.prep
    if it.staple_packs:
        out["weekly_staple_packs"] = it.staple_packs
    if product.get("weighted"):
        out["weighed"] = True
    if product.get("pieces"):
        out["pack_holds"] = f"about {product['pieces']} ({product.get('pieces_word') or 'pack'})"
    if desc := (product.get("description") or "").strip():
        out["description"] = desc[:MAX_DESC]
    return out


def check_draft(conn, draft_id: int, provider) -> int:
    """Review a draft's matched lines; apply clear corrections with a note. Returns the number of lines changed.

    Added items (`extra_id`) keep the quantity the user chose in the chooser and aren't sent."""
    rows = conn.execute("SELECT id, item, product, quantity, note, item_key FROM cart_lines WHERE cart_id=%s AND NOT removed "
                        "AND product IS NOT NULL AND quantity > 0 AND extra_id IS NULL ORDER BY position", (draft_id,)).fetchall()
    if not rows:
        return 0
    by_id = {r[0]: r for r in rows}
    try:
        lines = [_line(lid, item, product, qty) for lid, item, product, qty, _, _ in rows]
        res = provider.complete_json(PROMPT.format(lines=json.dumps(lines, ensure_ascii=False)))
    except AIError as e:
        log.warning("draft %s: quantity check skipped (%s)", draft_id, e)
        return 0
    except Exception:
        log.exception("draft %s: quantity check failed", draft_id)
        return 0
    changes = res.get("changes") if isinstance(res, dict) else None
    if not isinstance(changes, list):
        return 0
    from .drafts import order_quantity   # drafts imports this module
    done = 0
    for ch in changes:
        if isinstance(ch, dict) and ch.get("wrong_item") is True:
            done += _reject(conn, draft_id, by_id, ch)
            continue
        try:
            lid, new = int(ch.get("id")), int(ch.get("quantity"))
        except (AttributeError, TypeError, ValueError):
            continue
        row = by_id.pop(lid, None)
        if row is None or new < 1:
            continue
        _, item, product, old, note, _ = row
        if new > old * MAX_RAISE:
            continue
        if (learned := _learn(conn, product, ch)) is not None:   # plan with what a pack holds, as later carts will
            product = learned
            new = planner.default_quantity(ListItem(**item), product, new)
            conn.execute("UPDATE cart_lines SET product=%s WHERE id=%s", (Jsonb(product), lid))
        new = order_quantity(product, new)
        if not new or new == old:
            continue
        reason = str(ch.get("reason") or "").strip()[:160]
        unit = " g" if product.get("weighted") else ""
        why = f"Quantity checked: {old}{unit} → {new}{unit}" + (f" — {reason}" if reason else "") + "."
        conn.execute("UPDATE cart_lines SET quantity=%s, note=%s WHERE id=%s AND cart_id=%s",
                     (new, f"{note} {why}" if note else why, lid, draft_id))
        done += 1
    if done:
        log.info("draft %s: quantity check changed %d line(s)", draft_id, done)
    return done


def _reject(conn, draft_id: int, by_id: dict, ch: dict) -> int:
    """A line whose product isn't the needed food at all (a "Sharp Cheddar" boxed macaroni dinner for sharp cheddar):
    leave it unmatched with a note so the household chooses, and forget the remembered pick if the AI made it. A
    product the household chose itself is never rejected. Returns 1 when the line was changed."""
    try:
        lid = int(ch.get("id"))
    except (TypeError, ValueError):
        return 0
    row = by_id.pop(lid, None)
    if row is None:
        return 0
    _, item, product, _, note, key = row
    code = product.get("code")
    pick = conn.execute("SELECT product_code, chosen_by, pinned FROM picks WHERE key=%s", (key,)).fetchone()
    if pick and pick[0] == code and (pick[1] == "user" or pick[2]):
        return 0
    reason = str(ch.get("reason") or "").strip()[:160]
    found = " ".join(x for x in (product.get("brand"), product.get("name")) if x) or "the product found"
    why = (f"Not added: {found} is a different food" + (f" — {reason}" if reason else "")
           + ". Choose the right product.")
    with conn.transaction():
        if pick and pick[0] == code:
            conn.execute("DELETE FROM picks WHERE key=%s AND product_code=%s AND chosen_by='ai' AND NOT pinned",
                         (key, code))
        conn.execute("UPDATE cart_lines SET product=NULL, product_code=NULL, quantity=NULL, source='none', "
                     "status='unmatched', note=%s WHERE id=%s AND cart_id=%s",
                     (f"{note} {why}" if note else why, lid, draft_id))
    log.info("draft %s: quantity check rejected line %s (%s)", draft_id, lid, code)
    return 1


def _learn(conn, product: dict, ch: dict) -> dict | None:
    """Save what one pack holds when the AI says so (count packs only; never over the household's number). Returns
    the line's product with it attached, or None when nothing was learned."""
    try:
        n = int(ch.get("pieces_per_pack") or 0)
    except (TypeError, ValueError):
        return None
    pack = planner.parse_pack(product.get("package_size"), product.get("sold_by"))
    if (not 2 <= n <= packs.MAX_PIECES or pack.family != "count" or pack.priced_by_weight or product.get("weighted")
            or not product.get("code")):
        return None
    if not packs.save(conn, product["code"], n, ch.get("pack_word"), "check", str(ch.get("reason") or "")):
        return None
    return packs.attach(product, packs.lookup(conn, product["code"]))
