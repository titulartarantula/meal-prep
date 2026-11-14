"""Any-site import (importers/web.py): link checks, the guarded fetch, JSON-LD / microdata / AI extraction and the
attribution. Every page here is invented (made-up sites under example domains, made-up recipes)."""
import json

import httpcore
import httpx
import pytest
import respx

from mealprep.exchange import importer
from mealprep.importers import web
from mealprep.importers.web import WebImportError

PUBLIC = "93.184.216.34"


def resolver(table=None):
    """Answers for invented hosts: every host is public unless the table says otherwise."""
    table = table or {}
    def resolve(host, port):
        return table.get(host, [PUBLIC])
    return resolve


def page(*blocks, head="", body="<p>Some words about the recipe.</p>"):
    ld = "".join(f'<script type="application/ld+json">{json.dumps(b)}</script>' for b in blocks)
    return f"<!doctype html><html><head><title>Page</title>{head}{ld}</head><body>{body}</body></html>"


SOUP = {"@context": "https://schema.org", "@type": "Recipe", "name": "Invented Squash Soup",
        "author": {"@type": "Person", "name": "Pat Example"},
        "recipeYield": "4 servings", "totalTime": "PT45M",
        "image": ["https://img.example-recipes.test/soup-1x1.jpg",
                  {"@type": "ImageObject", "url": "https://img.example-recipes.test/soup-4x3.jpg", "width": 800}],
        "recipeIngredient": ["1 small squash, cubed", "2 cups stock", "Salt"],
        "recipeInstructions": [{"@type": "HowToStep", "text": "Roast the squash."},
                               {"@type": "HowToStep", "text": "Blend with the stock."}]}


# --- links ---------------------------------------------------------------------------------------------------------

def test_extract_url_from_share_text():
    assert web.extract_url("Try this! https://blog.example.org/soup/?utm_source=x.") == \
        "https://blog.example.org/soup/?utm_source=x"
    assert web.extract_url("(see https://a.example.com/x)") == "https://a.example.com/x"
    assert web.extract_url("no link here") is None


def test_normalise_drops_tracking_fragment_and_default_port():
    assert web.normalise_url("HTTPS://Blog.Example.org:443/Soup?utm_medium=s&id=3&fbclid=z#step-2") == \
        "https://blog.example.org/Soup?id=3"
    assert web.normalise_url("https://blog.example.org") == "https://blog.example.org/"


def test_url_variants_cover_www_scheme_and_slash():
    v = web.url_variants("https://www.example.org/soup")
    assert {"https://www.example.org/soup", "https://example.org/soup/", "http://example.org/soup"} <= set(v)


def test_registrable_domain():
    assert web.registrable_domain("www.recipes.example.co.uk") == "example.co.uk"
    assert web.registrable_domain("blog.example.com") == "example.com"


# --- safety --------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("url", [
    "ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)", "https://user:pw@example.com/x",
    "https://example.com:8443/x", "http://localhost/x", "http://127.0.0.1/x", "http://10.1.2.3/x",
    "http://192.168.0.5/x", "http://172.16.0.1/x", "http://169.254.169.254/latest/meta-data", "http://[::1]/x",
    "http://[::ffff:127.0.0.1]/x", "http://100.64.0.1/x", "http://0.0.0.0/x", "http://nas/x",
    "http://2130706433/x", "http://printer.local/x", "http://router.lan/x", "http://box.home.arpa/x",
])
def test_check_url_refuses(url):
    with pytest.raises(WebImportError) as e:
        web.check_url(url, resolver())
    assert e.value.reason in (web.BAD_LINK, web.PRIVATE)


def test_check_url_refuses_a_name_that_resolves_into_the_lan():
    with pytest.raises(WebImportError) as e:
        web.check_url("https://sneaky.example.com/x", resolver({"sneaky.example.com": ["192.168.1.20"]}))
    assert e.value.reason == web.PRIVATE
    with pytest.raises(WebImportError):   # one private answer among public ones is enough
        web.check_url("https://mixed.example.com/x", resolver({"mixed.example.com": [PUBLIC, "10.0.0.8"]}))


def test_check_url_accepts_a_public_site():
    assert web.check_url("https://blog.example.org/soup", resolver()) == "https://blog.example.org/soup"


