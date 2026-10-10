"""schema.org import mapping: one invented fixture per shape sites use (tests/fixtures/exchange/README.md)."""
import json
from pathlib import Path
import uuid

import pytest

from mealprep.exchange import extract, jsonld
from mealprep.exchange.safe import Unreadable

FX = Path(__file__).parent / "fixtures/exchange"


def nodes(name):
    if name.endswith(".html"):
        return extract.from_html((FX / name).read_text())
    return extract.find_recipes(json.loads((FX / name).read_text()))


def one(name):
    [n] = nodes(name)
    return jsonld.from_jsonld(n)


def test_single_minimal():
    inc = one("single_minimal.json")
    r = inc.recipe
    assert inc.origin == "schema.org" and r.title == "Test Lentil Soup" and r.source == "import"
    assert [(i.name, i.qty, i.unit, i.prep) for i in r.ingredients] == [
        ("red lentils", 1.0, "cup", None), ("carrots", 2.0, None, "diced"), ("kosher salt", 1.0, "tsp", None)]
    assert [i.likely_on_hand for i in r.ingredients] == [False, False, True]
    assert r.steps == ["Rinse the lentils.", "Simmer everything for 25 minutes."]
    assert r.servings is None and r.yield_text is None and r.source_kind == "other" and r.source_title is None
    assert inc.warnings == [] and uuid.UUID(r.uid)


def test_array_two_keeps_ids():
    a, b = [jsonld.from_jsonld(n) for n in nodes("array_two.json")]
    assert a.recipe.uid == "3f2b8c1e-6d4a-4b7f-9e0a-1c2d3e4f5a6b" and a.recipe.servings == 2
    assert b.recipe.uid == "8a7b6c5d-4e3f-4a1b-8c2d-0e9f8a7b6c5d" and b.recipe.servings == 4
    assert a.recipe.steps == ["Toast the bread.", "Top with tomato and oil."]


def test_graph_with_webpage():
    inc = one("graph_with_webpage.json")
    r = inc.recipe
    assert r.title == "Test Bean Chili" and r.servings == 4 and r.total_minutes == 35
    beans = r.ingredients[0]
    assert (beans.name, beans.qty, beans.unit) == ("black beans", 2.0, "can")
    assert "drained" in beans.prep and "14 oz" in beans.prep
    assert r.ingredients[2].likely_on_hand is True                      # ground cumin
    assert r.source_kind == "other" and r.source_title == "Example Recipes"
    assert r.source_url == "https://recipes.example.org/test-bean-chili" and inc.link == r.source_url
    assert r.schema_extra == {"recipeCuisine": "Test", "keywords": "beans, chili"}   # aggregateRating ignored
    assert r.uid == str(uuid.uuid5(uuid.NAMESPACE_URL, r.source_url))


def test_main_entity_author_names_the_source():
    r = one("main_entity.json").recipe
    assert r.title == "Test Garden Salad" and r.source_title == "Test Author" and r.steps == ["Toss everything together."]


def test_type_list_and_count_yield():
    r = one("type_list.json").recipe
    assert r.title == "Test Oat Cookies" and r.servings is None and r.yield_text == "Makes 24 cookies"
    assert [i.likely_on_hand for i in r.ingredients] == [False, True, True]


def test_instructions_html():
    r = one("instructions_html.json").recipe
    assert r.steps == ["Melt the butter & swirl.", "Crack in the eggs.", "Cook 3 minutes."]


def test_instructions_sections():
    r = one("instructions_sections.json").recipe
    assert r.steps == ["Dumplings: Stir the flour and milk.", "Shaping: Roll into balls.",
                       "Broth: Simmer the stock with the bay leaf.", "Broth: Drop in the dumplings."]


def test_heading_lines_are_sections_not_ingredients():
    r = one("instructions_sections.json").recipe
    assert [(i.raw, i.sub_recipe) for i in r.ingredients] == [
        ("1 cup flour", "Dumplings"), ("1/2 cup milk", "Dumplings"), ("4 cups stock", "Broth"), ("1 bay leaf", "Broth")]
    r = one("nested_ingredients.json").recipe
    assert [(i.raw, i.sub_recipe) for i in r.ingredients] == [
        ("1 lb ground beef", "Filling"), ("2 tbsp taco seasoning", "Filling"), ("8 small tortillas", "Filling"),
        ("1 cup shredded cheese", "Filling"), ("1 cup salsa", "Toppings")]


def test_instructions_mixed():
    r = one("instructions_mixed.json").recipe
    assert r.steps == ["Boil the water.", "Let it cool.", "Pour.", "Use filtered water."]


def test_yield_variants():
    got = [(i.recipe.title, i.recipe.servings, i.recipe.yield_text) for i in map(jsonld.from_jsonld, nodes("yield_variants.json"))]
    assert got == [("Y1", 4, None), ("Y2", 4, None), ("Y3", 4, None), ("Y4", 4, None), ("Y5", None, "Makes 24 cookies"),
                   ("Y6", 6, None), ("Y7", 4, None), ("Y8", 6, None), ("Y9", None, "5000")]


