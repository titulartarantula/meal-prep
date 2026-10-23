"""The morning after a fridge dinner: one tap takes what it used off the fridge list (never automatic)."""
import os, threading
from datetime import date

import psycopg
import pytest

from mealprep import db, fridge
from mealprep.models import Ingredient, Recipe
from test_api import H, client
from test_fridge import MapAI

TODAY = date(2026, 10, 7)          # a Wednesday; its week starts Sunday 2026-10-04
WEEK = date(2026, 10, 4)


def R(title, *lines, servings=4):
    return Recipe(title=title, source="photo", servings=servings, steps=[],
                  ingredients=[Ingredient(raw=raw, name=n, qty=q, unit=u) for raw, n, q, u in lines])


def place(conn, rid, day, mult=1.0, week=WEEK):
    eid = db.add_to_week(conn, week, rid)
    db.update_entry(conn, eid, day=day, multiplier=mult)
    return eid


def stock(conn, *items, as_of=date(2026, 10, 1)):
    for name, qty, unit in items:
        fridge.create_item(conn, MapAI(), name, qty, unit, today=as_of)


def qtys(conn):
    return {i.name: i.qty for i in fridge.list_items(conn, TODAY)}


def test_window_scaling_and_texts(conn):
    frit = db.save_recipe(conn, R("Frittata", ("2 eggs", "egg", 2, None), ("1 cup milk", "whole milk", 1, "cup"),
                                  ("cilantro", "cilantro", 0.25, "cup")))
    stock(conn, ("Eggs", 6, None), ("Whole milk", 4, "cup"), ("Cilantro", 1, "bunch"))
    e0 = place(conn, frit, 0)           # Sun 10-04: 3 days ago, too old
    e1 = place(conn, frit, 1, mult=2)   # Mon 10-05
    e2 = place(conn, frit, 2)           # Tue 10-06
    place(conn, frit, 3)                # tonight: not yet
    place(conn, frit, None)             # no night
    out = fridge.used_nights(conn, TODAY)
    assert [n["entry_id"] for n in out] == [e2, e1] and e0 not in [n["entry_id"] for n in out]
    tue, mon = out
    assert (tue["date"], tue["title"]) == ("2026-10-06", "Frittata")
    assert [(u["name"], u["used"], u["after"]) for u in tue["uses"]] == [("Eggs", "2 Eggs", "4 left"),
                                                                       ("Whole milk", "1 cup Whole milk", "3 cups left")]
    assert tue["check"] == ["Cilantro"]
    assert [u["used"] for u in mon["uses"]] == ["4 Eggs", "2 cups Whole milk"]   # × 2
    assert qtys(conn) == {"Eggs": 6, "Whole milk": 4, "Cilantro": 1}              # GET never changes anything
    fridge.used_nights(conn, TODAY)
    assert qtys(conn) == {"Eggs": 6, "Whole milk": 4, "Cilantro": 1}


def test_an_item_updated_since_the_dinner_is_skipped(conn):
    rid = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None), ("spinach", "spinach", 1, "bag")))
    stock(conn, ("Eggs", 6, None))
    stock(conn, ("Spinach", 2, "bag"), as_of=date(2026, 10, 6))
    place(conn, rid, 1)                 # Mon: spinach was counted Tue, so it's already right
    [n] = fridge.used_nights(conn, TODAY)
    assert [u["name"] for u in n["uses"]] == ["Eggs"]
    conn.execute("UPDATE fridge_items SET amount_as_of = '2026-10-05'")
    assert fridge.used_nights(conn, TODAY) == []           # nothing left to apply: no nudge


def test_a_night_with_only_checks_still_asks(conn):
    rid = db.save_recipe(conn, R("Salsa", ("cilantro", "cilantro", 0.25, "cup")))
    stock(conn, ("Cilantro", 1, "bunch"))
    place(conn, rid, 2)
    [n] = fridge.used_nights(conn, TODAY)
    assert n["uses"] == [] and n["check"] == ["Cilantro"]


