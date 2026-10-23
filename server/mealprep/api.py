import logging, re, tempfile, threading
from datetime import date, datetime, timedelta, timezone
from typing import Annotated, Callable, Literal

import httpx
import psycopg
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from . import books, db, drafts, extras, fridge, prepplan, recipe_edit, staples, subrecipe
from .ai import AIError, get_provider
from .exchange import importer, jsonld, safe
from .config import Settings
from .importers.nyt import BASE as NYT_BASE, extract_nyt_url, fetch_nyt_recipe, parse_nyt_html, recipe_id as nyt_id
from .importers.photo import import_photo, import_subrecipe
from .importers.structure import structure_ingredients
from .matcher import fill_cart
from .models import EDITABLE_FIELDS, FridgeItem, ListItem, PlanEntry, Recipe, RecipeDetail, RecipeOut, SourceKind, Staple, recipe_revision
from .pcx import Pcx
from .shopping import build_list


class ShareIn(BaseModel):
    text: str
    week: date | None = None


def _text(v: str | None, limit: int, what: str) -> str | None:
    """Collapse whitespace; blank → None; at most `limit` characters."""
    if v is None:
        return None
    v = " ".join(v.split())
    if len(v) > limit:
        raise ValueError(f"{what} is longer than {limit} characters")
    return v or None


_isbn = books.normalise_isbn


def _note(v: str | None, what: str) -> str | None:
    """Multi-line text (notes, description): lines trimmed, runs of blank lines kept to one; blank → None."""
    if v is None:
        return None
    v = re.sub(r"\n{3,}", "\n\n", "\n".join(" ".join(line.split()) for line in v.splitlines())).strip()
    if len(v) > jsonld.MAX_TEXT:
        raise ValueError(f"{what} is longer than {jsonld.MAX_TEXT} characters")
    return v or None


Minutes = Annotated[int, Field(strict=True, ge=0, le=10_000)]


class RecipePatch(BaseModel):
    """Partial update: omitted fields are left unchanged; null clears the source fields and the optional details
    (servings, notes, description, times, yield). `ingredients` are the lines as the recipe screen shows them (see
    recipe_edit), `steps` one per item; blank ones are dropped. `expected_revision` (RecipeOut.revision when the edit
    began) refuses the edit with 409 if the recipe changed meanwhile."""
    title: str | None = None
    source_kind: SourceKind | None = None
    source_title: str | None = None
    source_ref: str | None = None
    source_author: str | None = None
    source_isbn: str | None = None
    servings: Annotated[int, Field(strict=True, ge=1, le=1000)] | None = None
    ingredients: list[str] | None = None
    steps: list[str] | None = None
    notes: str | None = None
    description: str | None = None
    prep_minutes: Minutes | None = None
    cook_minutes: Minutes | None = None
    total_minutes: Minutes | None = None
    yield_text: str | None = None
    expected_revision: str | None = None

    @field_validator("title")
    @classmethod
    def _title(cls, v):
        return _text(v, 200, "title")

    @field_validator("ingredients")
    @classmethod
    def _ingredients(cls, v):
        if v is None:
            return None
        lines = [x for x in (_text(x, jsonld.MAX_LINE, "an ingredient line") for x in v) if x]
        if len(lines) > jsonld.MAX_LINES:
            raise ValueError(f"a recipe can have at most {jsonld.MAX_LINES} ingredient lines")
        return lines

    @field_validator("steps")
    @classmethod
    def _steps(cls, v):
        if v is None:
            return None
        steps = [x for x in (_text(x, jsonld.MAX_STEP, "a step") for x in v) if x]
        if len(steps) > jsonld.MAX_STEPS:
            raise ValueError(f"a recipe can have at most {jsonld.MAX_STEPS} steps")
        return steps

    @field_validator("notes")
    @classmethod
    def _notes(cls, v):
        return _note(v, "notes")

    @field_validator("description")
    @classmethod
    def _description(cls, v):
        return _note(v, "description")

    @field_validator("yield_text")
    @classmethod
    def _yield(cls, v):
        return _text(v, 200, "yield_text")

    @field_validator("source_title")
    @classmethod
    def _source_title(cls, v):
        return _text(v, 200, "source_title")

    @field_validator("source_ref")
    @classmethod
    def _source_ref(cls, v):
        return _text(v, 50, "source_ref")

    @field_validator("source_author")
    @classmethod
    def _source_author(cls, v):
        return _text(v, 200, "source_author")

    @field_validator("source_isbn")
    @classmethod
    def _source_isbn(cls, v):
        return _isbn(v)


class EntryIn(BaseModel):
    recipe_id: int


class EntryPatch(BaseModel):   # partial update: omitted fields are left unchanged
    day: int | None = None
    multiplier: float | None = None


class RatingIn(BaseModel):
    family: Annotated[int, Field(strict=True, ge=1, le=5)]
    company: Literal["yes", "maybe", "no"] | None = None
    note: Annotated[str, Field(max_length=2000)] | None = None

    @field_validator("note")
    @classmethod
    def blank_note_is_none(cls, v):
        return (v.strip() or None) if v is not None else None


class ListIn(BaseModel):
    weeks: list[date]
    people: int = 4
    staples: list[int] = []      # staple ids to merge in (the ones ticked on the list); unknown ids are skipped
    fridge: bool = False         # subtract what is in the fridge (0.14.0; ListItem.fridge)


def _staple_name(v):
    if v is None:
        raise ValueError("name can't be null")
    v = " ".join(v.split())
    if not v:
        raise ValueError("name can't be blank")
    return v


class StapleIn(BaseModel):
    name: Annotated[str, Field(max_length=100)]
    qty: Annotated[float, Field(gt=0, le=1000)] | None = None
    unit: str | None = None
    weekly: Annotated[bool, Field(strict=True)] = True

    @field_validator("name")
    @classmethod
    def _name(cls, v):
        return _staple_name(v)

    @field_validator("unit")
    @classmethod
    def _unit(cls, v):
        return staples.normalise_unit(v)


