"""The one-off Monday-weeks migration (2026-11-14): Sunday week ids (day 0 = Sun … 6 = Sat) become Monday ids (day 0 = Mon
… 6 = Sun) and every row keeps its calendar date. Rows are seeded as the database held them before, straight in SQL.
Invented data."""
from datetime import date, timedelta

import pytest
from psycopg.types.json import Jsonb

from mealprep import db, monday_weeks
from mealprep.models import Ingredient, Recipe

A = date(2026, 10, 11)          # a Sunday week (Sun 10-11 … Sat 10-17)
B = date(2026, 10, 18)          # the next one
A_MON, B_MON = date(2026, 10, 12), date(2026, 10, 19)
PREV_MON = date(2026, 10, 5)    # the Monday week that Sunday 10-11 ends


def recipe(conn, title):
    return db.save_recipe(conn, Recipe(title=title, source="photo", servings=4, steps=[],
                                       ingredients=[Ingredient(raw=f"1 {title}", name=title.lower(), qty=1)]))


def seed(conn):
    """Sunday-keyed rows: plan on Sun (0), Mon (1), Sat (6) and no night; week and night extras; named nights;
    rating history; a prep plan with a cook card; a fresh sent cart for A and a stale one for A + B."""
    chili, soup, pie, stew = (recipe(conn, t) for t in ("Chili", "Soup", "Pie", "Stew"))
    ins = "INSERT INTO plan(week, recipe_id, day) VALUES(%s,%s,%s) RETURNING id"
    p = {"sun": conn.execute(ins, (A, chili, 0)).fetchone()[0],
         "mon": conn.execute(ins, (A, soup, 1)).fetchone()[0],
         "sat": conn.execute(ins, (A, pie, 6)).fetchone()[0],
         "tray": conn.execute(ins, (A, stew, None)).fetchone()[0],
         "b_wed": conn.execute(ins, (B, chili, 3)).fetchone()[0]}
    with db.unscoped(conn):
        conn.execute("INSERT INTO products(code, name) VALUES('PIZ', 'Frozen pizza'), ('KIT', 'Salad kit'), "
                     "('TOW', 'Paper towels')")
    ex = "INSERT INTO extras(week, day, term, key, product_code, quantity) VALUES(%s,%s,%s,%s,%s,1) RETURNING id"
    e = {"week": conn.execute(ex, (A, None, "paper towels", "paper towels", "TOW")).fetchone()[0],
         "sun": conn.execute(ex, (A, 0, "caesar salad kit", "caesar salad kit", "KIT")).fetchone()[0],
         "thu": conn.execute(ex, (A, 4, "frozen pizza", "frozen pizza", "PIZ")).fetchone()[0]}
    conn.execute("INSERT INTO nights(week, day, title) VALUES(%s,0,'Leftovers'), (%s,5,'Pizza night'), "
                 "(%s,6,'Games night')", (A, A, B))
    rh = ("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, family) VALUES(%s,%s,%s,%s,'set',4) "
          "RETURNING id")
    h = {"sun": conn.execute(rh, (p["sun"], chili, A, 0)).fetchone()[0],
         "mon": conn.execute(rh, (p["mon"], soup, A, 1)).fetchone()[0],
         "none": conn.execute(rh, (p["tray"], stew, A, None)).fetchone()[0]}
    pp = conn.execute("INSERT INTO prep_plans(weeks, status, input) VALUES(%s, 'ready', '{}') RETURNING id",
                      ([A, B],)).fetchone()[0]
    cc = conn.execute("INSERT INTO cook_cards(prep_plan_id, plan_id, recipe_id, week, day, multiplier, card) "
                      "VALUES(%s,%s,%s,%s,6,1,%s) RETURNING id", (pp, p["sat"], pie, A, Jsonb({}))).fetchone()[0]
    # carts as the old code made them: the fingerprint over the Sunday ids, taken as they are
    cart = "INSERT INTO carts(status, weeks, plan_fingerprint, sent_at) VALUES('sent', %s, %s, now()) RETURNING id"
    fresh = conn.execute(cart, ([A], db.plan_fingerprint(conn, [A], normalise=False))).fetchone()[0]
    stale = conn.execute(cart, ([A, B], db.plan_fingerprint(conn, [A, B], normalise=False))).fetchone()[0]
    conn.execute("INSERT INTO cart_weeks VALUES(%s,%s), (%s,%s), (%s,%s)", (fresh, A, stale, A, stale, B))
    conn.execute(ins, (B, soup, 2))                          # B's plan changed after the second cart was sent
    return {"plan": p, "extras": e, "history": h, "prep_plan": pp, "cook_card": cc, "fresh": fresh, "stale": stale}


def rows(conn, table, key="id"):
    """{row key: (week, day)}."""
    return {k: (w, d) for k, w, d in conn.execute(f"SELECT {key}, week, day FROM {table}")}


def snapshot(conn):
    """Every week/day value the migration touches, and each cart's fingerprint."""
    return {"plan": rows(conn, "plan"), "extras": rows(conn, "extras"), "nights": rows(conn, "nights", "title"),
            "rating_history": rows(conn, "rating_history"), "cook_cards": rows(conn, "cook_cards"),
            "cart_weeks": sorted(conn.execute("SELECT cart_id, week FROM cart_weeks").fetchall()),
            "carts": sorted(conn.execute("SELECT id, weeks, plan_fingerprint FROM carts").fetchall()),
            "prep_plans": sorted(conn.execute("SELECT id, weeks FROM prep_plans").fetchall())}


def carts(conn):
    return {cid: (weeks, fp) for cid, weeks, fp in conn.execute("SELECT id, weeks, plan_fingerprint FROM carts")}


