import json
from . import bulk, db, planner
from .ai.base import AIError
from .models import CartLine, CartResult

OUT_OF_STOCK = {"OUT", "OUT_OF_STOCK"}   # live API reports "OUT"
MAX_DESC = 160               # characters of a candidate's description sent to the AI (what the product really is)

PROMPT = """Pick the best grocery product for this shopping-list need.
Need: {need}
Candidates (JSON; min_packs = the fewest packages of that product that cover the need, worked out from its
package_size; 1 where that can't be worked out; cover_cost = what min_packs of it cost, where known; description =
the start of the store's description): {cands}
The product must BE the needed ingredient, not a different food that is named after it or flavoured with it.
A boxed dinner, mix, kit, snack, sauce or seasoning whose name matches is wrong (need "sharp cheddar" → cheddar
cheese, never a "Sharp Cheddar" macaroni & cheese dinner). Read the description to tell. If no candidate is the
ingredient itself, return {{"code": null}}.
Prefer: the plain/standard version; the cheapest way to cover the amount (one bigger pack — club/family size — over
several small ones when its cover_cost is no higher; otherwise the smallest package that covers it); regular brands
over specialty. How many to buy is worked out separately.
Return ONLY JSON: {{"code": candidate code or null}}"""


def _need(it):
    q = f"{it.qty:g} {it.unit or ''}".strip() if it.qty is not None else "some"
    out = f"{q} {it.name}" + (f" ({it.prep})" if it.prep else "")
    sp = getattr(it, "staple_packs", None)
    return out + (f" — weekly staple: at least {sp} package{'' if sp == 1 else 's'}" if sp else "")


NONE_FIT = ""   # _choose: the AI said no candidate is the ingredient itself


def _choose(provider, it, cands) -> str | None:
    """AI pick: the chosen candidate's code, NONE_FIT when the AI says none of them is the needed ingredient, None
    when the AI fails. The quantity is the planner's (`planner.default_quantity`), checked with the rest of the cart by
    `qtycheck`."""
    mins = planner.min_packs(it, cands)

    def cand(p):
        out = {**p.model_dump(include={"code", "name", "brand", "package_size", "price"}), "min_packs": mins[p.code]}
        if desc := (p.description or "").strip():
            out["description"] = desc[:MAX_DESC]
        cov = bulk.cover(it, p)
        if cov:
            out["min_packs"], out["cover_cost"] = cov
            if not p.package_size:
                out["package_size"] = bulk.size_text(p)
        return out
    try:
        ch = provider.complete_json(PROMPT.format(need=_need(it), cands=json.dumps(
            [cand(p) for p in cands], ensure_ascii=False)))
    except AIError:
        return None
    if not isinstance(ch, dict):
        return None
    if "code" in ch and ch["code"] is None:
        return NONE_FIT
    return ch.get("code")


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
