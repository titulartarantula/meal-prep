import hashlib
import json
from typing import Literal

from pydantic import BaseModel, model_validator


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
COLUMN_FIELDS = SOURCE_FIELDS | {"uid"}   # every Recipe field kept in a recipes column (never inside `data`)


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
    # Stable id (import/export, 2026-10-06): a uuid in its own column, the schema.org identifier (urn:uuid:<uid>) and
    # the de-dup key across libraries. None only before saving (the column default fills it; an import brings its own).
    uid: str | None = None
    # Optional details an imported recipe can bring (kept in `data`; None/{} for recipes from photos; an NYT recipe
    # keeps only its photo link, `image`, read from the page at import or by `python -m mealprep.nyt_images`).
    description: str | None = None
    notes: str | None = None
    prep_minutes: int | None = None
    cook_minutes: int | None = None
    total_minutes: int | None = None
    yield_text: str | None = None    # the yield as the source wrote it ("Makes 24 cookies")
    image: str | None = None         # an http(s) link, kept as text, never fetched (the web app hotlinks it)
    schema_extra: dict = {}          # whitelisted schema.org keys passed through (recipeCuisine, keywords …)

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
    # An item added by search (`extras`, 0.13.0): the product and quantity were chosen in the chooser.
    extra_id: int | None = None
    product_code: str | None = None
    quantity: int | None = None
    # What the fridge covers (`POST /list {fridge: true}`, 0.14.0): "covered" = all of it (qty is still what the recipes
    # need; not needed, shown in Probably have), "partial" = qty is the remainder and fridge_qty what the fridge covers
    # (in the line's unit), "check" = the units don't convert (not needed, "check you have enough"). fridge_items = the
    # fridge item ids that matched.
    fridge: Literal["covered", "partial", "check"] | None = None
    fridge_qty: float | None = None
    fridge_items: list[int] = []


class Staple(BaseModel):
    id: int
    name: str
    qty: float | None = None     # no unit: packs ("1" = one carton); with a unit: an amount like a recipe line
    unit: str | None = None
    weekly: bool = True          # ticked on the shopping list by default
    position: int = 0
    last_bought: str | None = None   # ISO date: newest sent cart with this item, or the manual hint if later
    created_at: str


class FridgeItem(BaseModel):
    """An item in the household's fridge list (fridge.py)."""
    id: int
    name: str                    # as typed ("Large eggs")
    key: str                     # the shopping list's name it matches ("egg"), set once per name
    key_source: Literal["ai", "rule", "list"]
    qty: float | None = None     # None = "some" (no amount); 0 = used up (the item stays until removed)
    unit: str | None = None      # staples.normalise_unit; None = a count
    qty_estimated: bool = False  # the amount came from a photo ("about")
    amount_as_of: str            # ISO date the amount was last set or confirmed
    days_since_checked: int = 0
    use_soon: bool = False
    use_by: str | None = None
    soon: bool = False           # use_soon, or use_by within 3 days


class Product(BaseModel):
    code: str
    name: str
    brand: str | None = None
    package_size: str | None = None
    price: float | None = None
    stock: str | None = None
    sold_by: str | None = None   # PC Express pricing type: "SOLD_BY_EACH", "SOLD_BY_EACH_PRICED_BY_WEIGHT", …
    # Since 0.12.0 (product chooser). All optional: snapshots stored before then still load.
    image: str | None = None             # front photo, 250 px
    images: list[str] = []               # every photo, 800 px
    description: str | None = None       # plain text; list items as "• " lines
    unit_price: float | None = None      # comparison price: unit_price per unit_price_unit ("100 g", "1 kg")
    unit_price_unit: str | None = None
    regular_price: float | None = None   # the price without the sale (= price when not on sale)
    was_price: float | None = None
    sale_ends: str | None = None         # ISO date the sale price ends
    deal_text: str | None = None         # "SAVE $0.99"
    deal_ends: str | None = None
    optimum_text: str | None = None      # "PC Optimum Offer"
    optimum_points: int | None = None
    optimum_ends: str | None = None
    sponsored: bool = False              # a paid placement in the search results
    min_qty: int | None = None           # order limits; weighted products count grams
    max_qty: int | None = None
    interval: int | None = None
    weighted: bool = False               # sold by weight: quantity = grams, price per price_quantity grams
    price_quantity: float | None = None  # what `price` is for: 100 (g) for deli by weight; None/1 = per item
    link: str | None = None              # path on loblaws.ca
    # Since 0.15.0: what one pack holds when the size text doesn't say ("1 ea" Green Onion = a bunch of 6), from
    # `pack_contents` (`packs.attach`); never from the store, so `db.record_products` leaves it out.
    pieces: int | None = None
    pieces_word: str | None = None       # "bunch"
    pieces_source: str | None = None     # "check" (learned by the quantity check) | "user"


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
    imported_ratings: int = 0    # of times_rated, how many came from another library's export ("incl. N from …")


# What the app's Edit recipe changes (PATCH /recipes/{id}); `revision` is a hash of these, so an edit made on an older
# copy (another phone changed the recipe meanwhile) can be refused instead of undoing that change.
EDITABLE_FIELDS = {"title", "servings", "ingredients", "steps", "description", "notes", "prep_minutes", "cook_minutes",
                   "total_minutes", "yield_text"}


def recipe_revision(r: Recipe) -> str:
    text = json.dumps(r.model_dump(mode="json", include=EDITABLE_FIELDS), sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(text.encode()).hexdigest()[:16]


class RecipeOut(Recipe):
    ratings: RatingSummary = RatingSummary()
    planned_weeks: list[str] = []    # ISO Sundays from this week on that have this recipe (the library shows them)
    revision: str | None = None      # recipe_revision(), filled in here (PATCH expected_revision)

    @model_validator(mode="after")
    def _revision(self):
        self.revision = recipe_revision(self)
        return self


class CookedEntry(BaseModel):
    entry_id: int
    week: str
    day: int | None
    date: str | None
    multiplier: float
    rating: Rating | None


class ImportedEntry(BaseModel):
    date: str | None             # the night it was cooked in the other library, if known
    multiplier: float
    rating: Rating | None        # None = cooked, not rated
    rated_at: str | None = None


class RecipeDetail(RecipeOut):
    history: list[CookedEntry] = []
    imported_history: list[ImportedEntry] = []   # times cooked brought in by an import, newest first

