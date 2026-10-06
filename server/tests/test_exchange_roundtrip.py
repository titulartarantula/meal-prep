"""Round trip properties (Review Focus 2): Meal Prep → JSON-LD → Meal Prep is exact; another app that keeps only the
standard schema.org keys still gets the same recipe structure."""
import json
import string

from hypothesis import given, settings, strategies as st

from mealprep.exchange import jsonld
from mealprep.models import Ingredient, Recipe, RecipeOut

LETTERS = string.ascii_letters + "éèçñüÉ"
WORDCH = LETTERS + string.digits + ",.-()/'½"
word = st.text(alphabet=WORDCH, min_size=1, max_size=10)
words = st.lists(word, min_size=1, max_size=6).map(" ".join)
lead = st.text(alphabet=LETTERS, min_size=1, max_size=8)
text = st.tuples(lead, st.lists(word, max_size=6)).map(lambda t: " ".join([t[0], *t[1]]))   # starts with a letter
opt = lambda s: st.none() | s   # noqa: E731
SUBS = ["Crêpe batter", "Sauce", "Dough"]
UNITS = [None, "cup", "tbsp", "tsp", "g", "ml", "can", "clove", "lb", "oz"]


def raw_line():
    # a line as a cook writes it; never a heading ("Sauce:"), so another app's copy keeps the same line count
    return st.tuples(opt(st.integers(1, 12)), text).map(lambda t: f"{t[0]} {t[1]}" if t[0] else t[1]) \
        .filter(lambda s: not s.endswith(":"))


ingredient = st.builds(
    Ingredient, raw=raw_line(), name=words, qty=opt(st.floats(0.01, 1000, allow_nan=False, allow_infinity=False)),
    unit=st.sampled_from(UNITS), prep=opt(words), likely_on_hand=st.booleans(), ref_page=opt(st.integers(1, 999)),
    sub_recipe=opt(st.sampled_from(SUBS)), expanded=st.booleans())
step = st.tuples(opt(st.sampled_from(SUBS)), text).map(lambda t: f"{t[0]}: {t[1]}" if t[0] else t[1])
url = st.from_regex(r"[a-z]{1,12}", fullmatch=True).map(lambda s: f"https://recipes.example.org/{s}")
isbn = st.from_regex(r"[0-9]{10}", fullmatch=True).map(lambda s: "978" + s)


@st.composite
def sources(draw):
    kind = draw(st.sampled_from(["nyt", "book", "other"]))
    if kind == "nyt":
        slug = draw(st.from_regex(r"[0-9]{3,7}-[a-z]{1,10}", fullmatch=True))
        return {"source_kind": "nyt", "source": "nyt", "source_url": f"https://cooking.nytimes.com/recipes/{slug}"}
    title = draw(opt(words.map(lambda s: s[:200])))
    src = {"source_kind": kind, "source": draw(st.sampled_from(["photo", "import"])), "source_url": draw(opt(url)),
           "source_title": title, "source_ref": draw(opt(words.map(lambda s: s[:50])))}
    if kind == "book" and title:
        src.update(source_author=draw(opt(words)), source_isbn=draw(opt(isbn)))
    return src


@st.composite
def recipes(draw):
    ings = draw(st.lists(ingredient, max_size=40))
    steps = draw(st.lists(step, min_size=0 if ings else 1, max_size=30))
    extra = draw(st.dictionaries(st.sampled_from(["recipeCuisine", "keywords", "recipeCategory"]), words, max_size=3))
    return Recipe(title=draw(text.map(lambda s: s[:120])), servings=draw(opt(st.integers(1, 100))), ingredients=ings,
                  steps=steps, uid=str(draw(st.uuids())), description=draw(opt(text)), notes=draw(opt(text)),
                  prep_minutes=draw(opt(st.integers(1, 2000))), cook_minutes=draw(opt(st.integers(1, 2000))),
                  total_minutes=draw(opt(st.integers(1, 2000))),
                  yield_text=draw(st.sampled_from([None, "Makes 24 cookies", "One 9-inch pie", "About 2 cups"])),
                  image=draw(opt(url)), schema_extra=extra, **draw(sources()))


entry = st.fixed_dictionaries({
    "date": opt(st.dates().map(lambda d: d.isoformat())), "multiplier": st.sampled_from([0.5, 1.0, 1.5, 2.0, 3.0]),
    "family": opt(st.integers(1, 5)), "company": st.sampled_from([None, "yes", "maybe", "no"]), "note": opt(text),
    "rated_at": opt(st.datetimes(timezones=st.just(__import__("datetime").timezone.utc)).map(lambda d: d.isoformat()))})


def as_file(node):
    return json.loads(json.dumps(node, ensure_ascii=False))


@settings(max_examples=300, deadline=None, derandomize=True)
@given(recipes(), st.lists(entry, max_size=8))
def test_round_trip_is_exact(r, entries):
    inc = jsonld.from_jsonld(as_file(jsonld.to_jsonld(RecipeOut(**r.model_dump()), entries)))
    assert inc.origin == "mealprep" and inc.warnings == []
    assert inc.recipe.model_dump() == r.model_dump()
    assert [e.model_dump() for e in inc.ratings] == entries


@settings(max_examples=300, deadline=None, derandomize=True)
@given(recipes())
def test_other_apps_keep_the_structure(r):
    node = as_file(jsonld.to_jsonld(RecipeOut(**r.model_dump()), []))
    standard = {k: v for k, v in node.items() if not k.startswith("mealprep:")}
    inc = jsonld.from_jsonld(standard)
    assert inc.origin == "schema.org"
    assert inc.recipe.title == r.title
    assert len(inc.recipe.ingredients) == len(r.ingredients)
    assert len(inc.recipe.steps) == len(r.steps)
    assert inc.recipe.servings == r.servings
    assert inc.recipe.uid == r.uid
