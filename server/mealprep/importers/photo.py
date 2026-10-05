import json, re
from ..ai.base import AIError
from ..models import Recipe
from .structure import parse_qty, structure_ingredients

PROMPT = """These images are consecutive pages of ONE recipe from a cookbook. They may arrive OUT OF ORDER: put them in reading order using the printed page numbers (and where text continues mid-sentence) before extracting; it may continue across pages, and ingredients may be on a different page from the method. Combine them into a single recipe.
Return ONLY JSON: {"title": str, "servings": int or null, "ingredients": [...], "steps": [each method step]}.
- "servings": the number of PEOPLE it serves, only if the page says so ("Serves 4", "6 servings", "For 4 to 6 people" → 4: use the lower number of a range). If it only gives a count of items ("Makes 48 pieces", "Makes 2 dozen cookies"), use null.
- "ingredients": every ingredient line exactly as printed, including the lines inside sub-recipes (e.g. a marinade or sauce). Do NOT include sub-recipe headings or section titles themselves (e.g. "Thai Green Curry Marinade", "For the sauce:"), and do NOT include equipment (pans, saucepans, baking dishes, molds) — only food lines. Keep lines that point to another recipe in the book (e.g. "Batter for 24 crêpes, page 191") exactly as printed. Read amount columns carefully; keep both metric and imperial if printed.
The recipe may START partway down the first page and END partway down the last page. Ignore fragments of other recipes: e.g. the end of the previous recipe at the top of the first page, or the start of the next recipe at the bottom of the last page. The target recipe is the one that continues across the pages (its title is usually on the first page, possibly mid-page); with a single page, pick the one recipe that is complete.
If there is no recipe, return {"title": null}."""

HINT = "\nThe recipe wanted is titled (or close to): {hint!r}. Extract only that recipe."


def _int_or_none(v):
    if isinstance(v, str):
        m = re.search(r"\d+", v)      # "4 to 6" → 4
        return int(m.group(0)) if m else None
    try:
        return int(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def import_photo(provider, image_paths: list[str], title_hint: str | None = None) -> Recipe:
    prompt = PROMPT + (HINT.format(hint=title_hint.strip()[:200]) if title_hint and title_hint.strip() else "")
    d = provider.complete_json(prompt, images=image_paths)
    if not isinstance(d, dict) or not d.get("title"):
        raise AIError("no recipe found in photo")
    lines = [str(x) for x in d.get("ingredients") or []]
    return Recipe(title=str(d["title"]), source="photo", servings=_int_or_none(d.get("servings")),
                  ingredients=structure_ingredients(provider, lines), steps=[str(s) for s in d.get("steps") or []])


SUB_PROMPT = """These images are cookbook page(s) with a sub-recipe that another recipe points to with this ingredient line: {line}{page}.
Find that sub-recipe on the page(s) — it may start or end mid-page; ignore every other recipe.
Return ONLY JSON: {{"title": short name of the sub-recipe (e.g. "Crêpe batter"), "ingredients": [every food line exactly as printed; no headings, no equipment], "steps": [each method step], "factor": number}}
- "factor": how many batches of the printed sub-recipe the referencing line needs (e.g. the line asks for batter for 24 crêpes and the page's recipe makes 12 → 2). Use 1 if the line gives no amount or it matches.
If the page(s) don't contain it, return {{"title": null}}."""


def import_subrecipe(provider, image_paths: list[str], ref_line: str, ref_page: int | None):
    """→ (name, ingredients scaled by the batch factor, steps)."""
    page = f" (page {ref_page})" if ref_page else ""
    d = provider.complete_json(SUB_PROMPT.format(line=json.dumps(ref_line, ensure_ascii=False), page=page),
                               images=image_paths)
    if not isinstance(d, dict) or not d.get("title") or not d.get("ingredients"):
        raise AIError("sub-recipe not found on the page(s)")
    factor = min(max(parse_qty(d.get("factor")) or 1.0, 0.1), 20.0)
    ings = structure_ingredients(provider, [str(x) for x in d["ingredients"]])
    ings = [i.model_copy(update={"qty": i.qty * factor}) if i.qty is not None else i for i in ings]
    return str(d["title"]), ings, [str(s) for s in d.get("steps") or []]