def test_durations():
    r = one("durations.json").recipe
    assert (r.prep_minutes, r.cook_minutes, r.total_minutes) == (20, 90, None)
    assert r.steps == ["Soak. Cook."]


def test_book_based_on():
    inc = one("book_based_on.json")
    r = inc.recipe
    assert (r.source_kind, r.source_title, r.source_author, r.source_isbn, r.source_ref) == (
        "book", "Test Kitchen Basics", "A. Cook, B. Baker", "9780000000002", "p. 42")
    assert r.servings is None and r.yield_text == "12 muffins" and inc.warnings == []


def test_book_bad_isbn_dropped_with_warning():
    inc = one("book_bad_isbn.json")
    assert inc.recipe.source_kind == "book" and inc.recipe.source_isbn is None and inc.recipe.source_author == "C. Oven"
    assert inc.warnings == ["Left out an ISBN that isn't 10 or 13 digits"]


def test_nyt_url_normalised():
    inc = one("nyt_url.json")
    assert inc.recipe.source_kind == "nyt" and inc.recipe.source_title is None
    assert inc.recipe.source_url == inc.link == "https://cooking.nytimes.com/recipes/1099999-test-lentil-soup"


def test_bad_urls():
    a, b, c = map(jsonld.from_jsonld, nodes("bad_urls.json"))
    assert a.recipe.source_url is None and a.recipe.image is None and a.warnings == [jsonld.DROPPED_LINK]
    assert a.recipe.source_title is None
    assert b.recipe.source_url is None and b.recipe.image == "https://recipes.example.org/a.jpg"
    assert b.warnings == [jsonld.DROPPED_LINK]
    assert c.recipe.image == "https://recipes.example.org/c.jpg" and c.recipe.source_title == "recipes.example.org"


@pytest.mark.parametrize("u, ok", [("https://example.org/a", True), ("http://example.org", True),
                                   ("javascript:alert(1)", False), ("file:///etc/passwd", False),
                                   ("https://u:p@example.org/", False), ("ftp://example.org/x", False),
                                   ("https://example.org/" + "a" * 2000, False), ("https://exa mple.org", False),
                                   ("//example.org/x", False), ("data:text/html,hi", False), (42, False)])
def test_safe_url(u, ok):
    assert (jsonld.safe_url(u) is not None) == ok


def test_html_page_skips_broken_blocks():
    [n] = nodes("page.html")
    r = jsonld.from_jsonld(n).recipe
    assert r.title == "Test Page Pancakes" and r.steps == ["Whisk.", "Fry in batches."]
    assert r.image == "https://recipes.example.org/pancakes.jpg"


def test_html_fetches_nothing(monkeypatch):
    import httpx

    def boom(*a, **kw):
        raise AssertionError("network used")
    for name in ("get", "post", "request", "stream"):
        monkeypatch.setattr(httpx, name, boom)
    monkeypatch.setattr(httpx.Client, "send", boom)
    for n in nodes("page.html") + nodes("bad_urls.json") + nodes("graph_with_webpage.json"):
        jsonld.from_jsonld(n)


def test_find_recipes_shapes():
    assert extract.find_recipes({"@type": "schema:Recipe", "name": "a"}) == [{"@type": "schema:Recipe", "name": "a"}]
    assert len(extract.find_recipes({"@type": "ItemList", "itemListElement": [
        {"@type": "ListItem", "item": {"@type": "https://schema.org/Recipe", "name": "a"}},
        {"@type": "ListItem", "item": {"@type": "Recipe", "name": "b"}}]})) == 2
    assert extract.find_recipes({"a": {"@type": "Recipe"}}) == []        # only the known wrappers are walked
    assert extract.find_recipes([[{"@graph": [{"@type": "Recipe", "name": "deep"}]}]])[0]["name"] == "deep"
    assert extract.find_recipes("x") == [] and extract.find_recipes(None) == []


def test_find_recipes_limits():
    with pytest.raises(Unreadable):
        extract.find_recipes([[{"@type": "Thing"}] * 10] * 1000)


def test_uid_rules():
    base = {"@type": "Recipe", "name": "Test Uid", "recipeIngredient": ["1 egg"]}
    u = "C0FFEE00-1234-4ABC-8DEF-0123456789AB"
    assert jsonld.from_jsonld({**base, "identifier": f"urn:uuid:{u}"}).recipe.uid == u.lower()
    assert jsonld.from_jsonld({**base, "@id": u}).recipe.uid == u.lower()
    assert jsonld.from_jsonld({**base, "identifier": {"@type": "PropertyValue", "value": u}}).recipe.uid == u.lower()
    with_url = {**base, "url": "https://recipes.example.org/u"}
    assert jsonld.from_jsonld(with_url).recipe.uid == jsonld.from_jsonld(dict(with_url)).recipe.uid
    a, b = jsonld.from_jsonld(base).recipe.uid, jsonld.from_jsonld({**base, "name": "Test Uid 2"}).recipe.uid
    assert a != b and a == jsonld.from_jsonld(dict(base)).recipe.uid   # no id/url: same node, same uid (re-import)
    bad = {**base, "identifier": "not-a-uuid"}
    assert uuid.UUID(jsonld.from_jsonld(bad).recipe.uid) and jsonld.from_jsonld(bad).recipe.uid == \
        jsonld.from_jsonld(dict(bad)).recipe.uid