def test_guard_backend_never_connects_to_a_private_address():
    backend = web.GuardBackend(resolver({"rebind.example.com": ["127.0.0.1"]}))
    with pytest.raises(httpcore.ConnectError):
        backend.connect_tcp("rebind.example.com", 443, timeout=1)
    with pytest.raises(httpcore.ConnectError):
        backend.connect_unix_socket("/var/run/docker.sock")


def test_guarded_client_refuses_through_httpx():
    client = web.guarded_client(resolver({"rebind.example.com": ["10.0.0.1"]}))
    with pytest.raises(httpx.ConnectError):
        client.get("http://rebind.example.com/")


# --- fetching ------------------------------------------------------------------------------------------------------

HTML = {"content-type": "text/html; charset=utf-8"}


@respx.mock
def test_fetch_follows_redirects():
    respx.get("https://short.example.net/r").mock(return_value=httpx.Response(301, headers={"location": "https://blog.example.org/soup"}))
    respx.get("https://blog.example.org/soup").mock(return_value=httpx.Response(302, headers={"location": "/soup/"}))
    respx.get("https://blog.example.org/soup/").mock(return_value=httpx.Response(200, headers=HTML, text="<html>ok</html>"))
    p = web.fetch_page("https://short.example.net/r", 10_000, client=httpx.Client(), resolve=resolver())
    assert p.url == "https://blog.example.org/soup/" and "ok" in p.html


@respx.mock
def test_fetch_checks_every_redirect_hop():
    respx.get("https://blog.example.org/go").mock(return_value=httpx.Response(302, headers={"location": "http://192.168.1.1/admin"}))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/go", 10_000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.PRIVATE


@respx.mock
def test_fetch_too_many_redirects():
    respx.get("https://loop.example.org/a").mock(return_value=httpx.Response(302, headers={"location": "/a"}))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://loop.example.org/a", 10_000, client=httpx.Client(), resolve=resolver())
    assert "redirects" in str(e.value)


@pytest.mark.parametrize("status,headers,body,reason", [
    (403, {}, "Forbidden", web.BLOCKED),
    (503, {"cf-mitigated": "challenge"}, "<title>Just a moment...</title>", web.BLOCKED),
    (402, {}, "Subscribe", web.PAYWALL),
    (401, {}, "Sign in", web.PAYWALL),
    (404, {}, "Not found", web.NOT_FOUND),
    (429, {}, "Slow down", web.BUSY),
    (500, {}, "Oops", web.HTTP_ERROR),
])
@respx.mock
def test_fetch_failure_reasons(status, headers, body, reason):
    respx.get("https://blog.example.org/x").mock(return_value=httpx.Response(status, headers=headers, text=body))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/x", 10_000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == reason


@respx.mock
def test_fetch_refuses_non_html_and_big_pages():
    respx.get("https://blog.example.org/x.pdf").mock(return_value=httpx.Response(200, headers={"content-type": "application/pdf"}, content=b"%PDF"))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/x.pdf", 10_000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.NOT_HTML
    respx.get("https://blog.example.org/big").mock(return_value=httpx.Response(200, headers=HTML, content=b"a" * 5000))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/big", 1000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.TOO_BIG


@respx.mock
def test_fetch_timeout_and_unreachable():
    respx.get("https://slow.example.org/x").mock(side_effect=httpx.ReadTimeout("slow"))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://slow.example.org/x", 1000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.TIMEOUT_
    respx.get("https://down.example.org/x").mock(side_effect=httpx.ConnectError("refused"))
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://down.example.org/x", 1000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.UNREACHABLE


@respx.mock
def test_fetch_decodes_the_declared_charset():
    respx.get("https://blog.example.org/fr").mock(return_value=httpx.Response(
        200, headers={"content-type": "text/html"}, content='<meta charset="iso-8859-1"><p>crème</p>'.encode("latin-1")))
    assert "crème" in web.fetch_page("https://blog.example.org/fr", 10_000, client=httpx.Client(), resolve=resolver()).html


# --- extraction + attribution ---------------------------------------------------------------------------------------

class FakeAI:
    """Answers the document reader's prompt with `recipes`, and the ingredient tidy with one object per line."""

    def __init__(self, recipes=None):
        self.recipes, self.prompts = recipes or [], []

    def complete_json(self, prompt, images=None):
        self.prompts.append(prompt)
        if prompt.startswith("Convert each recipe ingredient line"):
            lines = json.loads(prompt.split("Lines:\n", 1)[1])
            return [{"name": l.split(",")[0].split()[-1].lower(), "qty": None, "unit": None, "prep": None,
                     "likely_on_hand": l == "Salt"} for l in lines]
        return {"recipes": self.recipes}