class StaplePatch(BaseModel):   # partial update: omitted fields are left unchanged; null clears qty/unit/last_bought
    name: Annotated[str, Field(max_length=100)] | None = None
    qty: Annotated[float, Field(gt=0, le=1000)] | None = None
    unit: str | None = None
    weekly: Annotated[bool, Field(strict=True)] | None = None
    last_bought: date | None = None
    position: Annotated[int, Field(ge=0)] | None = None

    @field_validator("name")
    @classmethod
    def _name(cls, v):
        return _staple_name(v)

    @field_validator("unit")
    @classmethod
    def _unit(cls, v):
        return staples.normalise_unit(v)


def _fridge_name(v):
    v = _staple_name(v)
    if len(v) > 80:
        raise ValueError("name is longer than 80 characters")
    return v


FridgeQty = Annotated[float, Field(ge=0, le=10_000)]


class FridgeIn(BaseModel):
    name: str
    qty: FridgeQty | None = None
    unit: str | None = None
    use_soon: Annotated[bool, Field(strict=True)] = False
    use_by: date | None = None
    from_list: Annotated[bool, Field(strict=True)] = False   # picked from the /fridge/names suggestions

    @field_validator("name")
    @classmethod
    def _name(cls, v):
        return _fridge_name(v)

    @field_validator("unit")
    @classmethod
    def _unit(cls, v):
        return staples.normalise_unit(v)


class FridgePatch(BaseModel):   # partial update: omitted fields are left unchanged; null clears qty/unit/use_by
    name: str | None = None
    qty: FridgeQty | None = None
    unit: str | None = None
    use_soon: Annotated[bool, Field(strict=True)] | None = None
    use_by: date | None = None
    confirm: Annotated[bool, Field(strict=True)] = False     # "Still right": the amount is dated today, unchanged
    from_list: Annotated[bool, Field(strict=True)] = False

    @field_validator("name")
    @classmethod
    def _name(cls, v):
        return _fridge_name(v)

    @field_validator("unit")
    @classmethod
    def _unit(cls, v):
        return staples.normalise_unit(v)


class SuggestIn(BaseModel):
    nights: Annotated[int, Field(strict=True, ge=1, le=7)]
    people: Annotated[int, Field(strict=True, ge=1, le=20)] = 4
    week: date | None = None          # recipes already on this week are left out and use the fridge first
    exclude: Annotated[list[int], Field(max_length=1000)] = []   # "Suggest others": the picks not wanted


class ApplyLine(BaseModel):
    """One reviewed line of a fridge read; name/qty/unit only when the reviewer edited them (an edited number is
    typed, no longer an estimate)."""
    pid: Annotated[str, Field(max_length=20)]
    accept: Annotated[bool, Field(strict=True)]
    name: str | None = None
    qty: FridgeQty | None = None
    unit: str | None = None
    use_soon: Annotated[bool, Field(strict=True)] | None = None

    @field_validator("name")
    @classmethod
    def _name(cls, v):
        return None if v is None else _fridge_name(v)

    @field_validator("unit")
    @classmethod
    def _unit(cls, v):
        return staples.normalise_unit(v)


class ApplyIn(BaseModel):
    lines: Annotated[list[ApplyLine], Field(max_length=500)]


# Photo types the AI reads (Anthropic: JPEG, PNG, GIF, WebP), told apart by content.
_PHOTO_TYPES = ((b"\xff\xd8\xff", "jpg"), (b"\x89PNG\r\n\x1a\n", "png"), (b"GIF87a", "gif"), (b"GIF89a", "gif"))


def _photo_ext(data: bytes) -> str | None:
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return next((ext for magic, ext in _PHOTO_TYPES if data.startswith(magic)), None)


class UsedIn(BaseModel):
    apply: Annotated[bool, Field(strict=True)]    # true = "Update fridge", false = "Not now"


class CartIn(BaseModel):
    items: list[ListItem]
    weeks: list[date] = []


class PickIn(BaseModel):
    product_code: str


class DraftIn(BaseModel):
    items: list[ListItem]
    weeks: list[date] = []


class LinePatch(BaseModel):   # partial update: omitted fields are left unchanged
    product_code: str | None = None
    quantity: int | None = Field(None, ge=0)
    removed: bool | None = None
    pinned: bool | None = None   # "Always buy this" for the line's item (with product_code: that product)


class SearchIn(BaseModel):
    term: str = Field(min_length=1)


class ExtraIn(BaseModel):     # an item added by search for a week (see extras.py)
    week: date
    term: Annotated[str, Field(min_length=1, max_length=200)]
    product_code: Annotated[str, Field(min_length=1, max_length=64)]
    quantity: Annotated[int, Field(gt=0, le=10000)] = 1


class ExtraPatch(BaseModel):
    product_code: Annotated[str, Field(min_length=1, max_length=64)] | None = None
    quantity: Annotated[int, Field(gt=0, le=10000)] | None = None


class AddLineIn(BaseModel):   # "Add an item" on a cart under review
    term: Annotated[str, Field(min_length=1, max_length=200)]
    product_code: Annotated[str, Field(min_length=1, max_length=64)]
    quantity: Annotated[int, Field(gt=0, le=10000)] = 1


class PrepPlanIn(BaseModel):
    weeks: list[date] = Field(min_length=1)


class TaskPatch(BaseModel):
    done: Annotated[bool, Field(strict=True)]


log = logging.getLogger(__name__)


def default_week(today: date) -> date:
    """Today if Sunday, else the upcoming Sunday."""
    return today if today.weekday() == 6 else today + timedelta(days=6 - today.weekday())


def _save_pages(d: str, files: list[UploadFile]) -> list[str]:
    """Write uploaded pages to d as page01.jpg … (in upload order); returns the paths."""
    paths = []
    for i, f in enumerate(files, 1):
        ext = (f.filename or "p.jpg").rsplit(".", 1)[-1].lower()
        ext = ext if ext.isalnum() and len(ext) <= 5 else "jpg"
        path = f"{d}/page{i:02d}.{ext}"
        with open(path, "wb") as out:
            out.write(f.file.read())
        paths.append(path)
    return paths


