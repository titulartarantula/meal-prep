"""Per-person ratings: each member rates a time cooked for themselves (a planner can rate for a child profile), the
shared token (the phones) rates as the household's legacy member, the summaries average everyone's ratings with a row
per person, and Good for company is one household verdict per recipe that Owners set. Invented household: Ada
(owner/planner, the shared token's member), Ben (owner/planner), Cleo (contributor), Dot (child profile)."""
import pytest
from fastapi.testclient import TestClient

from mealprep import db, households, legacy_ratings
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


def test_each_member_rates_for_themselves_and_the_shared_token_rates_as_the_legacy_member(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    assert rate(f, eid, H, family=3, note="phone").status_code == 204          # Android, unchanged
    assert rate(f, eid, f["ben_h"], family=5, note="more heat").status_code == 204
    assert rate(f, eid, f["cleo_h"], family=4).status_code == 204
    assert rate(f, eid, f["ben_h"], family=4, note="more heat").status_code == 204   # overwrite own
    rows = conn.execute("SELECT member_id, entered_by, family FROM ratings ORDER BY id").fetchall()
    assert rows == [(f["ada"], f["ada"], 3), (f["ben"], f["ben"], 4), (f["cleo"], f["cleo"], 4)]
    [e] = week(f, f["ben_h"])
    assert [(r["name"], r["kind"], r["family"]) for r in e["ratings"]] == \
        [("Ada", "account", 3), ("Ben", "account", 4), ("Cleo", "account", 4)]   # household order, no Family
    assert e["rating_avg"] == 3.7 and e["rating_count"] == 3
    assert e["my_rating"]["family"] == 4 and e["rating"]["family"] == 4 and e["my_rating"]["note"] == "more heat"
    # the shared token reads Ada's rating, in the shape Android knows
    [e] = week(f, H)
    assert PLAN_KEYS <= set(e) and e["rating"]["family"] == 3 and e["rating"]["note"] == "phone"
    assert e["my_rating"] == e["rating"]
    # Ada on the web edits the same rating the phone made: no duplicate
    assert rate(f, eid, f["ada_h"], family=5, note="phone").status_code == 204
    assert week(f, H)[0]["rating"]["family"] == 5
    assert conn.execute("SELECT count(*) FROM ratings WHERE member_id=%s", (f["ada"],)).fetchone()[0] == 1
    hist = conn.execute("SELECT member_id, action FROM rating_history ORDER BY id").fetchall()
    assert hist == [(f["ada"], "set"), (f["ben"], "set"), (f["cleo"], "set"), (f["ben"], "set"), (f["ada"], "set")]


def test_the_phone_only_counts_the_legacy_members_rating(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, f["ben_h"], family=5)
    [e] = week(f, H)
    assert e["rating"] is None and e["my_rating"] is None and e["rating_count"] == 1   # Ben's isn't Ada's answer
    assert [p["entry_id"] for p in f["c"].get("/ratings/pending", headers=H).json()] == [eid]
    rate(f, eid, H, family=3)                          # the phone's rating is Ada's, beside Ben's
    assert f["c"].get("/ratings/pending", headers=H).json() == []
    assert week(f, H)[0]["rating"]["family"] == 3
    assert [p["entry_id"] for p in f["c"].get("/ratings/pending", headers=f["cleo_h"]).json()] == [eid]


def test_removing_takes_only_your_own(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, H)
    rate(f, eid, f["ben_h"])
    rate(f, eid, f["cleo_h"])
    assert f["c"].delete(f"/plan/{eid}/rating", headers=f["cleo_h"]).status_code == 204
    assert {r for (r,) in conn.execute("SELECT member_id FROM ratings")} == {f["ada"], f["ben"]}
    assert f["c"].delete(f"/plan/{eid}/rating", headers=H).status_code == 204       # the phone removes Ada's
    assert [r for (r,) in conn.execute("SELECT member_id FROM ratings")] == [f["ben"]]
    assert f["c"].delete(f"/plan/{eid}/rating", headers=H).status_code == 204       # idempotent


def test_a_planner_rates_for_a_child_profile(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    assert rate(f, eid, f["ben_h"], family=5, note="ate it all", member_id=f["dot"]).status_code == 204
    [e] = week(f, f["ben_h"])
    [dot] = e["ratings"]
    assert (dot["name"], dot["kind"], dot["entered_by"], dot["entered_by_name"]) == ("Dot", "child", f["ben"], "Ben")
    assert "company" not in dot
    assert e["my_rating"] is None                     # Ben's own is still to do
    assert rate(f, eid, H, family=3, member_id=f["dot"]).status_code == 204      # the phone too (Ada is a planner)
    assert conn.execute("SELECT member_id, entered_by, family FROM ratings").fetchall() == [(f["dot"], f["ada"], 3)]
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
    assert pend(H) == pend(f["ada_h"]) == [tue, mon]  # the phones ask for Ada's ratings
    rate(f, mon, H)
    assert pend(f["ada_h"]) == [tue] and pend(f["ben_h"]) == [tue, mon]


def test_rating_from_the_recipe_is_per_person_too(conn, fam):
    f, rid = fam, setup(conn)
    c = f["c"]
    put = lambda h, **b: c.put(f"/recipes/{rid}/rating", headers=h, json={"family": 4, **b})   # noqa: E731
    a = put(f["ben_h"], family=5, note="great").json()
    b = put(f["cleo_h"], family=2).json()
    assert a["cooked_on"] == b["cooked_on"] == "2026-10-14" and a["recipe_rating_id"] != b["recipe_rating_id"]
    put(f["ben_h"], family=5)                         # Ben again, same day: overwrites his
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


def test_summary_people_and_average(conn, fam):
    f, rid = fam, setup(conn)
    mon, tue = placed(conn, rid, day=1), placed(conn, rid, day=2)
    rate(f, mon, H, family=4, note="old phone rating")
    rate(f, tue, H, family=2)
    rate(f, tue, f["ben_h"], family=5, note="too spicy for me")
    rate(f, mon, f["cleo_h"], family=3)
    rate(f, tue, f["ben_h"], family=5, member_id=f["dot"], note="loved the rice")
    s = f["c"].get(f"/recipes/{rid}", headers=f["cleo_h"]).json()["ratings"]
    assert SUMMARY_KEYS <= set(s)
    # every person's rating of every time cooked counts once
    assert (s["times_cooked"], s["times_rated"], s["rating_count"], s["avg_family"]) == (2, 2, 5, 3.8)
    people = {p["name"]: p for p in s["people"]}
    assert list(people) == ["Ada", "Ben", "Cleo", "Dot"]
    assert (people["Ben"]["family"], people["Ben"]["note"], people["Ben"]["date"]) == (5, "too spicy for me", "2026-10-13")
    assert (people["Ada"]["avg"], people["Ada"]["count"], people["Ada"]["family"]) == (3.0, 2, 2)
    assert {(n["name"], n["note"]) for n in s["notes"]} == {("Dot", "loved the rice"), ("Ben", "too spicy for me"),
                                                            ("Ada", "old phone rating")}
    assert s["company"] is None and s["good_for_company"] is False and "company_votes" not in s


def test_good_for_company_is_one_household_verdict_set_by_owners(conn, fam):
    f, rid = fam, setup(conn)
    c = f["c"]
    company = lambda h, v: c.put(f"/recipes/{rid}/company", headers=h, json={"company": v})   # noqa: E731
    summary = lambda: c.get(f"/recipes/{rid}", headers=f["cleo_h"]).json()["ratings"]          # noqa: E731
    out = company(f["ben_h"], "yes")
    assert out.status_code == 200 and out.json()["company"] == "yes" and out.json()["company_set_by_name"] == "Ben"
    s = summary()
    assert (s["company"], s["good_for_company"], s["company_set_by"]) == ("yes", True, f["ben"])
    assert company(f["cleo_h"], "no").status_code == 403           # a contributor can't
    assert company(f["ben_h"], "sometimes").status_code == 422
    assert company(f["ben_h"], "maybe").status_code == 422          # the verdict is yes / no (or not decided)
    assert c.put("/recipes/9999/company", headers=f["ben_h"], json={"company": "yes"}).status_code == 404
    assert [r["id"] for r in c.get("/recipes", headers=f["cleo_h"]).json() if r["ratings"]["good_for_company"]] == [rid]
    # a rating's company answer: a non-owner's is ignored; an owner's (and the phone's: Ada) sets the verdict
    eid = placed(conn, rid, day=2)
    rate(f, eid, f["cleo_h"], family=2, company="no")
    assert summary()["company"] == "yes"
    rate(f, eid, H, family=4, company="no")
    s = summary()
    assert (s["company"], s["company_set_by_name"]) == ("no", "Ada")
    rate(f, eid, H, family=4, company="maybe")                      # an older phone's Maybe: verdict unchanged
    assert summary()["company"] == "no"
    # everyone's `rating` carries the verdict (the phone shows and edits it); the person rows don't have it
    [e] = week(f, H)
    assert e["rating"]["company"] == "no" and all("company" not in r for r in e["ratings"])
    assert week(f, f["cleo_h"])[0]["my_rating"]["company"] == "no"
    rate(f, eid, H, family=4)                                       # Android omits a null company: unchanged
    assert summary()["company"] == "no"
    assert company(f["ada_h"], None).json()["company"] is None      # null clears
    assert summary()["company"] is None and summary()["company_set_by"] is None
    assert conn.execute("SELECT count(*) FROM ratings WHERE company IS NOT NULL").fetchone()[0] == 0
    db.unscope(conn)
    log = conn.execute("SELECT member_id, detail FROM audit_log WHERE action='recipe.company' ORDER BY id").fetchall()
    db.scope(conn, f["hid"])
    assert log == [(f["ben"], {"from": None, "to": "yes"}), (f["ada"], {"from": "yes", "to": "no"}),
                   (f["ada"], {"from": "no", "to": None})]


def test_old_family_rows_still_show_as_family(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    conn.execute("INSERT INTO ratings(plan_id, member_id, family) VALUES(%s, NULL, 4)", (eid,))
    rate(f, eid, f["ben_h"], family=2)
    [e] = week(f, f["ben_h"])
    assert [(r["name"], r["kind"]) for r in e["ratings"]] == [("Ben", "account"), ("Family", "family")]
    assert week(f, H)[0]["rating"] is None            # not Ada's
    s = f["c"].get(f"/recipes/{rid}", headers=H).json()["ratings"]
    assert [p["name"] for p in s["people"]] == ["Ben", "Family"] and s["avg_family"] == 3.0


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
    rate(f, eid, f["cleo_h"], family=4, note="mild is fine")
    rate(f, eid, H, family=2)
    [e] = db.rating_entries(conn, TODAY, [rid])[rid]
    assert (e["date"], e["family"]) == ("2026-10-13", 4)     # 11/3 = 3.67 → 4
    assert e["note"] == "Ben: more heat · Cleo: mild is fine"
    assert e["company"] is None                              # never a per-time-cooked vote: the verdict is the summary's
    out = f["c"].get(f"/recipes/{rid}/export", headers=H).json()
    assert len(out["mealprep:ratings"]["entries"]) == 1
    assert out["mealprep:ratings"]["summary"]["company"] == "yes" and out["mealprep:ratings"]["good_for_company"]


def test_rerunning_the_schema_keeps_per_person_ratings(conn, fam):
    f, rid = fam, setup(conn)
    eid = placed(conn, rid, day=2)
    rate(f, eid, H)
    rate(f, eid, f["ben_h"])
    f["c"].put(f"/recipes/{rid}/company", headers=f["ben_h"], json={"company": "no"})
    db.unscope(conn)
    db.migrate(conn)
    db.scope(conn, f["hid"])
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 2
    assert [r["name"] for r in week(f, H)[0]["ratings"]] == ["Ada", "Ben"]
    assert f["c"].get(f"/recipes/{rid}", headers=H).json()["ratings"]["company"] == "no"


def test_maybe_verdicts_become_not_decided_and_are_logged_when_the_schema_runs(conn, fam):
    f, yes_r, maybe_r = fam, setup(conn), setup(conn, "Soup")
    db.unscope(conn)
    conn.execute("ALTER TABLE recipe_company DROP CONSTRAINT recipe_company_verdict_check")   # as deployed before
    conn.execute("ALTER TABLE recipe_company ADD CONSTRAINT recipe_company_verdict_check "
                 "CHECK (verdict IN ('yes','maybe','no'))")
    conn.execute("INSERT INTO recipe_company(recipe_id, household_id, verdict, set_by) VALUES(%s,%s,'yes',%s), "
                 "(%s,%s,'maybe',%s)", (yes_r, f["hid"], f["ben"], maybe_r, f["hid"], f["ada"]))
    db.migrate(conn)
    assert conn.execute("SELECT recipe_id, verdict FROM recipe_company").fetchall() == [(yes_r, "yes")]
    [(target, detail)] = conn.execute("SELECT target, detail FROM audit_log WHERE action='recipe.company'").fetchall()
    assert target == f"recipe:{maybe_r}" and detail["from"] == "maybe" and detail["to"] is None
    assert (detail["reason"], detail["set_by"]) == ("yes-no-only", f["ada"])
    with pytest.raises(Exception):
        with conn.transaction():
            conn.execute("INSERT INTO recipe_company(recipe_id, household_id, verdict) VALUES(%s,%s,'maybe')",
                         (maybe_r, f["hid"]))
    db.migrate(conn)                                     # a second run changes nothing
    assert conn.execute("SELECT count(*) FROM audit_log WHERE action='recipe.company'").fetchone()[0] == 1
    db.scope(conn, f["hid"])
    assert f["c"].get(f"/recipes/{maybe_r}", headers=H).json()["ratings"]["company"] is None
    with pytest.raises(ValueError):
        db.set_company(conn, maybe_r, "maybe", None)
    assert db.adopt_company(conn, maybe_r, "maybe") is False       # an import's maybe is ignored


def test_legacy_ratings_migration_moves_family_to_the_legacy_member_and_makes_verdicts(conn, fam):
    f, rid = fam, setup(conn)
    other = setup(conn, "Soup")
    mon, tue, wed = placed(conn, rid, day=1), placed(conn, rid, day=2), placed(conn, other, day=3)
    ins = "INSERT INTO ratings(plan_id, member_id, family, company, note, rated_at) VALUES(%s,%s,%s,%s,%s,%s) RETURNING id"
    fam_mon = conn.execute(ins, (mon, None, 4, "yes", "old", "2026-10-12 20:00+00")).fetchone()[0]
    fam_tue = conn.execute(ins, (tue, None, 3, "no", None, "2026-10-13 20:00+00")).fetchone()[0]   # newer: wins
    conn.execute(ins, (tue, f["ada"], 5, None, None, "2026-10-13 21:00+00"))     # Ada rated Tuesday already
    fam_wed = conn.execute(ins, (wed, None, 2, None, "no answer", "2026-10-14 08:00+00")).fetchone()[0]
    rr = conn.execute("INSERT INTO recipe_ratings(recipe_id, cooked_on, member_id, family, company) "
                      "VALUES(%s, '2026-10-01', NULL, 5, 'maybe') RETURNING id", (other,)).fetchone()[0]
    third = setup(conn, "Stew")
    conn.execute("INSERT INTO recipe_ratings(recipe_id, cooked_on, member_id, family, company) "
                 "VALUES(%s, '2026-10-02', %s, 4, 'yes'), (%s, '2026-10-03', %s, 3, 'no')",
                 (third, f["ben"], other, f["cleo"]))   # Ben (an owner) on Stew; Cleo's older answer on Soup
    conn.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, family, member_id) "
                 "VALUES(%s,%s,'2026-10-11',1,'set',4,NULL), (%s,%s,'2026-10-11',2,'set',3,NULL)",
                 (mon, rid, tue, rid))
    db.unscope(conn)
    before = legacy_ratings.snapshot(conn)
    log = legacy_ratings.plan(conn)
    [h] = [x for x in log["households"] if x["household_id"] == f["hid"]]
    assert h["legacy_member_id"] == f["ada"]
    assert h["reattributed"] == {"ratings": [fam_mon, fam_wed], "recipe_ratings": [rr]}
    assert h["conflicts"] == [{"table": "ratings", "id": fam_tue}]
    assert len(h["history"]) == 1                     # Tuesday's history stays with the Family row left behind
    assert {(v["recipe_id"], v["verdict"], v["set_by"]) for v in h["verdicts"]} == \
        {(rid, "no", None), (other, "no", None), (third, "yes", f["ben"])}   # Cleo answered last on Soup; not an owner
    assert {(c["recipe_id"], c["taken"]) for c in h["verdict_conflicts"]} == {(rid, "no")}   # Soup's maybe isn't one
    legacy_ratings.apply(conn, log)
    assert legacy_ratings.snapshot(conn) == before    # scores, notes, dates, answers untouched
    assert conn.execute("SELECT member_id FROM ratings WHERE id = ANY(%s)", ([fam_mon, fam_wed],)).fetchall() == \
        [(f["ada"],), (f["ada"],)]
    assert conn.execute("SELECT member_id FROM ratings WHERE id=%s", (fam_tue,)).fetchone() == (None,)
    assert conn.execute("SELECT recipe_id, verdict, set_by, source FROM recipe_company ORDER BY recipe_id").fetchall() \
        == [(rid, "no", None, "migrated"), (other, "no", None, "migrated"), (third, "yes", f["ben"], "migrated")]
    assert legacy_ratings.plan(conn)["households"][0]["verdicts"] == []          # a second run changes nothing more
    db.scope(conn, f["hid"])
    assert week(f, H)[0]["rating"]["family"] == 4 and week(f, H)[0]["rating"]["company"] == "no"
    db.unscope(conn)
    legacy_ratings.revert(conn, log)
    assert legacy_ratings.snapshot(conn) == before
    assert conn.execute("SELECT count(*) FROM ratings WHERE member_id IS NULL").fetchone()[0] == 3
    assert conn.execute("SELECT count(*) FROM recipe_ratings WHERE member_id IS NULL").fetchone()[0] == 1
    assert conn.execute("SELECT count(*) FROM recipe_ratings").fetchone()[0] == 3
    assert conn.execute("SELECT count(*) FROM rating_history WHERE member_id IS NULL").fetchone()[0] == 2
    assert conn.execute("SELECT count(*) FROM recipe_company").fetchone()[0] == 0
    db.scope(conn, f["hid"])
