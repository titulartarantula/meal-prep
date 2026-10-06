"""Recipe sources (0.4.2): source_kind nyt | book | other, source_title (the book), source_ref (page). Invented data."""
from datetime import date

from psycopg.types.json import Jsonb

from mealprep import db
from mealprep.models import Ingredient, Recipe
from test_api import H, client
from test_subrecipe import BATTER, SubAI, gateau


def book(title, source_title=None, source_ref=None, kind=None):
    return Recipe(title=title, source="photo", ingredients=[], steps=[], source_kind=kind,
                  source_title=source_title, source_ref=source_ref)


def nyt(title, n):
    return Recipe(title=title, source="nyt", source_url=f"https://cooking.nytimes.com/recipes/{n}-x", ingredients=[], steps=[])


def test_migration_backfills_once_and_is_idempotent(conn):
    # a database from before sources: no columns, recipes as 0.4.1 saved them
    conn.execute("ALTER TABLE recipes DROP COLUMN source_kind, DROP COLUMN source_title, DROP COLUMN source_ref")
    old = [("nyt", "https://cooking.nytimes.com/recipes/1-soup", "Test Soup"), ("photo", None, "Test Stew"),
           ("photo", None, "Test Pie"), ("manual", None, "Test Toast")]
    for src, url, title in old:
        conn.execute("INSERT INTO recipes(source, source_url, title, data) VALUES(%s,%s,%s,%s)",
                     (src, url, title, Jsonb({"title": title, "source": src, "source_url": url, "ingredients": [], "steps": []})))
    conn.execute(db.SCHEMA)
    got = conn.execute("SELECT title, source_kind, source_title, source_ref FROM recipes ORDER BY id").fetchall()
    assert got == [("Test Soup", "nyt", None, None), ("Test Stew", "book", None, None), ("Test Pie", "book", None, None),
                   ("Test Toast", "other", None, None)]
    # a book filled in later survives the migration running again at the next start
    rid = conn.execute("SELECT id FROM recipes WHERE title='Test Pie'").fetchone()[0]
    db.update_source(conn, rid, "book", "Invented Bakes", "p. 12")
    conn.execute(db.SCHEMA)
    assert db.get_recipe(conn, rid).source_title == "Invented Bakes"
    assert conn.execute("SELECT count(*) FROM recipes WHERE source_kind IS NULL").fetchone()[0] == 0
    # the constraint is in place
    import psycopg, pytest
    with pytest.raises(psycopg.errors.CheckViolation):
        conn.execute("UPDATE recipes SET source_kind='magazine' WHERE id=%s", (rid,))


def test_save_derives_kind_and_nyt_has_no_book(conn):
    a = db.save_recipe(conn, nyt("Test Noodles", 7))
    b = db.save_recipe(conn, book("Test Tart", "Invented Bakes", "p. 40"))
    c = db.save_recipe(conn, book("Test Card", kind="other", source_title="Index card"))
    weird = db.save_recipe(conn, nyt("Test Soup", 8).model_copy(update={"source_title": "x", "source_ref": "1"}))
    got = {r.id: (r.source_kind, r.source_title, r.source_ref) for r in db.list_recipes(conn)}
    assert got == {a: ("nyt", None, None), b: ("book", "Invented Bakes", "p. 40"), c: ("other", "Index card", None),
                   weird: ("nyt", None, None)}
    assert "source_kind" not in conn.execute("SELECT data FROM recipes WHERE id=%s", (b,)).fetchone()[0]


def setup_library(conn):
    ids = {
        "noodles": db.save_recipe(conn, nyt("Test Noodles", 1)),
        "tart": db.save_recipe(conn, book("Test Tart", "invented bakes", "p. 40")),
        "bun": db.save_recipe(conn, book("Test Bun", "Invented Bakes", "12")),   # newest spelling is shown
        "stew": db.save_recipe(conn, book("Test Stew")),
        "salad": db.save_recipe(conn, book("Test Salad", "A Made-Up Garden")),
        "card": db.save_recipe(conn, book("Test Card", kind="other")),
    }
    return ids


def test_recipes_filter_by_source(conn):
    ids = setup_library(conn)
    c = client(conn)
    def got(source):
        r = c.get("/recipes", headers=H, params={"source": source})
        assert r.status_code == 200
        return {x["id"] for x in r.json()}
    assert got("nyt") == {ids["noodles"]}
    assert got("book") == {ids["tart"], ids["bun"], ids["stew"], ids["salad"]}
    assert got("other") == {ids["card"]}
    assert got("book:INVENTED BAKES") == got("Invented  Bakes") == {ids["tart"], ids["bun"]}   # any case/spacing
    assert got("book:") == {ids["stew"]}                                                      # unknown book
    assert got("No Such Book") == set()
    assert len(c.get("/recipes", headers=H).json()) == 6
    row = next(x for x in c.get("/recipes", headers=H, params={"source": "nyt"}).json())
    assert (row["source_kind"], row["source_title"], row["source_ref"]) == ("nyt", None, None)


