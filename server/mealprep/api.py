import hmac, html, logging, re, tempfile, threading
from urllib.parse import urlencode
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Annotated, Callable, Literal

import httpx
import psycopg
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, Response, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field, field_validator

from . import auth, books, db, drafts, extras, fridge, households, nights, rbac, recipe_edit, staples, subrecipe
from .ai import AIError, get_provider
from .ai.metered import Meter, Metered
from .exchange import importer, jsonld, safe
from .config import Settings
from .importers.nyt import BASE as NYT_BASE, extract_nyt_url, fetch_nyt_recipe, parse_nyt_author, parse_nyt_html, parse_nyt_image, \
    recipe_id as nyt_id
from .importers import web
from .importers.photo import import_photo, import_subrecipe
from .importers.web import fetch_page
from .importers.structure import structure_ingredients
from .matcher import fill_cart
from .models import EDITABLE_FIELDS, FridgeItem, ListItem, PlanEntry, Recipe, RecipeDetail, RecipeOut, SourceKind, Staple, recipe_revision
from .sources import NYT_SITE, is_nyt_url
from .pcx import Pcx
from .shopping import build_list


def web_resolve(host: str, port: int) -> list[str]:
    """DNS for the any-site import's link check (a module function so tests can answer for invented hosts)."""
    return web._resolve(host, port)


OLD_WEEK_APP = ("Weeks now start on Monday. Update Meal Prep from Google Play to keep planning — this version would "
                "show every dinner on the wrong day.")
PREP_RETIRED = "Sunday prep and cook cards have been retired; update the app."

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
    # the recipe's household Good for company verdict, not part of the person's rating: set when the caller has
    # recipe.company (Owners; the phones = the legacy member), ignored otherwise; omitted = left as it is, null = cleared.
    # The verdict is yes or no only: "maybe" (older Android builds still offer it) leaves the verdict as it is.
    company: Literal["yes", "maybe", "no"] | None = None
    note: Annotated[str, Field(max_length=2000)] | None = None
    member_id: int | None = None     # rate for a child profile (rate.for_children); omitted = yourself

    @field_validator("note")
    @classmethod
    def blank_note_is_none(cls, v):
        return (v.strip() or None) if v is not None else None


class CompanyIn(BaseModel):
    company: Literal["yes", "no"] | None   # None = not decided (clears it)


class CompanyOut(BaseModel):
    company: str | None
    company_set_by: int | None = None
    company_set_by_name: str | None = None
    company_set_at: str | None = None


class RecipeRatingIn(RatingIn):
    cooked_on: date | None = None    # the day it was cooked; omitted = the dinner waiting for a rating, else today


class RecipeRatingOut(BaseModel):
    cooked_on: str
    entry_id: int | None = None          # the planned night that took the rating
    recipe_rating_id: int | None = None  # else the recipe's own rating for that day


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


class PackIn(BaseModel):    # how many pieces one pack holds ("a bunch has 6"); pieces null = forget it
    pieces: int | None = Field(None, ge=1, le=100)
    word: str | None = Field(None, max_length=20)


class SearchIn(BaseModel):
    term: str = Field(min_length=1)


class ExtraIn(BaseModel):     # an item added by search for a week, or one night of it (see extras.py)
    week: date
    term: Annotated[str, Field(min_length=1, max_length=200)]
    product_code: Annotated[str, Field(min_length=1, max_length=64)]
    quantity: Annotated[int, Field(gt=0, le=10000)] = 1
    day: Annotated[int, Field(ge=0, le=6)] | None = None   # 0 = Mon … 6 = Sun; none = for the week


class ExtraPatch(BaseModel):
    product_code: Annotated[str, Field(min_length=1, max_length=64)] | None = None
    quantity: Annotated[int, Field(gt=0, le=10000)] | None = None
    day: Annotated[int, Field(ge=0, le=6)] | None = None   # sent as null = move to the whole week; left out = unchanged


class NightIn(BaseModel):     # a night's name; empty = clear it
    title: Annotated[str, Field(max_length=200)] | None = None


class NightTitle(BaseModel):
    week: str
    day: int
    title: str | None


class Night(BaseModel):
    day: int                     # 0 = Mon … 6 = Sun
    title: str | None            # the night's name, if it has one
    items: list[dict]            # the night's added items (as GET /extras)


class AddLineIn(BaseModel):   # "Add an item" on a cart under review
    term: Annotated[str, Field(min_length=1, max_length=200)]
    product_code: Annotated[str, Field(min_length=1, max_length=64)]
    quantity: Annotated[int, Field(gt=0, le=10000)] = 1


class GoogleIn(BaseModel):        # Android: Credential Manager's Google ID token
    id_token: Annotated[str, Field(min_length=20, max_length=8000)]
    invite: Annotated[str, Field(max_length=200)] | None = None


class AppTokenIn(BaseModel):      # Android: the one-time code from the sign-in's return URL + the app's PKCE verifier
    code: Annotated[str, Field(min_length=20, max_length=200)]
    verifier: Annotated[str, Field(min_length=43, max_length=128)]


class HouseholdPick(BaseModel):
    household_id: int


class InviteAccept(BaseModel):
    token: Annotated[str, Field(min_length=10, max_length=200)]


class HouseholdPatch(BaseModel):
    name: Annotated[str, Field(max_length=200)] | None = None
    store_id: Annotated[str, Field(max_length=10)] | None = None


class MemberIn(BaseModel):
    name: Annotated[str, Field(max_length=200)]
    kind: Literal["account", "child"]
    birth_year: Annotated[int, Field(ge=1900, le=2200)] | None = None
    email: Annotated[str, Field(max_length=320)] | None = None
    roles: list[str] | None = None


class MemberPatch(BaseModel):
    name: Annotated[str, Field(max_length=200)] | None = None
    birth_year: Annotated[int, Field(ge=1900, le=2200)] | None = None
    email: Annotated[str, Field(max_length=320)] | None = None


class RolesIn(BaseModel):
    roles: Annotated[list[str], Field(max_length=10)]


