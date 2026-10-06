from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class Ingredient(BaseModel):
    raw: str
    name: str                    # canonical, singular, lowercase: "onion", "chicken thigh"
    qty: float | None = None
    unit: str | None = None      # normalized: g, ml, tsp, tbsp, cup, lb, oz, each(None)
    prep: str | None = None      # "diced", "boneless skinless"
    likely_on_hand: bool = False
    ref_page: int | None = None  # "…, page 191": a sub-recipe printed elsewhere in the book
    sub_recipe: str | None = None  # attached sub-recipe this line belongs to ("Crêpe batter")
    expanded: bool = False         # a "…, page 191" line whose sub-recipe was attached: not bought itself


SourceKind = Literal["nyt", "book", "other"]
SOURCE_FIELDS = {"source_kind", "source_title", "source_ref", "source_author", "source_isbn"}   # stored in recipes columns, not in `data`


class Recipe(BaseModel):
    id: int | None = None
    title: str
    source: str                  # how it was imported: "nyt" | "photo"
    source_url: str | None = None
    servings: int | None = None
    ingredients: list[Ingredient]
    steps: list[str]
    # Where the recipe comes from (columns on recipes, editable with PATCH /recipes/{id}): NYT Cooking, a cookbook
    # (source_title = the book, null = not known yet; source_ref = page(s), free text) or something else.
    source_kind: SourceKind | None = None   # None only before saving: save_recipe derives it (default_kind)
    source_title: str | None = None
    source_ref: str | None = None
    # The book's author(s) and ISBN (0.4.3), filled when a book search suggestion is picked; only with a source_title.
    source_author: str | None = None
    source_isbn: str | None = None

    def default_kind(self) -> str:
        if self.source_url and "nytimes.com" in self.source_url:
            return "nyt"
        return "book" if self.source == "photo" else "other"


class ListItem(BaseModel):
    key: str                     # f"{name}|{unit or 'each'}"
    name: str
    qty: float | None = None
    unit: str | None = None
    prep: str | None = None
    likely_on_hand: bool = False
    needed: bool = True          # the shopper's checkbox
    recipes: list[str] = []
    staple: bool = False         # a weekly staple is (part of) this line; recipes then include "Staples"
    staple_packs: int | None = None  # a staple counted in packs ("1 carton of milk"): buy at least this many


class Staple(BaseModel):
    id: int
    name: str
    qty: float | None = None     # no unit: packs ("1" = one carton); with a unit: an amount like a recipe line
    unit: str | None = None
    weekly: bool = True          # ticked on the shopping list by default
    position: int = 0
    last_bought: str | None = None   # ISO date: newest sent cart with this item, or the manual hint if later
    created_at: str


class Product(BaseModel):
    code: str
    name: str
    brand: str | None = None
    package_size: str | None = None
    price: float | None = None
    stock: str | None = None
    sold_by: str | None = None   # PC Express pricing type: "SOLD_BY_EACH", "SOLD_BY_EACH_PRICED_BY_WEIGHT", …


class CartLine(BaseModel):
    item_key: str
    product: Product | None
    quantity: int = 1
    status: str                  # "added" | "unmatched"


class CartResult(BaseModel):
    cart_id: str
    draft_id: int | None = None
    lines: list[CartLine]


class Rating(BaseModel):
    family: int                  # 1–5 "was it a hit?"
    company: str | None = None   # "yes" | "maybe" | "no" — would I make it for guests?
    note: str | None = None
    rated_at: str                # ISO timestamp


class PlanEntry(BaseModel):
    id: int
    week: str                    # ISO date of the Sunday
    recipe_id: int
    day: int | None = None       # 0=Sun … 6=Sat; None = not placed on a night yet
    multiplier: float = 1.0
    rating: Rating | None = None
    title: str | None = None     # the recipe's title, so the app can draw a week without the whole library


class RatingNote(BaseModel):
    note: str
    date: str | None             # night it was cooked (None if never placed on a night)
    rated_at: str


class RatingSummary(BaseModel):
    times_cooked: int = 0        # entries on a past night, or rated
    times_rated: int = 0
    avg_family: float | None = None
    last_family: int | None = None
    last_rated_at: str | None = None
    company: str | None = None   # most recent non-null company verdict
    notes: list[RatingNote] = [] # most recent first, max 5


class RecipeOut(Recipe):
    ratings: RatingSummary = RatingSummary()
    planned_weeks: list[str] = []    # ISO Sundays from this week on that have this recipe (the library shows them)


class CookedEntry(BaseModel):
    entry_id: int
    week: str
    day: int | None
    date: str | None
    multiplier: float
    rating: Rating | None


class RecipeDetail(RecipeOut):
    history: list[CookedEntry] = []


# --- Stage 2: prep plans + cook cards ---

ShelfLife = Literal["ok", "day_of", "freeze_then_thaw"]


def _minutes(v) -> int:
    """LLM minutes: 7.6 → 8, "10" → 10, null/garbage → 0."""
    try:
        return max(0, round(float(v)))
    except (TypeError, ValueError):
        return 0


class Serves(BaseModel):
    entry_id: int
    recipe_id: int
    title: str
    night: str                   # "Tue", "Tue 13 Oct" (multi-week), "no night yet"
    date: str | None = None