def test_sources_list_with_counts(conn):
    setup_library(conn)
    got = client(conn).get("/recipes/sources", headers=H).json()
    assert [(s["key"], s["label"], s["count"]) for s in got] == [
        ("nyt", "NYT Cooking", 1), ("book:A Made-Up Garden", "A Made-Up Garden", 1),
        ("book:Invented Bakes", "Invented Bakes", 2), ("book:", "Unknown book", 1), ("other:", "Other", 1)]
    assert client(conn).get("/recipes/sources", headers=H).status_code == 200


def test_sources_empty_library(conn):
    assert client(conn).get("/recipes/sources", headers=H).json() == []


def test_patch_recipe_source_and_title(conn):
    ids = setup_library(conn)
    c = client(conn)
    stew = ids["stew"]
    r = c.patch(f"/recipes/{stew}", headers=H, json={"source_title": "  Invented   Bakes ", "source_ref": "p. 77"})
    assert r.status_code == 200
    body = r.json()
    assert (body["source_kind"], body["source_title"], body["source_ref"], body["title"]) == ("book", "Invented Bakes", "p. 77", "Test Stew")
    assert "ratings" in body and "planned_weeks" in body
    # only what is sent changes; null clears the page
    body = c.patch(f"/recipes/{stew}", headers=H, json={"source_ref": None}).json()
    assert (body["source_title"], body["source_ref"]) == ("Invented Bakes", None)
    body = c.patch(f"/recipes/{stew}", headers=H, json={"title": "Test Winter Stew"}).json()
    assert body["title"] == "Test Winter Stew" and body["source_title"] == "Invented Bakes"
    assert c.get(f"/recipes/{stew}", headers=H).json()["title"] == "Test Winter Stew"
    assert conn.execute("SELECT title FROM recipes WHERE id=%s", (stew,)).fetchone()[0] == "Test Winter Stew"
    # back to unknown book
    assert c.patch(f"/recipes/{stew}", headers=H, json={"source_title": ""}).json()["source_title"] is None
    # to NYT: the book and page go
    c.patch(f"/recipes/{ids['tart']}", headers=H, json={"source_kind": "nyt"})
    assert db.get_recipe(conn, ids["tart"]).source_title is None


def test_patch_recipe_rejects_bad_input(conn):
    ids = setup_library(conn)
    c = client(conn)
    rid = ids["tart"]
    assert c.patch("/recipes/999", headers=H, json={"source_title": "X"}).status_code == 404
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_kind": "magazine"}).status_code == 422
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_kind": None}).status_code == 422
    assert c.patch(f"/recipes/{rid}", headers=H, json={"title": "  "}).status_code == 422
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_ref": "x" * 51}).status_code == 422
    assert c.patch(f"/recipes/{ids['noodles']}", headers=H, json={"source_title": "Invented Bakes"}).status_code == 422
    assert c.patch(f"/recipes/{rid}", json={"source_title": "X"}).status_code == 401
    assert db.get_recipe(conn, rid).source_title == "invented bakes"


class PhotoAI:
    def complete_json(self, prompt, images=None):
        return {"title": "Test Loaf", "servings": 8, "ingredients": [], "steps": ["Bake."]} if images else []


def test_photo_import_with_source_fields(conn):
    c = client(conn, ai=PhotoAI())
    f = [("files", ("a.jpg", b"page1", "image/jpeg"))]
    r = c.post("/recipes/photo", headers=H, files=f, data={"source_title": "Invented Bakes", "source_ref": "pp. 12-13"})
    assert r.status_code == 200
    rec = r.json()["recipe"]
    assert (rec["source_kind"], rec["source_title"], rec["source_ref"], rec["source"]) == ("book", "Invented Bakes", "pp. 12-13", "photo")
    # skipped: unknown book
    rec = c.post("/recipes/photo", headers=H, files=f).json()["recipe"]
    assert (rec["source_kind"], rec["source_title"], rec["source_ref"]) == ("book", None, None)
    # another kind, and a bad one is refused before reading
    assert c.post("/recipes/photo", headers=H, files=f, data={"source_kind": "other"}).json()["recipe"]["source_kind"] == "other"
    assert c.post("/recipes/photo", headers=H, files=f, data={"source_kind": "magazine"}).status_code == 422
    assert c.post("/recipes/photo", headers=H, files=f, data={"source_title": "x" * 201}).status_code == 422
    assert conn.execute("SELECT count(*) FROM recipes").fetchone()[0] == 3