class InviteIn(BaseModel):
    email: Annotated[str, Field(max_length=320)] | None = None
    roles: Annotated[list[str], Field(min_length=1, max_length=10)] = ["contributor"]
    member_id: int | None = None
    days: Annotated[int, Field(ge=1, le=60)] = households.INVITE_DAYS


@dataclass(frozen=True)
class Principal:
    """Who is calling: the shared token from the home network (legacy), or a session (bearer = app, cookie = web)."""
    via: Literal["legacy", "bearer", "cookie"]
    user_id: int | None = None
    session_hash: str | None = None
    session_household: int | None = None
    session_token: str | None = None


@dataclass(frozen=True)
class Scope:
    """The household a request acts for, as whom, and what that member may do."""
    household_id: int
    member_id: int | None
    user_id: int | None
    permissions: frozenset
    via: str

    def require(self, permission: str) -> None:
        if permission not in self.permissions:
            raise HTTPException(403, f"You need the {permission} permission for this.")


def _page(title: str, message: str, status: int) -> HTMLResponse:
    """A bare page for the browser sign-in round trip (the web app shows its own once it exists)."""
    return HTMLResponse(f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                        f"<title>{html.escape(title)}</title><h1>{html.escape(title)}</h1><p>{html.escape(message)}</p>"
                        f"<p><a href='/'>Meal Prep</a></p>", status_code=status)


log = logging.getLogger(__name__)


