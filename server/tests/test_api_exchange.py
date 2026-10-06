"""Export / import endpoints (DB-backed; invented recipes only)."""
import json
from datetime import date

from mealprep import db
from mealprep.models import Ingredient, Recipe
from test_api import H, client


def add(conn, title="Test Lentil Soup", **kw):
    r = Recipe(**{"title": title, "source": "photo", "servings": 4, "source_kind": "other",
                  "ingredients": [Ingredient(raw="1 cup red lentils", name="red lentil", qty=1, unit="cup")],
                  "steps": ["Simmer."], **kw})
    return db.save_recipe(conn, r)


def test_export_one(conn):
    rid = add(conn, title="Crème brûlée / “best”")
    r = client(conn).get(f"/recipes/{rid}/export", headers=H)
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("application/ld+json")
    assert r.headers["content-disposition"] == 'attachment; filename="creme-brulee-best.recipe.json"'
    n = json.loads(r.content.decode("utf-8"))
    assert n["name"] == "Crème brûlée / “best”" and "“" in r.content.decode("utf-8")   # not \u-escaped
    uid = conn.execute("SELECT uid::text FROM recipes WHERE id=%s", (rid,)).fetchone()[0]
    assert n["identifier"] == f"urn:uuid:{uid}" and n["@context"]
    assert n["mealprep:ratings"]["entries"] == [] and "dateCreated" in n


def test_export_one_404_and_auth(conn):
    c = client(conn)
    assert c.get("/recipes/999/export", headers=H).status_code == 404
    rid = add(conn)
    assert c.get(f"/recipes/{rid}/export").status_code == 401
    assert c.get("/recipes/export").status_code == 401


def test_export_ratings_entries(conn):
    rid = add(conn)
    c = client(conn, today=date(2026, 10, 14))
    rated = db.add_to_week(conn, date(2026, 10, 4), rid)
    db.update_entry(conn, rated, day=2, multiplier=2.0)
    db.set_rating(conn, rated, 4, "yes", "less salt")
    cooked = db.add_to_week(conn, date(2026, 9, 27), rid)
    db.update_entry(conn, cooked, day=1)                     # cooked, never rated
    db.add_to_week(conn, date(2026, 10, 18), rid)            # planned ahead: not exported
    db.add_to_week(conn, date(2026, 10, 4), rid)             # never placed on a night: not exported
    n = c.get(f"/recipes/{rid}/export", headers=H).json()
    e = n["mealprep:ratings"]["entries"]
    assert [(x["date"], x["multiplier"], x["family"], x["company"], x["note"]) for x in e] == [
        ("2026-10-06", 2.0, 4, "yes", "less salt"), ("2026-09-28", 1.0, None, None, None)]
    assert e[0]["rated_at"] and e[1]["rated_at"] is None
    assert n["mealprep:ratings"]["summary"]["times_cooked"] == 2 and n["mealprep:ratings"]["good_for_company"] is True


def test_export_library_bundle(conn):
    a = add(conn, title="Soup A")
    b = add(conn, title="Soup B", source_kind="book", source_title="Test Kitchen Basics")
    c = client(conn, today=date(2026, 10, 6))
    r = c.get("/recipes/export", headers=H)
    assert r.status_code == 200 and r.headers["content-type"].startswith("application/ld+json")
    assert r.headers["content-disposition"] == 'attachment; filename="meal-prep-recipes-2026-10-06.json"'
    body = r.json()
    assert [n["name"] for n in body["@graph"]] == ["Soup B", "Soup A"]      # newest first
    assert all("@context" not in n for n in body["@graph"]) and body["mealprep:export"]["count"] == 2
    assert [n["name"] for n in c.get("/recipes/export?source=book:", headers=H).json()["@graph"]] == []
    assert [n["name"] for n in c.get("/recipes/export?source=book:test kitchen basics", headers=H).json()["@graph"]] == ["Soup B"]
    assert [n["name"] for n in c.get(f"/recipes/export?ids={a},999", headers=H).json()["@graph"]] == ["Soup A"]
    assert [n["name"] for n in c.get(f"/recipes/export?ids={a}&source=book", headers=H).json()["@graph"]] == []
    assert c.get(f"/recipes/export?ids={a},{b}", headers=H).json()["mealprep:export"]["count"] == 2
    assert c.get("/recipes/export?ids=x", headers=H).status_code == 422
    empty = c.get("/recipes/export?source=nyt", headers=H).json()
    assert empty["@graph"] == [] and empty["mealprep:export"]["count"] == 0
