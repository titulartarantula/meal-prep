"""Book metadata search (0.4.3). The HTTP layer is an httpx.MockTransport: no network. Every book here is invented."""
import json

import httpx
from fastapi.testclient import TestClient

from mealprep import books
from mealprep.api import create_app
from mealprep.books import BookSearch, RateGuard
from mealprep.config import Settings

H = {"Authorization": "Bearer secret"}


def ol_doc(title, authors=("Ada Pepper",), year=2001, isbn=("9780000000017",), publisher=("Imaginary Press",),
           subtitle=None):
    d = {"title": title, "author_name": list(authors), "first_publish_year": year, "isbn": list(isbn),
         "publisher": list(publisher)}
    if subtitle:
        d["subtitle"] = subtitle
    return d


def g_item(title, authors=("Ada Pepper",), date="2003-04-01", isbn13="9780000000024", publisher="Pretend House",
           subtitle=None):
    v = {"title": title, "authors": list(authors), "publishedDate": date, "publisher": publisher,
         "industryIdentifiers": [{"type": "ISBN_10", "identifier": "0000000027"}, {"type": "ISBN_13", "identifier": isbn13}]}
    if subtitle:
        v["subtitle"] = subtitle
    return {"volumeInfo": v}


class Upstream:
    """Fake Open Library + Google Books. `ol`/`google`: a list of docs/items, an int status, or an exception."""

    def __init__(self, ol=(), google=()):
        self.ol, self.google, self.calls = ol, google, []

    def handler(self, request: httpx.Request):
        self.calls.append(request)
        host = request.url.host
        what = self.ol if host == "openlibrary.org" else self.google
        if isinstance(what, Exception):
            raise what
        if isinstance(what, int):
            return httpx.Response(what, json={"error": "nope"})
        body = {"docs": list(what)} if host == "openlibrary.org" else {"items": list(what)}
        return httpx.Response(200, json=body)

    def hosts(self):
        return [r.url.host for r in self.calls]


class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t


def searcher(up: Upstream, key="test-key", clock=None, **kw):
    client = httpx.Client(transport=httpx.MockTransport(up.handler), headers={"User-Agent": books.USER_AGENT})
    return BookSearch(google_key=key, client=client, clock=clock or Clock(), **kw)


def test_open_library_answers_ranked_and_shaped():
    up = Upstream(ol=[ol_doc("Weeknight Soups of the Moon"), ol_doc("The Moon Kitchen", subtitle="Recipes for Late Nights"),
                      ol_doc("Moon Kitchen Companion", authors=("Basil Thyme",)), ol_doc("Kitchen of the Moon"),
                      ol_doc("Gardening by Moonlight")])
    got = searcher(up).search("moon kitchen")
    titles = [b["title"] for b in got]
    # exact title (leading article ignored) → prefix → every word in the title → the rest in upstream order
    assert titles == ["The Moon Kitchen", "Moon Kitchen Companion", "Kitchen of the Moon",
                      "Weeknight Soups of the Moon", "Gardening by Moonlight"]
    first = got[0]
    assert first == {"title": "The Moon Kitchen", "subtitle": "Recipes for Late Nights", "authors": ["Ada Pepper"],
                     "year": 2001, "isbn": "9780000000017", "publisher": "Imaginary Press", "source": "openlibrary"}
    # three good results: Google is not asked
    assert up.hosts() == ["openlibrary.org"]
    req = up.calls[0]
    assert req.headers["user-agent"] == books.USER_AGENT
    assert req.url.params["q"] == "moon kitchen" and req.url.params["fields"] == books.OL_FIELDS


def test_title_and_subtitle_exact_and_author_words():
    up = Upstream(ol=[ol_doc("Pan and Flame", authors=("Juniper Ash",)), ol_doc("Salt Story", subtitle="Pan and Flame"),
                      ol_doc("Ash Wednesday Pan Suppers")])
    got = searcher(up).search("Salt Story: Pan & Flame")
    assert got[0]["title"] == "Salt Story"   # title + subtitle, "&" = "and", punctuation ignored
    assert books.tier({"title": "Pan and Flame", "authors": ["Juniper Ash"]}, "pan flame ash") == 3