def default_week(today: date) -> date:
    """Today if Monday, else the coming Monday (the next week that hasn't started)."""
    return today if today.weekday() == 0 else today + timedelta(days=7 - today.weekday())


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
               today: Callable[[], date] = date.today, book_search=None, google=None) -> FastAPI:
    app = FastAPI(title="mealprep")
    if google is None and settings.google_client_id and settings.google_client_secret:
        google = auth.Google(settings.google_client_id, settings.google_client_secret,
                             settings.google_redirect_uri or settings.public_url.rstrip("/") + "/api/auth/callback")
    legacy_nets = auth.networks(settings.legacy_networks)
    ai = provider or get_provider(settings)
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
            importer.fail_interrupted(c)
            fridge.fail_interrupted(c)
            auth.clean_expired(c)

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

    @contextmanager
    def meter_conn():
        # the injected test connection, else a short connection of its own (never a request's or a job's)
        if conn is not None:
            yield conn
        else:
            with psycopg.connect(settings.dsn, autocommit=True) as mc:
                yield mc

    meter = Meter(meter_conn, settings.provider)

    def metered(p, s: "Scope", feature: str):
        return Metered(p, meter, s.household_id, s.member_id, feature)

    def scoped_conn(hid: int):
        # background jobs: their own connection, acting for the household that started them
        return db.scoped_connect(settings.dsn, hid)

    def principal(request: Request, c=Depends(get_conn)) -> Principal:
        authz = request.headers.get("authorization", "")
        if authz[:7].lower() == "bearer ":
            token = authz[7:].strip()
            if settings.token and hmac.compare_digest(token.encode(), settings.token.encode()):
                if not auth.address_in(request.client.host if request.client else None, legacy_nets):
                    raise HTTPException(401, "The shared token only works on the home network; sign in instead.")
                return Principal("legacy")
            s = auth.get_session(c, token, settings.session_days)
            if s is None or s["kind"] != "app":    # a web (cookie) session is no bearer token, and vice versa
                raise HTTPException(401)
            return Principal("bearer", s["user_id"], s["hash"], s["household_id"], token)
        cookie = request.cookies.get(auth.COOKIE)
        s = auth.get_session(c, cookie, settings.session_days) if cookie else None
        if s is None or s["kind"] != "web":
            raise HTTPException(401)
        if request.method not in ("GET", "HEAD", "OPTIONS") and not hmac.compare_digest(
                request.headers.get(auth.CSRF_HEADER, "").encode(), auth.csrf_token(cookie).encode()):
            raise HTTPException(403, "Missing or wrong X-CSRF-Token header.")
        return Principal("cookie", s["user_id"], s["hash"], s["household_id"], cookie)

    def household_scope(request: Request, p: Principal = Depends(principal), c=Depends(get_conn)) -> Scope:
        """Every household route: which household (X-Household header, else the session's last one, else the
        first), membership (none = 404, as if the household didn't exist), the route's permission (rbac.ROUTES;
        403 without it) — then the connection is scoped to that household (db.scope)."""
        route = request.scope.get("route")
        need = rbac.ROUTES.get((request.method, getattr(route, "path", None)))
        if need is None:   # a route missing from rbac.ROUTES: refused (the route sweep test catches it first)
            log.error("no permission mapped for %s %s", request.method, getattr(route, "path", request.url.path))
            raise HTTPException(403, "This route has no permission set.")
        if p.via == "legacy":
            hid = households.legacy_household(c)
            mid = households.legacy_member(c, hid)
            perms = households.permissions(c, mid) if mid is not None else households.all_permissions(c)
        else:
            want = request.headers.get("x-household", "").strip()
            if want and not want.isdigit():
                raise HTTPException(404, "household")
            hid = int(want) if want else p.session_household
            mid = households.membership(c, p.user_id, hid) if hid is not None else None
            if mid is None and not want:
                mine = households.user_households(c, p.user_id)
                if mine:
                    hid, mid = mine[0]["id"], mine[0]["member_id"]
            if mid is None:
                raise HTTPException(404, "household")
            perms = households.permissions(c, mid)
        if need != rbac.MEMBER and need not in perms:
            raise HTTPException(403, f"You need the {need} permission for this.")
        db.scope(c, hid)
        return Scope(hid, mid, p.user_id, perms, p.via)

    A = [Depends(household_scope)]

    def viewer(s: Scope) -> int | None:
        """Whose ratings a request reads and writes by default: the signed-in member; the shared token (the phones) =
        the household's legacy member (households.legacy_member). None (Family) only for a household without
        members yet."""
        return s.member_id

    def company_from_rating(c, s: Scope, body: RatingIn, recipe_id: int) -> None:
        """A rating write that carries `company` sets the recipe's verdict when the caller may (recipe.company);
        otherwise the answer is ignored (it is no longer part of a person's rating). "maybe" leaves the verdict as it
        is (the verdict is yes / no / not decided)."""
        if "company" in body.model_fields_set and "recipe.company" in s.permissions and body.company != "maybe":
            set_verdict(c, s, recipe_id, body.company)

    def set_verdict(c, s: Scope, recipe_id: int, company: str | None) -> dict | None:
        out = db.set_company(c, recipe_id, company, s.member_id)
        if out is not None and out["before"] != out["after"]:
            households.audit(c, s.household_id, "recipe.company", f"recipe:{recipe_id}", s.member_id, s.user_id,
                             {"from": out["before"], "to": out["after"]})
        return out

    def rater_for(c, s: Scope, member_id: int | None) -> tuple[int | None, int | None]:
        """(rater, entered_by) for a rating write: yourself, or a child profile of the household for a member with
        rate.for_children (the shared token too: it rates for a child as the household)."""
        me = viewer(s)
        if member_id is None or member_id == me:
            return me, me
        try:
            _, kind, _ = households._member(c, s.household_id, member_id)
        except households.NotFound:
            raise HTTPException(404, f"member {member_id}")
        if kind != "child":
            raise HTTPException(403, "You can only rate for yourself or a child profile.")
        s.require("rate.for_children")
        return member_id, me

    @app.middleware("http")
    async def import_size_guard(request, call_next):
        """Refuse an import upload whose declared size is far over the cap before it is spooled (the cap itself is
        enforced while reading, whatever the header says)."""
        if request.url.path == "/recipes/import":
            n = request.headers.get("content-length", "")
            if n.isdigit() and int(n) > import_cap + 1024 * 1024:
                return JSONResponse({"detail": too_big}, status_code=413)
        return await call_next(request)

    @app.middleware("http")
    async def sunday_week_app_guard(request, call_next):
        """Weeks start on Monday since 2026-11-14 (week ids are Mondays, day 0 = Mon). Android apps up to 0.20.0 work out
        Sunday weeks themselves and would show every dinner a day off (and save edits on the wrong night), so an
        OkHttp client that doesn't send `X-Week-Start: monday` (0.21.0 on) gets 426 and a sentence to update, except
        for /health and sign-in. The web app and scripts aren't OkHttp and aren't affected."""
        ua = request.headers.get("user-agent", "")
        if ua.lower().startswith("okhttp") and request.headers.get("x-week-start", "").lower() != "monday" and \
                request.url.path != "/health" and not request.url.path.startswith("/auth/"):
            return JSONResponse({"detail": OLD_WEEK_APP}, status_code=426)
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
    def share(body: ShareIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """Import the recipe at the first link in `text`: an NYT Cooking recipe link (its own importer: slug repair,
        photo crops) or any other web page (importers/web.py: JSON-LD, microdata, else the AI on the page's text;
        attributed to the site and author). A link already in the library (same NYT id, or the same page) returns
        that recipe with existing = true instead of importing it again. 422 = no link / not a public web page / no
        recipe on it; 502 = the site couldn't be read (blocked, paywall, down) or the AI failed."""
        if body.week is not None:
            s.require("plan.edit")
        if extract_nyt_url(body.text):
            return _share_nyt(c, s, body)
        link = web.extract_url(body.text)
        if not link:
            raise HTTPException(422, "No web link found. Share or paste the recipe page's link.")
        return _share_web(c, s, body, link)

    def _existing_web(c, url: str):
        return c.execute("SELECT id FROM recipes WHERE source_url = ANY(%s) ORDER BY id LIMIT 1",
                         (web.url_variants(url),)).fetchone()

    def _share_web(c, s: Scope, body: ShareIn, link: str):
        try:
            link = web.check_url(web.normalise_url(link), resolve=web_resolve)
        except web.WebImportError as e:
            log.warning("share import of %s refused: %s", link[:200], e.reason)
            raise HTTPException(422, str(e))
        existing = _existing_web(c, link)
        if existing:   # the shared link is already in the library (checked before fetching anything)
            return _save_and_plan(c, db.get_recipe(c, existing[0]), body.week, existing=True)
        try:
            page = fetch_page(link, import_cap)
            # the AI reads a page without recipe markup (several calls on a long page: the document reader's timeout)
            found = web.read_recipe(page.html, page.url, ai=metered(document_ai, s, "share"), tidy=importer.tidy)
        except web.WebImportError as e:
            log.warning("share import of %s failed: %s: %s", link[:200], e.reason, str(e)[:300])
            raise HTTPException(422 if e.reason in web.USER_ERRORS else 502, str(e))
        except Exception as e:   # AIError and anything unexpected
            log.warning("share import of %s failed: %s: %s", link[:200], type(e).__name__, str(e)[:300])
            raise HTTPException(502, f"import failed: {type(e).__name__}: {str(e)[:200]}")
        r = found.recipe
        existing = _existing_web(c, r.source_url) if r.source_url else None
        if existing:   # a redirect or the canonical link names a page already in the library
            return _save_and_plan(c, db.get_recipe(c, existing[0]), body.week, existing=True)
        log.warning("share import of %s: %s recipe from %s (%s)", link[:200], found.how, r.source_title, r.source_url)
        return _save_and_plan(c, r, body.week)

    def _share_nyt(c, s: Scope, body: ShareIn):
        url = extract_nyt_url(body.text)
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
            image = parse_nyt_image(html)   # a link to NYT's photo (hotlinked, never downloaded)
            author = parse_nyt_author(html)
            ings = structure_ingredients(metered(ai, s, "share"), lines)
        except Exception as e:   # NytFetchError, NotARecipe, AIError
            log.warning("share import of %s failed: %s: %s", url, type(e).__name__, str(e)[:300])
            raise HTTPException(502, f"import failed: {type(e).__name__}: {str(e)[:200]}")
        # a website like any other (2026-11-08): the site NYT Cooking and the recipe's author
        r = Recipe(title=title, source="nyt", source_url=url, servings=servings, ingredients=ings, steps=steps,
                   image=image, source_kind="web", source_title=NYT_SITE, source_author=author)
        return _save_and_plan(c, r, body.week)

    @app.post("/recipes/photo", dependencies=A)
    def photo(files: list[UploadFile] = File(...), week: date | None = Form(None),
              title: str | None = Form(None), source_kind: SourceKind = Form("book"),
              source_title: str | None = Form(None), source_ref: str | None = Form(None),
              source_author: str | None = Form(None), source_isbn: str | None = Form(None), c=Depends(get_conn),
              s: Scope = Depends(household_scope)):
        if week is not None:
            s.require("plan.edit")
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
                r = import_photo(metered(ai, s, "photo"), _save_pages(d, files), title_hint=title)
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
    def patch_recipe(recipe_id: int, body: RecipePatch, c=Depends(get_conn),
                     s: Scope = Depends(household_scope)) -> RecipeOut:
        """Edit a recipe (title, servings, ingredient lines, steps, notes, description, times, yield) or set where it
        comes from (web, book or other; "nyt" from an older app = the website NYT Cooking). A different book title sent without an author/ISBN clears
        them (they belonged to the old book). Changed ingredient lines are structured again (recipe_edit; the AI, else
        the deterministic clean-up). The id, uid, plan entries and ratings stay; a cart made from the old
        lines goes stale (db.plan_fingerprint)."""
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
            content["ingredients"] = recipe_edit.edit_ingredients(metered(edit_ai, s, "edit"), before.ingredients, content["ingredients"])
        with c.transaction():
            r = check(db.get_recipe(c, recipe_id, for_update=True))
            if "ingredients" in content and r.ingredients != before.ingredients:
                raise HTTPException(409, "The recipe changed while it was being saved; try again.")
            kind = fields.get("source_kind", r.source_kind)
            title = fields.get("source_title", r.source_title)
            ref = fields.get("source_ref", r.source_ref)
            # a book's author/ISBN belong to that book; a web recipe's author stays when the site's name is edited
            same_book = (title or "").lower() == (r.source_title or "").lower() or (kind == "web" == r.source_kind)
            author = fields.get("source_author", r.source_author if same_book else None)
            isbn = fields.get("source_isbn", r.source_isbn if same_book else None)
            if kind == "web" and not title and is_nyt_url(r.source_url):
                title = NYT_SITE   # an old app's "nyt" (= web without a site name) keeps the site's name
            db.update_source(c, recipe_id, kind, title, ref, author, isbn)
            if content:
                db.update_recipe(c, recipe_id, r.model_copy(update=content))
        return _recipe_out(c, recipe_id)

    @app.get("/recipes/sources", dependencies=A)
    def recipe_sources(c=Depends(get_conn)):
        """Each cookbook and named other source, each website (NYT Cooking among them), "Websites", "Unknown book",
        "Other", with counts; every `kind` is web, book or other; `key` is the GET /recipes?source= value."""
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
                     c=Depends(get_conn), s: Scope = Depends(household_scope)) -> RecipeOut:
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
                name, ings, steps = import_subrecipe(metered(ai, s, "pages"), _save_pages(d, files), ref.raw, ref.ref_page)
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

    def run_import(jid: int, items: list[dict], work: list, s: Scope):
        # background thread with its own connection, like draft builds
        try:
            with scoped_conn(s.household_id) as bc:
                importer.run_job(bc, jid, items, work, metered(import_ai, s, "import"), settings.import_workers,
                                 settings.provider)
        except Exception:
            log.exception("import job %s: background run crashed", jid)

    def run_read(jid: int, doc, name: str | None, s: Scope):
        # a document's first preview: the AI finds its recipes in the background (own connection, like imports)
        try:
            with scoped_conn(s.household_id) as bc:
                importer.run_read(bc, jid, doc, name, metered(document_ai, s, "document"))
        except Exception:
            log.exception("read job %s: background run crashed", jid)

    @app.post("/recipes/import", dependencies=A)
    def import_recipes(file: UploadFile = File(...), dry_run: bool = Form(True), choices: str | None = Form(None),
                       name: str | None = Form(None, max_length=500), c=Depends(get_conn),
                       s: Scope = Depends(household_scope)):
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
                    threading.Thread(target=run_read, args=(jid, doc, name, s), name=f"read-{jid}", daemon=True).start()
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
            threading.Thread(target=run_import, args=(jid, items, work, s), name=f"import-{jid}", daemon=True).start()
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
    def recipe_detail(recipe_id: int, c=Depends(get_conn), s: Scope = Depends(household_scope)) -> RecipeDetail:
        r = db.get_recipe(c, recipe_id)
        if r is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        return RecipeDetail(**r.model_dump(), ratings=db.rating_summaries(c, today(), [recipe_id])[recipe_id],
                            planned_weeks=db.planned_weeks(c, today(), [recipe_id]).get(recipe_id, []),
                            history=db.recipe_history(c, recipe_id, viewer(s)),
                            imported_history=db.imported_history(c, recipe_id),
                            rated_history=db.recipe_rated_history(c, recipe_id, viewer(s)))

    @app.put("/recipes/{recipe_id}/rating", dependencies=A)
    def put_recipe_rating(recipe_id: int, body: RecipeRatingIn, c=Depends(get_conn),
                          s: Scope = Depends(household_scope)) -> RecipeRatingOut:
        """Rate the recipe from the recipe itself (not from a night on the plan), as yourself or (member_id) for a
        child profile. cooked_on (default: the newest dinner of it still waiting for this person's rating, else today)
        can't be later than today. A night on the plan for it that day takes the rating, so it's the same as rating
        that dinner; otherwise it's one rating per recipe, day and person."""
        if body.cooked_on is not None and body.cooked_on > today():
            raise HTTPException(422, "cooked_on can't be in the future")
        rater, by = rater_for(c, s, body.member_id)
        out = db.rate_recipe(c, recipe_id, body.family, None, body.note, today(), body.cooked_on, rater, by)
        if out is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        company_from_rating(c, s, body, recipe_id)
        return RecipeRatingOut(**out)

    @app.put("/recipes/{recipe_id}/company", dependencies=A)
    def put_company(recipe_id: int, body: CompanyIn, c=Depends(get_conn),
                    s: Scope = Depends(household_scope)) -> CompanyOut:
        """The household's Good for company verdict for the recipe (yes / no; null = not decided). Owners
        (recipe.company); changes go to the audit log."""
        if set_verdict(c, s, recipe_id, body.company) is None:
            raise HTTPException(404, f"recipe {recipe_id}")
        return CompanyOut(**db.company_verdicts(c, [recipe_id]).get(recipe_id, {"company": None}))

    @app.delete("/recipes/{recipe_id}/rating", status_code=204, dependencies=A)
    def delete_recipe_rating(recipe_id: int, cooked_on: date = Query(...), member_id: int | None = Query(None),
                             c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """Remove your (or a child profile's) rating of the time it was cooked that day (the planned night's or the
        recipe's own)."""
        if not db.unrate_recipe(c, recipe_id, cooked_on, *rater_for(c, s, member_id)):
            raise HTTPException(404, f"recipe {recipe_id}")

    @app.get("/ratings/pending", dependencies=A)
    def ratings_pending(today_: date | None = Query(None, alias="today"), c=Depends(get_conn),
                        s: Scope = Depends(household_scope)):
        """Placed nights before today (last 14 days) you haven't rated yet — the morning-after prompt. The shared
        token: nights nobody has rated."""
        return db.pending_ratings(c, today_ or today(), rater=viewer(s))

    @app.get("/weeks", dependencies=A)
    def weeks(from_: date | None = Query(None, alias="from"), count: int = Query(8, ge=1, le=52), c=Depends(get_conn)):
        return db.week_summaries(c, from_ or today(), count)

    @app.get("/weeks/{week}", dependencies=A)
    def get_week(week: date, c=Depends(get_conn), s: Scope = Depends(household_scope)) -> list[PlanEntry]:
        return db.get_week(c, week, viewer(s))

    @app.get("/weeks/{week}/nights", dependencies=A)
    def get_nights(week: date, c=Depends(get_conn)) -> list[Night]:
        """The week's nights that have a name or store items (Monday first). A night with no recipe still counts as
        planned for display; it never asks for a rating."""
        return nights.list_nights(c, week)

    @app.put("/weeks/{week}/nights/{day}", dependencies=A)
    def put_night(week: date, day: int, body: NightIn, c=Depends(get_conn)) -> NightTitle:
        """Name or rename a night (trimmed, at most 60 characters); an empty name clears it."""
        try:
            return nights.set_title(c, week, day, body.title)
        except nights.NightError as e:
            raise HTTPException(422, str(e))

    @app.delete("/weeks/{week}/nights/{day}", status_code=204, dependencies=A)
    def delete_night(week: date, day: int, c=Depends(get_conn)):
        """Clear a night's name (its store items stay)."""
        try:
            nights.clear_title(c, week, day)
        except nights.NightError as e:
            raise HTTPException(422, str(e))

    @app.post("/weeks/{week}/entries", dependencies=A)
    def add_entry(week: date, body: EntryIn, c=Depends(get_conn), s: Scope = Depends(household_scope)) -> PlanEntry:
        if db.get_recipe(c, body.recipe_id) is None:
            raise HTTPException(404, f"recipe {body.recipe_id}")
        eid = db.add_to_week(c, week, body.recipe_id)
        return next(e for e in db.get_week(c, week, viewer(s)) if e.id == eid)

    @app.patch("/plan/{entry_id}", status_code=204, dependencies=A)
    def patch_entry(entry_id: int, body: EntryPatch, c=Depends(get_conn)):
        if body.day is not None and not 0 <= body.day <= 6:
            raise HTTPException(422, "day must be 0 (Mon) … 6 (Sun)")
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
    def put_rating(entry_id: int, body: RatingIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """Your rating of that dinner (member_id: a child profile's); the shared token rates as the
        household's legacy member. `company` sets the recipe's Good for company verdict for an Owner."""
        rater, by = rater_for(c, s, body.member_id)
        if not db.set_rating(c, entry_id, body.family, None, body.note, rater=rater, by=by):
            raise HTTPException(404, f"plan entry {entry_id}")
        row = c.execute("SELECT recipe_id FROM plan WHERE id=%s", (entry_id,)).fetchone()
        if row:
            company_from_rating(c, s, body, row[0])

    @app.delete("/plan/{entry_id}/rating", status_code=204, dependencies=A)
    def delete_rating(entry_id: int, member_id: int | None = Query(None), c=Depends(get_conn),
                      s: Scope = Depends(household_scope)):
        if not db.delete_rating(c, entry_id, *rater_for(c, s, member_id)):
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
    def add_fridge(body: FridgeIn, c=Depends(get_conn), s: Scope = Depends(household_scope)) -> FridgeItem:
        _use_by(body.use_by)
        return _fridge_call(fridge.create_item, c, metered(fridge_ai, s, "fridge"), body.name, body.qty, body.unit, today=today(),
                            use_soon=body.use_soon, use_by=body.use_by, from_list=body.from_list)

    @app.patch("/fridge/{item_id}", dependencies=A)
    def patch_fridge(item_id: int, body: FridgePatch, c=Depends(get_conn),
                     s: Scope = Depends(household_scope)) -> FridgeItem:
        fields = body.model_dump(include=body.model_fields_set)   # only what the client sent
        for f in ("name", "use_soon"):
            if f in fields and fields[f] is None:
                raise HTTPException(422, f"{f} can't be null")
        _use_by(fields.get("use_by"))
        return _fridge_call(fridge.update_item, c, metered(fridge_ai, s, "fridge"), item_id, today=today(), **fields)

    @app.post("/fridge/suggest", dependencies=A)
    def fridge_suggest(body: SuggestIn, c=Depends(get_conn)):
        """Dinners from the library that use what's in the fridge (use soon first) with the fewest extra ingredients
        (fridge.select): {picks, fridge_left, stale, thin_library}. Read-only, no AI."""
        return fridge.suggest(c, today(), body.nights, body.people, body.week, body.exclude)

    def run_fridge_read(rid: int, td: tempfile.TemporaryDirectory, paths: list[str], s: Scope):
        # background thread with its own connection, like draft builds; the photos are deleted whatever happens
        try:
            with scoped_conn(s.household_id) as bc:
                fridge.run_read(bc, rid, metered(fridge_photo_ai, s, "fridge_photo"), metered(fridge_ai, s, "fridge"),
                                paths, today())
        except Exception:
            log.exception("fridge read %s: background run crashed", rid)
        finally:
            td.cleanup()

    @app.post("/fridge/photos", status_code=202, dependencies=A)
    def fridge_photos(files: list[UploadFile] = File(...), c=Depends(get_conn), s: Scope = Depends(household_scope)):
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
                threading.Thread(target=run_fridge_read, args=(rid, td, paths, s), name=f"fridge-{rid}", daemon=True).start()
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
    def fridge_apply(read_id: int, body: ApplyIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        lines = [ln.model_dump(include=ln.model_fields_set) for ln in body.lines]
        return _read_call(fridge.apply_read, c, metered(fridge_ai, s, "fridge"), read_id, lines, today())

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
    def cart(body: CartIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """Draft + build + send synchronously (smoke scripts). The app uses /drafts."""
        try:
            return fill_cart(body.items, shop, metered(ai, s, "cart"), c, weeks=body.weeks, provider_name=settings.provider,
                             store_id=settings.store_id, workers=settings.match_workers)
        except Exception as e:   # PC Express create/add failures
            raise HTTPException(502, f"cart failed: {type(e).__name__}: {str(e)[:200]}")

    def run_build(did: int, s: Scope):
        # background thread with its own connection; the request connection is closed by then
        try:
            with scoped_conn(s.household_id) as bc:
                drafts.build_draft(bc, did, shop, metered(ai, s, "cart"), store_id=settings.store_id, workers=settings.match_workers)
        except Exception:
            log.exception("draft %s: background build crashed", did)

    @app.post("/drafts", status_code=202, dependencies=A)
    def create_draft(body: DraftIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        # The weeks' items added by search come after the list's lines (the app doesn't send them).
        items = [it for it in body.items if it.extra_id is None] + extras.items_for(c, body.weeks)
        did = drafts.create_draft(c, items, body.weeks, store_id=settings.store_id, ai_provider=settings.provider)
        threading.Thread(target=run_build, args=(did, s), name=f"draft-{did}", daemon=True).start()
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

    @app.put("/drafts/{draft_id}/lines/{line_id}/pack", dependencies=A)
    def put_pack(draft_id: int, line_id: int, body: PackIn, c=Depends(get_conn)):
        """How many pieces one pack of the line's product holds, saved for the product (null pieces = forget);
        returns the draft (every line with that product is re-planned)."""
        return _draft_call(drafts.set_pack, c, draft_id, line_id, body.pieces, body.word)

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
        return _extra_call(extras.add_extra, c, body.week, body.term, body.product_code, body.quantity, day=body.day)

    @app.patch("/extras/{extra_id}", dependencies=A)
    def patch_extra(extra_id: int, body: ExtraPatch, c=Depends(get_conn)):
        kw = {"day": body.day} if "day" in body.model_fields_set else {}
        return _extra_call(extras.update_extra, c, extra_id, quantity=body.quantity, product_code=body.product_code, **kw)

    @app.delete("/extras/{extra_id}", status_code=204, dependencies=A)
    def delete_extra(extra_id: int, c=Depends(get_conn)):
        extras.delete_extra(c, extra_id)

    @app.post("/drafts/{draft_id}/send", dependencies=A)
    def send_draft(draft_id: int, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        try:
            cart_id = _draft_call(drafts.send_draft, c, draft_id, shop)
        except httpx.HTTPError as e:   # nothing recorded; draft stays ready so the user can retry
            raise HTTPException(502, f"PC Express cart failed: {type(e).__name__}: {str(e)[:200]}")
        households.audit(c, s.household_id, "cart.send", f"draft:{draft_id}", s.member_id, s.user_id)
        return {"pcx_cart_id": cart_id}

    # Sunday prep plans and cook cards were retired on 2026-10-31: no more plans are written (no AI calls). The routes
    # answer 410 so an older app says so instead of failing oddly; the prep_plans/prep_tasks/prep_task_events/cook_cards
    # tables and their rows are left in place (DESIGN.md "Status — Sunday prep retired" says how to drop them).
    def prep_retired():
        raise HTTPException(410, PREP_RETIRED)

    for method, path in (("POST", "/prep-plans"), ("GET", "/prep-plans/{plan_id}"), ("GET", "/weeks/{week}/prep-plan"),
                         ("PATCH", "/prep-plans/{plan_id}/tasks/{task_id}"), ("GET", "/plan/{entry_id}/card")):
        app.add_api_route(path, prep_retired, methods=[method], dependencies=A, include_in_schema=False,
                          name=f"retired {method} {path}")

    @app.put("/picks/{key:path}", status_code=204, dependencies=A)
    def put_pick(key: str, body: PickIn, c=Depends(get_conn)):
        db.set_pick(c, key, body.product_code, chosen_by="user")

    # --- sign-in (task 1): Google OIDC, sessions, invites ---

    def _need_google():
        if google is None:
            raise HTTPException(503, "Google sign-in isn't set up on this server.")

    def _session_cookie(resp: Response, token: str) -> None:
        resp.set_cookie(auth.COOKIE, token, max_age=settings.session_days * 86400, path="/", secure=True,
                        httponly=True, samesite="lax")

    @app.get("/auth/login")
    def login(next: str | None = Query(None, max_length=500), invite: str | None = Query(None, max_length=200),
              client: str | None = Query(None, max_length=20), app_id: str | None = Query(None, alias="app", max_length=100),
              app_challenge: str | None = Query(None, max_length=100), c=Depends(get_conn)):
        """Start a sign-in: off to Google (authorization code + PKCE), back at /auth/callback. The browser (web) gets a
        cookie at the end. `client=android` (with `app_challenge` = S256 of a verifier the app keeps, and `app` = its
        application id, default the release build) is the Android app in a Custom Tab: the callback hands a one-time
        code to the app instead (see POST /auth/app-token)."""
        _need_google()
        if client is not None:
            if client != auth.ANDROID:
                raise HTTPException(422, "Unknown sign-in client.")
            app_id = app_id or auth.DEFAULT_APP
            if app_id not in auth.APP_IDS:
                raise HTTPException(422, "Unknown app.")
            if not auth.valid_challenge(app_challenge):
                raise HTTPException(422, "app_challenge must be an S256 code challenge.")
        elif app_id or app_challenge:
            raise HTTPException(422, "app and app_challenge go with client=android.")
        state, verifier, nonce = auth.start_login(c, next, invite, app_id if client else None,
                                                  app_challenge if client else None)
        return RedirectResponse(google.authorize_url(state, auth.pkce_challenge(verifier), nonce), status_code=302,
                                headers={"Cache-Control": "no-store"})

    def _to_app(app_id: str, **params) -> RedirectResponse:
        return RedirectResponse(auth.app_return(app_id, **params), status_code=303, headers={"Cache-Control": "no-store"})

    @app.get("/auth/callback")
    def callback(request: Request, code: str | None = Query(None, max_length=2000),
                 state: str | None = Query(None, max_length=200), error: str | None = Query(None, max_length=200),
                 c=Depends(get_conn)):
        _need_google()
        cancelled = "The Google sign-in was cancelled or didn't finish. Try again."
        if not state:
            return _page("Not signed in", cancelled, 400)
        try:
            login = auth.finish_login(c, state)
        except auth.AuthError as e:
            return _page("Not signed in", str(e), 400)
        app_id = login["app_id"] if login["app_id"] in auth.APP_IDS else None
        if error or not code:
            return _to_app(app_id, error="cancelled") if app_id else _page("Not signed in", cancelled, 400)
        try:
            claims = google.verify(google.exchange(code, login["verifier"]), nonce=login["nonce"])
            uid, hid = households.sign_in(c, claims, login["invite_hash"])
        except households.NotInvited as e:
            return _to_app(app_id, error="not_invited", message=str(e)) if app_id else _page("No household yet", str(e), 403)
        except (auth.AuthError, households.InviteError) as e:
            return _to_app(app_id, error="failed", message=str(e)) if app_id else _page("Not signed in", str(e), 400)
        if app_id:
            # no cookie: the app picks the session up with the code and its verifier
            return _to_app(app_id, code=auth.create_app_code(c, uid, hid, login["app_challenge"]))
        token, _ = auth.create_session(c, uid, hid, "web", settings.session_days, request.headers.get("user-agent"))
        resp = RedirectResponse(auth.safe_next(login["next"]), status_code=303, headers={"Cache-Control": "no-store"})
        _session_cookie(resp, token)
        return resp

    @app.post("/auth/app-token")
    def app_token(body: AppTokenIn, request: Request, c=Depends(get_conn)):
        """Android: the one-time code from the sign-in's return URL + the verifier of the app_challenge it started with
        → an app session token for `Authorization: Bearer` (the same session POST /auth/google gives). The code is
        used up by any attempt; 400 = expired, used, or the wrong verifier (sign in again)."""
        _need_google()
        try:
            uid, hid = auth.redeem_app_code(c, body.code, body.verifier)
        except auth.AuthError as e:
            raise HTTPException(400, str(e))
        token, expires = auth.create_session(c, uid, hid, "app", settings.session_days, request.headers.get("user-agent"))
        return {"token": token, "expires_at": expires.isoformat(), "household_id": hid}

    @app.post("/auth/google")
    def google_sign_in(body: GoogleIn, request: Request, c=Depends(get_conn)):
        """Android: a Google ID token (Credential Manager, audience = this server's web client id) → an app session
        token for `Authorization: Bearer`. 403 = not in a household and no invite."""
        _need_google()
        try:
            claims = google.verify(body.id_token)
            uid, hid = households.sign_in(c, claims, auth.hash_token(body.invite) if body.invite else None)
        except households.NotInvited as e:
            raise HTTPException(403, str(e))
        except (auth.AuthError, households.InviteError) as e:
            raise HTTPException(401 if isinstance(e, auth.AuthError) else 400, str(e))
        token, expires = auth.create_session(c, uid, hid, "app", settings.session_days, request.headers.get("user-agent"))
        return {"token": token, "expires_at": expires.isoformat(), "household_id": hid}

    def _signed_in(p: Principal) -> Principal:
        if p.via == "legacy":
            raise HTTPException(403, "Sign in for this (the shared token has no account).")
        return p

    @app.get("/auth/me")
    def me(p: Principal = Depends(principal), c=Depends(get_conn),
           x_household: str | None = Header(None, max_length=20)):
        """Who is signed in, their households, the current one (X-Household, else the session's) with their roles
        and permissions; csrf_token for a cookie session (send it as X-CSRF-Token on writes)."""
        if p.via == "legacy":
            hid = households.legacy_household(c)
            mid = households.legacy_member(c, hid)
            perms = households.permissions(c, mid) if mid is not None else households.all_permissions(c)
            return {"via": "legacy", "user": None, "households": [], "household": households.get_household(c, hid),
                    "member_id": mid, "roles": households.member_roles(c, mid) if mid else [],
                    "permissions": sorted(perms)}
        mine = households.user_households(c, p.user_id)
        want = int(x_household) if x_household and x_household.isdigit() else p.session_household
        cur = next((h for h in mine if h["id"] == want), mine[0] if mine else None)
        u = c.execute("SELECT id, email, name, picture FROM users WHERE id=%s", (p.user_id,)).fetchone()
        out = {"via": p.via, "user": dict(zip(("id", "email", "name", "picture"), u)), "households": mine,
               "household": households.get_household(c, cur["id"]) if cur else None,
               "member_id": cur["member_id"] if cur else None,
               "roles": households.member_roles(c, cur["member_id"]) if cur else [],
               "permissions": sorted(households.permissions(c, cur["member_id"])) if cur else []}
        if p.via == "cookie":
            out["csrf_token"] = auth.csrf_token(p.session_token)
        return out

    @app.post("/auth/logout", status_code=204)
    def logout(response: Response, p: Principal = Depends(principal), c=Depends(get_conn)):
        if p.session_hash:
            auth.delete_session(c, p.session_hash)
        response.delete_cookie(auth.COOKIE, path="/", secure=True, httponly=True, samesite="lax")

    @app.put("/auth/household")
    def pick_household(body: HouseholdPick, p: Principal = Depends(principal), c=Depends(get_conn)):
        """The household switcher: later requests without X-Household act for this one."""
        _signed_in(p)
        if households.membership(c, p.user_id, body.household_id) is None:
            raise HTTPException(404, "household")
        auth.set_session_household(c, p.session_hash, body.household_id)
        return {"household_id": body.household_id}

    @app.post("/invites/accept")
    def accept_invite(body: InviteAccept, p: Principal = Depends(principal), c=Depends(get_conn)):
        """A signed-in user joins another household with an invite (it becomes the session's household)."""
        _signed_in(p)
        try:
            hid = households.accept_invite(c, p.user_id, auth.hash_token(body.token))
        except households.InviteError as e:
            raise HTTPException(400, str(e))
        auth.set_session_household(c, p.session_hash, hid)
        return {"household_id": hid}

    # --- the household (task 2): settings, members, roles, invites ---

    def _rules(fn, *args, **kw):
        try:
            return fn(*args, **kw)
        except households.NotFound as e:
            raise HTTPException(404, str(e))
        except households.RuleError as e:
            raise HTTPException(409, str(e))

    @app.get("/household", dependencies=A)
    def get_household(c=Depends(get_conn), s: Scope = Depends(household_scope)):
        return households.get_household(c, s.household_id)

    @app.patch("/household", dependencies=A)
    def patch_household(body: HouseholdPatch, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        _rules(households.update_household, c, s.household_id, s.member_id,
               **body.model_dump(include=body.model_fields_set))
        return households.get_household(c, s.household_id)

    @app.get("/household/ai-usage", dependencies=A)
    def ai_usage(days: int = Query(30, ge=1, le=366), c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """AI calls per day and feature (metering for a later paid plan) and the daily budget."""
        rows = c.execute("SELECT at::date, feature, count(*), count(*) FILTER (WHERE NOT ok), sum(input_tokens), "
                         "sum(output_tokens) FROM ai_usage WHERE household_id=%s AND at > now() - %s * interval '1 day' "
                         "GROUP BY 1, 2 ORDER BY 1 DESC, 2", (s.household_id, days))
        return {"budget": households.get_household(c, s.household_id)["ai_daily_budget"],
                "days": [{"date": d.isoformat(), "feature": f, "calls": n, "failed": bad, "input_tokens": ti,
                          "output_tokens": to} for d, f, n, bad, ti, to in rows]}

    @app.get("/household/members", dependencies=A)
    def get_members(c=Depends(get_conn), s: Scope = Depends(household_scope)):
        return households.list_members(c, s.household_id)

    @app.post("/household/members", status_code=201, dependencies=A)
    def add_member(body: MemberIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """A child profile, or an account member nobody signs in as yet (claimed by its email or an invite)."""
        if body.roles is not None and body.roles != (["child"] if body.kind == "child" else ["contributor"]):
            s.require("roles.assign")
        mid = _rules(households.add_member, c, s.household_id, body.name, body.kind, body.birth_year, body.email,
                     body.roles, s.member_id)
        return next(m for m in households.list_members(c, s.household_id) if m["id"] == mid)

    @app.patch("/household/members/{member_id}", dependencies=A)
    def patch_member(member_id: int, body: MemberPatch, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        fields = body.model_dump(include=body.model_fields_set)
        if "name" in fields:
            fields["display_name"] = fields.pop("name")
        _rules(households.update_member, c, s.household_id, member_id, s.member_id, **fields)
        return next(m for m in households.list_members(c, s.household_id) if m["id"] == member_id)

    @app.delete("/household/members/{member_id}", status_code=204, dependencies=A)
    def delete_member(member_id: int, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        _rules(households.remove_member, c, s.household_id, member_id, s.member_id)

    @app.put("/household/members/{member_id}/roles", dependencies=A)
    def put_roles(member_id: int, body: RolesIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """Replace a member's roles (Child only on child profiles, alone; the last Owner / Approver stays)."""
        return {"roles": _rules(households.set_roles, c, s.household_id, member_id, body.roles, s.member_id)}

    @app.get("/household/invites", dependencies=A)
    def get_invites(c=Depends(get_conn), s: Scope = Depends(household_scope)):
        return households.list_invites(c, s.household_id)

    @app.post("/household/invites", status_code=201, dependencies=A)
    def create_invite(body: InviteIn, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        """A one-use invite link (token shown once). email = only that Google account; member_id = claims that
        member (a seeded account, or a child profile turning 13)."""
        iid, token, expires = _rules(households.create_invite, c, s.household_id, s.member_id, body.email, body.roles,
                                     body.member_id, body.days, may_assign="roles.assign" in s.permissions)
        url = settings.public_url.rstrip("/") + "/api/auth/login?" + urlencode({"invite": token})
        return {"id": iid, "token": token, "url": url, "expires_at": expires.isoformat()}

    @app.delete("/household/invites/{invite_id}", status_code=204, dependencies=A)
    def delete_invite(invite_id: int, c=Depends(get_conn), s: Scope = Depends(household_scope)):
        _rules(households.revoke_invite, c, s.household_id, invite_id, s.member_id)

    return app


def app_from_env():
    return create_app(Settings())

