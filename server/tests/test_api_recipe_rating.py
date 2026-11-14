"""Rating a recipe from the recipe itself (PUT/DELETE /recipes/{id}/rating): a planned night that day takes it, else
one rating per recipe and day in recipe_ratings; both count in the summary, history and export the same way."""
from datetime import date
from mealprep import db
from test_api import H, client
from test_api_ratings import TODAY, placed, setup


def rate(c, rid, **body):
    return c.put(f"/recipes/{rid}/rating", headers=H, json={"family": 4, **body})


def test_needs_auth_and_a_recipe(conn):
    c = client(conn, today=TODAY)
    assert c.put("/recipes/1/rating", json={"family": 4}).status_code == 401
    assert c.delete("/recipes/1/rating", params={"cooked_on": "2026-10-14"}).status_code == 401
    assert rate(c, 999).status_code == 404
    assert c.delete("/recipes/999/rating", headers=H, params={"cooked_on": "2026-10-14"}).status_code == 404


def test_validation(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    assert rate(c, rid, cooked_on="2026-10-15").status_code == 422          # tomorrow
    assert c.put(f"/recipes/{rid}/rating", headers=H, json={"family": 6}).status_code == 422
    assert rate(c, rid, company="sometimes").status_code == 422
    assert c.delete(f"/recipes/{rid}/rating", headers=H).status_code == 422  # cooked_on is required


def test_without_a_dinner_it_is_todays_rating_and_rerating_overwrites(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    r = rate(c, rid, family=3, note=" ok ")
    assert r.status_code == 200
    first = r.json()
    assert first["cooked_on"] == "2026-10-14" and first["entry_id"] is None and first["recipe_rating_id"]
    again = rate(c, rid, family=5, company="yes").json()
    assert again["recipe_rating_id"] == first["recipe_rating_id"]
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 1
    d = c.get(f"/recipes/{rid}", headers=H).json()
    assert d["ratings"]["times_cooked"] == 1 and d["ratings"]["times_rated"] == 1
    assert d["ratings"]["avg_family"] == 5 and d["ratings"]["company"] == "yes" and d["ratings"]["notes"] == []
    assert d["history"] == []
    assert [(e["date"], e["rating"]["family"]) for e in d["rated_history"]] == [("2026-10-14", 5)]
    # another day is another time cooked
    rate(c, rid, family=2, note="too dry", cooked_on="2026-10-01")
    d = c.get(f"/recipes/{rid}", headers=H).json()
    assert d["ratings"]["times_rated"] == 2 and d["ratings"]["avg_family"] == 3.5
    assert (d["ratings"]["notes"][0]["note"], d["ratings"]["notes"][0]["date"]) == ("too dry", "2026-10-01")
    assert [e["date"] for e in d["rated_history"]] == ["2026-10-14", "2026-10-01"]
    hist = conn.execute("SELECT plan_id, recipe_rating_id IS NOT NULL, week, day, action FROM rating_history "
                        "ORDER BY id").fetchall()
    assert hist[0] == (None, True, date(2026, 10, 12), 2, "set") and len(hist) == 3   # Wed 10-14: day 2


def test_a_dinner_waiting_for_a_rating_takes_it(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    tue = placed(conn, rid, day=1)                                          # 10-13 → pending
    assert [p["entry_id"] for p in c.get("/ratings/pending", headers=H).json()] == [tue]
    out = rate(c, rid, family=4, note="nice").json()
    assert out == {"cooked_on": "2026-10-13", "entry_id": tue, "recipe_rating_id": None}
    assert c.get("/ratings/pending", headers=H).json() == []
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 0
    [e] = c.get("/weeks/2026-10-12", headers=H).json()
    assert e["rating"]["family"] == 4 and e["rating"]["note"] == "nice"
    d = c.get(f"/recipes/{rid}", headers=H).json()
    assert d["ratings"]["times_cooked"] == 1 and d["ratings"]["times_rated"] == 1
    # the next one (nothing waiting) is today's
    assert rate(c, rid, family=2).json()["recipe_rating_id"]


def test_the_dinner_on_the_chosen_day_takes_it_rated_or_not(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    other = setup(conn, "Soup")
    wed = placed(conn, rid, day=2)                                          # tonight, 10-14
    placed(conn, other, day=2)
    assert rate(c, rid, cooked_on="2026-10-14").json()["entry_id"] == wed
    assert rate(c, rid, family=1, cooked_on="2026-10-14").json()["entry_id"] == wed   # re-rate the same dinner
    [e] = [x for x in c.get("/weeks/2026-10-12", headers=H).json() if x["id"] == wed]
    assert e["rating"]["family"] == 1
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 0
    # a dinner of another recipe that night isn't touched
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 1


def test_a_night_rated_from_the_recipe_no_longer_asks(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    rate(c, rid, cooked_on="2026-10-13")             # rated from the recipe first …
    placed(conn, rid, day=1)                         # … and that night was put on the plan afterwards
    assert c.get("/ratings/pending", headers=H).json() == []


def test_delete(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    tue = placed(conn, rid, day=1)
    rate(c, rid, cooked_on="2026-10-13")             # the dinner's
    rate(c, rid, cooked_on="2026-10-14")             # the recipe's own
    assert c.delete(f"/recipes/{rid}/rating", headers=H, params={"cooked_on": "2026-10-14"}).status_code == 204
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 0
    assert c.delete(f"/recipes/{rid}/rating", headers=H, params={"cooked_on": "2026-10-13"}).status_code == 204
    [e] = c.get("/weeks/2026-10-12", headers=H).json()
    assert e["id"] == tue and e["rating"] is None
    assert c.delete(f"/recipes/{rid}/rating", headers=H, params={"cooked_on": "2026-09-01"}).status_code == 204
    actions = [a for (a,) in conn.execute("SELECT action FROM rating_history ORDER BY id")]
    assert actions == ["set", "set", "delete", "delete"]
    d = c.get(f"/recipes/{rid}", headers=H).json()
    assert d["ratings"]["times_rated"] == 0 and d["rated_history"] == []


def test_list_summary_and_export_include_them(conn):
    c = client(conn, today=TODAY)
    rid = setup(conn)
    rate(c, rid, family=5, note="a hit", company="yes")
    [r] = c.get("/recipes", headers=H).json()
    assert r["ratings"]["avg_family"] == 5 and r["ratings"]["times_cooked"] == 1
    entries = db.rating_entries(conn, TODAY, [rid])[rid]
    assert [(e["date"], e["family"], e["note"], e["multiplier"]) for e in entries] == [("2026-10-14", 5, "a hit", 1.0)]


def test_deleting_a_recipe_takes_its_ratings(conn):
    rid = setup(conn)
    db.rate_recipe(conn, rid, 4, None, None, TODAY)
    conn.execute("DELETE FROM recipes WHERE id=%s", (rid,))
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 0
