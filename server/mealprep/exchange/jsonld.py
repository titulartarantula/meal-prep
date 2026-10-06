"""Recipe ⇄ schema.org/Recipe JSON-LD.

Export (`to_jsonld`, `bundle`) writes the standard fields other apps read (name, recipeIngredient strings,
recipeInstructions as HowToStep / HowToSection, recipeYield, times, url, isBasedOn …) plus two namespaced blocks that
other apps ignore: `mealprep:recipe` (our structured lines and fields, so a Meal Prep → Meal Prep trip loses nothing)
and `mealprep:ratings` (the household's ratings and notes, per time cooked). Household ratings are never written as
`aggregateRating` (they are not public ratings)."""
from datetime import datetime
import re
import unicodedata

from ..ingredients import BASE, COUNT_UNITS, parse_amount
from ..models import Ingredient, RecipeOut
from . import durations

CONTEXT = ["https://schema.org", {"mealprep": "https://github.com/titulartarantula/meal-prep/ns/v1#"}]
FORMAT = 1
BLOCK, RATINGS, EXPORT = "mealprep:recipe", "mealprep:ratings", "mealprep:export"
# schema.org keys passed through untouched (Recipe.schema_extra); everything else in a foreign file is left out.
EXTRA_KEYS = ("recipeCategory", "recipeCuisine", "keywords", "suitableForDiet", "nutrition", "tool", "author",
              "datePublished")
EXTRA_MAX_BYTES = 16 * 1024

_FRACTIONS = {0.5: "½", 1 / 3: "⅓", 2 / 3: "⅔", 0.25: "¼", 0.75: "¾", 0.125: "⅛", 0.375: "⅜", 0.625: "⅝",
              0.875: "⅞"}
_PLURAL = {"cup": "cups", **{u: u + "s" for u in set(COUNT_UNITS.values())},
           "box": "boxes", "bunch": "bunches", "pinch": "pinches", "dash": "dashes"}


def fmt_qty(q: float) -> str:
    """3 → "3", 1.5 → "1 ½", 0.333 → "⅓", 2.4 → "2.4" (fractions a cook reads; decimals otherwise)."""
    whole = int(q)
    frac = q - whole
    if frac < 0.01:
        return str(whole)
    if frac > 0.99:
        return str(whole + 1)
    for f, s in _FRACTIONS.items():
        if abs(frac - f) < 0.01:
            return f"{whole} {s}" if whole else s
    return f"{q:.2f}".rstrip("0").rstrip(".")


def _same_amount(ing: Ingredient, amt) -> bool:
    """Does the structured amount say what the raw line says? Different units of one dimension are compared in base
    units; amounts that can't be compared count as the same (the raw line is kept)."""
    if ing.qty is None or amt.qty is None:
        return True
    a, b = ing.qty, amt.qty
    if ing.unit != amt.unit:
        if ing.unit in BASE and amt.unit in BASE and BASE[ing.unit][0] == BASE[amt.unit][0]:
            a, b = a * BASE[ing.unit][1], b * BASE[amt.unit][1]
        else:
            return True
    return abs(a - b) <= 1e-3 * max(abs(a), abs(b), 1e-9)


def ingredient_line(ing: Ingredient) -> str:
    """The standard recipeIngredient string: the raw line, except when the structured amount differs from what the raw
    line reads (a sub-recipe attached from another page is scaled by its batch factor, but its raw text is not): then
    the line is written from the structured amount, so other apps don't under-buy ("1 1/2 cups flour" ×2 → "3 cups
    flour")."""
    amt = parse_amount(ing.raw)
    if _same_amount(ing, amt):
        return ing.raw
    unit = ing.unit
    if unit and ing.qty > 1:
        unit = _PLURAL.get(unit, unit)
    notes = f"({', '.join(amt.notes)})" if amt.notes else ""
    rest = amt.rest or (ing.name + (f", {ing.prep}" if ing.prep else ""))
    return " ".join(p for p in (fmt_qty(ing.qty), notes, unit, rest) if p)


