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


# --- POST /recipes/import ---------------------------------------------------------------------------------------

import time
from pathlib import Path

import pytest

from mealprep.exchange import importer, jsonld
from test_api import AI

FX = Path(__file__).parent / "fixtures/exchange"


class TidyAI:
    """Answers the ingredient prompt like the real model would (one object per line)."""
    def __init__(self, fail=False):
        self.fail, self.n = fail, 0

    def complete_json(self, prompt, images=None):
        self.n += 1
        if self.fail:
            from mealprep.ai.base import AIError
            raise AIError("down")
        lines = json.loads(prompt.split("Lines:\n", 1)[1])
        return [{"name": "tidied " + str(i), "qty": None, "unit": None, "prep": None, "likely_on_hand": i == 0}
                for i in range(len(lines))]


def post(c, data, dry_run=True, choices=None, name="recipes.json"):
    if isinstance(data, str):
        data = (FX / data).read_bytes()
    form = {"dry_run": "true" if dry_run else "false"}
    if choices is not None:
        form["choices"] = json.dumps(choices)
    return c.post("/recipes/import", headers=H, files={"file": (name, data, "application/json")}, data=form)


def apply(c, data, choices=None):
    r = post(c, data, dry_run=False, choices=choices)
    assert r.status_code == 202, r.text
    job = r.json()
    for _ in range(200):
        if job["status"] != "running":
            return job
        time.sleep(0.05)
        job = c.get(f"/imports/{job['id']}", headers=H).json()
    raise AssertionError("import job didn't finish")


def nrecipes(conn):
    return conn.execute("SELECT count(*) FROM recipes").fetchone()[0]


def test_preview_writes_nothing(conn):
    c = client(conn)
    r = post(c, "array_two.json")
    assert r.status_code == 200
    body = r.json()
    assert body["dry_run"] is True and body["format"] == "jsonld" and len(body["file_sha"]) == 64
    assert body["counts"] == {"new": 2, "duplicate": 0, "failed": 0}
    a = body["items"][0]
    assert a["key"].startswith("0:") and len(a["key"]) == 10 and a["status"] == "new" and a["action"] == "add"
    assert (a["title"], a["ingredients"], a["steps"], a["ratings"], a["source"], a["ai_tidy"]) == (
        "Test Tomato Toast", 3, 2, 0, "Other", True)
    assert nrecipes(conn) == 0 and conn.execute("SELECT count(*) FROM import_jobs").fetchone()[0] == 0


def test_apply_then_reapply_is_all_duplicates(conn):
    ai = TidyAI()
    c = client(conn, ai=ai)
    job = apply(c, "array_two.json")
    assert job["status"] == "done" and job["counts"]["added"] == 2 and job["progress"] == {"done": 2, "total": 2}
    assert [i["status"] for i in job["items"]] == ["added", "added"] and all(i["recipe_id"] for i in job["items"])
    assert all(i["ai_tidy"] for i in job["items"]) and ai.n == 2
    uids = {r[0] for r in conn.execute("SELECT uid::text FROM recipes")}
    assert uids == {"3f2b8c1e-6d4a-4b7f-9e0a-1c2d3e4f5a6b", "8a7b6c5d-4e3f-4a1b-8c2d-0e9f8a7b6c5d"}
    toast = db.get_recipe(conn, job["items"][0]["recipe_id"])
    assert [i.name for i in toast.ingredients] == ["tidied 0", "tidied 1", "tidied 2"]
    assert toast.ingredients[0].likely_on_hand and toast.source == "import"
    assert conn.execute("SELECT ai_provider FROM recipes WHERE id=%s", (toast.id,)).fetchone()[0] == "fake"
    prev = post(c, "array_two.json").json()
    assert prev["counts"] == {"new": 0, "duplicate": 2, "failed": 0}
    assert prev["items"][0]["match"] == {"recipe_id": toast.id, "title": "Test Tomato Toast", "by": "id"}
    assert prev["items"][0]["can_update"] is False
    same = post(c, "array_two.json", dry_run=False).json()          # a retried apply: the same job, nothing new
    assert same["existing"] is True and same["id"] == job["id"]
    again = apply(c, (FX / "array_two.json").read_bytes() + b"\n")   # the same recipes in another file
    assert again["id"] != job["id"] and again["existing"] is False
    assert [i["status"] for i in again["items"]] == ["duplicate", "duplicate"] and nrecipes(conn) == 2