URL = "https://www.example-recipes.test/recipes/squash-soup"


def test_jsonld_recipe_with_site_author_canonical_and_photo():
    html = page(SOUP, head='<meta property="og:site_name" content="Example Kitchen">'
                           f'<link rel="canonical" href="{URL}?utm_source=feed">')
    w = web.read_recipe(html, URL + "?utm_campaign=share")
    r = w.recipe
    assert w.how == "jsonld" and r.title == "Invented Squash Soup"
    assert (r.source, r.source_kind, r.source_title, r.source_author) == ("web", "web", "Example Kitchen", "Pat Example")
    assert r.source_url == URL and r.servings == 4 and r.total_minutes == 45
    assert r.image == "https://img.example-recipes.test/soup-4x3.jpg"   # closest to 800 px wide
    assert [i.raw for i in r.ingredients] == SOUP["recipeIngredient"]
    assert r.steps == ["Roast the squash.", "Blend with the stock."]


def test_graph_with_references_resolves_publisher_and_author():
    graph = {"@context": "https://schema.org", "@graph": [
        {"@type": "WebSite", "@id": "https://blog.example.org/#site", "name": "Weeknight Pots"},
        {"@type": "Person", "@id": "https://blog.example.org/#cook", "name": "Robin Sample"},
        {"@type": "Article", "headline": "Our soup"},
        {**{k: v for k, v in SOUP.items() if k not in ("author", "@context")},
         "author": {"@id": "https://blog.example.org/#cook"}, "publisher": {"@id": "https://blog.example.org/#site"}},
    ]}
    r = web.read_recipe(page(graph), "https://blog.example.org/soup").recipe
    assert (r.source_title, r.source_author) == ("Weeknight Pots", "Robin Sample")


def test_top_level_array_and_nested_sections():
    node = {**SOUP, "recipeInstructions": [
        {"@type": "HowToSection", "name": "Soup", "itemListElement": [
            {"@type": "HowToStep", "text": "Roast the squash."},
            {"@type": "HowToSection", "name": "Topping", "itemListElement": [{"@type": "HowToStep", "text": "Toast seeds."}]}]}]}
    r = web.read_recipe(page([{"@type": "BreadcrumbList"}, node]), URL).recipe
    assert r.steps == ["Soup: Roast the squash.", "Topping: Toast seeds."]


def test_missing_author_and_no_site_name_uses_the_domain():
    node = {k: v for k, v in SOUP.items() if k != "author"}
    r = web.read_recipe(page(node), "https://www.cooks.example.co.uk/soup").recipe
    assert r.source_author is None and r.source_title == "example.co.uk"


def test_author_that_is_the_site_is_left_out():
    node = {**SOUP, "author": {"@type": "Organization", "name": "Example Kitchen"}}
    r = web.read_recipe(page(node, head='<meta property="og:site_name" content="Example Kitchen">'), URL).recipe
    assert r.source_author is None


def test_canonical_on_another_site_is_ignored():
    html = page(SOUP, head='<link rel="canonical" href="https://elsewhere.example.com/copied">')
    assert web.read_recipe(html, URL).recipe.source_url == URL


def test_og_image_when_the_recipe_has_none_and_http_images_dropped():
    node = {**SOUP, "image": "http://img.example-recipes.test/plain-http.jpg"}
    html = page(node, head='<meta property="og:image" content="/img/soup-og.jpg">')
    assert web.read_recipe(html, URL).recipe.image == "https://www.example-recipes.test/img/soup-og.jpg"


def test_roundup_takes_the_recipe_named_like_the_page():
    a = {**SOUP, "name": "Invented Lentil Stew"}
    html = page(a, SOUP, head='<meta property="og:title" content="Invented Squash Soup | Example Kitchen">')
    assert web.read_recipe(html, URL).recipe.title == "Invented Squash Soup"


