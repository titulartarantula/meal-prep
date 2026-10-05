from .models import ListItem

# Convertible units: (dimension, size in the dimension's base unit). Quantities are summed in the
# base unit (tsp / g) and reported in the smallest unit any merged line used.
_BASE = {"tsp": ("vol", 1), "tbsp": ("vol", 3), "cup": ("vol", 48), "ml": ("vol", 0.2029), "l": ("vol", 202.9),
         "g": ("mass", 1), "kg": ("mass", 1000), "oz": ("mass", 28.35), "lb": ("mass", 453.6)}


def scale_factor(servings: int | None, multiplier: float, people: int = 4) -> float:
    """people/servings × multiplier; a recipe without servings is made as written × multiplier."""
    return people / servings * multiplier if servings else multiplier


def build_list(entries, people: int = 4) -> list[ListItem]:
    """entries: [(Recipe, multiplier)]. Scale each recipe to `people` × multiplier, merge like items."""
    items: dict[str, ListItem] = {}
    base_qty: dict[str, float | None] = {}
    for recipe, mult in entries:
        factor = scale_factor(recipe.servings, mult, people)
        for ing in recipe.ingredients:
            if ing.expanded:   # covered by the attached sub-recipe's own lines
                continue
            dim, size = _BASE.get(ing.unit, (ing.unit or "each", 1))
            qty = None if ing.qty is None else ing.qty * size * factor
            key = f"{ing.name}|{dim}"
            it = items.get(key)
            if it is None:
                items[key] = ListItem(key=key, name=ing.name, qty=None, unit=ing.unit, prep=ing.prep,
                                      likely_on_hand=ing.likely_on_hand, needed=not ing.likely_on_hand,
                                      recipes=[recipe.title])
                base_qty[key] = qty
            else:
                prev = base_qty[key]
                base_qty[key] = None if (prev is None or qty is None) else prev + qty
                if ing.unit in _BASE and _BASE[ing.unit][1] < _BASE[it.unit][1]:
                    it.unit = ing.unit
                if recipe.title not in it.recipes:
                    it.recipes.append(recipe.title)
    for key, it in items.items():
        q = base_qty[key]
        if q is not None:
            it.qty = round(q / _BASE.get(it.unit, ("", 1))[1], 2)
    return list(items.values())