def test_retried_apply_returns_the_same_job(conn):
    c = client(conn, ai=TidyAI())
    first = apply(c, "single_minimal.json")
    r = post(c, "single_minimal.json", dry_run=False)
    assert r.status_code == 202 and r.json()["id"] == first["id"] and r.json()["existing"] is True
    assert nrecipes(conn) == 1
    other = post(c, "single_minimal.json", dry_run=False, choices={first["items"][0]["key"]: "skip"}).json()
    assert other["id"] != first["id"]


def test_ai_failure_falls_back_to_the_clean_up(conn):
    c = client(conn, ai=TidyAI(fail=True))
    job = apply(c, "single_minimal.json")
    [it] = job["items"]
    assert it["status"] == "added" and it["ai_tidy"] is False and importer.NO_AI in it["reasons"]
    r = db.get_recipe(conn, it["recipe_id"])
    assert [i.name for i in r.ingredients] == ["red lentils", "carrots", "kosher salt"]
    assert conn.execute("SELECT ai_provider FROM recipes").fetchone()[0] is None


def test_own_export_skips_the_ai(conn):
    ai = TidyAI()
    c = client(conn, ai=ai)
    job = apply(c, "mealprep_v1.json")
    assert job["items"][0]["status"] == "added" and job["items"][0]["ai_tidy"] is False and ai.n == 0


def test_duplicate_by_link(conn):
    add(conn, title="Lentil soup", source="nyt", source_kind="nyt",
        source_url="https://cooking.nytimes.com/recipes/1099999-test-lentil-soup")
    [it] = post(client(conn), "nyt_url.json").json()["items"]
    assert it["status"] == "duplicate" and it["match"]["by"] == "link" and it["match"]["title"] == "Lentil soup"
    assert it["source"] == "NYT Cooking"


def test_duplicate_by_title_and_source(conn):
    rid = add(conn, title="test book muffins!", source_kind="book", source_title="TEST KITCHEN BASICS")
    [it] = post(client(conn), "book_based_on.json").json()["items"]
    assert it["status"] == "duplicate" and it["match"] == {"recipe_id": rid, "title": "test book muffins!",
                                                            "by": "title_source"}
    assert it["can_add_anyway"] is False and it["source"] == "Book · Test Kitchen Basics"


def test_title_only_can_add_anyway(conn):
    add(conn, title="Test Lentil Soup")                       # other source without a name
    c = client(conn, ai=TidyAI())
    [it] = post(c, "single_minimal.json").json()["items"]
    assert it["status"] == "duplicate" and it["match"]["by"] == "title" and it["can_add_anyway"] is True
    job = apply(c, "single_minimal.json", {it["key"]: "add"})
    assert job["items"][0]["status"] == "added" and nrecipes(conn) == 2


def test_different_sources_same_title_is_new(conn):
    add(conn, title="Test Book Muffins", source_kind="book", source_title="Another Book")
    assert post(client(conn), "book_based_on.json").json()["items"][0]["status"] == "new"


def test_same_id_changed_can_update(conn):
    c = client(conn, today=date(2026, 10, 14))
    first = apply(c, "mealprep_v1.json")
    rid = first["items"][0]["recipe_id"]
    eid = db.add_to_week(conn, date(2026, 10, 11), rid)
    db.update_entry(conn, eid, day=1)
    db.set_rating(conn, eid, 3, note="ours")
    n = json.loads((FX / "mealprep_v1.json").read_text())
    n["mealprep:recipe"]["steps"].append("Dust with icing sugar.")
    n["mealprep:ratings"]["entries"].append({"date": "2026-07-01", "multiplier": 1.0, "family": 2, "company": "no",
                                             "note": None, "rated_at": "2026-07-02T00:00:00+00:00"})
    data = json.dumps(n).encode()
    [it] = post(c, data).json()["items"]
    assert it["status"] == "duplicate" and it["match"]["by"] == "id" and it["can_update"] is True
    assert it["action"] == "skip"
    job = apply(c, data, {it["key"]: "update"})
    assert job["items"][0]["status"] == "updated" and job["items"][0]["recipe_id"] == rid
    r = db.get_recipe(conn, rid)
    assert r.steps[-1] == "Dust with icing sugar." and nrecipes(conn) == 1
    assert db.get_week(conn, date(2026, 10, 11))[0].rating.note == "ours"           # own plan + rating kept
    assert conn.execute("SELECT count(*) FROM imported_ratings").fetchone()[0] == 4  # 3 + the new one, no copies


