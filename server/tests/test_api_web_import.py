"""POST /recipes/share with a link to any site (2026-11-08): routing (NYT keeps its importer), saving with the site's
attribution, de-duplication by the page's link, the library's site filter, refusals. Invented pages only."""
import json

import pytest

import mealprep.api as api_mod
from mealprep.importers import web
from mealprep.importers.web import Page, WebImportError

from test_api import AI, FIX, H, client

URL = "https://www.example-recipes.test/recipes/squash-soup"
SOUP = {"@context": "https://schema.org", "@type": "Recipe", "name": "Invented Squash Soup",
        "author": {"@type": "Person", "name": "Pat Example"}, "recipeYield": "4",
        "image": "https://img.example-recipes.test/soup.jpg",
        "recipeIngredient": ["1 small squash", "2 cups stock"], "recipeInstructions": ["Roast.", "Blend."]}


def html(node=SOUP, site="Example Kitchen", canonical=URL):
    return (f'<html><head><meta property="og:site_name" content="{site}"><link rel="canonical" href="{canonical}">'
            f'<script type="application/ld+json">{json.dumps(node)}</script></head><body></body></html>')


@pytest.fixture
def fetched(monkeypatch):
    """fetch_page answers from `pages` (link → (final link, html)); every call is recorded; DNS says public."""
    calls, pages = [], {}

    def fetch(url, max_bytes, **kw):
        calls.append(url)
        if url not in pages:
            raise WebImportError(web.NOT_FOUND, "no page")
        final, body = pages[url]
        return Page(url=final, html=body)
    monkeypatch.setattr(api_mod, "fetch_page", fetch)
    monkeypatch.setattr(api_mod, "web_resolve", lambda host, port: ["93.184.216.34"])
    return calls, pages


def share(c, text, week=None):
    return c.post("/recipes/share", headers=H, json={"text": text, "week": week})


def test_share_any_site_saves_with_attribution(conn, fetched):
    calls, pages = fetched
    pages[URL] = (URL, html())
    c = client(conn)
    r = share(c, f"Look at this {URL}?utm_source=app", "2026-10-11")
    assert r.status_code == 200, r.text
    body = r.json()
    rec = body["recipe"]
    assert body["existing"] is False and body["entry"] is not None
    assert (rec["source"], rec["source_kind"], rec["source_title"], rec["source_author"]) == \
        ("web", "web", "Example Kitchen", "Pat Example")
    assert rec["source_url"] == URL and rec["image"] == "https://img.example-recipes.test/soup.jpg"
    assert calls == [URL]   # tracking parameters dropped before fetching


def test_the_same_page_again_opens_the_existing_recipe_without_fetching(conn, fetched):
    calls, pages = fetched
    pages[URL] = (URL, html())
    c = client(conn)
    first = share(c, URL).json()["recipe"]
    again = share(c, "http://example-recipes.test/recipes/squash-soup/?fbclid=abc#comments")
    assert again.status_code == 200 and again.json()["existing"] is True
    assert again.json()["recipe"]["id"] == first["id"] and len(calls) == 1
    assert len(c.get("/recipes", headers=H).json()) == 1


def test_a_link_whose_canonical_page_is_in_the_library_is_not_imported_twice(conn, fetched):
    calls, pages = fetched
    pages[URL] = (URL, html())
    amp = "https://www.example-recipes.test/amp/squash-soup"
    pages[amp] = (amp, html())   # the AMP page names the same canonical link
    c = client(conn)
    first = share(c, URL).json()["recipe"]
    again = share(c, amp).json()
    assert again["existing"] is True and again["recipe"]["id"] == first["id"]
    assert len(c.get("/recipes", headers=H).json()) == 1


def test_redirect_saves_the_final_canonical_link(conn, fetched):
    _, pages = fetched
    short = "https://sho.example.net/abc"
    pages[short] = (URL, html(canonical=URL))
    rec = share(client(conn), short).json()["recipe"]
    assert rec["source_url"] == URL