def test_not_usable():
    with pytest.raises(jsonld.NotUsable, match="No recipe name"):
        jsonld.from_jsonld({"@type": "Recipe", "recipeIngredient": ["1 egg"]})
    with pytest.raises(jsonld.NotUsable, match="No ingredients or steps"):
        jsonld.from_jsonld({"@type": "Recipe", "name": "Empty", "recipeIngredient": ["", "Sauce:"]})


def test_caps():
    inc = jsonld.from_jsonld({"@type": "Recipe", "name": "T" * 500, "recipeIngredient": ["1 egg " + "x" * 900] * 400,
                              "recipeInstructions": ["Stir. " * 2000] * 300})
    r = inc.recipe
    assert len(r.title) == 200 and len(r.ingredients) == 300 and len(r.ingredients[0].raw) == 500
    assert len(r.steps) == 200 and len(r.steps[0]) <= 5000
    assert "Shortened a very long recipe name" in inc.warnings and "Only the first 200 steps were kept" in inc.warnings


def test_schema_extra_cap():
    inc = jsonld.from_jsonld({"@type": "Recipe", "name": "X", "recipeIngredient": ["1 egg"], "keywords": "k" * 20000})
    assert inc.recipe.schema_extra == {} and inc.warnings


def test_mealprep_v1_block_wins():
    inc = one("mealprep_v1.json")
    r = inc.recipe
    assert inc.origin == "mealprep" and inc.warnings == []
    assert r.uid == "5c0ffee0-1234-4abc-8def-0123456789ab" and r.title == "Test Crêpe Cake" and r.source == "photo"
    assert r.ingredients[1].raw == "1 1/2 cups flour" and r.ingredients[1].qty == 3.0 and r.ingredients[0].expanded
    assert r.ingredients[5].ref_page == 202 and r.steps[0] == "Crêpe batter: Whisk the flour and eggs."
    assert (r.source_kind, r.source_title, r.source_ref, r.source_author, r.source_isbn) == (
        "book", "Test Kitchen Basics", "p. 88", "A. Cook", "9780000000002")
    assert (r.servings, r.prep_minutes, r.cook_minutes, r.notes) == (6, 30, 45, "Make the batter the night before.")
    assert r.schema_extra == {"recipeCategory": "Dessert", "keywords": "test, cake"}
    assert [(e.date, e.multiplier, e.family, e.company, e.note) for e in inc.ratings] == [
        ("2026-09-27", 1.0, 5, "yes", "Less sugar next time."), ("2026-09-06", 2.0, 4, None, None),
        ("2026-08-16", 1.0, None, None, None)]
    assert len({e.fingerprint() for e in inc.ratings}) == 3


def test_v99_block_falls_back_with_warning():
    inc = one("mealprep_v99.json")
    assert inc.origin == "schema.org" and inc.warnings[0] == jsonld.NEWER and inc.ratings == []
    r = inc.recipe
    assert r.title == "Test Crêpe Cake" and len(r.ingredients) == 6 and r.servings == 6
    assert r.uid == "5c0ffee0-1234-4abc-8def-0123456789ab"
    assert r.steps[:2] == ["Crêpe batter: Whisk the flour and eggs.", "Crêpe batter: Fry 24 thin crêpes."]
    assert r.source_kind == "book" and r.source_ref == "p. 88" and r.source_isbn == "9780000000002"


def test_bad_block_ratings_skipped():
    n = json.loads((FX / "mealprep_v1.json").read_text())
    n["mealprep:ratings"]["entries"] += [{"family": 9}, {"date": "yesterday"}, "x", {"company": "always"}]
    inc = jsonld.from_jsonld(n)
    assert len(inc.ratings) == 3 and inc.warnings == ["Left out 4 ratings that couldn't be read"]


def test_bad_block_ingredients_fall_back():
    n = json.loads((FX / "mealprep_v1.json").read_text())
    n["mealprep:recipe"]["ingredients"][0] = {"raw": 5}
    inc = jsonld.from_jsonld(n)
    assert inc.origin == "schema.org" and len(inc.recipe.ingredients) == 6 and inc.warnings


def test_odd_type_values_are_ignored_not_a_crash():
    inc = jsonld.from_jsonld({"@type": "Recipe", "name": "Test Odd Types", "recipeInstructions": [
        {"@type": {}, "text": "Stir."}, {"@type": [{}, "HowToStep"], "text": "Serve."}],
        "isBasedOn": {"@type": {"x": 1}, "name": "Test Source"}})
    assert inc.recipe.steps == ["Stir.", "Serve."] and inc.recipe.source_title == "Test Source"