def _instructions(r: RecipeOut) -> list[dict]:
    """HowToStep per step; consecutive "<sub-recipe>: …" steps of a sub-recipe in the ingredients form a HowToSection
    named after it (prefix removed). Empty steps are left out here (kept in the block)."""
    subs = {i.sub_recipe for i in r.ingredients if i.sub_recipe}
    out: list[dict] = []
    for s in r.steps:
        if not s.strip():
            continue
        name, sep, text = s.partition(": ")
        if sep and name in subs and text.strip():
            if out and out[-1]["@type"] == "HowToSection" and out[-1]["name"] == name:
                out[-1]["itemListElement"].append({"@type": "HowToStep", "text": text})
            else:
                out.append({"@type": "HowToSection", "name": name,
                            "itemListElement": [{"@type": "HowToStep", "text": text}]})
        else:
            out.append({"@type": "HowToStep", "text": s})
    return out


def _based_on(r: RecipeOut):
    if r.source_kind == "nyt":
        return r.source_url
    if r.source_kind == "book" and r.source_title:
        b = {"@type": "Book", "name": r.source_title, "author": r.source_author, "isbn": r.source_isbn,
             "mealprep:page": r.source_ref}
        return {k: v for k, v in b.items() if v is not None}
    if r.source_kind == "other" and r.source_title:
        o = {"@type": "CreativeWork", "name": r.source_title, "mealprep:note": r.source_ref}
        return {k: v for k, v in o.items() if v is not None}
    return None


def _drop_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


def to_jsonld(r: RecipeOut, entries: list[dict], *, ratings: bool = True, standalone: bool = True,
              created_at: datetime | None = None) -> dict:
    """One recipe as a schema.org Recipe node. `entries` = the times cooked (newest first: date, multiplier, family,
    company, note, rated_at). standalone = with its own @context (a single-recipe file); bundle nodes leave it out."""
    rid = f"urn:uuid:{r.uid}" if r.uid else None
    if r.yield_text:
        yld = r.yield_text
    elif r.servings:
        yld = [str(r.servings), f"{r.servings} servings"]
    else:
        yld = None
    node = _drop_none({
        "@context": CONTEXT if standalone else None,
        "@type": "Recipe",
        "@id": rid,
        "identifier": rid,
        "name": r.title,
        "description": r.description,
        "image": r.image,
        "url": r.source_url,
        "isBasedOn": _based_on(r),
        "recipeYield": yld,
        "prepTime": durations.to_iso(r.prep_minutes),
        "cookTime": durations.to_iso(r.cook_minutes),
        "totalTime": durations.to_iso(r.total_minutes),
        "dateCreated": created_at.isoformat() if created_at else None,
        "recipeIngredient": [ingredient_line(i) for i in r.ingredients],
        "recipeInstructions": _instructions(r),
    })
    node[BLOCK] = {
        "format": FORMAT, "uid": r.uid, "title": r.title, "servings": r.servings, "made_as_written": r.servings is None,
        "source": {"kind": r.source_kind, "title": r.source_title, "ref": r.source_ref, "author": r.source_author,
                   "isbn": r.source_isbn, "url": r.source_url, "imported_via": r.source},
        "ingredients": [i.model_dump(mode="json") for i in r.ingredients], "steps": list(r.steps),
        "description": r.description, "notes": r.notes, "prep_minutes": r.prep_minutes,
        "cook_minutes": r.cook_minutes, "total_minutes": r.total_minutes, "yield_text": r.yield_text,
        "image": r.image, "schema_extra": dict(r.schema_extra),
    }
    if ratings:
        node[RATINGS] = {"summary": r.ratings.model_dump(mode="json"), "good_for_company": r.ratings.company == "yes",
                         "entries": list(entries)}
    for k, v in r.schema_extra.items():   # last, and never over a key written above
        if k in EXTRA_KEYS and k not in node:
            node[k] = v
    return node


def bundle(nodes: list[dict], exported_at: datetime) -> dict:
    """The library file: one @graph of Recipe nodes (each without its own @context)."""
    return {"@context": CONTEXT, "@graph": nodes,
            EXPORT: {"format": FORMAT, "exported_at": exported_at.isoformat(), "count": len(nodes)}}


def slug(title: str, rid: int) -> str:
    """ASCII file-name stem from a title: "Crème brûlée / “best”" → "creme-brulee-best" (≤ 60 chars, never empty)."""
    s = unicodedata.normalize("NFKD", title or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:60].strip("-")
    return s or f"recipe-{rid}"