def create_app(settings: Settings, provider=None, pcx=None, conn=None,
               today: Callable[[], date] = date.today, book_search=None) -> FastAPI:
    app = FastAPI(title="mealprep")
    ai = provider or get_provider(settings)
    prep_ai = provider or get_provider(settings, timeout=settings.prep_timeout)
    import_ai = provider or get_provider(settings, timeout=settings.import_ai_timeout)
    document_ai = provider or get_provider(settings, timeout=settings.document_ai_timeout)
    edit_ai = provider or get_provider(settings, timeout=settings.edit_ai_timeout)
    fridge_ai = provider or get_provider(settings, timeout=settings.fridge_ai_timeout)
    fridge_photo_ai = provider or get_provider(settings, timeout=settings.fridge_photo_timeout)
    import_cap = settings.import_max_mb * 1024 * 1024
    too_big = f"This file is too big to import (max {settings.import_max_mb} MB)."
    shop = pcx or Pcx(settings.store_id, api_key=settings.pcx_apikey)
    book_search = book_search or books.BookSearch(google_key=settings.google_books_key)
    if conn is None:
        with db.connect(settings.dsn) as c:       # apply schema once at startup
            drafts.fail_interrupted(c)            # builds cut off by a restart will never finish
            prepplan.fail_interrupted(c)
            importer.fail_interrupted(c)
            fridge.fail_interrupted(c)

    def get_conn():
        # injected connection (tests) or one connection per request (thread-safe transactions)
        if conn is not None:
            yield conn
            return
        c = psycopg.connect(settings.dsn, autocommit=True)
        try:
            yield c
        finally:
            c.close()

    def auth(authorization: str = Header(default="")):
        if not settings.token or authorization != f"Bearer {settings.token}":
            raise HTTPException(401)

    A = [Depends(auth)]

    @app.middleware("http")
    async def import_size_guard(request, call_next):
        """Refuse an import upload whose declared size is far over the cap before it is spooled (the cap itself is
        enforced while reading, whatever the header says)."""
        if request.url.path == "/recipes/import":
            n = request.headers.get("content-length", "")
            if n.isdigit() and int(n) > import_cap + 1024 * 1024:
                return JSONResponse({"detail": too_big}, status_code=413)
        return await call_next(request)

    @app.get("/health")
    def health():
        return {"ok": True, "provider": settings.provider}

    def _save_and_plan(c, r: Recipe, week: date | None, existing: bool = False) -> dict:
        """Save (or find) the library recipe; with a week also add it to that week (apps up to 0.4.1 always send
        one). Without a week it is library only: `entry` is null."""
        r.id = db.save_recipe(c, r, ai_provider=settings.provider)
        entry = None
        if week is not None:
            wk = db.week_start(week)
            eid = db.add_to_week(c, wk, r.id)
            entry = next(e for e in db.get_week(c, wk) if e.id == eid)
        # As saved (source fields included), with the current rating summary so the app can show "you rated this
        # 5/5" on re-share.
        return {"recipe": _recipe_out(c, r.id), "entry": entry, "existing": existing}

    @app.post("/recipes/share", dependencies=A)
    def share(body: ShareIn, c=Depends(get_conn)):
        url = extract_nyt_url(body.text)
        if not url:
            raise HTTPException(422, "no NYT Cooking link found")
        # Same numeric id = same recipe, whatever the slug (share links don't always carry the canonical one).
        rid = nyt_id(url)
        existing = c.execute("SELECT id FROM recipes WHERE source_url=%s OR source_url=%s OR source_url LIKE %s "
                             "ORDER BY id LIMIT 1", (url, f"{NYT_BASE}/recipes/{rid}", f"{NYT_BASE}/recipes/{rid}-%")
                             ).fetchone() if rid else \
            c.execute("SELECT id FROM recipes WHERE source_url=%s", (url,)).fetchone()
        if existing:   # already in the library: no re-import (added to the week if one was sent)
            return _save_and_plan(c, db.get_recipe(c, existing[0]), body.week, existing=True)
        try:
            html, url = fetch_nyt_recipe(url)   # a wrong slug or id-only link resolves to the canonical one
            title, servings, lines, steps = parse_nyt_html(html, url)
            ings = structure_ingredients(ai, lines)
        except Exception as e:   # NytFetchError, NotARecipe, AIError
            log.warning("share import of %s failed: %s: %s", url, type(e).__name__, str(e)[:300])
            raise HTTPException(502, f"import failed: {type(e).__name__}: {str(e)[:200]}")
        r = Recipe(title=title, source="nyt", source_url=url, servings=servings, ingredients=ings, steps=steps)
        return _save_and_plan(c, r, body.week)

    @app.post("/recipes/photo", dependencies=A)
    def photo(files: list[UploadFile] = File(...), week: date | None = Form(None),
              title: str | None = Form(None), source_kind: SourceKind = Form("book"),
              source_title: str | None = Form(None), source_ref: str | None = Form(None),
              source_author: str | None = Form(None), source_isbn: str | None = Form(None), c=Depends(get_conn)):
        # sync def on purpose: FastAPI runs it in the threadpool, so the minutes-long Claude call
        # doesn't block the event loop
        if not 1 <= len(files) <= 10:
            raise HTTPException(422, "send 1–10 pages")
        try:   # checked before the slow read
            src = RecipePatch(source_kind=source_kind, source_title=source_title, source_ref=source_ref,
                              source_author=source_author, source_isbn=source_isbn)
        except ValueError as e:
            raise HTTPException(422, str(e))
        with tempfile.TemporaryDirectory(prefix="mealprep-") as d:
            try:
                r = import_photo(ai, _save_pages(d, files), title_hint=title)
            except AIError as e:
                raise HTTPException(502, f"couldn't read recipe: {e}")
        r.source_kind, r.source_title, r.source_ref = src.source_kind, src.source_title, src.source_ref
        r.source_author, r.source_isbn = src.source_author, src.source_isbn
        return _save_and_plan(c, r, week)

    def _recipe_out(c, rid: int) -> RecipeOut:
        r = db.get_recipe(c, rid)
        return RecipeOut(**r.model_dump(), ratings=db.rating_summaries(c, today(), [rid])[rid],
                         planned_weeks=db.planned_weeks(c, today(), [rid]).get(rid, []))

    @app.patch("/recipes/{recipe_id}", dependencies=A)
    def patch_recipe(recipe_id: int, body: RecipePatch, c=Depends(get_conn)) -> RecipeOut:
        """Edit a recipe (title, servings, ingredient lines, steps, notes, description, times, yield) or set where it
        comes from. An NYT recipe has no book title or page. A different book title sent without an author/ISBN clears
        them (they belonged to the old book). Changed ingredient lines are structured again (recipe_edit; the AI, else
        the deterministic clean-up). The id, uid, plan entries and ratings stay; a cart or prep plan made from the old
        lines goes stale (db.plan_fingerprint, prepplan._stale)."""
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        expected = fields.pop("expected_revision", None)
        for f in ("title", "source_kind", "ingredients", "steps"):
            if f in fields and fields[f] is None:
                raise HTTPException(422, "title can't be blank" if f == "title" else f"{f} can't be null")

        def check(r: Recipe | None) -> Recipe:
            if r is None:
                raise HTTPException(404, f"recipe {recipe_id}")
            if expected is not None and recipe_revision(r) != expected:
                raise HTTPException(409, "This recipe was changed (on another phone?) since you started editing it.")
            return r

        before = check(db.get_recipe(c, recipe_id))
        content = {k: v for k, v in fields.items() if k in EDITABLE_FIELDS}
        if "ingredients" in content:   # the AI may take seconds: outside the row lock, re-checked under it
            content["ingredients"] = recipe_edit.edit_ingredients(edit_ai, before.ingredients, content["ingredients"])
        with c.transaction():
            r = check(db.get_recipe(c, recipe_id, for_update=True))
            if "ingredients" in content and r.ingredients != before.ingredients:
                raise HTTPException(409, "The recipe changed while it was being saved; try again.")
            kind = fields.get("source_kind", r.source_kind)
            title = fields.get("source_title", r.source_title)
            ref = fields.get("source_ref", r.source_ref)
            same_book = (title or "").lower() == (r.source_title or "").lower()
            author = fields.get("source_author", r.source_author if same_book else None)
            isbn = fields.get("source_isbn", r.source_isbn if same_book else None)
            if kind == "nyt":
                if any(fields.get(f) for f in ("source_title", "source_ref", "source_author", "source_isbn")):
                    raise HTTPException(422, "an NYT Cooking recipe has no book title or page")
            db.update_source(c, recipe_id, kind, title, ref, author, isbn)
            if content:
                db.update_recipe(c, recipe_id, r.model_copy(update=content))
        return _recipe_out(c, recipe_id)

    @app.get("/recipes/sources", dependencies=A)
    def recipe_sources(c=Depends(get_conn)):
        """NYT Cooking, each cookbook and named other source, "Unknown book", "Other", with counts; `key` is the
        GET /recipes?source= value."""
        return db.recipe_sources(c)

    @app.get("/books/search", dependencies=A)
    def search_books(q: str = Query("", max_length=200), limit: int = Query(8, ge=1, le=20)):
        """Book suggestions for "Which book?": Open Library, then Google Books when that finds too little. Upstream
        trouble is never an error here: whatever was found, [] at worst (and for fewer than 2 characters)."""
        try:
            return book_search.search(q, limit)
        except Exception:   # never 5xx the app over a book lookup
            log.exception("book search crashed")
            return []

    @app.post("/recipes/{recipe_id}/pages", dependencies=A)
    def attach_pages(recipe_id: int, files: list[UploadFile] = File(...), for_line: int | None = Form(None),
                     c=Depends(get_conn)) -> RecipeOut:
        """Read a cross-referenced sub-recipe page and add its ingredients. Sync like /recipes/photo (~25–30 s)."""
        r = db.get_recipe(c, recipe_id)
        if r is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        if not 1 <= len(files) <= 10:
            raise HTTPException(422, "send 1–10 pages")
        try:
            line = subrecipe.choose_ref_line(r, for_line)
        except subrecipe.AlreadyAttached as e:
            raise HTTPException(409, str(e))
        except LookupError as e:
            raise HTTPException(422, str(e))
        ref = r.ingredients[line]
        with tempfile.TemporaryDirectory(prefix="mealprep-") as d:
            try:
                name, ings, steps = import_subrecipe(ai, _save_pages(d, files), ref.raw, ref.ref_page)
            except AIError as e:
                raise HTTPException(502, f"couldn't read the sub-recipe: {e}")
        with c.transaction():   # re-check under a row lock: the recipe may have changed during the AI call
            cur = db.get_recipe(c, recipe_id, for_update=True)
            if cur is None:
                raise HTTPException(404, f"recipe {recipe_id}")
            if line >= len(cur.ingredients) or cur.ingredients[line].raw != ref.raw or cur.ingredients[line].expanded:
                raise HTTPException(409, "the recipe changed while the page was being read; try again")
                # the page is part of the same recipe, so it keeps the recipe's source (book, page) as it is
            db.update_recipe(c, recipe_id, subrecipe.attach_subrecipe(cur, line, name, ings, steps))
        return _recipe_out(c, recipe_id)

    def _export_nodes(c, rs: list[Recipe], standalone: bool) -> list[dict]:
        ids = [r.id for r in rs]
        if not ids:
            return []
        summaries, planned = db.rating_summaries(c, today(), ids), db.planned_weeks(c, today(), ids)
        entries, created = db.rating_entries(c, today(), ids), db.created_at(c, ids)
        return [jsonld.to_jsonld(RecipeOut(**r.model_dump(), ratings=summaries.get(r.id, {}),
                                           planned_weeks=planned.get(r.id, [])),
                                 entries.get(r.id, []), standalone=standalone, created_at=created.get(r.id))
                for r in rs]

    def _ld(content: dict, filename: str) -> JSONResponse:
        return JSONResponse(content, media_type="application/ld+json; charset=utf-8",
                            headers={"Content-Disposition": f'attachment; filename="{filename}"'})

    @app.get("/recipes/export", dependencies=A)
    def export_library(source: str | None = None, ids: str | None = None, c=Depends(get_conn)):
        """The library (or the `source` filter, as GET /recipes, and/or `ids`=1,2,3; unknown ids are ignored) as one
        schema.org JSON-LD file with an @graph of recipes, ratings and notes included, newest first."""
        wanted = None
        if ids is not None:
            try:
                wanted = {int(x) for x in ids.split(",") if x.strip()}
            except ValueError:
                raise HTTPException(422, "ids must be recipe ids separated by commas")
        rs = [r for r in db.list_recipes(c, source) if wanted is None or r.id in wanted]
        day = today()
        return _ld(jsonld.bundle(_export_nodes(c, rs, standalone=False), datetime.now(timezone.utc)),
                   f"meal-prep-recipes-{day.isoformat()}.json")

    def run_import(jid: int, items: list[dict], work: list):
        # background thread with its own connection, like draft builds
        try:
            with psycopg.connect(settings.dsn, autocommit=True) as bc:
                importer.run_job(bc, jid, items, work, import_ai, settings.import_workers, settings.provider)
        except Exception:
            log.exception("import job %s: background run crashed", jid)

    def run_read(jid: int, doc, name: str | None):
        # a document's first preview: the AI finds its recipes in the background (own connection, like imports)
        try:
            with psycopg.connect(settings.dsn, autocommit=True) as bc:
                importer.run_read(bc, jid, doc, name, document_ai)
        except Exception:
            log.exception("read job %s: background run crashed", jid)

    @app.post("/recipes/import", dependencies=A)
    def import_recipes(file: UploadFile = File(...), dry_run: bool = Form(True), choices: str | None = Form(None),
                       name: str | None = Form(None, max_length=500), c=Depends(get_conn)):
        """Import schema.org JSON-LD (one recipe, a list, an @graph, our export), a saved web page's JSON-LD, or a
        recipe document (PDF, Word .docx, plain text) with one or more recipes, told apart by content.
        dry_run (default) = preview: per-item new / duplicate / failed with default actions, nothing written. A
        document's FIRST preview is a read job instead (202, GET /imports/{id}: the AI finds its recipes; done = its
        report is the preview); later previews of the same file answer at once from that read.
        dry_run=false + choices {item key: add/skip/update} → 202 and a background job (GET /imports/{id}).
        `name`: the file's name on the phone (a document's recipes take it as their source when the document names
        none). A file that can't be read → 422, over the cap → 413; anything wrong with one recipe is that item's
        `failed` status, never a 5xx. Links in the file are kept as text and never fetched."""
        try:
            data = safe.read_capped(file.file, import_cap)
        except safe.ImportTooBig:
            raise HTTPException(413, too_big)
        name = name or file.filename
        try:
            if dry_run:
                try:
                    return importer.preview(c, data, name)[0]
                except importer.NeedsReading as e:
                    jid, doc = importer.start_read(c, data, e.kind)
                if doc is not None:
                    threading.Thread(target=run_read, args=(jid, doc, name), name=f"read-{jid}", daemon=True).start()
                return JSONResponse({**importer.get_job(c, jid), "existing": doc is None}, status_code=202)
            jid, items, work, existing = importer.start(c, data, importer.parse_choices(choices), name)
        except importer.NeedsReading:
            raise HTTPException(422, importer.READ_AGAIN)
        except (safe.Unreadable, importer.BadChoices) as e:
            raise HTTPException(422, str(e))
        except Exception:   # a bug must not become a 500 for a file
            log.exception("import: couldn't read the file")
            raise HTTPException(422, "Couldn't read this file.")
        if work:
            threading.Thread(target=run_import, args=(jid, items, work), name=f"import-{jid}", daemon=True).start()
        return JSONResponse({**importer.get_job(c, jid), "existing": existing}, status_code=202)

    @app.get("/imports/{job_id}", dependencies=A)
    def get_import(job_id: int, c=Depends(get_conn)):
        """An import job: status running / done / failed, progress, per-item results (see POST /recipes/import). A
        document's read job has dry_run true; when done its items are the preview (new / duplicate / failed)."""
        job = importer.get_job(c, job_id)
        if job is None:
            raise HTTPException(404, f"import {job_id}")
        return job

    @app.get("/recipes/{recipe_id}/export", dependencies=A)
    def export_recipe(recipe_id: int, c=Depends(get_conn)):
        """One recipe as a schema.org JSON-LD file (ratings and notes included)."""
        r = db.get_recipe(c, recipe_id)
        if r is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        return _ld(_export_nodes(c, [r], standalone=True)[0], f"{jsonld.slug(r.title, r.id)}.recipe.json")

    @app.get("/recipes", dependencies=A)
    def recipes(sort: Literal["newest", "favourites"] = "newest", source: str | None = None,
                c=Depends(get_conn)) -> list[RecipeOut]:
        """`source`: nyt, book (any book), other (any other source), book:<title> / other:<name> (any case), book: (book
        not known yet), other: (no name) or a book title."""
        summaries, planned = db.rating_summaries(c, today()), db.planned_weeks(c, today())
        out = [RecipeOut(**r.model_dump(), ratings=summaries.get(r.id, {}), planned_weeks=planned.get(r.id, []))
               for r in db.list_recipes(c, source)]  # newest first
        if sort == "favourites":   # avg family desc (unrated last), then times cooked; stable → newest breaks ties
            out.sort(key=lambda r: (r.ratings.avg_family is None, -(r.ratings.avg_family or 0), -r.ratings.times_cooked))
        return out

    @app.get("/recipes/{recipe_id}", dependencies=A)
    def recipe_detail(recipe_id: int, c=Depends(get_conn)) -> RecipeDetail:
        r = db.get_recipe(c, recipe_id)
        if r is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        return RecipeDetail(**r.model_dump(), ratings=db.rating_summaries(c, today(), [recipe_id])[recipe_id],
                            planned_weeks=db.planned_weeks(c, today(), [recipe_id]).get(recipe_id, []),
                            history=db.recipe_history(c, recipe_id),
                            imported_history=db.imported_history(c, recipe_id))

    @app.get("/ratings/pending", dependencies=A)
    def ratings_pending(today_: date | None = Query(None, alias="today"), c=Depends(get_conn)):
        """Placed nights before today (last 14 days) with no rating yet — the app's morning-after prompt."""
        return db.pending_ratings(c, today_ or today())

    @app.get("/weeks", dependencies=A)
    def weeks(from_: date | None = Query(None, alias="from"), count: int = Query(8, ge=1, le=52), c=Depends(get_conn)):
        return db.week_summaries(c, from_ or today(), count)

    @app.get("/weeks/{week}", dependencies=A)
    def get_week(week: date, c=Depends(get_conn)) -> list[PlanEntry]:
        return db.get_week(c, week)

    @app.post("/weeks/{week}/entries", dependencies=A)
    def add_entry(week: date, body: EntryIn, c=Depends(get_conn)) -> PlanEntry:
        if db.get_recipe(c, body.recipe_id) is None:
            raise HTTPException(404, f"recipe {body.recipe_id}")
        eid = db.add_to_week(c, week, body.recipe_id)
        return next(e for e in db.get_week(c, week) if e.id == eid)

    @app.patch("/plan/{entry_id}", status_code=204, dependencies=A)
    def patch_entry(entry_id: int, body: EntryPatch, c=Depends(get_conn)):
        if body.day is not None and not 0 <= body.day <= 6:
            raise HTTPException(422, "day must be 0 (Sun) … 6 (Sat)")
        if body.multiplier is not None and body.multiplier <= 0:
            raise HTTPException(422, "multiplier must be > 0")
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        if fields.get("multiplier", 1) is None:
            raise HTTPException(422, "multiplier can't be null")
        db.update_entry(c, entry_id, **fields)

    @app.delete("/plan/{entry_id}", status_code=204, dependencies=A)
    def delete_entry(entry_id: int, c=Depends(get_conn)):
        db.remove_entry(c, entry_id)

    @app.put("/plan/{entry_id}/rating", status_code=204, dependencies=A)
    def put_rating(entry_id: int, body: RatingIn, c=Depends(get_conn)):
        if not db.set_rating(c, entry_id, body.family, body.company, body.note):
            raise HTTPException(404, f"plan entry {entry_id}")

    @app.delete("/plan/{entry_id}/rating", status_code=204, dependencies=A)
    def delete_rating(entry_id: int, c=Depends(get_conn)):
        if not db.delete_rating(c, entry_id):
            raise HTTPException(404, f"plan entry {entry_id}")

    @app.post("/list", dependencies=A)
    def make_list(body: ListIn, c=Depends(get_conn)) -> list[ListItem]:
        entries = []
        for wk in body.weeks:
            for e in db.get_week(c, wk):
                r = db.get_recipe(c, e.recipe_id)
                if r is not None:
                    entries.append((r, e.multiplier))
        chosen = staples.list_staples(c, body.staples) if body.staples else []
        return build_list(entries, body.people, chosen, fridge.list_items(c, today()) if body.fridge else ())

    @app.get("/staples", dependencies=A)
    def get_staples(c=Depends(get_conn)) -> list[Staple]:
        return staples.list_staples(c)

    def _staple_call(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except staples.StapleNotFound as e:
            raise HTTPException(404, str(e))
        except staples.StapleExists as e:
            raise HTTPException(409, str(e))

    @app.post("/staples", status_code=201, dependencies=A)
    def add_staple(body: StapleIn, c=Depends(get_conn)) -> Staple:
        return _staple_call(staples.create_staple, c, body.name, body.qty, body.unit, body.weekly)

    @app.patch("/staples/{staple_id}", dependencies=A)
    def patch_staple(staple_id: int, body: StaplePatch, c=Depends(get_conn)) -> Staple:
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        for f in ("weekly", "position"):
            if f in fields and fields[f] is None:
                raise HTTPException(422, f"{f} can't be null")
        return _staple_call(staples.update_staple, c, staple_id, **fields)

    @app.delete("/staples/{staple_id}", status_code=204, dependencies=A)
    def remove_staple(staple_id: int, c=Depends(get_conn)):
        staples.delete_staple(c, staple_id)

    def _use_by(d: date | None):
        if d is not None and abs((d - today()).days) > 366:
            raise HTTPException(422, "use_by must be within a year of today")

    def _fridge_call(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except fridge.FridgeNotFound as e:
            raise HTTPException(404, str(e))
        except fridge.FridgeExists as e:   # the app offers to edit the item that is already there
            return JSONResponse({"detail": str(e), "id": e.item.id}, status_code=409)

    @app.get("/fridge", dependencies=A)
    def get_fridge(c=Depends(get_conn)) -> list[FridgeItem]:
        """The household's fridge list: soon first (use soon / use-by within 3 days), then name A–Z."""
        return fridge.list_items(c, today())

    @app.get("/fridge/names", dependencies=A)
    def fridge_names(q: str = Query("", max_length=80), c=Depends(get_conn)) -> list[str]:
        """Typing suggestions: the shopping list's names containing q (≤ 12; from 1 character)."""
        return fridge.suggest_names(c, q)

    @app.post("/fridge", status_code=201, dependencies=A)
    def add_fridge(body: FridgeIn, c=Depends(get_conn)) -> FridgeItem:
        _use_by(body.use_by)
        return _fridge_call(fridge.create_item, c, fridge_ai, body.name, body.qty, body.unit, today=today(),
                            use_soon=body.use_soon, use_by=body.use_by, from_list=body.from_list)

    @app.patch("/fridge/{item_id}", dependencies=A)
    def patch_fridge(item_id: int, body: FridgePatch, c=Depends(get_conn)) -> FridgeItem:
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        for f in ("name", "use_soon"):
            if f in fields and fields[f] is None:
                raise HTTPException(422, f"{f} can't be null")
        _use_by(fields.get("use_by"))
        return _fridge_call(fridge.update_item, c, fridge_ai, item_id, today=today(), **fields)

    @app.post("/fridge/suggest", dependencies=A)
    def fridge_suggest(body: SuggestIn, c=Depends(get_conn)):
        """Dinners from the library that use what's in the fridge (use soon first) with the fewest extra ingredients
        (fridge.select): {picks, fridge_left, stale, thin_library}. Read-only, no AI."""
        return fridge.suggest(c, today(), body.nights, body.people, body.week, body.exclude)

    def run_fridge_read(rid: int, td: tempfile.TemporaryDirectory, paths: list[str]):
        # background thread with its own connection, like draft builds; the photos are deleted whatever happens
        try:
            with psycopg.connect(settings.dsn, autocommit=True) as bc:
                fridge.run_read(bc, rid, fridge_photo_ai, fridge_ai, paths, today())
        except Exception:
            log.exception("fridge read %s: background run crashed", rid)
        finally:
            td.cleanup()

    @app.post("/fridge/photos", status_code=202, dependencies=A)
    def fridge_photos(files: list[UploadFile] = File(...), c=Depends(get_conn)):
        """Read 1–6 fridge photos in the background (GET /fridge/reads/{id}): the read proposes changes to the list,
        nothing is saved until POST /fridge/reads/{id}/apply. The same photos again → the same read (existing)."""
        if not 1 <= len(files) <= settings.fridge_max_photos:
            raise HTTPException(422, f"Send 1–{settings.fridge_max_photos} photos.")
        cap, total_cap = settings.fridge_photo_max_mb * 1024 * 1024, settings.fridge_photos_max_mb * 1024 * 1024
        photos = []
        for f in files:
            data = f.file.read(cap + 1)
            if len(data) > cap:
                raise HTTPException(413, f"A photo is too big (max {settings.fridge_photo_max_mb} MB).")
            if not data:
                raise HTTPException(422, "A photo is empty.")
            if _photo_ext(data) is None:
                raise HTTPException(422, "That isn't a photo the server can read (JPEG, PNG, WebP or GIF).")
            photos.append(data)
        if sum(map(len, photos)) > total_cap:
            raise HTTPException(413, f"The photos are too big together (max {settings.fridge_photos_max_mb} MB).")
        rid, existing = fridge.start_read(c, photos)
        if not existing:
            td = tempfile.TemporaryDirectory(prefix="mealprep-fridge-")
            try:
                paths = []
                for i, data in enumerate(photos, 1):
                    path = f"{td.name}/photo{i:02d}.{_photo_ext(data)}"
                    with open(path, "wb") as out:
                        out.write(data)
                    paths.append(path)
                threading.Thread(target=run_fridge_read, args=(rid, td, paths), name=f"fridge-{rid}", daemon=True).start()
            except Exception:
                td.cleanup()
                c.execute("UPDATE fridge_reads SET status='failed', error=%s WHERE id=%s", ("Couldn't start the read.", rid))
                raise
        return JSONResponse({**fridge.get_read(c, rid), "existing": existing}, status_code=202)

    @app.get("/fridge/reads", dependencies=A)
    def fridge_open_read(c=Depends(get_conn)):
        """{read}: the newest read still reading or ready to review (for the Fridge screen and the other phone)."""
        return {"read": fridge.open_read(c)}

    def _read_call(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except fridge.ReadNotFound as e:
            raise HTTPException(404, str(e))
        except fridge.ReadState as e:   # e.g. applied on the other phone: the app treats that as done
            return JSONResponse({"detail": str(e), "read": e.read}, status_code=409)

    @app.get("/fridge/reads/{read_id}", dependencies=A)
    def fridge_read(read_id: int, c=Depends(get_conn)):
        return _read_call(fridge.get_read, c, read_id)

    @app.post("/fridge/reads/{read_id}/apply", dependencies=A)
    def fridge_apply(read_id: int, body: ApplyIn, c=Depends(get_conn)):
        lines = [ln.model_dump(include=ln.model_fields_set) for ln in body.lines]
        return _read_call(fridge.apply_read, c, fridge_ai, read_id, lines, today())

    @app.post("/fridge/reads/{read_id}/dismiss", dependencies=A)
    def fridge_dismiss(read_id: int, c=Depends(get_conn)):
        return _read_call(fridge.dismiss_read, c, read_id)

    @app.get("/fridge/used", dependencies=A)
    def fridge_used(today_: date | None = Query(None, alias="today"), c=Depends(get_conn)):
        """The morning-after nudge: nights of the last 2 days (before today) that used the fridge and nobody answered,
        newest first, with what one tap would take off the list. Read-only."""
        return fridge.used_nights(c, today_ or today(), settings.default_people)

    @app.post("/fridge/used/{entry_id}", dependencies=A)
    def fridge_used_answer(entry_id: int, body: UsedIn, c=Depends(get_conn)):
        """Apply what that dinner used (once; floor 0, the items stay) or dismiss; a repeat returns the first answer."""
        try:
            return fridge.answer_used(c, entry_id, body.apply, today(), settings.default_people)
        except fridge.EntryNotFound as e:
            raise HTTPException(404, str(e))

    @app.delete("/fridge/{item_id}", status_code=204, dependencies=A)
    def remove_fridge(item_id: int, c=Depends(get_conn)):
        fridge.delete_item(c, item_id)

    @app.get("/cart/default-week", dependencies=A)
    def cart_default_week(c=Depends(get_conn)):
        wk = db.default_cart_week(c, today())
        return {"week": wk.isoformat() if wk else None}

    @app.post("/cart", dependencies=A)
    def cart(body: CartIn, c=Depends(get_conn)):
        """Draft + build + send synchronously (smoke scripts). The app uses /drafts."""
        try:
            return fill_cart(body.items, shop, ai, c, weeks=body.weeks, provider_name=settings.provider,
                             store_id=settings.store_id, workers=settings.match_workers)
        except Exception as e:   # PC Express create/add failures
            raise HTTPException(502, f"cart failed: {type(e).__name__}: {str(e)[:200]}")

    def run_build(did: int):
        # background thread with its own connection; the request connection is closed by then
        try:
            with psycopg.connect(settings.dsn, autocommit=True) as bc:
                drafts.build_draft(bc, did, shop, ai, store_id=settings.store_id, workers=settings.match_workers)
        except Exception:
            log.exception("draft %s: background build crashed", did)

    @app.post("/drafts", status_code=202, dependencies=A)
    def create_draft(body: DraftIn, c=Depends(get_conn)):
        # The weeks' items added by search come after the list's lines (the app doesn't send them).
        items = [it for it in body.items if it.extra_id is None] + extras.items_for(c, body.weeks)
        did = drafts.create_draft(c, items, body.weeks, store_id=settings.store_id, ai_provider=settings.provider)
        threading.Thread(target=run_build, args=(did,), name=f"draft-{did}", daemon=True).start()
        return {"id": did, "status": "building"}

    @app.get("/drafts/{draft_id}", dependencies=A)
    def get_draft(draft_id: int, c=Depends(get_conn)):
        d = drafts.get_draft(c, draft_id)
        if d is None:
            raise HTTPException(404, f"draft {draft_id}")
        return d

    @app.get("/weeks/{week}/draft", dependencies=A)
    def week_draft(week: date, c=Depends(get_conn)):
        d = drafts.latest_for_week(c, week)
        if d is None:
            raise HTTPException(404, f"no cart draft for the week of {db.week_start(week)}")
        return d

    def _draft_call(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except drafts.DraftNotFound as e:
            raise HTTPException(404, str(e))
        except drafts.DraftStateError as e:
            raise HTTPException(409, str(e))
        except drafts.UnknownProduct as e:
            raise HTTPException(422, f"unknown product {e} — search for it first")
        except ValueError as e:
            raise HTTPException(422, str(e))

    @app.patch("/drafts/{draft_id}/lines/{line_id}", dependencies=A)
    def patch_line(draft_id: int, line_id: int, body: LinePatch, c=Depends(get_conn)):
        return _draft_call(drafts.update_line, c, draft_id, line_id, **body.model_dump(include=body.model_fields_set))

    @app.post("/drafts/{draft_id}/lines/{line_id}/search", dependencies=A)
    def search_line(draft_id: int, line_id: int, body: SearchIn, c=Depends(get_conn)):
        try:
            return _draft_call(drafts.search_line, c, draft_id, line_id, body.term, shop, store_id=settings.store_id)
        except httpx.HTTPError as e:
            raise HTTPException(502, f"PC Express search failed: {type(e).__name__}")

    @app.get("/drafts/{draft_id}/lines/{line_id}/choices", dependencies=A)
    def line_choices(draft_id: int, line_id: int, term: str | None = Query(None, max_length=200),
                     page: int = Query(0, ge=0, le=50), c=Depends(get_conn)):
        """Products to choose from for a line, one Loblaws page at a time (see drafts.choices)."""
        try:
            return _draft_call(drafts.choices, c, draft_id, line_id, shop, term=term, page=page,
                               store_id=settings.store_id)
        except httpx.HTTPError as e:
            raise HTTPException(502, f"PC Express search failed: {type(e).__name__}")

    @app.post("/drafts/{draft_id}/lines", status_code=201, dependencies=A)
    def add_line(draft_id: int, body: AddLineIn, c=Depends(get_conn)):
        """Add an item found by search to a cart under review (and to its week's added items)."""
        return _draft_call(extras.add_to_draft, c, draft_id, body.term, body.product_code, body.quantity)

    @app.get("/products/choices", dependencies=A)
    def product_choices(term: str = Query(..., min_length=1, max_length=200), page: int = Query(0, ge=0, le=50),
                        c=Depends(get_conn)):
        """The chooser for an item added by search: like a line's choices, without a line."""
        try:
            return drafts.search_choices(c, shop, term, page=page, store_id=settings.store_id)
        except httpx.HTTPError as e:
            raise HTTPException(502, f"PC Express search failed: {type(e).__name__}")

    def _extra_call(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except extras.ExtraNotFound as e:
            raise HTTPException(404, str(e))
        except drafts.UnknownProduct as e:
            raise HTTPException(422, f"unknown product {e} — search for it first")
        except ValueError as e:
            raise HTTPException(422, str(e))

    @app.get("/extras", dependencies=A)
    def get_extras(week: list[date] = Query(..., min_length=1), c=Depends(get_conn)):
        """Items added by search for these weeks (oldest week first, then as added)."""
        return extras.list_extras(c, week)

    @app.post("/extras", status_code=201, dependencies=A)
    def add_extra(body: ExtraIn, c=Depends(get_conn)):
        return _extra_call(extras.add_extra, c, body.week, body.term, body.product_code, body.quantity)

    @app.patch("/extras/{extra_id}", dependencies=A)
    def patch_extra(extra_id: int, body: ExtraPatch, c=Depends(get_conn)):
        return _extra_call(extras.update_extra, c, extra_id, quantity=body.quantity, product_code=body.product_code)

    @app.delete("/extras/{extra_id}", status_code=204, dependencies=A)
    def delete_extra(extra_id: int, c=Depends(get_conn)):
        extras.delete_extra(c, extra_id)

    @app.post("/drafts/{draft_id}/send", dependencies=A)
    def send_draft(draft_id: int, c=Depends(get_conn)):
        try:
            return {"pcx_cart_id": _draft_call(drafts.send_draft, c, draft_id, shop)}
        except httpx.HTTPError as e:   # nothing recorded; draft stays ready so the user can retry
            raise HTTPException(502, f"PC Express cart failed: {type(e).__name__}: {str(e)[:200]}")

    def run_prep(pid: int):
        # background thread with its own connection, like draft builds
        try:
            with psycopg.connect(settings.dsn, autocommit=True) as bc:
                prepplan.build_prep_plan(bc, pid, prep_ai, workers=settings.prep_workers)
        except Exception:
            log.exception("prep plan %s: background build crashed", pid)

    @app.post("/prep-plans", status_code=202, dependencies=A)
    def create_prep_plan(body: PrepPlanIn, c=Depends(get_conn)):
        try:
            pid = prepplan.create_prep_plan(c, body.weeks, today(), ai_provider=settings.provider,
                                            people=settings.default_people)
        except prepplan.NothingPlanned as e:
            raise HTTPException(422, str(e))
        threading.Thread(target=run_prep, args=(pid,), name=f"prep-{pid}", daemon=True).start()
        return {"id": pid, "status": "building"}

    @app.get("/prep-plans/{plan_id}", dependencies=A)
    def get_prep_plan(plan_id: int, c=Depends(get_conn)):
        p = prepplan.get_prep_plan(c, plan_id)
        if p is None:
            raise HTTPException(404, f"prep plan {plan_id}")
        return p

    @app.get("/weeks/{week}/prep-plan", dependencies=A)
    def week_prep_plan(week: date, c=Depends(get_conn)):
        p = prepplan.latest_for_week(c, week)
        if p is None:
            raise HTTPException(404, f"no prep plan for the week of {db.week_start(week)}")
        return p

    @app.patch("/prep-plans/{plan_id}/tasks/{task_id}", dependencies=A)
    def patch_prep_task(plan_id: int, task_id: str, body: TaskPatch, c=Depends(get_conn)):
        try:
            return prepplan.set_task_done(c, plan_id, task_id, body.done)
        except prepplan.PrepNotFound as e:
            raise HTTPException(404, str(e))
        except prepplan.PrepStateError as e:
            raise HTTPException(409, str(e))

    @app.get("/plan/{entry_id}/card", dependencies=A)
    def cook_card(entry_id: int, c=Depends(get_conn)):
        card = prepplan.get_card(c, entry_id)
        if card is None:
            raise HTTPException(404, f"no cook card for plan entry {entry_id} — place it on a night and generate the prep plan")
        return card

    @app.put("/picks/{key:path}", status_code=204, dependencies=A)
    def put_pick(key: str, body: PickIn, c=Depends(get_conn)):
        db.set_pick(c, key, body.product_code, chosen_by="user")

    return app


def app_from_env():
    return create_app(Settings())

