import json
from . import db, planner
from .ai.base import AIError
from .models import CartLine, CartResult

OUT_OF_STOCK = {"OUT", "OUT_OF_STOCK"}   # live API reports "OUT"

PROMPT = """Pick the best grocery product for this shopping-list need, and how many packages to buy.
Need: {need}
Candidates (JSON; min_packs = the fewest packages of that product that cover the need, worked out from its
package_size; 1 where that can't be worked out): {cands}
Prefer: the plain/standard version, the smallest package that covers the amount, regular brands over specialty.
Quantity: its min_packs (where it was worked out, exactly that is bought); otherwise the packages the need calls for, usually 1.
Return ONLY JSON: {{"code": candidate code, "quantity": integer packages}}"""


def _need(it):
    q = f"{it.qty:g} {it.unit or ''}".strip() if it.qty is not None else "some"
    return f"{q} {it.name}" + (f" ({it.prep})" if it.prep else "")


def _choose(provider, it, cands):
    """AI pick (code, packages). The caller settles the quantity with `planner.default_quantity`."""
    mins = planner.min_packs(it, cands)
    try:
        ch = provider.complete_json(PROMPT.format(need=_need(it), cands=json.dumps(
            [{**p.model_dump(include={"code", "name", "brand", "package_size", "price"}), "min_packs": mins[p.code]}
             for p in cands], ensure_ascii=False)))
    except AIError:
        return None, 0
    if not isinstance(ch, dict):
        return None, 0
    try:
        qty = max(1, int(ch.get("quantity") or 1))
    except (TypeError, ValueError):
        qty = 1
    return ch.get("code"), qty


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
