from datetime import date
from mealprep import db
from mealprep.models import Recipe
from test_api import H, client

TODAY = date(2026, 10, 14)   # Wednesday of week 2026-10-11


def setup(conn, title="Chili"):
    rid = db.save_recipe(conn, Recipe(title=title, source="photo", ingredients=[], steps=[]))
    return rid


def placed(conn, rid, week="2026-10-11", day=2):
    eid = db.add_to_week(conn, date.fromisoformat(week), rid)
    if day is not None:
        db.update_entry(conn, eid, day=day)
    return eid


def test_rating_endpoints_need_auth(conn):
    c = client(conn, today=TODAY)
    assert c.put("/plan/1/rating", json={"family": 4}).status_code == 401
    assert c.delete("/plan/1/rating").status_code == 401
    assert c.get("/ratings/pending").status_code == 401
    assert c.get("/recipes/1").status_code == 401


def test_put_rating_then_rerate_overwrites(conn):
    c = client(conn, today=TODAY)
    eid = placed(conn, setup(conn))
    assert c.put(f"/plan/{eid}/rating", headers=H, json={"family": 3}).status_code == 204
    r = c.put(f"/plan/{eid}/rating", headers=H, json={"family": 5, "company": "yes", "note": "  less salt "})
    assert r.status_code == 204
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert {k: e["rating"][k] for k in ("family", "company", "note")} == {"family": 5, "company": "yes", "note": "less salt"}
    assert e["rating"]["rated_at"]
    assert conn.execute("SELECT count(*) FROM rating_history WHERE action='set'").fetchone()[0] == 2


def test_blank_note_and_omitted_company_stored_as_null(conn):
    c = client(conn, today=TODAY)
    eid = placed(conn, setup(conn))
    c.put(f"/plan/{eid}/rating", headers=H, json={"family": 4, "company": "maybe", "note": "x"})
    c.put(f"/plan/{eid}/rating", headers=H, json={"family": 4, "note": "   "})
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["rating"]["company"] is None and e["rating"]["note"] is None


def test_week_entry_without_rating_is_null(conn):
    c = client(conn, today=TODAY)
    placed(conn, setup(conn))
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["rating"] is None


def test_put_rating_unknown_entry_404(conn):
    assert client(conn).put("/plan/999/rating", headers=H, json={"family": 4}).status_code == 404


def test_put_rating_invalid_422(conn):
    c = client(conn, today=TODAY)
    eid = placed(conn, setup(conn))
    for body in [{}, {"family": 0}, {"family": 6}, {"family": "great"}, {"family": 4.5}, {"family": None},
                 {"family": 4, "company": "sure"}, {"family": 4, "company": "Yes"}, {"family": 4, "note": 5}]:
        assert c.put(f"/plan/{eid}/rating", headers=H, json=body).status_code == 422, body
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 0


def test_delete_rating(conn):
    c = client(conn, today=TODAY)
    eid = placed(conn, setup(conn))
    c.put(f"/plan/{eid}/rating", headers=H, json={"family": 2})
    assert c.delete(f"/plan/{eid}/rating", headers=H).status_code == 204
    assert c.get("/weeks/2026-10-11", headers=H).json()[0]["rating"] is None
    assert c.delete(f"/plan/{eid}/rating", headers=H).status_code == 204     # idempotent
    assert c.delete("/plan/999/rating", headers=H).status_code == 404


def test_pending(conn):
    rid = setup(conn)
    tue = placed(conn, rid, day=2)                      # 10-13 yesterday → pending
    placed(conn, rid, day=3)                            # today → not yet
    placed(conn, rid, day=6)                            # future
    placed(conn, rid, day=None)                         # unplaced → never
    rated = placed(conn, rid, day=1)
    db.set_rating(conn, rated, 4)
    placed(conn, rid, week="2026-09-27", day=2)         # 09-29 = 15 days ago → too old
    c = client(conn, today=TODAY)
    pend = c.get("/ratings/pending", headers=H).json()  # default = server today
    assert pend == [{"entry_id": tue, "week": "2026-10-11", "day": 2, "date": "2026-10-13",
                     "recipe_id": rid, "title": "Chili", "multiplier": 1.0}]
    # explicit today: the next morning, today's (Wed) entry is pending too
    assert [p["date"] for p in c.get("/ratings/pending", headers=H, params={"today": "2026-10-15"}).json()] == \
        ["2026-10-14", "2026-10-13"]
    assert c.get("/ratings/pending", headers=H, params={"today": "nope"}).status_code == 422
    # rating it clears it from the prompt list
    c.put(f"/plan/{tue}/rating", headers=H, json={"family": 4})
    assert c.get("/ratings/pending", headers=H).json() == []