MICRODATA = """<html><head><title>Pan Bread</title><meta property="og:site_name" content="Crumb Notes"></head><body>
<div itemscope itemtype="https://schema.org/Recipe">
  <h1 itemprop="name">Invented Pan Bread</h1>
  <span itemprop="author" itemscope itemtype="https://schema.org/Person"><span itemprop="name">Sam Placeholder</span></span>
  <img itemprop="image" src="https://img.crumb.example.net/bread.jpg">
  <meta itemprop="totalTime" content="PT1H">
  <span itemprop="recipeYield">8 servings</span>
  <ul><li itemprop="recipeIngredient">2 cups flour</li><li itemprop="recipeIngredient">1 tsp <b>yeast</b></li></ul>
  <div itemprop="recipeInstructions"><p>Mix everything.</p></div>
  <div itemprop="recipeInstructions"><p>Bake until golden.</p></div>
</div></body></html>"""


def test_microdata_recipe():
    w = web.read_recipe(MICRODATA, "https://crumb.example.net/bread")
    r = w.recipe
    assert w.how == "microdata" and r.title == "Invented Pan Bread"
    assert [i.raw for i in r.ingredients] == ["2 cups flour", "1 tsp yeast"]
    assert r.steps == ["Mix everything.", "Bake until golden."]
    assert (r.source_title, r.source_author, r.servings, r.total_minutes) == ("Crumb Notes", "Sam Placeholder", 8, 60)
    assert r.image == "https://img.crumb.example.net/bread.jpg"


RDFA = """<html><body><div vocab="https://schema.org/" typeof="Recipe">
<h1 property="name">Invented Tomato Rice</h1>
<p property="recipeIngredient">1 cup rice</p><p property="recipeIngredient">3 tomatoes</p>
<p property="recipeInstructions">Cook it all together.</p></div></body></html>"""


def test_rdfa_lite_recipe():
    w = web.read_recipe(RDFA, "https://rice.example.org/tomato-rice")
    assert w.how == "microdata" and w.recipe.title == "Invented Tomato Rice"
    assert [i.raw for i in w.recipe.ingredients] == ["1 cup rice", "3 tomatoes"]


PLAIN = """<html><head><title>Grandpa-style Oat Bars – Little Example Blog</title>
<meta property="og:site_name" content="Little Example Blog"></head><body>
<nav>Home Recipes About</nav><script>var tracking = 1;</script>
<article><h1>Grandpa-style Oat Bars</h1><p>""" + "A long story about oats and an invented grandfather. " * 8 + """</p>
<h2>Ingredients</h2><ul><li>2 cups oats</li><li>1/2 cup butter</li></ul>
<h2>Method</h2><ol><li>Melt the butter.</li><li>Stir in the oats and bake.</li></ol></article>
<footer>Copyright</footer></body></html>"""


def test_no_markup_falls_back_to_the_ai_on_the_visible_text():
    ai = FakeAI([{"title": "Grandpa-style Oat Bars", "start_line": 1, "end_line": 3, "complete": True,
                  "servings": "Makes 12 bars", "ingredients": ["2 cups oats", "1/2 cup butter"],
                  "steps": ["Melt the butter.", "Stir in the oats and bake."],
                  "source": {"from": "an invented grandfather"}}])
    w = web.read_recipe(PLAIN, "https://little.example.org/oat-bars", ai=ai, tidy=importer.tidy)
    r = w.recipe
    assert w.how == "ai" and r.title == "Grandpa-style Oat Bars"
    assert r.source_title == "Little Example Blog" and r.source_kind == "web" and r.source_author is None
    assert [i.raw for i in r.ingredients] == ["2 cups oats", "1/2 cup butter"]
    assert r.ingredients[0].name == "oats"     # tidied by the (fake) AI
    text_prompt = ai.prompts[0]
    assert "var tracking" not in text_prompt and "Home Recipes About" not in text_prompt and "Melt the butter" in text_prompt


def test_a_page_without_a_recipe():
    ai = FakeAI([])
    with pytest.raises(WebImportError) as e:
        web.read_recipe(PLAIN.replace("Ingredients", "Thoughts"), "https://little.example.org/essay", ai=ai)
    assert e.value.reason == web.NO_RECIPE and "Little Example Blog" in str(e.value)
    with pytest.raises(WebImportError) as e:   # no AI and no markup
        web.read_recipe("<html><body><p>Hello</p></body></html>", "https://little.example.org/hi")
    assert e.value.reason == web.NO_RECIPE


