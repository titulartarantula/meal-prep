"""Several recipes on one night (2026-11-14): they show in the order they were put on the night, the old app's first
recipe stays first, the cart buys for all of them, each is rated on its own; and the one-off numbering of the rows
placed before (night_order). Invented data."""
from datetime import date

from mealprep import db, night_order
from mealprep.models import Ingredient, Recipe
from tests.test_api import H, client

W = date(2026, 10, 12)          # a Monday week
TUE = 1


def recipe(conn, title, item=None):
    return db.save_recipe(conn, Recipe(title=title, source="photo", servings=4, steps=[],
                                       ingredients=[Ingredient(raw=f"1 {item or title}", name=(item or title).lower(), qty=1)]))


def titles(c, week=W, day=TUE):
    return [e["title"] for e in c.get(f"/weeks/{week}", headers=H).json() if e["day"] == day]


def add(c, rid, day=None, week=W):
    eid = c.post(f"/weeks/{week}/entries", headers=H, json={"recipe_id": rid}).json()["id"]
    if day is not None:
        assert c.patch(f"/plan/{eid}", headers=H, json={"day": day}).status_code == 204
    return eid


def test_a_night_holds_several_recipes_in_the_order_they_were_put_on_it(conn):
    c = client(conn)
    salad = recipe(conn, "Green Salad", "lettuce")
    chili = recipe(conn, "Chili", "beans")
    pie = recipe(conn, "Apple Pie", "apples")
    e_salad = add(c, salad)                     # added first, but waits in the tray
    add(c, chili, TUE)                          # the first recipe put on Tuesday
    add(c, pie, TUE)
    assert c.patch(f"/plan/{e_salad}", headers=H, json={"day": TUE}).status_code == 204
    assert titles(c) == ["Chili", "Apple Pie", "Green Salad"]
    # placing it on the same night again (the old app's sheet) keeps its place; a multiplier change too
    c.patch(f"/plan/{e_salad}", headers=H, json={"day": TUE, "multiplier": 2})
    assert titles(c) == ["Chili", "Apple Pie", "Green Salad"]
    # back to the tray and onto the night again: now last
    first = c.get(f"/weeks/{W}", headers=H).json()[0]
    c.patch(f"/plan/{first['id']}", headers=H, json={"day": None})
    assert titles(c) == ["Apple Pie", "Green Salad"]
    assert [e["title"] for e in c.get(f"/weeks/{W}", headers=H).json() if e["day"] is None] == ["Chili"]
    c.patch(f"/plan/{first['id']}", headers=H, json={"day": TUE})
    assert titles(c) == ["Apple Pie", "Green Salad", "Chili"]
    # moved to another night: last there
    add(c, recipe(conn, "Soup", "broth"), 3)
    c.patch(f"/plan/{e_salad}", headers=H, json={"day": 3})
    assert titles(c, day=3) == ["Soup", "Green Salad"]
    assert c.get("/weeks", headers=H, params={"from": W.isoformat(), "count": 1}).json()[0]["entries"] == 4


def test_each_recipe_on_a_night_is_rated_and_asked_about_on_its_own(conn):
    c = client(conn, today=date(2026, 10, 14))
    chili, salad = recipe(conn, "Chili", "beans"), recipe(conn, "Green Salad", "lettuce")
    e1, e2 = add(c, chili, TUE), add(c, salad, TUE)
    pending = c.get("/ratings/pending", headers=H).json()
    assert [p["entry_id"] for p in pending] == [e1, e2]
    assert c.put(f"/plan/{e1}/rating", headers=H, json={"family": 5}).status_code == 204
    assert [p["entry_id"] for p in c.get("/ratings/pending", headers=H).json()] == [e2]
    week = {e["id"]: e for e in c.get(f"/weeks/{W}", headers=H).json()}
    assert week[e1]["rating_count"] == 1 and week[e2]["rating_count"] == 0
    # rating a recipe from the recipe for that day lands on its own night entry, not the other one's
    assert c.put(f"/recipes/{salad}/rating", headers=H, json={"family": 3, "cooked_on": "2026-10-13"}).status_code == 200
    week = {e["id"]: e for e in c.get(f"/weeks/{W}", headers=H).json()}
    assert week[e2]["rating_count"] == 1 and week[e1]["rating_count"] == 1


def test_the_shopping_list_has_every_recipe_on_the_night(conn):
    c = client(conn)
    add(c, recipe(conn, "Chili", "kidney beans"), TUE)
    add(c, recipe(conn, "Green Salad", "romaine lettuce"), TUE)
    r = c.post("/list", headers=H, json={"weeks": [W.isoformat()]})
    assert r.status_code == 200, r.text
    names = " ".join(i["name"] for i in r.json()).lower()
    assert "kidney beans" in names and "romaine lettuce" in names


def test_numbering_the_old_rows_keeps_their_order_and_reverts(conn, tmp_path):
    a, b, c_, d = (recipe(conn, t) for t in ("A", "B", "C", "D"))
    ins = "INSERT INTO plan(week, recipe_id, day) VALUES(%s,%s,%s) RETURNING id"
    ids = [conn.execute(ins, (W, r, dy)).fetchone()[0] for r, dy in ((a, TUE), (b, TUE), (c_, 4), (d, None))]
    order = [e.id for e in db.get_week(conn, W)]
    log = night_order.plan(conn)
    assert (log["numbered"], log["nights"], log["nights_with_several"]) == (3, 2, 1)
    night_order.apply(conn, log)
    pos = dict(conn.execute("SELECT id, position FROM plan").fetchall())
    assert [pos[i] for i in ids] == [1, 2, 1, None]
    assert [e.id for e in db.get_week(conn, W)] == order
    assert night_order.plan(conn)["numbered"] == 0           # nothing left to number: a second run changes nothing
    out = night_order.revert(conn, log)
    assert out["cleared"] == 3
    assert all(p is None for p in dict(conn.execute("SELECT id, position FROM plan").fetchall()).values())
    assert [e.id for e in db.get_week(conn, W)] == order


def test_a_recipe_placed_before_numbering_still_goes_after_the_old_ones(conn):
    a, b = recipe(conn, "A"), recipe(conn, "B")
    old = conn.execute("INSERT INTO plan(week, recipe_id, day) VALUES(%s,%s,%s) RETURNING id", (W, a, TUE)).fetchone()[0]
    new = db.add_to_week(conn, W, b)
    db.update_entry(conn, new, day=TUE)
    assert [e.id for e in db.get_week(conn, W) if e.day == TUE] == [old, new]