def test_forward_keeps_every_date_and_revert_restores_the_rows(conn):
    ids = seed(conn)
    before = snapshot(conn)
    with db.unscoped(conn):                  # the migration runs as the login role, over every household
        log = monday_weeks.plan(conn)
        assert snapshot(conn) == before                                           # a plan changes nothing
        assert log["direction"] == "monday"
        assert log["tables"] == {"plan": 6, "extras": 3, "nights": 3, "rating_history": 3, "cook_cards": 1,
                                 "cart_weeks": 3, "carts": 2, "prep_plans": 1}
        assert {c["id"]: c["fresh"] for c in log["carts"]} == {ids["fresh"]: True, ids["stale"]: False}
        assert {"week": "2026-10-11", "day": 0, "new_week": "2026-10-05", "new_day": 6} in log["moved_rows"]["plan"]
        monday_weeks.apply(conn, log)
    after = snapshot(conn)

    # every night keeps its calendar date; a row without a night moves to the Monday of its Sunday–Saturday span
    for t in ("plan", "extras", "nights", "rating_history", "cook_cards"):
        assert after[t].keys() == before[t].keys(), t
        for k, (w, d) in before[t].items():
            nw, nd = after[t][k]
            assert nw.weekday() == 0, (t, k)
            if d is None:
                assert (nw, nd) == (w + timedelta(days=1), None), (t, k)
            else:
                assert nw + timedelta(days=nd) == w + timedelta(days=d), (t, k)
                assert nd == (w + timedelta(days=d)).weekday(), (t, k)
    p, e, h = ids["plan"], ids["extras"], ids["history"]
    assert after["plan"][p["sun"]] == (PREV_MON, 6)                 # Sunday 10-11 ends the week before
    assert after["plan"][p["mon"]] == (A_MON, 0)
    assert after["plan"][p["sat"]] == (A_MON, 5)
    assert after["plan"][p["tray"]] == (A_MON, None)
    assert after["plan"][p["b_wed"]] == (B_MON, 2)
    assert (after["extras"][e["week"]], after["extras"][e["sun"]], after["extras"][e["thu"]]) == \
        ((A_MON, None), (PREV_MON, 6), (A_MON, 3))
    assert after["nights"] == {"Leftovers": (PREV_MON, 6), "Pizza night": (A_MON, 4), "Games night": (B_MON, 5)}
    assert (after["rating_history"][h["sun"]], after["rating_history"][h["mon"]],
            after["rating_history"][h["none"]]) == ((PREV_MON, 6), (A_MON, 0), (A_MON, None))
    assert after["cook_cards"][ids["cook_card"]] == (A_MON, 5)
    assert after["cart_weeks"] == sorted([(ids["fresh"], A_MON), (ids["stale"], A_MON), (ids["stale"], B_MON)])
    assert after["prep_plans"] == [(ids["prep_plan"], [A_MON, B_MON])]
    cs = carts(conn)
    assert cs[ids["fresh"]][0] == [A_MON] and cs[ids["stale"]][0] == [A_MON, B_MON]
    assert cs[ids["stale"]][1] == dict((c[0], c[2]) for c in before["carts"])[ids["stale"]]   # left as it was

    # the new code reads them as Monday weeks: the fresh cart is still up to date, the stale one still stale
    assert db.cart_is_stale(conn, *cs[ids["fresh"]]) is False
    assert db.cart_is_stale(conn, *cs[ids["stale"]]) is True
    # a week is judged by its newest sent cart: both weeks' is the stale A + B one, as before the run
    assert db.sent_cart_stale(conn, [A_MON, B_MON]) == {A_MON: True, B_MON: True}
    assert [(x.day, x.title) for x in db.get_week(conn, A_MON)] == [(0, "Soup"), (5, "Pie"), (None, "Stew")]
    assert [(x.day, x.title) for x in db.get_week(conn, PREV_MON)] == [(6, "Chili")]

    with db.unscoped(conn):
        with pytest.raises(SystemExit, match="Sunday"):               # can't run forward twice
            monday_weeks.plan(conn)
        assert snapshot(conn) == after
        back = monday_weeks.plan(conn, revert=True)
        assert back["direction"] == "revert"
        assert {c["id"]: c["fresh"] for c in back["carts"]} == {ids["fresh"]: True, ids["stale"]: False}
        monday_weeks.apply(conn, back)
        with pytest.raises(SystemExit, match="Monday"):               # nor revert twice
            monday_weeks.plan(conn, revert=True)
    assert snapshot(conn) == before                                   # the exact rows and fingerprints again
    cs = carts(conn)
    assert db.plan_fingerprint(conn, cs[ids["fresh"]][0], normalise=False) == cs[ids["fresh"]][1]
    assert db.plan_fingerprint(conn, cs[ids["stale"]][0], normalise=False) != cs[ids["stale"]][1]


def test_refuses_mixed_or_already_monday_ids(conn):
    rid = recipe(conn, "Chili")
    conn.execute("INSERT INTO plan(week, recipe_id, day) VALUES(%s,%s,1), (%s,%s,2)", (A, rid, A_MON, rid))
    with db.unscoped(conn):
        with pytest.raises(SystemExit, match="not every week id is a Sunday"):
            monday_weeks.plan(conn)
        with pytest.raises(SystemExit, match="not every week id is a Monday"):
            monday_weeks.plan(conn, revert=True)
    assert sorted(conn.execute("SELECT week, day FROM plan").fetchall()) == [(A, 1), (A_MON, 2)]


def test_an_empty_database_runs_both_ways(conn):
    with db.unscoped(conn):
        log = monday_weeks.plan(conn)
        assert set(log["tables"].values()) == {0} and log["carts"] == []
        monday_weeks.apply(conn, log)
        monday_weeks.apply(conn, monday_weeks.plan(conn, revert=True))
