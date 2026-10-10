"""Recipe ⇄ schema.org/Recipe JSON-LD.

Export (`to_jsonld`, `bundle`) writes the standard fields other apps read (name, recipeIngredient strings,
recipeInstructions as HowToStep / HowToSection, recipeYield, times, url, isBasedOn …) plus two namespaced blocks that
other apps ignore: `mealprep:recipe` (our structured lines and fields, so a Meal Prep → Meal Prep trip loses nothing)
and `mealprep:ratings` (the household's ratings and notes, per time cooked). Household ratings are never written as
`aggregateRating` (they are not public ratings).

Import (`from_jsonld`) reads one Recipe node back. A node with our `mealprep:recipe` block (format ≤ FORMAT) is read
from the block, losslessly. Any other node is mapped from the standard fields: ingredient strings go through the
deterministic clean-up (`ingredients.clean_ingredient`, pantry words), instructions in any of the shapes sites use,
yields, times, links (http(s) only, never fetched) and the source. Per-field caps keep a hostile file small."""
from datetime import date, datetime, timezone
import hashlib
import html as htmllib
import json
import re
import unicodedata
import uuid
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ValidationError, field_validator

from ..books import normalise_isbn
from ..importers.nyt import _servings, extract_nyt_url
from ..importers.structure import _ref_page
from ..ingredients import BASE, COUNT_UNITS, clean_ingredient, likely_on_hand, parse_amount
from ..models import Ingredient, Recipe, RecipeOut
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


def _utc(iso: str | None) -> str | None:
    """Timestamps are written in UTC, so the same moment reads the same from any server."""
    if not iso:
        return iso
    try:
        d = datetime.fromisoformat(iso)
    except ValueError:
        return iso
    return d.astimezone(timezone.utc).isoformat() if d.tzinfo else iso


def _drop_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


def to_jsonld(r: RecipeOut, entries: list[dict], *, ratings: bool = True, standalone: bool = True,
              created_at: datetime | None = None) -> dict:
    """One recipe as a schema.org Recipe node. `entries` = the times cooked (newest first: date, multiplier, family,
    company, note, rated_at). standalone = with its own @context (a single-recipe file); bundle nodes leave it out."""
    rid = f"urn:uuid:{r.uid}" if r.uid else None
    if r.yield_text and r.servings:
        yld = [str(r.servings), r.yield_text]
    elif r.yield_text:
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
        summary = r.ratings.model_dump(mode="json")
        summary["last_rated_at"] = _utc(summary["last_rated_at"])
        for n in summary["notes"]:
            n["rated_at"] = _utc(n["rated_at"])
        node[RATINGS] = {"summary": summary, "good_for_company": r.ratings.company == "yes", "entries": list(entries)}
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


# --- import ---------------------------------------------------------------------------------------------------------

MAX_TITLE, MAX_LINES, MAX_LINE, MAX_STEPS, MAX_STEP = 200, 300, 500, 200, 5000
MAX_TEXT, MAX_URL, MAX_ENTRIES = 5000, 2000, 1000
NEWER = "Made by a newer Meal Prep; some details were left out."
DROPPED_LINK = "Left out a link that isn't a plain web address"


class NotUsable(ValueError):
    """The node can't become a recipe (no name, or no ingredients and no steps); the message is the reason."""


class ImportedRating(BaseModel):
    """One time cooked from another library's export (mealprep:ratings.entries)."""
    date: str | None = None          # ISO date of the night, if known
    multiplier: float = 1.0
    family: int | None = None        # 1–5; None = cooked, not rated
    company: Literal["yes", "maybe", "no"] | None = None
    note: str | None = None
    rated_at: str | None = None      # ISO timestamp

    @field_validator("date")
    @classmethod
    def _date(cls, v):
        return None if v is None else date.fromisoformat(v).isoformat()

    @field_validator("rated_at")
    @classmethod
    def _rated_at(cls, v):
        if v is not None:
            datetime.fromisoformat(v)
        return v

    @field_validator("family")
    @classmethod
    def _family(cls, v):
        if v is not None and not 1 <= v <= 5:
            raise ValueError("family must be 1–5")
        return v

    @field_validator("multiplier")
    @classmethod
    def _multiplier(cls, v):
        if not 0 < v <= 100:
            raise ValueError("multiplier must be > 0")
        return v

    @field_validator("note")
    @classmethod
    def _note(cls, v):
        return (v.strip()[:2000] or None) if v is not None else None

    def fingerprint(self) -> str:
        """Same entry → same fingerprint: re-importing a file adds nothing."""
        key = json.dumps([self.date, self.multiplier, self.family, self.company, self.note, self.rated_at])
        return hashlib.sha256(key.encode()).hexdigest()[:32]