def test_site_filter_and_sources(conn, fetched):
    _, pages = fetched
    other = "https://pots.example.org/stew"
    pages[URL] = (URL, html())
    pages[other] = (other, html({**SOUP, "name": "Invented Stew"}, site="Weeknight Pots", canonical=other))
    c = client(conn)
    share(c, URL), share(c, other)
    sources = c.get("/recipes/sources", headers=H).json()
    web_sources = [s for s in sources if s["kind"] == "web"]
    assert [(s["key"], s["label"], s["count"]) for s in web_sources] == \
        [("web:Example Kitchen", "Example Kitchen", 1), ("web:Weeknight Pots", "Weeknight Pots", 1)]
    assert [r["title"] for r in c.get("/recipes", headers=H, params={"source": "web:example kitchen"}).json()] == \
        ["Invented Squash Soup"]
    assert len(c.get("/recipes", headers=H, params={"source": "web"}).json()) == 2


@pytest.mark.parametrize("text", ["http://127.0.0.1:8790/recipes", "http://192.168.1.1/", "file:///etc/passwd x",
                                  "http://localhost/admin", "https://[::1]/"])
def test_private_or_odd_links_are_refused_before_any_fetch(conn, fetched, text):
    calls, _ = fetched
    r = share(client(conn), text)
    assert r.status_code == 422 and calls == []


def test_a_name_resolving_into_the_lan_is_refused(conn, fetched, monkeypatch):
    calls, _ = fetched
    monkeypatch.setattr(api_mod, "web_resolve", lambda host, port: ["10.0.0.5"])
    assert share(client(conn), "https://evil.example.com/recipe").status_code == 422 and calls == []


def test_no_link_is_422(conn, fetched):
    r = share(client(conn), "just words")
    assert r.status_code == 422 and "link" in r.json()["detail"]


def test_site_failure_is_502_with_the_reason_and_saves_nothing(conn, fetched, monkeypatch):
    def blocked(url, max_bytes, **kw):
        raise WebImportError(web.BLOCKED, "blog.example.org refused to show that page to the Meal Prep server")
    monkeypatch.setattr(api_mod, "fetch_page", blocked)
    c = client(conn)
    r = share(c, "https://blog.example.org/x")
    assert r.status_code == 502 and "refused" in r.json()["detail"]
    assert c.get("/recipes", headers=H).json() == []


def test_a_page_without_a_recipe_is_422(conn, fetched):
    _, pages = fetched
    essay = "https://blog.example.org/essay"
    pages[essay] = (essay, "<html><body><p>Thoughts.</p></body></html>")
    r = share(client(conn, ai=AI()), essay)
    assert r.status_code == 422 and "No recipe found" in r.json()["detail"]


def test_nyt_links_still_use_the_nyt_importer(conn, fetched, monkeypatch):
    calls, _ = fetched
    monkeypatch.setattr(api_mod, "fetch_nyt_recipe", lambda url: (FIX.read_text(), url))
    rec = share(client(conn), "https://cooking.nytimes.com/recipes/1015819-x").json()["recipe"]
    assert rec["source_kind"] == "nyt" and calls == []


def test_web_recipe_round_trips_through_export(conn, fetched):
    _, pages = fetched
    pages[URL] = (URL, html())
    c = client(conn)
    rid = share(c, URL).json()["recipe"]["id"]
    node = c.get(f"/recipes/{rid}/export", headers=H).json()
    assert node["isBasedOn"]["@type"] == "WebPage" and node["isBasedOn"]["publisher"]["name"] == "Example Kitchen"
    assert node["mealprep:recipe"]["source"]["kind"] == "web"
    from mealprep.exchange import jsonld
    inc = jsonld.from_jsonld(node)
    assert (inc.recipe.source_kind, inc.recipe.source_title, inc.recipe.source_author) == \
        ("web", "Example Kitchen", "Pat Example")
    # a plain schema.org copy (another app) keeps the website attribution too
    plain = {k: v for k, v in node.items() if not k.startswith("mealprep:")}
    inc = jsonld.from_jsonld(plain)
    assert (inc.recipe.source_kind, inc.recipe.source_title) == ("web", "Example Kitchen")


def test_patch_keeps_a_web_source_to_site_and_author(conn, fetched):
    _, pages = fetched
    pages[URL] = (URL, html())
    c = client(conn)
    rid = share(c, URL).json()["recipe"]["id"]
    r = c.patch(f"/recipes/{rid}", headers=H, json={"source_title": "Example Kitchen Blog", "source_ref": "p. 3"})
    assert r.status_code == 200, r.text
    rec = r.json()
    assert (rec["source_kind"], rec["source_title"], rec["source_ref"], rec["source_author"]) == \
        ("web", "Example Kitchen Blog", None, "Pat Example")
