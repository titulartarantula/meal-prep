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


# --- wrong slug / id-only links: NYT 404s unless the slug is the canonical one --------------------------------
import httpx, respx
from mealprep.importers.nyt import NytFetchError, fetch_nyt_recipe, recipe_id, resolve_canonical

B = "https://cooking.nytimes.com"
CANON = f"{B}/recipes/791389843-sicilian-style-pasta-fish-ragu"
SHARE = f"{B}/recipes/791389843-sicilian-style-pasta-with-fish-ragu"
PAGE = "<html><script type='application/ld+json'>{\"@type\": \"Recipe\", \"name\": \"Fish Ragu\"}</script></html>"


def _results(*paths):
    return "<html>" + "".join(f'<a href="{p}?searchResultPosition=1">x</a>' for p in paths) + "</html>"


def test_recipe_id():
    assert recipe_id(SHARE) == "791389843" and recipe_id(f"{B}/recipes/791389843") == "791389843"
    assert recipe_id("https://example.com/x") is None


@respx.mock
def test_404_then_search_finds_canonical():
    respx.get(SHARE).mock(return_value=httpx.Response(404, text="not found"))
    search = respx.get(f"{B}/search").mock(return_value=httpx.Response(200, text=_results(
        "/recipes/1023456-other-fish-ragu", "/recipes/791389843-sicilian-style-pasta-fish-ragu")))
    respx.get(CANON).mock(return_value=httpx.Response(200, text=PAGE))
    html, url = fetch_nyt_recipe(SHARE)
    assert url == CANON and html == PAGE
    assert search.calls[0].request.url.params["q"] == "sicilian style pasta with fish ragu"
    assert parse_nyt_html(html, url)[0] == "Fish Ragu"


@respx.mock
def test_search_retries_without_filler_words():
    respx.get(SHARE).mock(return_value=httpx.Response(404))
    search = respx.get(f"{B}/search").mock(side_effect=[
        httpx.Response(200, text=_results()), httpx.Response(200, text=_results("/recipes/791389843-sicilian-style-pasta-fish-ragu"))])
    respx.get(CANON).mock(return_value=httpx.Response(200, text=PAGE))
    assert fetch_nyt_recipe(SHARE)[1] == CANON
    assert [c.request.url.params["q"] for c in search.calls] == ["sicilian style pasta with fish ragu", "sicilian style pasta fish ragu"]


@respx.mock
def test_canonical_link_fetched_once():
    respx.get(CANON).mock(return_value=httpx.Response(200, text=PAGE))
    search = respx.get(f"{B}/search")
    assert fetch_nyt_recipe(CANON) == (PAGE, CANON) and not search.called


@respx.mock
def test_id_only_link_fails_clearly_without_searching(caplog):
    idonly = f"{B}/recipes/791389843"
    respx.get(idonly).mock(return_value=httpx.Response(404))
    search = respx.get(f"{B}/search")
    with pytest.raises(NytFetchError, match="only the recipe number"):
        fetch_nyt_recipe(idonly)
    assert not search.called       # searching by id finds nothing on NYT, so it isn't tried
    assert "HTTP 404" in caplog.text and idonly in caplog.text


@respx.mock
def test_no_matching_result_fails(caplog):
    respx.get(SHARE).mock(return_value=httpx.Response(404))
    respx.get(f"{B}/search").mock(return_value=httpx.Response(200, text=_results()))
    with pytest.raises(NytFetchError, match="no recipe with id 791389843"):
        fetch_nyt_recipe(SHARE)
    assert "no result with id 791389843" in caplog.text and "no canonical link found" in caplog.text


@respx.mock
def test_result_with_a_different_id_is_ignored():
    respx.get(SHARE).mock(return_value=httpx.Response(404))
    respx.get(f"{B}/search").mock(return_value=httpx.Response(200, text=_results(
        "/recipes/7913898430-sicilian-style-pasta-fish-ragu", "/recipes/1791389843-sicilian-style-pasta-fish-ragu",
        "/recipes/12345-sicilian-style-pasta-with-fish-ragu")))
    other = respx.get(url__regex=r".*/recipes/(?!791389843-).*")
    assert resolve_canonical(SHARE) is None
    with pytest.raises(NytFetchError):
        fetch_nyt_recipe(SHARE)
    assert not other.called


@respx.mock
def test_other_http_errors_are_not_resolved(caplog):
    respx.get(SHARE).mock(return_value=httpx.Response(503))
    search = respx.get(f"{B}/search")
    with pytest.raises(NytFetchError, match="HTTP 503"):
        fetch_nyt_recipe(SHARE)
    assert not search.called and "HTTP 503" in caplog.text


@respx.mock
def test_network_error_is_a_fetch_error():
    respx.get(SHARE).mock(side_effect=httpx.ConnectError("down"))
    with pytest.raises(NytFetchError, match="ConnectError"):
        fetch_nyt_recipe(SHARE)