class Incoming(BaseModel):
    recipe: Recipe                   # uid always set; id None
    ratings: list[ImportedRating] = []
    warnings: list[str] = []
    origin: Literal["mealprep", "schema.org"]
    link: str | None = None          # the source link used for de-dup (NYT links normalised)


def clean_text(v, limit: int = MAX_TEXT) -> str | None:
    """Text from a JSON value: tags stripped, entities unescaped, whitespace collapsed, at most `limit` chars."""
    if isinstance(v, list):
        v = next((x for x in v if isinstance(x, str) and x.strip()), None)
    if not isinstance(v, str):
        return None
    v = v[: limit * 4]
    if "<" in v or "&" in v:   # markup: tags become spaces (no stray space left before punctuation)
        v = re.sub(r"\s+([.,;:!?])", r"\1", " ".join(htmllib.unescape(re.sub(r"<[^>]*>", " ", v)).split()))
    return " ".join(v.split())[:limit] or None


def safe_url(v) -> str | None:
    """An http(s) link without credentials, ≤ 2000 chars, else None."""
    if not isinstance(v, str):
        return None
    v = v.strip()
    if not v or len(v) > MAX_URL or any(c.isspace() or ord(c) < 32 for c in v):
        return None
    try:
        u = urlsplit(v)
    except ValueError:
        return None
    if u.scheme.lower() not in ("http", "https") or not u.hostname or "@" in u.netloc:
        return None
    return v


def _uuid(v) -> str | None:
    if isinstance(v, dict):
        v = v.get("value") or v.get("@id")
    if isinstance(v, list):
        return next((u for u in map(_uuid, v) if u), None)
    if not isinstance(v, str):
        return None
    v = v.strip()
    if v.lower().startswith("urn:uuid:"):
        v = v[9:]
    try:
        return str(uuid.UUID(v)) if len(v) in (32, 36) else None
    except ValueError:
        return None


def node_hash(node) -> str:
    """sha256 of the node's canonical JSON (keys sorted): the import's item key and the fallback uid seed."""
    return hashlib.sha256(json.dumps(node, sort_keys=True, ensure_ascii=False, default=str).encode()).hexdigest()


def _type_set(t) -> set[str]:
    """@type as a set of strings (a string or a list; anything else, e.g. an object, has no types)."""
    return {x for x in (t if isinstance(t, list) else [t]) if isinstance(x, str)}


def _heading(line: str) -> str | None:
    """"For the sauce:" → "Sauce"; "TOPPINGS:" → "Toppings"; a line with digits or no final colon is no heading."""
    if not line.endswith(":") or re.search(r"\d", line) or len(line) > 80:
        return None
    name = re.sub(r"^(?:for\s+(?:the\s+)?)", "", line[:-1].strip(), flags=re.I).strip()
    if not name:
        return None
    if name.isupper():
        name = name.lower()
    return name[0].upper() + name[1:]


def _flatten_lines(v, depth=0) -> list[str]:
    if depth > 8:
        return []
    if isinstance(v, str):
        return [v]
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return [str(v)]
    if isinstance(v, list):
        return [x for item in v for x in _flatten_lines(item, depth + 1)]
    if isinstance(v, dict):
        for k in ("text", "name"):
            if isinstance(v.get(k), str):
                return [v[k]]
        if "itemListElement" in v:
            return _flatten_lines(v["itemListElement"], depth + 1)
    return []


def foreign_ingredients(raw_lines: list, warnings: list[str]) -> list[Ingredient]:
    """Foreign ingredient strings → our lines (deterministic). Heading lines name the sub-recipe of the lines after
    them instead of being ingredients."""
    out: list[Ingredient] = []
    section = None
    for raw in raw_lines:
        line = clean_text(raw, MAX_LINE * 2)
        if not line:
            continue
        head = _heading(line)
        if head:
            section = head
            continue
        if len(line) > MAX_LINE:
            line = line[:MAX_LINE]
            if "Shortened a very long ingredient line" not in warnings:
                warnings.append("Shortened a very long ingredient line")
        if len(out) >= MAX_LINES:
            warnings.append(f"Only the first {MAX_LINES} ingredient lines were kept")
            break
        ing = clean_ingredient(Ingredient(raw=line, name=line.lower()))
        out.append(ing.model_copy(update={"likely_on_hand": likely_on_hand(ing.name), "ref_page": _ref_page(line),
                                          "sub_recipe": section}))
    return out


