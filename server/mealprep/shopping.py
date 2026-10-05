from .ingredients import BASE as _BASE, clean_ingredient, item_key, unit_dim
from .models import ListItem

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


def build_list(entries, people: int = 4) -> list[ListItem]:
    """entries: [(Recipe, multiplier)]. Scale each recipe to `people` × multiplier, merge like items."""
    items: dict[str, ListItem] = {}
    base_qty: dict[str, float | None] = {}
    seen_units: dict[str, set[str]] = {}
    for recipe, mult in entries:
        factor = scale_factor(recipe.servings, mult, people)
        for ing in recipe.ingredients:
            if ing.expanded:   # covered by the attached sub-recipe's own lines
                continue
            ing = clean_ingredient(ing)
            size = _BASE[ing.unit][1] if ing.unit in _BASE else 1
            qty = None if ing.qty is None else ing.qty * size * factor
            key = item_key(ing.name, unit_dim(ing.unit))
            it = items.get(key)
            if it is None:
                items[key] = ListItem(key=key, name=ing.name, qty=None, unit=ing.unit, prep=ing.prep,
                                      likely_on_hand=ing.likely_on_hand, needed=not ing.likely_on_hand,
                                      recipes=[recipe.title])
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
                if recipe.title not in it.recipes:
                    it.recipes.append(recipe.title)
    for key, it in items.items():
        q = base_qty[key]
        if q is not None:
            if it.unit in _BASE:
                for u in sorted(seen_units[key], key=lambda u: _BASE[u][1]):   # step up past "26 tbsp"
                    if q / _BASE[it.unit][1] > 12 and q / _BASE[u][1] >= 1:
                        it.unit = u
            it.qty = round(q / (_BASE[it.unit][1] if it.unit in _BASE else 1), 2)
    return _fold_unmeasured(list(items.values()))


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