def test_attached_page_keeps_the_recipes_book(conn):
    rid = db.save_recipe(conn, gateau().model_copy(update={"source_title": "Invented Classics", "source_ref": "p. 189"}))
    c = client(conn, ai=SubAI(BATTER))
    r = c.post(f"/recipes/{rid}/pages", headers=H, files=[("files", ("p191.jpg", b"x", "image/jpeg"))], data={"for_line": "1"})
    assert r.status_code == 200
    assert (r.json()["source_kind"], r.json()["source_title"], r.json()["source_ref"]) == ("book", "Invented Classics", "p. 189")
    got = db.get_recipe(conn, rid)
    assert got.source_title == "Invented Classics" and got.ingredients[1].expanded


def test_share_nyt_sets_kind(conn, monkeypatch):
    import mealprep.api as api_mod
    from test_api import FIX
    monkeypatch.setattr(api_mod, "fetch_nyt", lambda url: FIX.read_text())
    rec = client(conn).post("/recipes/share", headers=H, json={"text": "https://cooking.nytimes.com/recipes/1015819-x"}).json()["recipe"]
    assert (rec["source_kind"], rec["source_title"], rec["source_ref"]) == ("nyt", None, None)


# --- book author / ISBN (0.4.3) ---

def test_author_isbn_migration_is_additive_and_idempotent(conn):
    rid = db.save_recipe(conn, book("Test Tart", "Invented Bakes", "p. 40"))
    conn.execute("ALTER TABLE recipes DROP COLUMN source_author, DROP COLUMN source_isbn")   # a 0.4.2 database
    conn.execute(db.SCHEMA)
    got = db.get_recipe(conn, rid)
    assert (got.source_title, got.source_ref, got.source_author, got.source_isbn) == ("Invented Bakes", "p. 40", None, None)
    db.update_source(conn, rid, "book", "Invented Bakes", "p. 40", "Ada Pepper", "9780000000017")
    conn.execute(db.SCHEMA)
    assert db.get_recipe(conn, rid).source_author == "Ada Pepper"


def test_photo_import_with_author_and_isbn(conn):
    c = client(conn, ai=PhotoAI())
    f = [("files", ("a.jpg", b"page1", "image/jpeg"))]
    rec = c.post("/recipes/photo", headers=H, files=f, data={"source_title": "Invented Bakes", "source_ref": "12",
                                                              "source_author": " Ada  Pepper ", "source_isbn": "978-0-00-000001-7"}).json()["recipe"]
    assert (rec["source_author"], rec["source_isbn"]) == ("Ada Pepper", "9780000000017")
    # no book title: no author either
    rec = c.post("/recipes/photo", headers=H, files=f, data={"source_author": "Ada Pepper"}).json()["recipe"]
    assert (rec["source_title"], rec["source_author"]) == (None, None)
    assert c.post("/recipes/photo", headers=H, files=f, data={"source_isbn": "12345"}).status_code == 422
    assert conn.execute("SELECT count(*) FROM recipes").fetchone()[0] == 2


def test_patch_author_isbn_follow_the_book(conn):
    ids = setup_library(conn)
    c = client(conn)
    rid = ids["stew"]
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_title": "Invented Bakes", "source_author": "Ada Pepper",
                                                       "source_isbn": "000000002x"}).json()
    assert (body["source_author"], body["source_isbn"]) == ("Ada Pepper", "000000002X")
    # same book (any case), only the page changes: author and ISBN stay
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_title": "invented bakes", "source_ref": "p. 9"}).json()
    assert (body["source_author"], body["source_isbn"]) == ("Ada Pepper", "000000002X")
    # another book typed without an author (an 0.4.2 app, or free text): the old book's details go
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_title": "A Made-Up Garden"}).json()
    assert (body["source_author"], body["source_isbn"]) == (None, None)
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_author": "Basil Thyme"}).json()
    assert body["source_author"] == "Basil Thyme"
    # unknown book or NYT: none
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_title": None}).json()["source_author"] is None
    assert c.patch(f"/recipes/{ids['noodles']}", headers=H, json={"source_author": "Ada Pepper"}).status_code == 422
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_isbn": "97800000000"}).status_code == 422
    assert c.patch(f"/recipes/{rid}", headers=H, json={"source_author": "x" * 201}).status_code == 422