_NUMBERING = re.compile(r"^\s*(?:step\s*\d{1,3}\s*[:.)\-–]?|\d{1,3}\s*[.):])\s*", re.I)


def _html_steps(s: str) -> list[str]:
    s = re.sub(r"(?i)<\s*(?:br|/?li|/?p|/?div|/?ol|/?ul|/?h\d)\b[^>]*>", "\n", s)
    return [htmllib.unescape(re.sub(r"[ \t]+([.,;:!?])", r"\1", re.sub(r"<[^>]*>", " ", s)))]


def foreign_steps(v, warnings: list[str]) -> list[str]:
    """Instructions in any common shape → our steps. A HowToSection's name prefixes its steps ("Sauce: …", like our
    sub-recipe steps); nested sections take the innermost name."""
    out: list[str] = []

    def add(text: str, prefix: str | None):
        for part in re.split(r"\n+", text):
            part = _NUMBERING.sub("", " ".join(htmllib.unescape(part).split()), count=1).strip()
            if not part:
                continue
            if len(part) > MAX_STEP:
                part = part[:MAX_STEP]
            out.append(f"{prefix}: {part}" if prefix else part)

    def walk(x, prefix, depth):
        if depth > 16 or len(out) > MAX_STEPS:
            return
        if isinstance(x, str):
            add(_html_steps(x)[0] if "<" in x else x, prefix)
        elif isinstance(x, list):
            for y in x:
                walk(y, prefix, depth + 1)
        elif isinstance(x, dict):
            types = _type_set(x.get("@type"))
            if "HowToSection" in types:
                name = clean_text(x.get("name"), 200)
                walk(x.get("itemListElement"), name or prefix, depth + 1)
            elif isinstance(x.get("text"), str) and x["text"].strip():
                add(_html_steps(x["text"])[0] if "<" in x["text"] else x["text"], prefix)
            elif "itemListElement" in x:
                walk(x["itemListElement"], prefix, depth + 1)
            elif "item" in x:
                walk(x["item"], prefix, depth + 1)
            elif isinstance(x.get("name"), str):
                add(x["name"], prefix)

    walk(v, None, 0)
    if len(out) > MAX_STEPS:
        warnings.append(f"Only the first {MAX_STEPS} steps were kept")
        out = out[:MAX_STEPS]
    return out


def _yield(v) -> tuple[int | None, str | None]:
    """(servings, yield_text): people served by the nyt._servings rules (a count of things is "made as written");
    the first text form is kept as yield_text only when there are no servings."""
    vals = v if isinstance(v, list) else [v]
    flat = []
    for x in vals[:10]:
        if isinstance(x, dict):
            x = x.get("value", x.get("name"))
        if isinstance(x, bool) or x is None:
            continue
        if isinstance(x, (int, float)):
            x = str(int(x)) if float(x).is_integer() else str(x)
        if isinstance(x, str) and x.strip():
            flat.append(" ".join(x.split())[:200])
    servings = _servings(flat) if flat else None
    if servings is not None and not 1 <= servings <= 100:
        servings = None
    text = None if servings is not None else next((x for x in flat if not x.isdigit()), flat[0] if flat else None)
    return servings, text


def _names(v) -> str | None:
    """Author/publisher name(s): a string, a Person/Organization, or a list of them → "A. Cook, B. Baker"."""
    vals = v if isinstance(v, list) else [v]
    names = [clean_text(x.get("name") if isinstance(x, dict) else x, 200) for x in vals[:10]]
    return ", ".join(n for n in names if n)[:200] or None


def _image(v) -> str | None:
    if isinstance(v, list):
        v = v[0] if v else None
    if isinstance(v, dict):
        v = v.get("url") or v.get("contentUrl")
    return v if isinstance(v, str) else None


def _host(url: str) -> str | None:
    h = (urlsplit(url).hostname or "").lower()
    return h[4:] if h.startswith("www.") else h or None