def test_a_rated_night_still_gets_the_nudge_and_other_recipes_none(conn):
    a = db.save_recipe(conn, R("Frittata", ("2 eggs", "egg", 2, None)))
    b = db.save_recipe(conn, R("Pork", ("pork", "pork shoulder", 1, "kg")))
    stock(conn, ("Eggs", 6, None))
    ea = place(conn, a, 2)
    place(conn, b, 2)
    db.set_rating(conn, ea, 5)
    assert [n["entry_id"] for n in fridge.used_nights(conn, TODAY)] == [ea]


def test_apply_once_subtracts_with_a_floor_of_zero(conn):
    rid = db.save_recipe(conn, R("Quiche", ("8 eggs", "egg", 8, None), ("1 cup milk", "whole milk", 1, "cup")))
    stock(conn, ("Eggs", 6, None), ("Whole milk", 4, "cup"))
    eid = place(conn, rid, 2)
    r = fridge.answer_used(conn, eid, True, TODAY)
    assert r["action"] == "applied"
    assert [(c["name"], c["before"], c["after"], c["text"]) for c in r["changes"]] == [
        ("Eggs", 6, 0, "Eggs 0"), ("Whole milk", 4, 3, "Whole milk 3 cups")]
    assert qtys(conn) == {"Eggs": 0, "Whole milk": 3}
    items = {i.name: i for i in fridge.list_items(conn, TODAY)}
    assert items["Eggs"].amount_as_of == TODAY.isoformat() and not items["Eggs"].qty_estimated
    again = fridge.answer_used(conn, eid, True, TODAY)
    assert again == r and qtys(conn) == {"Eggs": 0, "Whole milk": 3}
    assert fridge.answer_used(conn, eid, False, TODAY)["action"] == "applied"    # the first answer stands
    assert fridge.used_nights(conn, TODAY) == []


def test_two_phones_tapping_at_once_subtract_once(conn):
    dsn = os.environ["MEALPREP_TEST_DSN"]
    rid = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None)))
    stock(conn, ("Eggs", 6, None))
    eid = place(conn, rid, 2)
    go, out = threading.Barrier(2), []

    def tap():
        with psycopg.connect(dsn, autocommit=True) as c:
            go.wait()
            out.append(fridge.answer_used(c, eid, True, TODAY))
    ts = [threading.Thread(target=tap) for _ in range(2)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert qtys(conn) == {"Eggs": 4} and out[0] == out[1]


def test_not_now_changes_nothing(conn):
    rid = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None)))
    stock(conn, ("Eggs", 6, None))
    eid = place(conn, rid, 2)
    r = fridge.answer_used(conn, eid, False, TODAY)
    assert (r["action"], r["changes"]) == ("dismissed", [])
    assert qtys(conn) == {"Eggs": 6} and fridge.used_nights(conn, TODAY) == []
    assert fridge.answer_used(conn, eid, True, TODAY)["action"] == "dismissed"
    assert qtys(conn) == {"Eggs": 6}


def test_a_deleted_dinner_drops_its_nudge(conn):
    rid = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None)))
    stock(conn, ("Eggs", 6, None))
    eid = place(conn, rid, 2)
    fridge.answer_used(conn, eid, False, TODAY)
    db.remove_entry(conn, eid)
    assert conn.execute("SELECT count(*) FROM fridge_nudges").fetchone()[0] == 0
    with pytest.raises(fridge.EntryNotFound):
        fridge.answer_used(conn, eid, True, TODAY)


def test_api(conn):
    rid = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None)))
    stock(conn, ("Eggs", 6, None))
    eid = place(conn, rid, 2)
    c = client(conn, today=TODAY)
    assert c.get("/fridge/used").status_code == 401
    [n] = c.get("/fridge/used", headers=H).json()
    assert n["entry_id"] == eid and n["uses"][0]["used"] == "2 Eggs"
    assert c.get("/fridge/used?today=2026-10-09", headers=H).json() == []
    assert c.post(f"/fridge/used/{eid}", headers=H, json={"apply": "yes"}).status_code == 422
    r = c.post(f"/fridge/used/{eid}", headers=H, json={"apply": True})
    assert r.status_code == 200 and r.json()["changes"][0]["text"] == "Eggs 4"
    assert c.get("/fridge/used", headers=H).json() == []
    assert c.post("/fridge/used/9999", headers=H, json={"apply": True}).status_code == 404
