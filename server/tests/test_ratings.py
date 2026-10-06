from datetime import date, datetime, timezone
import psycopg
import pytest
from mealprep import db
from mealprep.models import Recipe

TODAY = date(2026, 10, 14)          # Wednesday; week of 2026-10-11


def at(day, hour=20):
    return datetime(2026, 10, day, hour, tzinfo=timezone.utc)


def recipe(conn, title="Chili"):
    return db.save_recipe(conn, Recipe(title=title, source="photo", ingredients=[], steps=[]))


def entry(conn, rid, week, day=None):
    eid = db.add_to_week(conn, date.fromisoformat(week), rid)
    if day is not None:
        db.update_entry(conn, eid, day=day)
    return eid


def test_rating_upsert_keeps_history(conn):
    eid = entry(conn, recipe(conn), "2026-10-11", 2)
    assert db.set_rating(conn, eid, 3, at=at(14, 8)) is True
    assert db.set_rating(conn, eid, 5, company="yes", note="kids loved it", at=at(14, 9)) is True
    [e] = db.get_week(conn, date(2026, 10, 11))
    assert e.rating.family == 5 and e.rating.company == "yes" and e.rating.note == "kids loved it"
    assert datetime.fromisoformat(e.rating.rated_at) == at(14, 9)
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 1
    hist = conn.execute("SELECT plan_id, family, company, note, action FROM rating_history ORDER BY id").fetchall()
    assert hist == [(eid, 3, None, None, "set"), (eid, 5, "yes", "kids loved it", "set")]


def test_rating_unknown_entry(conn):
    assert db.set_rating(conn, 999, 4) is False
    assert db.delete_rating(conn, 999) is False


def test_rating_constraints(conn):
    eid = entry(conn, recipe(conn), "2026-10-11", 2)
    for fam, comp in [(0, None), (6, None), (3, "sure")]:
        with pytest.raises(psycopg.errors.CheckViolation):
            db.set_rating(conn, eid, fam, company=comp)


def test_delete_rating(conn):
    eid = entry(conn, recipe(conn), "2026-10-11", 2)
    db.set_rating(conn, eid, 4)
    assert db.delete_rating(conn, eid) is True
    assert db.get_week(conn, date(2026, 10, 11))[0].rating is None
    assert db.delete_rating(conn, eid) is True            # no rating left: still fine
    assert [a for (a,) in conn.execute("SELECT action FROM rating_history ORDER BY id")] == ["set", "delete"]


def test_unrated_entry_has_null_rating(conn):
    entry(conn, recipe(conn), "2026-10-11", 2)
    assert db.get_week(conn, date(2026, 10, 11))[0].rating is None


def test_pending(conn):
    rid = recipe(conn)
    yesterday = entry(conn, rid, "2026-10-11", 2)         # Tue 10-13
    entry(conn, rid, "2026-10-11", 3)                     # Wed 10-14 = today: not yet
    entry(conn, rid, "2026-10-11", 5)                     # Fri: future
    entry(conn, rid, "2026-10-11")                        # unplaced: never
    rated = entry(conn, rid, "2026-10-11", 1)             # Mon, rated
    db.set_rating(conn, rated, 4)
    edge = entry(conn, rid, "2026-09-27", 3)              # Wed 09-30 = today − 14: included
    entry(conn, rid, "2026-09-27", 2)                     # Tue 09-29 = today − 15: too old
    entry(conn, rid, "2026-09-27")                        # old unplaced
    pend = db.pending_ratings(conn, TODAY)
    assert [p["entry_id"] for p in pend] == [yesterday, edge]          # most recent first
    assert pend[0] == {"entry_id": yesterday, "week": "2026-10-11", "day": 2, "date": "2026-10-13",
                       "recipe_id": rid, "title": "Chili", "multiplier": 1.0}