def _extra(node: dict, warnings: list[str]) -> dict:
    extra = {k: node[k] for k in EXTRA_KEYS if k in node and node[k] not in (None, "", [], {})}
    if extra and len(json.dumps(extra, ensure_ascii=False, default=str).encode()) > EXTRA_MAX_BYTES:
        warnings.append("Left out extra details (categories, nutrition …) that were too long")
        return {}
    return json.loads(json.dumps(extra, default=str)) if extra else {}


def _foreign_source(node: dict, url: str | None, warnings: list[str]) -> dict:
    """source_kind/title/ref/author/isbn (+ source_url) from url, isBasedOn, publisher and author."""
    if url and _host(url) in ("cooking.nytimes.com",) and extract_nyt_url(url):
        return {"source_kind": "nyt", "source_url": extract_nyt_url(url)}
    based = node.get("isBasedOn")
    if isinstance(based, list):
        based = next((b for b in based if isinstance(b, dict)), based[0] if based else None)
    src: dict = {"source_kind": "other", "source_url": url}
    if isinstance(based, dict):
        types = _type_set(based.get("@type"))
        title = clean_text(based.get("name"), 200)
        if "Book" in types or based.get("isbn"):
            isbn = None
            if based.get("isbn"):
                try:
                    isbn = normalise_isbn(str(based["isbn"]))
                except ValueError:
                    warnings.append("Left out an ISBN that isn't 10 or 13 digits")
            if title:
                return {**src, "source_kind": "book", "source_title": title,
                        "source_ref": clean_text(based.get("mealprep:page"), 50),
                        "source_author": _names(based.get("author")), "source_isbn": isbn}
        elif title:
            return {**src, "source_title": title, "source_ref": clean_text(based.get("mealprep:note"), 50)}
    elif isinstance(based, str) and not url and safe_url(based):
        return _foreign_source({**node, "isBasedOn": None}, safe_url(based), warnings)
    title = _names(node.get("publisher")) or (_names(node.get("author")) if not isinstance(based, dict) else None)
    src["source_title"] = (title or (_host(url) if url else None))
    if src["source_title"]:
        src["source_title"] = src["source_title"][:200]
    return src


def _from_foreign(node: dict, warnings: list[str]) -> Incoming:
    title = clean_text(node.get("name"), MAX_TITLE * 4)
    if not title:
        raise NotUsable("No recipe name")
    if len(title) > MAX_TITLE:
        title = title[:MAX_TITLE].rstrip()
        warnings.append("Shortened a very long recipe name")
    lines = _flatten_lines(node.get("recipeIngredient", node.get("ingredients")))
    ingredients = foreign_ingredients(lines[: MAX_LINES * 2], warnings)
    steps = foreign_steps(node.get("recipeInstructions"), warnings)
    if not ingredients and not steps:
        raise NotUsable("No ingredients or steps")
    raw_url = node.get("url") if isinstance(node.get("url"), str) else None
    url = safe_url(raw_url)
    raw_image = _image(node.get("image"))
    image = safe_url(raw_image)
    if (raw_url and raw_url.strip() and not url) or (raw_image and raw_image.strip() and not image):
        warnings.append(DROPPED_LINK)
    servings, yield_text = _yield(node.get("recipeYield"))
    src = _foreign_source(node, url, warnings)
    uid = _uuid(node.get("identifier")) or _uuid(node.get("@id"))
    if not uid:
        seed = src.get("source_url") or url or (node.get("@id") if isinstance(node.get("@id"), str) else None)
        uid = str(uuid.uuid5(uuid.NAMESPACE_URL, seed)) if seed else \
            str(uuid.uuid5(uuid.NAMESPACE_OID, "mealprep-import:" + node_hash(node)))
    r = Recipe(title=title, source="import", servings=servings, ingredients=ingredients, steps=steps, uid=uid,
               description=clean_text(node.get("description"), 2000), image=image, yield_text=yield_text,
               prep_minutes=durations.parse(node.get("prepTime")), cook_minutes=durations.parse(node.get("cookTime")),
               total_minutes=durations.parse(node.get("totalTime")), schema_extra=_extra(node, warnings), **src)
    return Incoming(recipe=r, warnings=warnings, origin="schema.org", link=r.source_url)


def _opt_str(v, limit):
    return v[:limit] if isinstance(v, str) and v.strip() else None


def _opt_int(v):
    return v if isinstance(v, int) and not isinstance(v, bool) and 0 < v <= durations.MAX_MINUTES else None


