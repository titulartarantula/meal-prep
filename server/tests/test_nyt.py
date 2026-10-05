from pathlib import Path
import pytest
from mealprep.importers.nyt import NotARecipe, extract_nyt_url, parse_nyt_html


def test_extract_from_share_text_strips_tracking():
    t = "Check out this recipe from NYT Cooking! https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies?smid=ck-recipe-android-share"
    assert extract_nyt_url(t) == "https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies"


def test_extract_strips_www():
    assert extract_nyt_url("https://www.cooking.nytimes.com/recipes/1-x") == "https://cooking.nytimes.com/recipes/1-x"


def test_extract_none_for_other_sites():
    assert extract_nyt_url("https://example.com/recipes/1") is None


def test_parse_fixture():
    html = Path(__file__).parent.joinpath("fixtures/recipe_page.html").read_text()
    title, servings, ings, steps = parse_nyt_html(html, "u")
    assert title == "Best Chocolate Chip Cookies"
    assert len(ings) == 11 and len(steps) == 4
    assert servings is None          # "18 5-inch cookies" is a yield, not servings


def test_parse_page_without_recipe_raises():
    with pytest.raises(NotARecipe):
        parse_nyt_html("<html><script type='application/ld+json'>{\"@type\": \"WebPage\"}</script></html>", "u")


def test_servings_only_from_people_counts():
    from mealprep.importers.nyt import _servings
    assert _servings("4 servings") == 4
    assert _servings(["6", "6 servings"]) == 6
    assert _servings("Serves 4 to 6") == 4
    assert _servings("8") == 8
    assert _servings("18 5-inch cookies") is None
    assert _servings("Makes 48 pieces") is None
    assert _servings("2 quarts") is None
    assert _servings(None) is None