def test_sources_list_carries_the_books_author(conn):
    db.save_recipe(conn, book("Test Tart", "Invented Bakes", "p. 40").model_copy(update={"source_author": "Ada Pepper"}))
    db.save_recipe(conn, book("Test Bun", "Invented Bakes", "12"))
    got = client(conn).get("/recipes/sources", headers=H).json()
    assert [(s["label"], s.get("author"), s["count"]) for s in got] == [("Invented Bakes", "Ada Pepper", 2)]


# --- named other sources (0.7.1): "Mum's recipes", a free-text name + optional note in source_ref ---

def setup_others(conn):
    ids = setup_library(conn)   # "card" = an other source without a name
    ids["mum1"] = db.save_recipe(conn, book("Test Pie", "mum's recipes", "the blue binder", kind="other"))
    ids["mum2"] = db.save_recipe(conn, book("Test Crumble", "Mum's Recipes", kind="other"))
    ids["club"] = db.save_recipe(conn, book("Test Curry", "Supper Club", kind="other"))
    return ids


def test_recipes_filter_by_named_other_source(conn):
    ids = setup_others(conn)
    c = client(conn)
    def got(source):
        r = c.get("/recipes", headers=H, params={"source": source})
        assert r.status_code == 200
        return {x["id"] for x in r.json()}
    assert got("other") == {ids["card"], ids["mum1"], ids["mum2"], ids["club"]}   # every other source, as before
    assert got("other:MUM'S  RECIPES") == got(" other:mum's recipes ") == {ids["mum1"], ids["mum2"]}
    assert got("Other:Supper Club") == {ids["club"]}
    assert got("other:") == got("other:   ") == {ids["card"]}                       # no name
    assert got("other:Invented Bakes") == set()                                       # a book is not an other source
    assert got("book:Supper Club") == got("Supper Club") == set()                    # and the other way round
    assert got("book:Invented Bakes") == {ids["tart"], ids["bun"]}


def test_sources_list_named_others_sorted_with_books(conn):
    setup_others(conn)
    got = client(conn).get("/recipes/sources", headers=H).json()
    assert [(s["key"], s["kind"], s["label"], s["count"]) for s in got] == [
        ("nyt", "nyt", "NYT Cooking", 1), ("book:A Made-Up Garden", "book", "A Made-Up Garden", 1),
        ("book:Invented Bakes", "book", "Invented Bakes", 2), ("other:Mum's Recipes", "other", "Mum's Recipes", 2),
        ("other:Supper Club", "other", "Supper Club", 1), ("book:", "book", "Unknown book", 1),
        ("other:", "other", "Other", 1)]
    c = client(conn)
    for s in got:   # each key lists exactly its count
        assert len(c.get("/recipes", headers=H, params={"source": s["key"]}).json()) == s["count"]


def test_patch_to_other_source_has_no_author_or_isbn(conn):
    ids = setup_library(conn)
    c = client(conn)
    rid = ids["stew"]
    c.patch(f"/recipes/{rid}", headers=H, json={"source_title": "Invented Bakes", "source_author": "Ada Pepper",
                                                "source_isbn": "000000002x", "source_ref": "12"})
    # same name, other kind: the book's author/ISBN go, the name and note are kept
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_kind": "other"}).json()
    assert (body["source_kind"], body["source_title"], body["source_ref"], body["source_author"], body["source_isbn"]) == (
        "other", "Invented Bakes", "12", None, None)
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_kind": "other", "source_title": " Mum's   recipes ",
                                                       "source_ref": "from the blue binder", "source_author": "Ada Pepper",
                                                       "source_isbn": "000000002x"}).json()
    assert (body["source_title"], body["source_ref"], body["source_author"], body["source_isbn"]) == (
        "Mum's recipes", "from the blue binder", None, None)
    # no name: a bare Other
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_title": None, "source_ref": None}).json()
    assert (body["source_kind"], body["source_title"]) == ("other", None)
    # and back to a book
    body = c.patch(f"/recipes/{rid}", headers=H, json={"source_kind": "book", "source_title": "Invented Bakes"}).json()
    assert (body["source_kind"], body["source_title"]) == ("book", "Invented Bakes")


def test_photo_import_into_a_named_other_source(conn):
    c = client(conn, ai=PhotoAI())
    f = [("files", ("a.jpg", b"page1", "image/jpeg"))]
    rec = c.post("/recipes/photo", headers=H, files=f, data={"source_kind": "other", "source_title": "Mum's recipes",
                                                              "source_ref": "card 3", "source_author": "Ada Pepper"}).json()["recipe"]
    assert (rec["source_kind"], rec["source_title"], rec["source_ref"], rec["source_author"]) == (
        "other", "Mum's recipes", "card 3", None)