def test_recipes_include_rating_summary(conn):
    chili, soup = setup(conn, "Chili"), setup(conn, "Soup")
    c = client(conn, today=TODAY)
    a, b = placed(conn, chili, day=1), placed(conn, chili, day=2)
    placed(conn, chili, day=5)                          # future, unrated
    c.put(f"/plan/{a}/rating", headers=H, json={"family": 3, "company": "no", "note": "bland"})
    c.put(f"/plan/{b}/rating", headers=H, json={"family": 4})
    rs = {r["id"]: r for r in c.get("/recipes", headers=H).json()}
    s = rs[chili]["ratings"]
    assert (s["times_cooked"], s["times_rated"], s["avg_family"], s["last_family"], s["company"]) == (2, 2, 3.5, 4, "no")
    assert [(n["note"], n["date"]) for n in s["notes"]] == [("bland", "2026-10-12")] and n_has_rated_at(s)
    assert rs[soup]["ratings"] == {"times_cooked": 0, "times_rated": 0, "avg_family": None, "last_family": None,
                                   "last_rated_at": None, "company": None, "notes": [],
                                   "imported_ratings": 0}
    assert [r["id"] for r in c.get("/recipes", headers=H).json()] == [soup, chili]   # default sort unchanged


def n_has_rated_at(s):
    return all(n["rated_at"] for n in s["notes"])


def test_recipe_detail(conn):
    rid = setup(conn)
    c = client(conn, today=TODAY)
    a = placed(conn, rid, week="2026-10-04", day=4)
    b = placed(conn, rid, day=2)
    db.update_entry(conn, b, multiplier=1.5)
    c.put(f"/plan/{a}/rating", headers=H, json={"family": 5, "company": "yes"})
    r = c.get(f"/recipes/{rid}", headers=H).json()
    assert r["title"] == "Chili" and r["ratings"]["times_cooked"] == 2 and r["ratings"]["avg_family"] == 5.0
    assert [(h["entry_id"], h["week"], h["day"], h["date"], h["multiplier"]) for h in r["history"]] == \
        [(b, "2026-10-11", 2, "2026-10-13", 1.5), (a, "2026-10-04", 4, "2026-10-08", 1.0)]
    assert r["history"][0]["rating"] is None and r["history"][1]["rating"]["family"] == 5
    assert c.get("/recipes/999", headers=H).status_code == 404


def test_recipes_sort_favourites(conn):
    c = client(conn, today=TODAY)
    low, high, unrated, high_more, cooked_unrated = (setup(conn, t) for t in ("low", "high", "unrated", "high2", "cooked"))
    for rid, fams in [(low, [2]), (high, [5]), (high_more, [5, 5])]:
        for i, f in enumerate(fams):
            db.set_rating(conn, placed(conn, rid, week="2026-10-04", day=i), f)
    placed(conn, cooked_unrated, week="2026-10-04", day=3)          # cooked, never rated
    ids = [r["id"] for r in c.get("/recipes", headers=H, params={"sort": "favourites"}).json()]
    assert ids == [high_more, high, low, cooked_unrated, unrated]   # avg desc, then times_cooked; unrated last
    assert c.get("/recipes", headers=H, params={"sort": "bogus"}).status_code == 422


def test_resharing_existing_recipe_shows_current_rating(conn, monkeypatch):
    import mealprep.api as api_mod
    from test_api import FIX
    monkeypatch.setattr(api_mod, "fetch_nyt", lambda url: FIX.read_text())
    c = client(conn, today=TODAY)
    text = {"text": "https://cooking.nytimes.com/recipes/1015819-x", "week": "2026-10-04"}
    first = c.post("/recipes/share", headers=H, json=text).json()
    assert first["existing"] is False and first["recipe"]["ratings"]["times_rated"] == 0
    eid = first["entry"]["id"]
    c.patch(f"/plan/{eid}", headers=H, json={"day": 1})
    c.put(f"/plan/{eid}/rating", headers=H, json={"family": 5, "company": "maybe", "note": "kids loved it"})
    again = c.post("/recipes/share", headers=H, json={**text, "week": "2026-10-18"}).json()
    assert again["existing"] is True
    s = again["recipe"]["ratings"]
    assert s["last_family"] == 5 and s["company"] == "maybe" and s["notes"][0]["note"] == "kids loved it"


def test_recipes_list_the_weeks_they_are_planned_in_from_this_week_on(conn):
    rid, other = setup(conn, "Chili"), setup(conn, "Soup")
    c = client(conn, today=TODAY)
    placed(conn, rid, week="2026-10-04", day=1)                      # last week: history only
    placed(conn, rid, week="2026-10-18", day=None)                   # in the tray still counts
    placed(conn, rid, day=2)
    placed(conn, rid, day=4)                                         # twice in one week: listed once
    rs = {r["id"]: r for r in c.get("/recipes", headers=H).json()}
    assert rs[rid]["planned_weeks"] == ["2026-10-11", "2026-10-18"] and rs[other]["planned_weeks"] == []
    assert c.get(f"/recipes/{rid}", headers=H).json()["planned_weeks"] == ["2026-10-11", "2026-10-18"]
