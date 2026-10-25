"""Quantity check: one AI pass over a built draft before it is ready.

The planner works from free-text pack sizes, and some of them hide what a pack really holds ("1 ea" of Green Onion
is a bunch of several). After every line is matched, the AI reviews all of them together — the recipe need, the
product and the quantity — and corrects only quantities that are clearly wrong. A corrected line says so in its
`note` (shown on the cart row), so the user sees the change and can undo it. Fails open: any AI or data problem
leaves the draft exactly as built."""
import json
import logging

from . import planner
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

For each line decide whether the quantity is reasonable: it covers the need without buying a lot more than needed.
A little extra is fine (packs come in fixed sizes); weekly staples are bought as listed. Change ONLY quantities that
are clearly wrong, e.g. several bunches where one bunch covers it, or far too little for the need. When unsure, leave
the line alone. Lines marked "weighed" are in grams.

Lines (JSON): {lines}

Return ONLY JSON: {{"changes": [{{"id": line id, "quantity": corrected integer, "reason": "short plain reason for the
shopper, e.g. one bunch has about 6–8 green onions"}}]}} — an empty list when every quantity is reasonable."""


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
    if desc := (product.get("description") or "").strip():
        out["description"] = desc[:MAX_DESC]
    return out


def check_draft(conn, draft_id: int, provider) -> int:
    """Review a draft's matched lines; apply clear corrections with a note. Returns the number of lines changed.

    Added items (`extra_id`) keep the quantity the user chose in the chooser and aren't sent."""
    rows = conn.execute("SELECT id, item, product, quantity, note FROM cart_lines WHERE cart_id=%s AND NOT removed "
                        "AND product IS NOT NULL AND quantity > 0 AND extra_id IS NULL ORDER BY position", (draft_id,)).fetchall()
    if not rows:
        return 0
    by_id = {r[0]: r for r in rows}
    try:
        lines = [_line(lid, item, product, qty) for lid, item, product, qty, _ in rows]
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
        try:
            lid, new = int(ch.get("id")), int(ch.get("quantity"))
        except (AttributeError, TypeError, ValueError):
            continue
        row = by_id.pop(lid, None)
        if row is None or new < 1:
            continue
        _, item, product, old, note = row
        if new > old * MAX_RAISE:
            continue
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
