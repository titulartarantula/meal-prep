"""Bulk packs: when a bigger pack covers the need for less.

Loblaws calls its bulk packs "Club Pack" (also "Club Size", "case"); a plain search often leaves them out
(live probe 2026-10-15: "lean ground beef" lists only 450 g trays, "lean ground beef club pack" brings the
1.75 kg club pack). So when no pack in the plain results covers the need on its own, or the line is a weekly
staple, matching and the chooser also search "<item> club pack" and keep the results of the same item.

What a product costs to cover the need (`cover`) uses the planner's floor; a club pack of meat has no package
size (priced by weight, sold by the tray), so its weight is estimated from price ÷ unit price. A bigger pack is
taken (`better_cover`) only when it covers the need in fewer packs for no more money: never "buy more to save"
on a perishable. A cheaper unit price at a higher total stays a hint (`drafts.better_value`)."""
import math
import re

from . import planner
from .ingredients import BASE, COUNT_UNITS, CONTAINERS

BULK_WORDS = "club pack"
# Words that name a pack or its size, not the product: "Lean Ground Beef, Club Pack" is the same kind as
# "Lean Ground Beef".
PACK_WORDS = {"bar", "bars", "block", "blocks", "pack", "packs", "club", "size", "value", "family", "bag", "bags",
              "box", "tub", "jar", "bottle", "can", "carton", "case", "format", "maxi", "bulk", "jumbo", "economy",
              "the", "and", "with", "of", "a"}
_UNIT = {"g": ("mass", 1), "kg": ("mass", 1000), "lb": ("mass", 453.6), "ml": ("vol", 1), "l": ("vol", 1000),
         "ea": ("count", 1)}


def _get(product, field):
    return product.get(field) if isinstance(product, dict) else getattr(product, field, None)


def _singular(w: str) -> str:
    return w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w


def kind(name: str | None) -> frozenset:
    """The product's name words without pack words, singular: what kind of thing it is."""
    return frozenset(_singular(w) for w in re.findall(r"[a-z]+", (name or "").lower()) if w not in PACK_WORDS)


def same_item(term: str, product) -> bool:
    """A bulk search result is kept only if its name has every word of the item ("chicken thigh" → "Chicken
    Thighs, Club Pack" yes, "Chicken Breasts, Club Pack" no)."""
    want = kind(term)
    return bool(want) and want <= kind(_get(product, "name"))


def est_amount(product) -> tuple[str | None, float | None]:
    """(family, amount in ml / g / count) of one pack: its package size, or for a pack sold by the piece but
    priced by weight (a club pack of meat) price ÷ unit price. (None, None) when neither is known, and for a
    product sold by weight at the counter (any amount can be ordered)."""
    if _get(product, "weighted"):
        return None, None
    pack = planner.parse_pack(_get(product, "package_size"), _get(product, "sold_by"))
    if pack.family and not pack.by_weight and not pack.priced_by_weight:
        return pack.family, pack.total
    price, up, unit = _get(product, "price"), _get(product, "unit_price"), _get(product, "unit_price_unit")
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)?\s*([a-z]+)\s*", (unit or "").lower())
    if not (price and up and m and m.group(2) in _UNIT) or up <= 0:
        return None, None
    fam, per = _UNIT[m.group(2)]
    return fam, price / up * float(m.group(1) or 1) * per


def _need(it, fam: str) -> float | None:
    """The line's need in a family's base (g, ml, count), or None."""
    if it.qty is None or it.qty <= 0:
        return None
    if fam == "mass":
        return planner.need_grams(it)
    if fam == "vol" and it.unit in BASE:
        f, n = planner._to_base(it.qty, it.unit)
        if f == "vol":
            return n
        d = planner.density(it)
        return n / d if d else None
    if fam == "count" and (it.unit is None or it.unit in set(COUNT_UNITS.values()) - CONTAINERS):
        return it.qty
    return None