def test_dedup_same_title_and_an_author_surname():
    up = Upstream(ol=[ol_doc("The Imaginary Larder", authors=("Ada Pepper", "Basil Thyme"), year=1999, isbn=(), publisher=()),
                      ol_doc("Imaginary Larder", authors=("A. Pepper",), year=1995),
                      ol_doc("The Imaginary Larder", authors=("Rosemary Quill",)),
                      ol_doc("the imaginary larder!", authors=("Basil Thyme", "Ada Pepper")),   # co-author listed first
                      ol_doc("Imaginary Larder Two")])
    got = searcher(up).search("imaginary larder")
    assert [(b["title"], b["authors"][0]) for b in got] == [
        ("The Imaginary Larder", "Ada Pepper"), ("The Imaginary Larder", "Rosemary Quill"), ("Imaginary Larder Two", "Ada Pepper")]
    # the duplicate filled the missing ISBN/publisher and the earlier year
    assert (got[0]["isbn"], got[0]["publisher"], got[0]["year"]) == ("9780000000017", "Imaginary Press", 1995)


def test_google_fills_in_when_open_library_has_too_little():
    up = Upstream(ol=[ol_doc("Fennel Days")], google=[g_item("Fennel Days", subtitle="A Year of Bulbs"),
                                                      g_item("Fennel Nights", authors=("Basil Thyme",)), g_item("Bread")])
    got = searcher(up).search("fennel")
    assert up.hosts() == ["openlibrary.org", "www.googleapis.com"]
    assert [(b["title"], b["source"]) for b in got] == [("Fennel Days", "openlibrary"), ("Fennel Nights", "google"),
                                                        ("Bread", "google")]
    assert got[0]["subtitle"] == "A Year of Bulbs"   # borrowed from Google's copy of the same book
    assert got[1] == {"title": "Fennel Nights", "subtitle": None, "authors": ["Basil Thyme"], "year": 2003,
                      "isbn": "9780000000024", "publisher": "Pretend House", "source": "google"}
    g = up.calls[1]
    # the key travels in a header, never in the URL
    assert g.headers["x-goog-api-key"] == "test-key" and "test-key" not in str(g.url)
    assert g.url.params["q"] == "fennel" and g.url.params["printType"] == "books"


def test_open_library_error_or_timeout_falls_back_to_google():
    for failure in (503, httpx.ConnectTimeout("slow"), httpx.ReadTimeout("slow")):
        up = Upstream(ol=failure, google=[g_item("Turnip Theory")])
        got = searcher(up).search("turnip theory")
        assert [(b["title"], b["source"]) for b in got] == [("Turnip Theory", "google")]


def test_bad_json_from_open_library_falls_back():
    def handler(request):
        if request.url.host == "openlibrary.org":
            return httpx.Response(200, content=b"<html>maintenance</html>")
        return httpx.Response(200, json={"items": [g_item("Okra Hours")]})
    bs = BookSearch(google_key="k", client=httpx.Client(transport=httpx.MockTransport(handler)), clock=Clock())
    assert [b["title"] for b in bs.search("okra hours")] == ["Okra Hours"]


def test_both_failing_returns_empty():
    up = Upstream(ol=500, google=httpx.ConnectError("down"))
    assert searcher(up).search("parsnip") == []
    assert up.hosts() == ["openlibrary.org", "www.googleapis.com"]


def test_missing_google_key_skips_google():
    up = Upstream(ol=[ol_doc("Kale Unlimited")], google=[g_item("Kale Forever")])
    assert [b["title"] for b in searcher(up, key="").search("kale")] == ["Kale Unlimited"]
    up = Upstream(ol=500)
    assert searcher(up, key=None).search("kale") == []
    assert up.hosts() == ["openlibrary.org"]


def test_cache_hit_and_ttl():
    clock = Clock()
    up = Upstream(ol=[ol_doc("Leek Season"), ol_doc("Leek Season Two"), ol_doc("Leek Season Three")])
    bs = searcher(up, clock=clock)
    a = bs.search("Leek  Season")
    a[0]["title"] = "changed by the caller"
    assert bs.search("leek season")[0]["title"] == "Leek Season"   # same normalised query; a copy came back
    assert len(up.calls) == 1
    clock.t += 86400 + 1
    bs.search("leek season")
    assert len(up.calls) == 2


def test_failures_are_cached_briefly_only():
    clock = Clock()
    up = Upstream(ol=500, google=500)
    bs = searcher(up, clock=clock)
    assert bs.search("chard") == []
    bs.search("chard")
    assert len(up.calls) == 2           # cached: no second round
    clock.t += 301
    up.ol = [ol_doc("Chard Life"), ol_doc("Chard Life II"), ol_doc("Chard Life III")]
    assert bs.search("chard")[0]["title"] == "Chard Life"


