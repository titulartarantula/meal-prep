"""Attach a cross-referenced sub-recipe ("Batter for 24 crêpes, page 191") read from a photo of its page."""
from .models import Ingredient, Recipe


class AlreadyAttached(Exception):
    pass


def choose_ref_line(r: Recipe, for_line: int | None) -> int:
    """The ingredient line to expand: for_line if given, else the only un-attached line with a page reference."""
    if for_line is not None:
        if not 0 <= for_line < len(r.ingredients):
            raise LookupError(f"for_line {for_line} is out of range (the recipe has {len(r.ingredients)} ingredient lines)")
        if r.ingredients[for_line].expanded:
            raise AlreadyAttached(f"line {for_line} already has its sub-recipe attached")
        return for_line
    refs = [i for i, ing in enumerate(r.ingredients) if ing.ref_page is not None and not ing.expanded]
    if not refs:
        raise LookupError("no ingredient line refers to another page; pass for_line")
    if len(refs) > 1:
        lines = ", ".join(f"{i}: {r.ingredients[i].raw!r}" for i in refs)
        raise LookupError(f"several lines refer to other pages ({lines}); pass for_line")
    return refs[0]


def attach_subrecipe(r: Recipe, line: int, name: str, ingredients: list[Ingredient], steps: list[str]) -> Recipe:
    """Insert the sub-recipe's ingredients after the referencing line (marked with its name), mark that line
    expanded (no longer bought itself, ref_page cleared) and put the sub-recipe's steps first."""
    ings = list(r.ingredients)
    ings[line] = ings[line].model_copy(update={"ref_page": None, "expanded": True, "sub_recipe": name})
    ings[line + 1:line + 1] = [i.model_copy(update={"sub_recipe": name}) for i in ingredients]
    return r.model_copy(update={"ingredients": ings, "steps": [f"{name}: {s}" for s in steps] + list(r.steps)})