def cover(it, product) -> tuple[int, float] | None:
    """(packs, cost) of covering the line's need with this product, or None when that can't be worked out
    (no price, no amount on the line, sizes that can't be compared, sold by weight at the counter)."""
    price = _get(product, "price")
    if price is None or _get(product, "weighted") or it.qty is None or getattr(it, "staple_packs", None):
        return None
    plan = planner.plan(it, product)
    if plan is not None and plan.enforce and plan.need is not None:
        packs = plan.packs_min
    else:
        fam, amt = est_amount(product)
        need = _need(it, fam) if fam else None
        if not need or not amt:
            return None
        packs = max(1, math.ceil(need * (1 - planner.SLACK) / amt - 1e-9))
        if packs > planner.MAX_PACKS:
            return None
    return packs, round(price * packs, 2)


_BULK_NAME = re.compile(r"\bclub\b|\bbulk\b|\bfamily (?:pack|size)\b|\bvalue (?:pack|size)\b|\bcase\b|\bformat maxi\b",
                        re.I)


def is_bulk(product, cov=None, current_cover=None) -> bool:
    """A bigger pack: it covers the need in fewer packs than the line's current product, or (no need to compare
    with) Loblaws names it a club / family / value pack."""
    if cov and current_cover:
        return cov[0] < current_cover[0]
    return bool(_BULK_NAME.search(_get(product, "name") or ""))


def wanted(it, cands) -> bool:
    """Search for club packs too: the line is a weekly staple, or no in-stock product of the plain search covers
    the need in one pack (and at least one could be compared)."""
    if getattr(it, "staple", False):
        return True
    covers = [c for c in (cover(it, p) for p in cands) if c]
    return bool(covers) and min(n for n, _ in covers) >= 2


def merge(term: str, found: list, extra: list) -> list:
    """The plain results, then the club-pack results of the same item that weren't in them."""
    seen = {_get(p, "code") for p in found}
    return found + [p for p in extra if _get(p, "code") not in seen and same_item(term, p)]


def better_cover(it, chosen, cands, out_of_stock=frozenset({"OUT", "OUT_OF_STOCK"})):
    """(product, saving) of the cheapest in-stock product of the same kind that covers the need in fewer packs
    than `chosen` and costs no more; None when there is none (or `chosen` covers it in one pack)."""
    mine = cover(it, chosen)
    if mine is None or mine[0] < 2:
        return None
    k = kind(_get(chosen, "name"))
    best = None
    for p in cands:
        if _get(p, "code") == _get(chosen, "code") or _get(p, "stock") in out_of_stock or kind(_get(p, "name")) != k:
            continue
        c = cover(it, p)
        if c and c[0] < mine[0] and c[1] <= mine[1] + 1e-9 and (best is None or c[1] < best[1][1]):
            best = (p, c)
    return (best[0], round(mine[1] - best[1][1], 2)) if best else None


def _kg(g: float) -> str:
    return f"{g / 1000:.2f}".rstrip("0").rstrip(".") + " kg"


def size_text(product) -> str:
    """The pack's size: its package size, or the estimated weight of a club pack without one ("≈ 1.75 kg")."""
    ps = (_get(product, "package_size") or "").strip()
    if ps:
        return ps
    fam, amt = est_amount(product)
    if fam == "mass" and amt:
        return f"≈ {_kg(amt)}" if amt >= 1000 else f"≈ {round(amt, -1):g} g"
    if fam == "vol" and amt:
        return f"≈ {amt / 1000:g} L" if amt >= 1000 else f"≈ {round(amt):g} mL"
    return "pack"


def swap_note(chosen, n_chosen: int, bigger, n_bigger: int, saving: float) -> str:
    """Why the build took a bigger pack than the AI's pick."""
    return (f"Bigger pack: {n_bigger} × {size_text(bigger)} instead of {n_chosen} × {size_text(chosen)} "
            f"— ${saving:.2f} less.")


def hint_note(bigger, n_bigger: int, saving: float) -> str:
    """A remembered pick is kept; the line says a bigger pack would cost less."""
    return (f"A bigger pack ({n_bigger} × {size_text(bigger)}) would cover this for ${saving:.2f} less "
            f"— Swap product to see it.")
