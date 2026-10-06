"""JSON-LD export mapping (pure; invented recipes)."""
from datetime import datetime, timezone

from mealprep.exchange import jsonld
from mealprep.models import Ingredient, RatingSummary, RecipeOut

UID = "0b4f9a52-1c3d-4e6f-8a7b-9c0d1e2f3a4b"


def recipe(**kw) -> RecipeOut:
    base = dict(id=3, uid=UID, title="Test Lentil Soup", source="photo", servings=4, source_kind="other",
                ingredients=[Ingredient(raw="1 cup red lentils", name="red lentil", qty=1, unit="cup"),
                             Ingredient(raw="2 carrots, diced", name="carrot", qty=2, prep="diced")],
                steps=["Rinse the lentils.", "Simmer everything for 20 minutes."])
    return RecipeOut(**{**base, **kw})


def test_minimal_recipe_is_valid_schema_org():
    n = jsonld.to_jsonld(recipe(), [])
    assert n["@context"] == jsonld.CONTEXT and n["@type"] == "Recipe" and n["name"] == "Test Lentil Soup"
    assert n["identifier"] == n["@id"] == "urn:uuid:" + UID
    assert n["recipeIngredient"] == ["1 cup red lentils", "2 carrots, diced"]
    assert all(s["@type"] == "HowToStep" and s["text"] for s in n["recipeInstructions"])
    assert "aggregateRating" not in n and "url" not in n and "isBasedOn" not in n
    assert jsonld.to_jsonld(recipe(), [], standalone=False).get("@context") is None


def test_yield():
    assert jsonld.to_jsonld(recipe(servings=4), [])["recipeYield"] == ["4", "4 servings"]
    assert jsonld.to_jsonld(recipe(servings=None, yield_text="Makes 24 cookies"), [])["recipeYield"] == "Makes 24 cookies"
    assert "recipeYield" not in jsonld.to_jsonld(recipe(servings=None), [])
    assert jsonld.to_jsonld(recipe(servings=8, yield_text="One 9-inch pie"), [])["recipeYield"] == ["8", "One 9-inch pie"]
    n = jsonld.to_jsonld(recipe(servings=None), [])
    assert n["mealprep:recipe"]["made_as_written"] is True and n["mealprep:recipe"]["servings"] is None


def test_times_and_details():
    n = jsonld.to_jsonld(recipe(prep_minutes=15, cook_minutes=90, description="Thick.", image="https://example.org/a.jpg",
                                notes="Freezes well."), [], created_at=datetime(2026, 10, 1, 12, tzinfo=timezone.utc))
    assert n["prepTime"] == "PT15M" and n["cookTime"] == "PT1H30M" and "totalTime" not in n
    assert n["description"] == "Thick." and n["image"] == "https://example.org/a.jpg"
    assert n["dateCreated"] == "2026-10-01T12:00:00+00:00"
    assert n["mealprep:recipe"]["notes"] == "Freezes well."


def crepe_recipe():
    ings = [Ingredient(raw="Batter for 24 crêpes, page 191", name="crêpe batter", expanded=True, sub_recipe="Crêpe batter"),
            Ingredient(raw="1 1/2 cups flour", name="flour", qty=3.0, unit="cup", sub_recipe="Crêpe batter"),
            Ingredient(raw="3 large eggs", name="egg", qty=6.0, prep="large", sub_recipe="Crêpe batter"),
            Ingredient(raw="2 (14 oz) cans black beans, drained", name="black bean", qty=4.0, unit="can",
                       prep="14 oz, drained", sub_recipe="Crêpe batter"),
            Ingredient(raw="1/3 cup plus 1 tablespoon sugar", name="sugar", qty=0.3958333, unit="cup"),
            Ingredient(raw="Juice of 1 lemon", name="lemon", qty=1.0, prep="juiced"),
            Ingredient(raw="200 g ricotta", name="ricotta", qty=200, unit="g")]
    steps = ["Crêpe batter: Whisk the flour and eggs.", "Crêpe batter: Rest 30 minutes.", "Fill the crêpes.",
             "Unknown: not a section."]
    return recipe(ingredients=ings, steps=steps)


def test_scaled_subrecipe_line_rendered():
    r = crepe_recipe()
    n = jsonld.to_jsonld(r, [])
    assert n["recipeIngredient"] == [
        "Batter for 24 crêpes, page 191", "3 cups flour", "6 large eggs", "4 (14 oz) cans black beans, drained",
        "1/3 cup plus 1 tablespoon sugar", "Juice of 1 lemon", "200 g ricotta"]
    block = n["mealprep:recipe"]["ingredients"]
    assert block[1]["raw"] == "1 1/2 cups flour" and block[1]["qty"] == 3.0
    assert [Ingredient(**i) for i in block] == r.ingredients