def test_update_not_allowed_without_change(conn):
    c = client(conn)
    apply(c, "mealprep_v1.json")
    [it] = post(c, "mealprep_v1.json").json()["items"]
    job = apply(c, "mealprep_v1.json", {it["key"]: "update"})
    assert job["items"][0]["status"] == "failed" and importer.CANT_UPDATE in job["items"][0]["reasons"]


def test_stale_key_fails_that_item(conn):
    c = client(conn, ai=TidyAI())
    prev = post(c, "array_two.json").json()
    choices = {"0:deadbeef": "add", prev["items"][1]["key"]: "skip"}
    job = apply(c, "array_two.json", choices)
    assert [i["status"] for i in job["items"]] == ["failed", "skipped"]
    assert importer.FILE_CHANGED in job["items"][0]["reasons"] and nrecipes(conn) == 0


def test_bad_choices_422(conn):
    c = client(conn)
    r = c.post("/recipes/import", headers=H, files={"file": ("a.json", (FX / "single_minimal.json").read_bytes())},
               data={"dry_run": "false", "choices": "{\"0:x\": \"delete\"}"})
    assert r.status_code == 422
    r = c.post("/recipes/import", headers=H, files={"file": ("a.json", (FX / "single_minimal.json").read_bytes())},
               data={"dry_run": "false", "choices": "[1]"})
    assert r.status_code == 422


def test_imported_ratings_count_and_export_back(conn):
    c = client(conn, today=date(2026, 10, 14))
    job = apply(c, "mealprep_v1.json")
    rid = job["items"][0]["recipe_id"]
    assert job["items"][0]["ratings"] == 3
    d = c.get(f"/recipes/{rid}", headers=H).json()
    s = d["ratings"]
    assert (s["times_cooked"], s["times_rated"], s["avg_family"], s["last_family"], s["company"], s["imported_ratings"]) \
        == (3, 2, 4.5, 5, "yes", 2)
    assert s["notes"] == [{"note": "Less sugar next time.", "date": "2026-09-27", "rated_at": s["last_rated_at"]}]
    assert [(h["date"], h["rating"]["family"] if h["rating"] else None) for h in d["imported_history"]] == [
        ("2026-09-27", 5), ("2026-09-06", 4), ("2026-08-16", None)]
    assert d["history"] == []
    apply(c, (FX / "mealprep_v1.json").read_bytes() + b" ")       # a different file, same recipe: no new rows
    assert conn.execute("SELECT count(*) FROM imported_ratings").fetchone()[0] == 3
    # export from this library = the original export (apart from dateCreated and the imported count)
    out = c.get(f"/recipes/{rid}/export", headers=H).json()
    orig = json.loads((FX / "mealprep_v1.json").read_text())
    for n in (out, orig):
        n.pop("dateCreated")
        n["mealprep:ratings"]["summary"].pop("imported_ratings", None)
    assert out == orig


def test_favourites_include_imported_ratings(conn):
    c = client(conn, today=date(2026, 10, 14))
    plain = add(conn, title="Test Plain")
    eid = db.add_to_week(conn, date(2026, 10, 4), plain)
    db.update_entry(conn, eid, day=1)
    db.set_rating(conn, eid, 4)
    rid = apply(c, "mealprep_v1.json")["items"][0]["recipe_id"]           # avg 4.5 from another library
    order = [r["id"] for r in c.get("/recipes?sort=favourites", headers=H).json()]
    assert order == [rid, plain]


