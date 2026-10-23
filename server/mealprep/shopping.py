import math

from .fridge import amount_in_base
from .ingredients import BASE as _BASE, clean_ingredient, item_key, unit_dim
from .models import Ingredient, ListItem
from .staples import STAPLES, buy_name, name_key

# Convertible units (`ingredients.BASE`): quantities are summed in the dimension's base unit (tsp / g) and
# reported in the smallest unit any merged line used, stepping up to a larger one seen when that runs past 12
# ("26 tbsp" → "1.62 cup"). Lines merge on the cleaned name + unit family, so "1/3 cup whole milk" and
# "1/2 cup whole milk, warmed" are one line; "2 cloves garlic" and "1 tbsp garlic" are not.


def scale_factor(servings: int | None, multiplier: float, people: int = 4) -> float:
    """people/servings × multiplier; a recipe without servings is made as written × multiplier."""
    return people / servings * multiplier if servings else multiplier


def _add_prep(it: ListItem, prep: str | None) -> None:
    if prep and prep.lower() not in {p.lower() for p in (it.prep or "").split("; ") if p}:
        it.prep = f"{it.prep}; {prep}" if it.prep else prep


def build_list(entries, people: int = 4, staples=(), fridge=()) -> list[ListItem]:
    """entries: [(Recipe, multiplier)]. Scale each recipe to `people` × multiplier, merge like items.

    `staples` (models.Staple, see `staples.py`) join the same merge: one with a unit adds its amount like a
    recipe line (never scaled); one without a unit counts packs and joins a line for the same item (any unit)
    as a floor (`staple_packs`), or stands alone. Lines with a staple are `staple`, needed and not
    likely-on-hand (the shopper ticked the staple), and list "Staples" among their recipes.

    `fridge` (models.FridgeItem) is subtracted once, after the merge, from the recipes' amount (never from a staple's):
    see `_fridge_cover` and `_apply_fridge`. No fridge = the list as before."""
    items: dict[str, ListItem] = {}
    base_qty: dict[str, float | None] = {}
    seen_units: dict[str, set[str]] = {}

    def add(title: str, ing, factor: float) -> str:
        ing = clean_ingredient(ing)
        size = _BASE[ing.unit][1] if ing.unit in _BASE else 1
        qty = None if ing.qty is None else ing.qty * size * factor
        key = item_key(ing.name, unit_dim(ing.unit))
        it = items.get(key)
        if it is None:
            items[key] = ListItem(key=key, name=ing.name, qty=None, unit=ing.unit, prep=ing.prep,
                                  likely_on_hand=ing.likely_on_hand, needed=not ing.likely_on_hand,
                                  recipes=[title])
            base_qty[key] = qty
            seen_units[key] = {ing.unit} if ing.unit in _BASE else set()
        else:
            prev = base_qty[key]
            base_qty[key] = None if (prev is None or qty is None) else prev + qty
            if ing.unit in _BASE:
                seen_units[key].add(ing.unit)
                if _BASE[ing.unit][1] < _BASE[it.unit][1]:
                    it.unit = ing.unit
            _add_prep(it, ing.prep)
            if title not in it.recipes:
                it.recipes.append(title)
        return key

    for recipe, mult in entries:
        factor = scale_factor(recipe.servings, mult, people)
        for ing in recipe.ingredients:
            if ing.expanded:   # covered by the attached sub-recipe's own lines
                continue
            add(recipe.title, ing, factor)
    cover = _fridge_cover(items, base_qty, fridge) if fridge else {}
    measured = {add(STAPLES, Ingredient(raw="", name=buy_name(s.name), qty=s.qty, unit=s.unit), 1)
                for s in staples if s.unit and s.qty}
    for key, it in items.items():
        q = base_qty[key]
        if q is not None:
            if it.unit in _BASE:
                for u in sorted(seen_units[key], key=lambda u: _BASE[u][1]):   # step up past "26 tbsp"
                    if q / _BASE[it.unit][1] > 12 and q / _BASE[u][1] >= 1:
                        it.unit = u
            it.qty = round(q / (_BASE[it.unit][1] if it.unit in _BASE else 1), 2)
    out = _fold_unmeasured(list(items.values()))
    for it in out:
        if it.key in measured:
            _mark_staple(it)
    for s in staples:
        if not (s.unit and s.qty):
            _add_packs(out, s)
    for it in out:
        if it.key in cover:
            _apply_fridge(it, base_qty[it.key], *cover[it.key])
    return out