class MealIn(BaseModel):
    """One plan entry as the prep-plan job sees it (snapshotted in prep_plans.input)."""
    entry_id: int
    recipe_id: int
    title: str
    week: str                    # ISO Sunday
    day: int | None              # 0=Sun … 6=Sat; None = no night yet
    multiplier: float
    date: str | None             # ISO date of the night
    offset: int | None           # days after the prep Sunday (the first week's Sunday)
    label: str
    factor: float                # scale applied to the recipe as written
    ingredients: list[str]       # scaled, one line each
    steps: list[str]
    notes: list[str] = []        # "last time: less salt"

    def serves(self) -> Serves:
        return Serves(entry_id=self.entry_id, recipe_id=self.recipe_id, title=self.title, night=self.label,
                      date=self.date)


class PrepTask(BaseModel):
    id: str                      # "knife-1": stable within a plan (PATCH target)
    section: Literal["knife", "sauces", "proteins", "pack"]
    text: str
    serves: list[Serves] = []
    est_minutes: int = 0
    shelf_life: ShelfLife = "ok"
    thaw: str | None = None      # freeze_then_thaw only
    contents: list[str] = []     # pack tasks: what goes in the kit
    flags: list[str] = []        # why the safety pass changed it
    done: bool = False
    done_at: str | None = None


class RawPrepTask(BaseModel):
    """A task as the LLM writes it — lenient; the safety pass turns it into a PrepTask."""
    text: str
    serves: list[int] = []
    est_minutes: int = 0
    shelf_life: ShelfLife = "ok"
    thaw: str | None = None
    contents: list[str] = []

    @model_validator(mode="before")
    @classmethod
    def _bare_string(cls, v):
        return {"text": v} if isinstance(v, str) else v

    @field_validator("serves", mode="before")
    @classmethod
    def _serves(cls, v):
        out = []
        for x in v if isinstance(v, list) else [v]:
            if isinstance(x, dict):   # the LLM echoing the input shape: {"entry_id": 4}
                x = x.get("entry_id", x.get("id"))
            try:
                out.append(int(x))
            except (TypeError, ValueError):
                pass
        return out

    @field_validator("est_minutes", mode="before")
    @classmethod
    def _est(cls, v):
        return _minutes(v)

    @field_validator("shelf_life", mode="before")
    @classmethod
    def _shelf(cls, v):
        s = str(v or "ok").lower()
        return "freeze_then_thaw" if "freeze" in s else "day_of" if "day" in s else "ok"

    @field_validator("thaw", mode="before")
    @classmethod
    def _thaw(cls, v):
        return str(v) if v not in (None, "") else None

    @field_validator("contents", mode="before")
    @classmethod
    def _contents(cls, v):
        return [str(x) for x in v] if isinstance(v, list) else []


class RawPrepPlan(BaseModel):
    knife: list[RawPrepTask] = []
    sauces: list[RawPrepTask] = []
    proteins: list[RawPrepTask] = []
    pack: list[RawPrepTask] = []
    warnings: list[str] = []

    @model_validator(mode="before")
    @classmethod
    def _shape(cls, v):
        """Unwrap {"plan": {...}} and accept [{"key": "knife", "tasks": [...]}, ...]; a plan with none of the
        four sections is an error (not an empty 'ready' plan)."""
        if isinstance(v, dict) and len(v) == 1 and isinstance(next(iter(v.values())), dict):
            v = next(iter(v.values()))
        if isinstance(v, dict) and isinstance(v.get("sections"), list):
            v = {**{s.get("key"): s.get("tasks") for s in v["sections"] if isinstance(s, dict)},
                 "warnings": v.get("warnings")}
        if not isinstance(v, dict) or not {"knife", "sauces", "proteins", "pack"} & v.keys():
            raise ValueError("expected an object with knife/sauces/proteins/pack sections")
        return v

    @field_validator("knife", "sauces", "proteins", "pack", mode="before")
    @classmethod
    def _null_section(cls, v):
        return [] if v is None else v

    @field_validator("warnings", mode="before")
    @classmethod
    def _warnings(cls, v):
        return [str(x) for x in v] if isinstance(v, list) else []


class CardStep(BaseModel):
    text: str
    minutes: int | None = None
    timer_minutes: int | None = None   # a timed wait (oven, simmer, rest) the app can run a timer for

    @model_validator(mode="before")
    @classmethod
    def _bare_string(cls, v):
        return {"text": v} if isinstance(v, str) else v

    @field_validator("minutes", "timer_minutes", mode="before")
    @classmethod
    def _m(cls, v):
        return None if v is None else (_minutes(v) or None)


class RawCookCard(BaseModel):
    steps: list[CardStep] = Field(min_length=1)
    total_minutes: int | None = None

    @field_validator("total_minutes", mode="before")
    @classmethod
    def _t(cls, v):
        return None if v is None else _minutes(v)


class CookCard(BaseModel):
    entry_id: int
    recipe_id: int
    title: str
    night: str
    date: str | None
    kit: list[str] = []          # packed on Sunday
    day_of: list[str] = []       # prep left for the night (fragile or hot)
    thaw: list[str] = []
    steps: list[CardStep]
    total_minutes: int
    rating_notes: list[str] = [] # "last time: less salt"
    prep_plan_id: int | None = None
    generated_at: str | None = None
    stale: bool = False          # the entry changed night/multiplier since the card was written