def _from_block(node: dict, block: dict, warnings: list[str]) -> Incoming:
    """Our own export: the block wins (lossless); ratings come from mealprep:ratings.entries."""
    title = _opt_str(block.get("title"), MAX_TITLE) or clean_text(node.get("name"), MAX_TITLE)
    if not title:
        raise NotUsable("No recipe name")
    raw_ings = block.get("ingredients") if isinstance(block.get("ingredients"), list) else []
    try:
        ingredients = [Ingredient.model_validate(i) for i in raw_ings[:MAX_LINES]]
        if any(len(i.raw) > MAX_STEP or len(i.name) > MAX_STEP or len(i.prep or "") > MAX_STEP
               or len(i.sub_recipe or "") > MAX_TITLE for i in ingredients):
            raise ValueError("ingredient text too long")
    except (ValidationError, ValueError):
        warnings.append("Some ingredient details couldn't be read; the ingredient lines were tidied again")
        return _from_foreign(node, warnings)
    steps = [s[:MAX_STEP] for s in block.get("steps", []) if isinstance(s, str)][:MAX_STEPS] \
        if isinstance(block.get("steps"), list) else []
    if not ingredients and not steps:
        raise NotUsable("No ingredients or steps")
    src = block.get("source") if isinstance(block.get("source"), dict) else {}
    url = src.get("url")
    if url is not None and not safe_url(url):
        warnings.append(DROPPED_LINK)
        url = None
    kind = src.get("kind") if src.get("kind") in ("nyt", "book", "other") else None
    isbn = None
    if src.get("isbn"):
        try:
            isbn = normalise_isbn(str(src["isbn"]))
        except ValueError:
            warnings.append("Left out an ISBN that isn't 10 or 13 digits")
    image = block.get("image")
    if image is not None and not safe_url(image):
        warnings.append(DROPPED_LINK)
        image = None
    servings = block.get("servings")
    servings = servings if isinstance(servings, int) and not isinstance(servings, bool) and 0 < servings <= 100 else None
    extra = block.get("schema_extra") if isinstance(block.get("schema_extra"), dict) else {}
    extra = _extra({k: v for k, v in extra.items() if k in EXTRA_KEYS}, warnings)
    uid = _uuid(block.get("uid")) or _uuid(node.get("identifier")) or _uuid(node.get("@id")) or \
        str(uuid.uuid5(uuid.NAMESPACE_OID, "mealprep-import:" + node_hash(node)))
    r = Recipe(title=title, source=_opt_str(src.get("imported_via"), 20) or "import", source_url=url,
               servings=servings, ingredients=ingredients, steps=steps, uid=uid, source_kind=kind,
               source_title=_opt_str(src.get("title"), 200), source_ref=_opt_str(src.get("ref"), 50),
               source_author=_opt_str(src.get("author"), 200), source_isbn=isbn,
               description=_opt_str(block.get("description"), 2000), notes=_opt_str(block.get("notes"), MAX_TEXT),
               prep_minutes=_opt_int(block.get("prep_minutes")), cook_minutes=_opt_int(block.get("cook_minutes")),
               total_minutes=_opt_int(block.get("total_minutes")), yield_text=_opt_str(block.get("yield_text"), 200),
               image=image, schema_extra=extra)
    ratings, bad = [], 0
    rblock = node.get(RATINGS) if isinstance(node.get(RATINGS), dict) else {}
    for e in (rblock.get("entries") if isinstance(rblock.get("entries"), list) else [])[:MAX_ENTRIES]:
        try:
            ratings.append(ImportedRating.model_validate(e))
        except (ValidationError, TypeError, ValueError):
            bad += 1
    if bad:
        warnings.append(f"Left out {bad} rating{'s' if bad > 1 else ''} that couldn't be read")
    link = extract_nyt_url(url) if url and kind == "nyt" and extract_nyt_url(url) else url
    return Incoming(recipe=r, ratings=ratings, warnings=warnings, origin="mealprep", link=link)


def from_jsonld(node: dict) -> Incoming:
    """One Recipe node → Incoming (raises NotUsable with a plain reason)."""
    if not isinstance(node, dict):
        raise NotUsable("Not a recipe")
    warnings: list[str] = []
    block = node.get(BLOCK)
    if isinstance(block, dict):
        fmt = block.get("format")
        if isinstance(fmt, int) and not isinstance(fmt, bool) and fmt <= FORMAT:
            return _from_block(node, block, warnings)
        warnings.append(NEWER)
    return _from_foreign(node, warnings)