def test_subrecipe_steps_become_section():
    n = jsonld.to_jsonld(crepe_recipe(), [])
    assert n["recipeInstructions"] == [
        {"@type": "HowToSection", "name": "Crêpe batter", "itemListElement": [
            {"@type": "HowToStep", "text": "Whisk the flour and eggs."}, {"@type": "HowToStep", "text": "Rest 30 minutes."}]},
        {"@type": "HowToStep", "text": "Fill the crêpes."}, {"@type": "HowToStep", "text": "Unknown: not a section."}]
    assert n["mealprep:recipe"]["steps"] == crepe_recipe().steps


def test_book_source_is_based_on():
    n = jsonld.to_jsonld(recipe(source_kind="book", source_title="Test Kitchen Basics", source_ref="p. 42",
                                source_author="A. Cook", source_isbn="9780000000002"), [])
    assert n["isBasedOn"] == {"@type": "Book", "name": "Test Kitchen Basics", "author": "A. Cook",
                              "isbn": "9780000000002", "mealprep:page": "p. 42"}
    assert n["mealprep:recipe"]["source"] == {"kind": "book", "title": "Test Kitchen Basics", "ref": "p. 42",
                                              "author": "A. Cook", "isbn": "9780000000002", "url": None,
                                              "imported_via": "photo"}
    assert "isBasedOn" not in jsonld.to_jsonld(recipe(source_kind="book"), [])


def test_nyt_source_url():
    url = "https://cooking.nytimes.com/recipes/1234-test-soup"
    n = jsonld.to_jsonld(recipe(source="nyt", source_kind="nyt", source_url=url), [])
    assert n["url"] == url and n["isBasedOn"] == url


def test_other_named_source():
    n = jsonld.to_jsonld(recipe(source_kind="other", source_title="Family binder", source_ref="tab 3"), [])
    assert n["isBasedOn"] == {"@type": "CreativeWork", "name": "Family binder", "mealprep:note": "tab 3"}


ENTRIES = [{"date": "2026-10-02", "multiplier": 1.0, "family": 5, "company": "yes", "note": "more lemon",
            "rated_at": "2026-10-03T01:00:00+00:00"},
           {"date": "2026-09-20", "multiplier": 2.0, "family": None, "company": None, "note": None, "rated_at": None}]


def test_ratings_always_by_default():
    summary = RatingSummary(times_cooked=2, times_rated=1, avg_family=5.0, last_family=5, company="yes")
    n = jsonld.to_jsonld(recipe(ratings=summary), ENTRIES)
    assert n["mealprep:ratings"]["entries"] == ENTRIES
    assert n["mealprep:ratings"]["summary"]["times_cooked"] == 2 and n["mealprep:ratings"]["good_for_company"] is True
    assert jsonld.to_jsonld(recipe(ratings=summary.model_copy(update={"company": "maybe"})), ENTRIES)[
        "mealprep:ratings"]["good_for_company"] is False
    assert "mealprep:ratings" not in jsonld.to_jsonld(recipe(), ENTRIES, ratings=False)


def test_schema_extra_cannot_override_core_keys():
    n = jsonld.to_jsonld(recipe(schema_extra={"name": "X", "keywords": "soup", "@type": "Thing",
                                              "mealprep:recipe": {}}), [])
    assert n["name"] == "Test Lentil Soup" and n["keywords"] == "soup" and n["@type"] == "Recipe"
    assert n["mealprep:recipe"]["format"] == 1 and n["mealprep:recipe"]["schema_extra"]["keywords"] == "soup"


def test_empty_steps_skipped_in_standard_kept_in_block():
    n = jsonld.to_jsonld(recipe(steps=["", "Stir."]), [])
    assert n["recipeInstructions"] == [{"@type": "HowToStep", "text": "Stir."}]
    assert n["mealprep:recipe"]["steps"] == ["", "Stir."]


def test_bundle():
    nodes = [jsonld.to_jsonld(recipe(), [], standalone=False)]
    b = jsonld.bundle(nodes, datetime(2026, 10, 6, 12, tzinfo=timezone.utc))
    assert b["@context"] == jsonld.CONTEXT and b["@graph"] == nodes
    assert b["mealprep:export"] == {"format": 1, "exported_at": "2026-10-06T12:00:00+00:00", "count": 1}


def test_slug():
    assert jsonld.slug("Crème brûlée / “best”", 7) == "creme-brulee-best"
    assert jsonld.slug("“”/", 7) == "recipe-7"
    assert len(jsonld.slug("a" * 200, 1)) == 60
