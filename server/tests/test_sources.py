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
        ("book:Invented Bakes", "Invented Bakes", 2), ("book:", "Unknown book", 1), ("other", "Other", 1)]
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