def test_a_paywalled_page_says_so():
    art = {"@type": "NewsArticle", "isAccessibleForFree": "False", "headline": "A soup"}
    with pytest.raises(WebImportError) as e:
        web.read_recipe(page(art), "https://paper.example.com/soup", ai=FakeAI([]))
    assert e.value.reason == web.PAYWALL


def test_ai_failure_is_reported():
    class Down:
        def complete_json(self, prompt, images=None):
            raise RuntimeError("down")
    with pytest.raises(WebImportError) as e:
        web.read_recipe(PLAIN, "https://little.example.org/oat-bars", ai=Down())
    assert e.value.reason == web.AI_FAILED


def test_jsonld_lines_tidied_by_the_ai_and_kept_when_it_fails():
    w = web.read_recipe(page(SOUP), URL, ai=FakeAI(), tidy=importer.tidy)
    assert w.recipe.ingredients[2].likely_on_hand and not w.warnings

    class Down:
        def complete_json(self, prompt, images=None):
            raise RuntimeError("down")
    w = web.read_recipe(page(SOUP), URL, ai=Down(), tidy=importer.tidy)
    assert [i.raw for i in w.recipe.ingredients] == SOUP["recipeIngredient"] and w.warnings == [importer.NO_AI]


# --- the browser-like second try (2026-11-14: a Cloudflare bot check turned away httpx on a public recipe page) ----

def _blocked_route():
    respx.get("https://blog.example.org/x").mock(return_value=httpx.Response(
        403, headers={"cf-mitigated": "challenge"}, text="<title>Just a moment...</title>"))


@respx.mock
def test_blocked_page_is_fetched_again_with_the_fallback():
    _blocked_route()
    calls = []
    def fallback(url, max_bytes, resolve, start):
        calls.append(url)
        return web.Page(url=url, html="<html>the recipe</html>")
    p = web.fetch_page("https://blog.example.org/x", 10_000, client=httpx.Client(), resolve=resolver(), fallback=fallback)
    assert calls == ["https://blog.example.org/x"] and "the recipe" in p.html


@respx.mock
def test_other_failures_never_use_the_fallback():
    respx.get("https://blog.example.org/x").mock(return_value=httpx.Response(404, text="Not found"))
    def fallback(*a, **kw):
        raise AssertionError("not called")
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/x", 10_000, client=httpx.Client(), resolve=resolver(), fallback=fallback)
    assert e.value.reason == web.NOT_FOUND


@respx.mock
def test_injected_client_has_no_fallback_by_default():
    _blocked_route()
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/x", 10_000, client=httpx.Client(), resolve=resolver())
    assert e.value.reason == web.BLOCKED


@pytest.mark.parametrize("raised,reason", [
    (WebImportError(web.BLOCKED, "still blocked"), web.BLOCKED),
    (WebImportError(web.NOT_FOUND, "gone"), web.NOT_FOUND),
    (RuntimeError("curl broke"), web.BLOCKED),
])
@respx.mock
def test_fallback_failures(raised, reason):
    _blocked_route()
    def fallback(*a, **kw):
        raise raised
    with pytest.raises(WebImportError) as e:
        web.fetch_page("https://blog.example.org/x", 10_000, client=httpx.Client(), resolve=resolver(), fallback=fallback)
    assert e.value.reason == reason
    if reason == web.BLOCKED:   # the first answer's sentence: it says what to do
        assert "refused to show that page" in str(e.value)


def test_browser_fetch_refuses_private_hosts_before_connecting():
    with pytest.raises(WebImportError) as e:
        web.browser_fetch("https://lan.example.org/x", 10_000, resolve=resolver({"lan.example.org": ["192.168.1.5"]}))
    assert e.value.reason == web.PRIVATE
    with pytest.raises(WebImportError) as e:
        web.browser_fetch("http://10.1.2.3/x", 10_000, resolve=resolver())
    assert e.value.reason == web.PRIVATE


def test_browser_fetch_pins_a_fresh_public_answer():
    answers = iter([[PUBLIC], ["127.0.0.1"]])   # public when checked, loopback when pinned (DNS rebinding)
    with pytest.raises(WebImportError) as e:
        web.browser_fetch("https://rebind.example.org/x", 10_000, resolve=lambda h, p: next(answers))
    assert e.value.reason == web.PRIVATE