def test_one_bad_recipe_of_three(conn):
    nodes = [{"@type": "Recipe", "name": "Test One", "recipeIngredient": ["1 egg"]},
             {"@type": "Recipe", "recipeIngredient": ["1 egg"]},
             {"@type": "Recipe", "name": "Test Three", "recipeInstructions": "Stir."}]
    body = post(client(conn), json.dumps(nodes).encode()).json()
    assert [i["status"] for i in body["items"]] == ["new", "failed", "new"]
    assert body["items"][1]["reasons"] == ["No recipe name"] and body["items"][2]["ai_tidy"] is False


def test_mapper_crash_is_a_failed_item_not_a_500(conn, monkeypatch):
    real = jsonld.from_jsonld

    def boom(node):
        if node.get("name") == "Test Rice Bowl":
            raise KeyError("surprise")
        return real(node)
    monkeypatch.setattr(jsonld, "from_jsonld", boom)
    r = post(client(conn), "array_two.json")
    assert r.status_code == 200 and [i["status"] for i in r.json()["items"]] == ["new", "failed"]
    assert r.json()["items"][1]["reasons"] == [importer.COULDNT_READ]


def test_whole_file_crash_is_422(conn, monkeypatch):
    def boom(*a):
        raise RuntimeError("bug")
    monkeypatch.setattr(importer, "analyse", boom)
    assert post(client(conn), "array_two.json").status_code == 422


@pytest.mark.parametrize("data", [b"", b"hello", b"[]", b"<html><body>no recipe</body></html>",
                                  b"<html><script type='application/ld+json'>{\"@type\": \"Thing\"}</script></html>",
                                  b"PK\x03\x04zip", b"{" * 100])
def test_unreadable_files_422(conn, data):
    r = post(client(conn), data)
    assert r.status_code == 422 and r.json()["detail"]


def test_too_big_413(conn):
    from fastapi.testclient import TestClient
    from mealprep.api import create_app
    from mealprep.config import Settings
    import os
    s = Settings(dsn=os.environ.get("MEALPREP_TEST_DSN", "unused"), token="secret", provider="fake", import_max_mb=1)
    c = TestClient(create_app(s, provider=AI(), pcx=object(), conn=conn))
    r = post(c, b'{"@type": "Recipe", "name": "' + b"x" * (1024 * 1024) + b'"}')
    assert r.status_code == 413 and "max 1 MB" in r.json()["detail"]
    r = post(c, b"[" + b" " * (3 * 1024 * 1024) + b"]")          # declared size far over: refused before reading
    assert r.status_code == 413


def test_html_page_import(conn):
    c = client(conn, ai=TidyAI())
    body = post(c, "page.html").json()
    assert body["format"] == "html" and [i["title"] for i in body["items"]] == ["Test Page Pancakes"]


def test_javascript_url_dropped_with_reason(conn):
    c = client(conn, ai=TidyAI())
    job = apply(c, "bad_urls.json")
    first = job["items"][0]
    assert first["status"] == "added" and jsonld.DROPPED_LINK in first["reasons"]
    assert db.get_recipe(conn, first["recipe_id"]).source_url is None


def test_import_needs_auth_and_unknown_job_404(conn):
    c = client(conn)
    r = c.post("/recipes/import", files={"file": ("a.json", b"[]")})
    assert r.status_code == 401
    assert c.get("/imports/999", headers=H).status_code == 404


def test_interrupted_job_marked_failed(conn):
    c = client(conn)
    report, analysed = importer.preview(conn, (FX / "array_two.json").read_bytes())
    items, work = importer.plan(analysed, {})
    report.update(dry_run=False, items=items)
    jid = importer.create_job(conn, report, len(work), "x")
    importer.fail_interrupted(conn)
    job = c.get(f"/imports/{jid}", headers=H).json()
    assert job["status"] == "failed" and [i["status"] for i in job["items"]] == ["failed", "failed"]
    assert importer.INTERRUPTED in job["items"][0]["reasons"]