def _fridge_cover(items: dict[str, ListItem], base_qty: dict, fridge) -> dict[str, tuple[float, list[int]]]:
    """{line key: (base amount the fridge covers, item ids)} over the recipes' merged lines. An item counts only on the
    line of its name in its own unit family (fridge.uses' rule: volume, mass, or the same count unit; never volume vs
    mass) and only with an amount; it covers at most the recipes' amount. The item's other lines of that name — and
    every line of it when it has no amount — get 0: "check you have enough"."""
    out: dict[str, tuple[float, list[int]]] = {}
    left = {k: q for k, q in base_qty.items()}
    for f in sorted(fridge, key=lambda f: f.id):
        if f.qty is not None and f.qty <= 1e-9:
            continue                                    # used up: nothing there
        lines = [k for k, it in items.items() if name_key(it.name) == f.key]
        have = amount_in_base(f.qty, f.unit)
        for k in lines:
            covered, ids = out.get(k, (0.0, []))
            dim = k.rpartition("|")[2]
            if have is not None and dim == have[0] and left[k] is not None and left[k] > 1e-9 and have[1] > 1e-9:
                c = min(left[k], have[1])
                left[k] -= c
                have = (have[0], have[1] - c)
                covered += c
            out[k] = (covered, ids + [f.id])
    return out


def _apply_fridge(it: ListItem, total: float | None, covered: float, ids: list[int]) -> None:
    size = _BASE[it.unit][1] if it.unit in _BASE else 1
    shown = round(covered / size, 2)
    if shown <= 0:                                      # nothing that converts (or too little to show)
        if covered > 0:
            return
        it.fridge, it.fridge_items = "check", ids
        it.needed = it.needed if it.staple else False
        return
    it.fridge_items = ids
    rest = round(max(0.0, total - covered) / size, 2)
    if rest <= 0 and not it.staple:
        it.fridge, it.fridge_qty, it.needed = "covered", it.qty, False
        return
    it.fridge, it.qty, it.fridge_qty = "partial", rest, shown


def _mark_staple(it: ListItem, packs: int | None = None) -> None:
    it.staple, it.needed, it.likely_on_hand = True, True, False
    if packs:
        it.staple_packs = max(it.staple_packs or 0, packs)
    if STAPLES not in it.recipes:
        it.recipes.append(STAPLES)


def _add_packs(out: list[ListItem], s) -> None:
    """A staple counted in packs: a floor on the same item's line (measured first), else a line of its own."""
    name = buy_name(s.name)
    k = item_key(name, "")
    same = [it for it in out if item_key(it.name, "") == k]
    target = next((it for it in same if it.qty is not None), same[0] if same else None)
    packs = max(1, math.ceil((s.qty or 1) - 1e-9))
    if target is None:
        target = ListItem(key=item_key(name, "each"), name=name, recipes=[])
        out.append(target)
    _mark_staple(target, packs)


def _fold_unmeasured(items: list[ListItem]) -> list[ListItem]:
    """A line with no amount at all ("salt, to taste") joins a measured line of the same item instead of
    standing alone; it can't change the amount to buy."""
    by_name: dict[str, ListItem] = {}
    for it in items:
        if it.qty is not None:
            by_name.setdefault(item_key(it.name, ""), it)
    out = []
    for it in items:
        target = by_name.get(item_key(it.name, "")) if it.qty is None and it.unit is None else None
        if target is None:
            out.append(it)
            continue
        _add_prep(target, it.prep)
        target.recipes += [r for r in it.recipes if r not in target.recipes]
    return out
