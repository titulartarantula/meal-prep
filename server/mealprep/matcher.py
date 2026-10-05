import json, math
from . import db
from .ai.base import AIError
from .models import CartLine, CartResult

OUT_OF_STOCK = {"OUT", "OUT_OF_STOCK"}   # live API reports "OUT"

PROMPT = """Pick the best grocery product for this shopping-list need, and how many packages to buy.
Need: {need}
Candidates (JSON): {cands}
Prefer: the plain/standard version, the smallest package that covers the amount, regular brands over specialty.
Return ONLY JSON: {{"code": candidate code, "quantity": integer packages}}"""


def _need(it):
    q = f"{it.qty:g} {it.unit or ''}".strip() if it.qty is not None else "some"
    return f"{q} {it.name}" + (f" ({it.prep})" if it.prep else "")


def _choose(provider, it, cands):
    try:
        ch = provider.complete_json(PROMPT.format(need=_need(it), cands=json.dumps(
            [p.model_dump(include={"code", "name", "brand", "package_size", "price"}) for p in cands])))
    except AIError:
        return None, 0
    if not isinstance(ch, dict):
        return None, 0
    try:
        qty = max(1, int(ch.get("quantity") or 1))
    except (TypeError, ValueError):
        qty = 1
    return ch.get("code"), qty


def _remembered_qty(conn, it, code, key: str | None = None) -> int:
    """Scale the last purchase of this product for this list line to this week's need (1 if no history)."""
    last = db.last_purchase(conn, key or it.key, code)
    if not last:
        return 1
    need, qty = last
    if it.qty is None or not need:
        return max(1, int(qty or 1))
    return max(1, math.ceil(qty * it.qty / need - 1e-9))


def fill_cart(items, pcx, provider, conn, weeks, provider_name: str | None = None, store_id: str = "1092",
              workers: int = 6) -> CartResult:
    """Draft + build + send in one go (for scripts/smoke tests; the app uses the draft endpoints).

    Raises DraftFailed (nothing sent, week not carted) if every needed item's search failed."""
    from . import drafts
    did = drafts.create_draft(conn, items, weeks, store_id=store_id, ai_provider=provider_name)
    drafts.build_draft(conn, did, pcx, provider, store_id=store_id, workers=workers)
    d = drafts.get_draft(conn, did)
    if d["status"] != "ready":
        raise drafts.DraftFailed(d["error"] or d["status"])
    cart_id = drafts.send_draft(conn, did, pcx)
    lines = [CartLine(item_key=l["item_key"], product=l["product"], quantity=l["quantity"] or 1,
                      status="added" if l["product"] else "unmatched") for l in drafts.get_draft(conn, did)["lines"]]
    return CartResult(cart_id=cart_id, draft_id=did, lines=lines)
