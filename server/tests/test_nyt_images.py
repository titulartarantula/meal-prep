"""The one-off backfill of NYT recipes' photo links (python -m mealprep.nyt_images)."""
import json

from mealprep import db, nyt_images
from mealprep.importers.nyt import NytFetchError
from mealprep.models import Recipe

B = "https://cooking.nytimes.com/recipes"


def _page(image) -> str:
    return ("<html><script type='application/ld+json'>"
            + json.dumps({"@type": "Recipe", "name": "x", "image": image}) + "</script></html>")


def _save(conn, title, url=None, image=None, source="nyt"):
    return db.save_recipe(conn, Recipe(title=title, source=source, source_url=url, ingredients=[], steps=[],
                                       image=image))


def test_backfill_sets_missing_links_politely(conn):
    a = _save(conn, "A", f"{B}/101-a")
    b = _save(conn, "B", f"{B}/102-b")
    c = _save(conn, "C", f"{B}/103-c")
    has = _save(conn, "Has one", f"{B}/104-d", image="https://images.example/kept.jpg")
    book = _save(conn, "Book", None, source="photo")
    pages = {f"{B}/101-a": _page("https://images.example/a.jpg"), f"{B}/102-b": _page(None)}
    fetched, slept = [], []

    def fetch(url):
        fetched.append(url)
        if url not in pages:
            raise NytFetchError("404")
        return pages[url], url

    out = nyt_images.backfill(conn, delay=3, fetch=fetch, sleep=slept.append)
    assert fetched == [f"{B}/101-a", f"{B}/102-b", f"{B}/103-c"]   # only NYT recipes without a link
    assert slept == [3, 3]                                          # a pause between fetches, none before the first
    assert out["done"] == [a] and out["no_image"] == [b] and [r for r, _ in out["failed"]] == [c]
    assert db.get_recipe(conn, a).image == "https://images.example/a.jpg"
    assert db.get_recipe(conn, a).title == "A"                     # the rest of the recipe is untouched
    assert db.get_recipe(conn, b).image is None and db.get_recipe(conn, c).image is None
    assert db.get_recipe(conn, has).image == "https://images.example/kept.jpg"
    assert db.get_recipe(conn, book).image is None
    # a second run only retries what's still missing
    assert [r for r, _ in nyt_images.missing(conn)] == [b, c]


def test_backfill_dry_run_writes_nothing(conn):
    a = _save(conn, "A", f"{B}/101-a")
    out = nyt_images.backfill(conn, dry_run=True, fetch=lambda u: (_page("https://images.example/a.jpg"), u),
                              sleep=lambda s: None)
    assert out["done"] == [a] and db.get_recipe(conn, a).image is None
