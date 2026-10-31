"""Per-person ratings: each member rates a time cooked for themselves (a planner can rate for a child profile), the
shared token keeps rating as "Family" (as before profiles), and the summaries average everyone's ratings with a row
per person. Invented household: Ada (owner/planner, the shared token's member), Ben (owner/planner), Cleo
(contributor), Dot (child profile)."""
import pytest
from fastapi.testclient import TestClient

from mealprep import db, households
from mealprep.api import create_app
from mealprep.config import Settings
from test_api_ratings import TODAY, placed, setup
from test_rbac import AI, NoPcx, member_session, new_household

H = {"Authorization": "Bearer secret"}           # the shared token (the phones)
PLAN_KEYS = {"id", "week", "recipe_id", "day", "multiplier", "rating", "title"}          # Android's PlanEntry
SUMMARY_KEYS = {"times_cooked", "times_rated", "avg_family", "last_family", "last_rated_at", "company", "notes",
                "imported_ratings"}                                                       # Android's RatingSummary


@pytest.fixture
def fam(conn):
    hid = db.first_household(conn)
    db.unscope(conn)
    ada_h, ada = member_session(conn, hid, ["owner", "approver", "planner", "contributor"], name="Ada")
    ben_h, ben = member_session(conn, hid, ["owner", "planner", "contributor"], name="Ben")
    cleo_h, cleo = member_session(conn, hid, ["contributor"], name="Cleo")
    dot = households.add_member(conn, hid, "Dot", "child", birth_year=2016)
    conn.execute("UPDATE households SET legacy_member_id=%s WHERE id=%s", (ada, hid))
    db.scope(conn, hid)
    c = TestClient(create_app(Settings(dsn="unused", token="secret", provider="fake"), provider=AI(), pcx=NoPcx(),
                              conn=conn, today=lambda: TODAY))
    return {"c": c, "hid": hid, "ada": ada, "ben": ben, "cleo": cleo, "dot": dot,
            "ada_h": ada_h, "ben_h": ben_h, "cleo_h": cleo_h}


def week(f, h):
    return f["c"].get("/weeks/2026-10-11", headers=h).json()


def rate(f, eid, h, **body):
    return f["c"].put(f"/plan/{eid}/rating", headers=h, json={"family": 4, **body})


