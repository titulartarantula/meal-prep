import logging, tempfile, threading
from datetime import date, datetime, timedelta, timezone
from typing import Annotated, Callable, Literal

import httpx
import psycopg
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from . import books, db, drafts, prepplan, staples, subrecipe
from .ai import AIError, get_provider
from .exchange import importer, jsonld, safe
from .config import Settings
from .importers.nyt import extract_nyt_url, fetch_nyt, parse_nyt_html
from .importers.photo import import_photo, import_subrecipe
from .importers.structure import structure_ingredients
from .matcher import fill_cart
from .models import ListItem, PlanEntry, Recipe, RecipeDetail, RecipeOut, SourceKind, Staple
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


class RecipePatch(BaseModel):   # partial update: omitted fields are left unchanged; null clears the source fields
    title: str | None = None
    source_kind: SourceKind | None = None
    source_title: str | None = None
    source_ref: str | None = None
    source_author: str | None = None
    source_isbn: str | None = None

    @field_validator("title")
    @classmethod
    def _title(cls, v):
        return _text(v, 200, "title")

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


class SearchIn(BaseModel):
    term: str = Field(min_length=1)


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
    import_cap = settings.import_max_mb * 1024 * 1024
    too_big = f"This file is too big to import (max {settings.import_max_mb} MB)."
    shop = pcx or Pcx(settings.store_id, api_key=settings.pcx_apikey)
    book_search = book_search or books.BookSearch(google_key=settings.google_books_key)
    if conn is None:
        with db.connect(settings.dsn) as c:       # apply schema once at startup
            drafts.fail_interrupted(c)            # builds cut off by a restart will never finish
            prepplan.fail_interrupted(c)
            importer.fail_interrupted(c)

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
        existing = c.execute("SELECT id FROM recipes WHERE source_url=%s", (url,)).fetchone()
        if existing:   # already in the library: no re-import (added to the week if one was sent)
            return _save_and_plan(c, db.get_recipe(c, existing[0]), body.week, existing=True)
        try:
            title, servings, lines, steps = parse_nyt_html(fetch_nyt(url), url)
            ings = structure_ingredients(ai, lines)
        except Exception as e:   # NotARecipe, AIError, httpx errors
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
        """Rename a recipe or set where it comes from. An NYT recipe has no book title or page. A different book title
        sent without an author/ISBN clears them (they belonged to the old book)."""
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        for f in ("title", "source_kind"):
            if f in fields and fields[f] is None:
                raise HTTPException(422, f"{f} can't be null" if f == "source_kind" else "title can't be blank")
        with c.transaction():
            r = db.get_recipe(c, recipe_id, for_update=True)
            if r is None:
                raise HTTPException(404, f"recipe {recipe_id}")
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
            if "title" in fields:
                db.update_recipe(c, recipe_id, r.model_copy(update={"title": fields["title"]}))
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
        return build_list(entries, body.people, chosen)

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
        did = drafts.create_draft(c, body.items, body.weeks, store_id=settings.store_id, ai_provider=settings.provider)
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

    @app.patch("/drafts/{draft_id}/lines/{line_id}", dependencies=A)
    def patch_line(draft_id: int, line_id: int, body: LinePatch, c=Depends(get_conn)):
        return _draft_call(drafts.update_line, c, draft_id, line_id, **body.model_dump(include=body.model_fields_set))

    @app.post("/drafts/{draft_id}/lines/{line_id}/search", dependencies=A)
    def search_line(draft_id: int, line_id: int, body: SearchIn, c=Depends(get_conn)):
        try:
            return _draft_call(drafts.search_line, c, draft_id, line_id, body.term, shop, store_id=settings.store_id)
        except httpx.HTTPError as e:
            raise HTTPException(502, f"PC Express search failed: {type(e).__name__}")

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