def test_cache_is_bounded_lru():
    up = Upstream(ol=[ol_doc("A Book"), ol_doc("B Book"), ol_doc("C Book")])
    bs = searcher(up, cache_size=2)
    bs.search("aa"); bs.search("bb"); bs.search("aa"); bs.search("cc")   # "bb" is the least recently used
    assert list(k[0] for k in bs.cache) == ["aa", "cc"]


def test_query_length_limits():
    up = Upstream(ol=[ol_doc("Fig")])
    bs = searcher(up)
    assert bs.search("") == [] and bs.search(" f ") == [] and bs.search(None) == []
    assert up.calls == []
    bs.search("fi")
    bs.search("x" * 500)
    assert len(up.calls[-1].url.params["q"]) == books.MAX_QUERY


def test_rate_guard():
    clock = Clock()
    g = RateGuard(2, 60, clock)
    assert g.allow() and g.allow() and not g.allow()
    clock.t += 60
    assert g.allow()


def test_rate_guard_skips_upstream():
    up = Upstream(ol=[ol_doc("Bean Notes")])
    bs = searcher(up, key="")
    bs.ol_guard = RateGuard(1, 60, bs.clock)
    bs.search("bean notes")
    assert bs.search("bean notes two") == []   # over the limit: not asked at all
    assert len(up.calls) == 1


def test_parsers_tolerate_odd_records():
    assert books.parse_open_library({"docs": [{"title": ""}, {"author_name": ["X"]}, {"title": "Lone Title"}]}) == [
        {"title": "Lone Title", "subtitle": None, "authors": [], "year": None, "isbn": None, "publisher": None,
         "source": "openlibrary"}]
    assert books.parse_google({"totalItems": 0}) == []
    assert books.parse_google({"items": [{"volumeInfo": {"title": "Undated", "publishedDate": "n.d."}}]})[0]["year"] is None
    assert books._isbn(["0-00-000002-7", "978-0-00-000001-7"]) == "9780000000017"
    assert books._isbn(["9788500000001", "9781000000001"]) == "9781000000001"   # English-language group first
    assert books._isbn(["9788500000001", "0000000027"]) == "9788500000001"
    assert books._isbn(["123"]) is None


# --- the endpoint ---

class FakeSearch:
    def __init__(self, result=None, boom=False): self.result, self.boom, self.calls = result or [], boom, []
    def search(self, q, limit):
        self.calls.append((q, limit))
        if self.boom:
            raise RuntimeError("bug")
        return self.result


def api(book_search):
    s = Settings(dsn="unused", token="secret", provider="fake")
    return TestClient(create_app(s, provider=object(), pcx=object(), conn=object(), book_search=book_search))


def test_endpoint_returns_results_and_needs_auth():
    fs = FakeSearch([{"title": "Invented Pantry", "subtitle": None, "authors": ["Ada Pepper"], "year": 2010,
                      "isbn": None, "publisher": None, "source": "openlibrary"}])
    c = api(fs)
    r = c.get("/books/search", headers=H, params={"q": "invented pantry"})
    assert r.status_code == 200 and r.json()[0]["title"] == "Invented Pantry"
    assert fs.calls == [("invented pantry", 8)]
    c.get("/books/search", headers=H, params={"q": "x y", "limit": 3})
    assert fs.calls[-1] == ("x y", 3)
    assert c.get("/books/search", params={"q": "invented"}).status_code == 401
    assert c.get("/books/search", headers=H, params={"q": "x" * 201}).status_code == 422
    assert c.get("/books/search", headers=H, params={"q": "ok", "limit": 0}).status_code == 422
    assert c.get("/books/search", headers=H, params={"q": "ok", "limit": 21}).status_code == 422


def test_endpoint_never_5xx():
    c = api(FakeSearch(boom=True))
    r = c.get("/books/search", headers=H, params={"q": "anything"})
    assert r.status_code == 200 and r.json() == []


def test_endpoint_with_real_searcher_and_dead_upstreams():
    up = Upstream(ol=httpx.ConnectError("down"), google=503)
    r = api(searcher(up)).get("/books/search", headers=H, params={"q": "a"})
    assert r.json() == [] and up.calls == []          # too short: nobody asked
    r = api(searcher(up)).get("/books/search", headers=H, params={"q": "carrot cake"})
    assert r.status_code == 200 and r.json() == []
    assert json.dumps(r.json()) == "[]"


def test_settings_hide_the_google_key(monkeypatch):
    monkeypatch.setenv("MEALPREP_GOOGLE_BOOKS_KEY", "not-a-real-key")
    s = Settings()
    assert s.google_books_key == "not-a-real-key" and "not-a-real-key" not in repr(s)