def test_each_member_rates_for_themselves_and_the_shared_token_rates_as_family(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    assert rate(f, eid, H, family=3, note="phone").status_code == 204          # Android, unchanged
    assert rate(f, eid, f["ben_h"], family=5, company="yes", note="more heat").status_code == 204
    assert rate(f, eid, f["cleo_h"], family=4, company="no").status_code == 204
    assert rate(f, eid, f["ben_h"], family=4, company="yes", note="more heat").status_code == 204   # overwrite own
    rows = conn.execute("SELECT member_id, entered_by, family FROM ratings ORDER BY id").fetchall()
    assert rows == [(None, None, 3), (f["ben"], f["ben"], 4), (f["cleo"], f["cleo"], 4)]
    [e] = week(f, f["ben_h"])
    assert [(r["name"], r["kind"], r["family"]) for r in e["ratings"]] == \
        [("Ben", "account", 4), ("Cleo", "account", 4), ("Family", "family", 3)]   # household order, Family last
    assert e["rating_avg"] == 3.7 and e["rating_count"] == 3
    assert e["my_rating"]["family"] == 4 and e["rating"]["family"] == 4 and e["my_rating"]["note"] == "more heat"
    # the shared token reads its own (Family) rating, in the shape Android knows
    [e] = week(f, H)
    assert PLAN_KEYS <= set(e) and e["rating"]["family"] == 3 and e["rating"]["note"] == "phone"
    assert e["my_rating"]["family"] == 3
    # Ada hasn't rated: no own rating; `rating` (older clients) falls back to the newest
    [e] = week(f, f["ada_h"])
    assert e["my_rating"] is None and e["rating"]["family"] == 4 and e["rating_count"] == 3
    hist = conn.execute("SELECT member_id, action FROM rating_history ORDER BY id").fetchall()
    assert hist == [(None, "set"), (f["ben"], "set"), (f["cleo"], "set"), (f["ben"], "set")]


def test_the_shared_token_sees_a_night_rated_on_the_web_as_rated(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, f["ben_h"], family=5)
    [e] = week(f, H)
    assert e["rating"]["family"] == 5 and e["my_rating"] is None   # no reminder on the phone; editing adds Family
    assert f["c"].get("/ratings/pending", headers=H).json() == []  # nobody-rated question: answered


def test_removing_takes_only_your_own(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, H)
    rate(f, eid, f["ben_h"])
    rate(f, eid, f["cleo_h"])
    assert f["c"].delete(f"/plan/{eid}/rating", headers=f["cleo_h"]).status_code == 204
    assert {r for (r,) in conn.execute("SELECT member_id FROM ratings")} == {None, f["ben"]}
    assert f["c"].delete(f"/plan/{eid}/rating", headers=H).status_code == 204       # the phone removes Family's
    assert [r for (r,) in conn.execute("SELECT member_id FROM ratings")] == [f["ben"]]
    assert f["c"].delete(f"/plan/{eid}/rating", headers=H).status_code == 204       # idempotent


def test_a_planner_rates_for_a_child_profile(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    assert rate(f, eid, f["ben_h"], family=5, note="ate it all", member_id=f["dot"]).status_code == 204
    [e] = week(f, f["ben_h"])
    [dot] = e["ratings"]
    assert (dot["name"], dot["kind"], dot["entered_by"], dot["entered_by_name"]) == ("Dot", "child", f["ben"], "Ben")
    assert e["my_rating"] is None                     # Ben's own is still to do
    assert rate(f, eid, H, family=3, member_id=f["dot"]).status_code == 204      # the phone too (Ada is a planner)
    assert conn.execute("SELECT member_id, entered_by, family FROM ratings").fetchall() == [(f["dot"], None, 3)]
    # a contributor can't, nobody rates for another account, unknown or other-household members are 404
    assert rate(f, eid, f["cleo_h"], member_id=f["dot"]).status_code == 403
    assert rate(f, eid, f["ben_h"], member_id=f["cleo"]).status_code == 403
    assert rate(f, eid, f["ben_h"], member_id=9999).status_code == 404
    db.unscope(conn)
    other = new_household(conn)
    stranger = households.add_member(conn, other, "Zed", "child", birth_year=2017)
    db.scope(conn, f["hid"])
    assert rate(f, eid, f["ben_h"], member_id=stranger).status_code == 404
    assert f["c"].delete(f"/plan/{eid}/rating", headers=f["cleo_h"], params={"member_id": f["dot"]}).status_code == 403
    assert f["c"].delete(f"/plan/{eid}/rating", headers=f["ben_h"], params={"member_id": f["dot"]}).status_code == 204
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 0


def test_pending_is_per_member(conn, fam):
    f, rid = fam, setup(conn)
    mon, tue = placed(conn, rid, day=1), placed(conn, rid, day=2)
    rate(f, tue, f["cleo_h"])
    pend = lambda h: [p["entry_id"] for p in f["c"].get("/ratings/pending", headers=h).json()]   # noqa: E731
    assert pend(f["ben_h"]) == [tue, mon]             # Cleo's rating isn't Ben's
    assert pend(f["cleo_h"]) == [mon]
    assert pend(H) == [mon]                           # the phones: nights nobody rated
    rate(f, mon, H)                                   # a Family rating doesn't answer for Ben either
    assert pend(f["ben_h"]) == [tue, mon] and pend(H) == []


def test_rating_from_the_recipe_is_per_person_too(conn, fam):
    f, rid = fam, setup(conn)
    c = f["c"]
    put = lambda h, **b: c.put(f"/recipes/{rid}/rating", headers=h, json={"family": 4, **b})   # noqa: E731
    a = put(f["ben_h"], family=5, note="great").json()
    b = put(f["cleo_h"], family=2, company="no").json()
    assert a["cooked_on"] == b["cooked_on"] == "2026-10-14" and a["recipe_rating_id"] != b["recipe_rating_id"]
    put(f["ben_h"], family=5, company="yes")          # Ben again, same day: overwrites his
    put(f["ben_h"], family=3, member_id=f["dot"], cooked_on="2026-10-14")
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 3
    d = c.get(f"/recipes/{rid}", headers=f["ben_h"]).json()
    [day] = d["rated_history"]
    assert day["date"] == "2026-10-14" and day["my_rating"]["family"] == 5 and day["rating"]["family"] == 5
    assert [(r["name"], r["family"]) for r in day["ratings"]] == [("Ben", 5), ("Cleo", 2), ("Dot", 3)]
    s = d["ratings"]
    assert (s["times_cooked"], s["times_rated"], s["rating_count"], s["avg_family"]) == (1, 1, 3, 3.3)
    # a dinner of it waiting for Cleo's rating takes hers (not Ben's, who already rated that night)
    tue = placed(conn, rid, day=2)
    rate(f, tue, f["ben_h"])
    out = put(f["cleo_h"], family=3)
    assert out.json()["entry_id"] == tue
    assert put(f["ben_h"]).json()["entry_id"] is None          # Ben has nothing waiting: today
    # removing a day's rating removes only yours
    assert c.delete(f"/recipes/{rid}/rating", headers=f["cleo_h"], params={"cooked_on": "2026-10-14"}).status_code == 204
    assert {m for (m,) in conn.execute("SELECT member_id FROM recipe_ratings")} == {f["ben"], f["dot"]}
    assert c.delete(f"/recipes/{rid}/rating", headers=f["ben_h"],
                    params={"cooked_on": "2026-10-14", "member_id": f["dot"]}).status_code == 204
    assert [m for (m,) in conn.execute("SELECT member_id FROM recipe_ratings")] == [f["ben"]]


def test_summary_people_average_and_company(conn, fam):
    f, rid = fam, setup(conn)
    mon, tue = placed(conn, rid, day=1), placed(conn, rid, day=2)
    rate(f, mon, H, family=4, company="yes", note="old phone rating")
    rate(f, tue, H, family=2)
    rate(f, tue, f["ben_h"], family=5, company="no", note="too spicy for me")
    rate(f, mon, f["cleo_h"], family=3, company="yes")
    rate(f, tue, f["ben_h"], family=5, member_id=f["dot"], note="loved the rice")
    s = f["c"].get(f"/recipes/{rid}", headers=f["cleo_h"]).json()["ratings"]
    assert SUMMARY_KEYS <= set(s)
    # every person's rating of every time cooked counts once; Family counts as one rater
    assert (s["times_cooked"], s["times_rated"], s["rating_count"], s["avg_family"]) == (2, 2, 5, 3.8)
    people = {p["name"]: p for p in s["people"]}
    assert list(people) == ["Ben", "Cleo", "Dot", "Family"]
    assert (people["Ben"]["family"], people["Ben"]["note"], people["Ben"]["date"]) == (5, "too spicy for me", "2026-10-13")
    assert (people["Family"]["avg"], people["Family"]["count"], people["Family"]["family"]) == (3.0, 2, 2)
    # latest answer per rater: Family yes (its Tuesday rating has none), Ben no, Cleo yes → yes
    assert s["company_votes"] == {"yes": 2, "maybe": 0, "no": 1}
    assert s["company"] == "yes" and s["good_for_company"] is True
    assert {(n["name"], n["note"]) for n in s["notes"]} == {("Dot", "loved the rice"), ("Ben", "too spicy for me"),
                                                            ("Family", "old phone rating")}
    # a tie is maybe, not good for company
    rate(f, mon, f["cleo_h"], family=3, company="no")
    s = f["c"].get(f"/recipes/{rid}", headers=H).json()["ratings"]
    assert s["company"] == "no" and s["good_for_company"] is False
    rate(f, mon, f["cleo_h"], family=3, company="maybe")
    s = f["c"].get(f"/recipes/{rid}", headers=H).json()["ratings"]
    assert s["company_votes"] == {"yes": 1, "maybe": 1, "no": 1} and s["company"] == "maybe"


def test_a_removed_members_ratings_stay(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, f["cleo_h"], family=2)
    rate(f, eid, f["ben_h"], family=4)
    db.unscope(conn)
    households.remove_member(conn, f["hid"], f["cleo"])
    db.scope(conn, f["hid"])
    [e] = week(f, f["ben_h"])
    assert [(r["name"], r["kind"], r["family"]) for r in e["ratings"]] == [("Ben", "account", 4),
                                                                          ("Former member", "former", 2)]
    assert e["rating_avg"] == 3.0


def test_export_has_one_entry_per_time_cooked(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, f["ben_h"], family=5, company="yes", note="more heat")
    rate(f, eid, f["cleo_h"], family=4, company="yes", note="mild is fine")
    rate(f, eid, H, family=2, company="no")
    [e] = db.rating_entries(conn, TODAY, [rid])[rid]
    assert (e["date"], e["family"], e["company"]) == ("2026-10-13", 4, "yes")     # 11/3 = 3.67 → 4
    assert e["note"] == "Ben: more heat · Cleo: mild is fine"
    out = f["c"].get(f"/recipes/{rid}/export", headers=H).json()
    assert len(out["mealprep:ratings"]["entries"]) == 1


def test_rerunning_the_schema_keeps_per_person_ratings(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, H)
    rate(f, eid, f["ben_h"])
    db.unscope(conn)
    db.migrate(conn)
    db.scope(conn, f["hid"])
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 2
    assert [r["name"] for r in week(f, H)[0]["ratings"]] == ["Ben", "Family"]