def test_summary_math(conn):
    chili, soup, never = recipe(conn, "Chili"), recipe(conn, "Soup"), recipe(conn, "Never")
    a = entry(conn, chili, "2026-09-27", 3)               # 09-30
    b = entry(conn, chili, "2026-10-04", 3)               # 10-07
    c = entry(conn, chili, "2026-10-11", 1)               # 10-12
    entry(conn, chili, "2026-10-11", 2)                   # 10-13, past but unrated → cooked, not rated
    entry(conn, chili, "2026-10-11", 5)                   # future, unrated → not cooked
    entry(conn, chili, "2026-10-18")                      # unplaced → not cooked
    db.set_rating(conn, a, 3, company="yes", note="too salty", at=at(1))
    db.set_rating(conn, b, 4, company="maybe", at=at(8))
    db.set_rating(conn, c, 4, note="kids picked out peppers", at=at(13))
    u = entry(conn, soup, "2026-10-25")                   # unplaced but rated (cooked off-plan) → counts
    db.set_rating(conn, u, 5, at=at(14))
    s = db.rating_summaries(conn, TODAY)
    assert s[chili] == {
        "times_cooked": 4, "times_rated": 3, "avg_family": 3.7, "last_family": 4,
        "last_rated_at": s[chili]["last_rated_at"], "company": "maybe",
        "notes": [{"note": "kids picked out peppers", "date": "2026-10-12", "rated_at": s[chili]["notes"][0]["rated_at"]},
                  {"note": "too salty", "date": "2026-09-30", "rated_at": s[chili]["notes"][1]["rated_at"]}],
        "imported_ratings": 0}
    assert datetime.fromisoformat(s[chili]["last_rated_at"]) == at(13)
    assert datetime.fromisoformat(s[chili]["notes"][1]["rated_at"]) == at(1)
    assert s[soup]["times_cooked"] == 1 and s[soup]["avg_family"] == 5.0
    assert s[soup]["notes"] == [] and s[soup]["company"] is None
    assert s[never] == {"times_cooked": 0, "times_rated": 0, "avg_family": None, "last_family": None,
                        "last_rated_at": None, "company": None, "notes": [],
                                   "imported_ratings": 0}


def test_avg_rounds_half_up(conn):
    rid = recipe(conn)
    for d, fam in [(1, 4), (2, 4), (3, 4), (4, 5)]:      # 4.25 → 4.3
        db.set_rating(conn, entry(conn, rid, "2026-10-04", d), fam)
    assert db.rating_summaries(conn, TODAY)[rid]["avg_family"] == 4.3


def test_notes_capped_at_five(conn):
    rid = recipe(conn)
    for d in range(7):
        db.set_rating(conn, entry(conn, rid, "2026-10-04", d), 3, note=f"n{d}", at=at(4 + d))
    notes = db.rating_summaries(conn, TODAY)[rid]["notes"]
    assert [n["note"] for n in notes] == ["n6", "n5", "n4", "n3", "n2"]


def test_recipe_history(conn):
    rid, other = recipe(conn), recipe(conn, "Other")
    a = entry(conn, rid, "2026-10-04", 2)
    b = entry(conn, rid, "2026-10-11", 1)
    db.update_entry(conn, b, multiplier=1.5)
    entry(conn, other, "2026-10-11", 3)
    db.set_rating(conn, a, 5)
    h = db.recipe_history(conn, rid)
    assert [x["entry_id"] for x in h] == [b, a]                         # newest first
    assert h[0] == {"entry_id": b, "week": "2026-10-11", "day": 1, "date": "2026-10-12", "multiplier": 1.5, "rating": None}
    assert h[1]["rating"]["family"] == 5


def test_deleting_plan_entry_keeps_rating_history(conn):
    eid = entry(conn, recipe(conn), "2026-10-11", 2)
    db.set_rating(conn, eid, 4)
    db.remove_entry(conn, eid)
    assert conn.execute("SELECT count(*) FROM ratings").fetchone()[0] == 0
    assert conn.execute("SELECT plan_id, family FROM rating_history").fetchall() == [(eid, 4)]


def test_ratings_schema_idempotent(conn):
    eid = entry(conn, recipe(conn), "2026-10-11", 2)
    db.set_rating(conn, eid, 4)
    for _ in range(2):
        conn.execute(db.SCHEMA)
    assert conn.execute("SELECT family FROM ratings").fetchone() == (4,)
