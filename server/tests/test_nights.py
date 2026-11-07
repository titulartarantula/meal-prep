"""Store items for a night + night names (2026-11-06): an added item can belong to one night of its week (day 0–6),
which its cart line carries; a night can have a name. Neither changes the cart's plan fingerprint nor asks for a
rating. Invented products and names."""
from datetime import date

from fastapi.testclient import TestClient

from mealprep import db, drafts, extras, nights
from mealprep.api import create_app
from mealprep.config import Settings
from mealprep.models import Product, Recipe

from test_chooser import AI, Pcx, WK, build
from test_rbac import ALL_ROLES, member_session, new_household

H = {"Authorization": "Bearer secret"}
WEEK = "2026-10-11"


def pizza(code="PIZ", price=6.99):
    return Product(code=code, name="Frozen Pizza Pepperoni", brand="Brand", package_size="800 g", price=price, stock="OK")


def kit(code="KIT"):
    return Product(code=code, name="Caesar Salad Kit", package_size="300 g", price=4.49, stock="OK")


def pcx():
    return Pcx({"frozen pizza": [pizza(), pizza("PZ2", 5.49)], "caesar salad kit": [kit()]},
               catalog={"PIZ": pizza(), "PZ2": pizza("PZ2", 5.49), "KIT": kit()})


def client(conn, p=None):
    s = Settings(dsn="unused", token="secret", provider="fake")
    return TestClient(create_app(s, provider=AI(), pcx=p or pcx(), conn=conn, today=lambda: date(2026, 10, 15)))


def seen(conn, p, *terms):
    for t in terms or ("frozen pizza", "caesar salad kit"):
        drafts.search_choices(conn, p, t)


# --- store items for a night ---------------------------------------------------------------------------------------

def test_add_an_item_for_a_night_and_list_it(conn):
    p = pcx(); seen(conn, p)
    c = client(conn, p)
    r = c.post("/extras", headers=H, json={"week": WEEK, "day": 4, "term": "frozen pizza", "product_code": "PIZ",
                                           "quantity": 2})
    assert r.status_code == 201
    e = r.json()
    assert (e["week"], e["day"], e["quantity"], e["product"]["code"]) == (WEEK, 4, 2, "PIZ")
    week_wide = c.post("/extras", headers=H, json={"week": WEEK, "term": "caesar salad kit", "product_code": "KIT"}).json()
    assert week_wide["day"] is None
    assert [(x["term"], x["day"]) for x in c.get("/extras", headers=H, params={"week": WEEK}).json()] == [
        ("frozen pizza", 4), ("caesar salad kit", None)]
    assert c.post("/extras", headers=H, json={"week": WEEK, "day": 7, "term": "frozen pizza",
                                              "product_code": "PIZ"}).status_code == 422
    assert c.post("/extras", headers=H, json={"week": WEEK, "day": -1, "term": "frozen pizza",
                                              "product_code": "PIZ"}).status_code == 422


def test_move_an_item_to_another_night_or_the_whole_week(conn):
    p = pcx(); seen(conn, p)
    c = client(conn, p)
    e = c.post("/extras", headers=H, json={"week": WEEK, "day": 4, "term": "frozen pizza", "product_code": "PIZ"}).json()
    r = c.patch(f"/extras/{e['id']}", headers=H, json={"day": 5})
    assert r.status_code == 200 and (r.json()["day"], r.json()["quantity"]) == (5, 1)
    r = c.patch(f"/extras/{e['id']}", headers=H, json={"quantity": 3})          # left out = the night stays
    assert (r.json()["day"], r.json()["quantity"]) == (5, 3)
    r = c.patch(f"/extras/{e['id']}", headers=H, json={"day": None})            # null = for the week
    assert r.json()["day"] is None and r.json()["quantity"] == 3
    assert c.patch(f"/extras/{e['id']}", headers=H, json={"day": 9}).status_code == 422
    try:
        extras.update_extra(conn, e["id"], day=8)
        assert False, "day 8"
    except ValueError:
        pass


def test_a_built_cart_line_carries_the_night(conn):
    p = pcx(); seen(conn, p)
    extras.add_extra(conn, WK[0], "frozen pizza", "PIZ", 2, day=4)
    extras.add_extra(conn, WK[0], "caesar salad kit", "KIT", 1)
    assert [(i.name, i.day) for i in extras.items_for(conn, WK)] == [("frozen pizza", 4), ("caesar salad kit", None)]
    did = build(conn, p, AI("CH"), extras.items_for(conn, WK))
    lines = drafts.get_draft(conn, did)["lines"]
    assert [(l["name"], l["day"], l["recipes"], l["product"]["code"], l["quantity"]) for l in lines] == [
        ("frozen pizza", 4, ["Added"], "PIZ", 2), ("caesar salad kit", None, ["Added"], "KIT", 1)]


def test_the_open_cart_gets_the_line_with_its_night_and_follows_moves(conn):
    p = pcx()
    p.results["cheddar"] = [Product(code="CH", name="Old Cheddar", package_size="400 g", price=5.0, stock="OK")]
    did = build(conn, p, AI("CH"))
    before = drafts.get_draft(conn, did)
    assert before["stale"] is False
    seen(conn, p)
    c = client(conn, p)
    e = c.post("/extras", headers=H, json={"week": WEEK, "day": 4, "term": "frozen pizza", "product_code": "PIZ"}).json()
    d = c.get(f"/drafts/{did}", headers=H).json()
    added = d["lines"][-1]
    assert (added["name"], added["day"], added["extra_id"], added["product"]["code"]) == ("frozen pizza", 4, e["id"], "PIZ")
    assert d["progress"] == {"done": 2, "total": 2}
    # a night or a name doesn't make the cart stale: the line reached it directly
    nights.set_title(conn, WK[0], 4, "Pizza night")
    assert d["stale"] is False and c.get(f"/drafts/{did}", headers=H).json()["stale"] is False
    # move it: the line follows, its product and quantity unchanged
    c.patch(f"/extras/{e['id']}", headers=H, json={"day": 6})
    line = c.get(f"/drafts/{did}", headers=H).json()["lines"][-1]
    assert (line["day"], line["quantity"], line["product"]["code"], line["source"]) == (6, 1, "PIZ", "user")
    # removing the line on the cart deletes the item; Undo puts it back on its night
    c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"removed": True})
    assert c.get("/extras", headers=H, params={"week": WEEK}).json() == []
    c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"removed": False})
    [back] = c.get("/extras", headers=H, params={"week": WEEK}).json()
    assert (back["id"], back["day"]) == (e["id"], 6)
    # quantity edits on the line keep the night
    r = c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"quantity": 3})
    assert r.json()["day"] == 6 and r.json()["quantity"] == 3
    assert c.get("/extras", headers=H, params={"week": WEEK}).json()[0]["day"] == 6
    # the week summary isn't stale either
    assert c.get("/weeks", headers=H, params={"from": WEEK, "count": 1}).json()[0]["cart_stale"] is False


# --- night names -----------------------------------------------------------------------------------------------------

def test_name_rename_and_clear_a_night(conn):
    c = client(conn)
    r = c.put(f"/weeks/{WEEK}/nights/5", headers=H, json={"title": "  Pizza   night "})
    assert r.status_code == 200 and r.json() == {"week": WEEK, "day": 5, "title": "Pizza night"}
    assert c.put("/weeks/2026-10-14/nights/5", headers=H, json={"title": "Movie night"}).json()["week"] == WEEK
    assert c.get(f"/weeks/{WEEK}/nights", headers=H).json() == [{"day": 5, "title": "Movie night", "items": []}]
    assert c.put(f"/weeks/{WEEK}/nights/5", headers=H, json={"title": "   "}).json()["title"] is None
    assert c.get(f"/weeks/{WEEK}/nights", headers=H).json() == []
    c.put(f"/weeks/{WEEK}/nights/0", headers=H, json={"title": "Leftovers"})
    assert c.delete(f"/weeks/{WEEK}/nights/0", headers=H).status_code == 204
    assert c.delete(f"/weeks/{WEEK}/nights/0", headers=H).status_code == 204     # idempotent
    assert c.put(f"/weeks/{WEEK}/nights/1", headers=H, json={}).json()["title"] is None
    assert c.get(f"/weeks/{WEEK}/nights", headers=H).json() == []


def test_night_name_validation(conn):
    c = client(conn)
    assert c.put(f"/weeks/{WEEK}/nights/7", headers=H, json={"title": "x"}).status_code == 422
    assert c.put(f"/weeks/{WEEK}/nights/-1", headers=H, json={"title": "x"}).status_code == 422
    assert c.delete(f"/weeks/{WEEK}/nights/7", headers=H).status_code == 422
    assert c.put(f"/weeks/{WEEK}/nights/2", headers=H, json={"title": "x" * 61}).status_code == 422
    assert c.put(f"/weeks/{WEEK}/nights/2", headers=H, json={"title": "x" * 60}).status_code == 200
    assert c.put(f"/weeks/{WEEK}/nights/2", headers=H, json={"title": "y" * 201}).status_code == 422
    assert c.put("/weeks/not-a-date/nights/2", headers=H, json={"title": "x"}).status_code == 422


def test_nights_list_names_and_store_items_together(conn):
    p = pcx(); seen(conn, p)
    c = client(conn, p)
    pz = extras.add_extra(conn, WK[0], "frozen pizza", "PIZ", 2, day=4)
    extras.add_extra(conn, WK[0], "caesar salad kit", "KIT", 1, day=4)
    extras.add_extra(conn, WK[0], "caesar salad kit", "KIT", 1)                     # week-wide: not on a night
    extras.add_extra(conn, date(2026, 10, 18), "frozen pizza", "PIZ", 1, day=4)     # another week
    nights.set_title(conn, WK[0], 4, "Pizza night")
    nights.set_title(conn, WK[0], 1, "Leftovers")
    out = c.get(f"/weeks/{WEEK}/nights", headers=H).json()
    assert [(n["day"], n["title"], [(i["term"], i["quantity"]) for i in n["items"]]) for n in out] == [
        (1, "Leftovers", []), (4, "Pizza night", [("frozen pizza", 2), ("caesar salad kit", 1)])]
    assert out[1]["items"][0]["id"] == pz["id"] and out[1]["items"][0]["product"]["code"] == "PIZ"


def test_nights_without_recipes_never_ask_for_a_rating(conn):
    p = pcx(); seen(conn, p)
    c = client(conn, p)
    nights.set_title(conn, WK[0], 1, "Leftovers")                                    # Monday, before "today"
    extras.add_extra(conn, WK[0], "frozen pizza", "PIZ", 1, day=2)
    assert c.get("/ratings/pending", headers=H).json() == []
    assert c.get(f"/weeks/{WEEK}", headers=H).json() == []                          # the plan stays recipes only
    assert c.get("/weeks", headers=H, params={"from": WEEK, "count": 1}).json()[0]["entries"] == 0


# --- permissions + households ----------------------------------------------------------------------------------------

def test_permissions(conn):
    hid = db.first_household(conn)
    reader, _ = member_session(conn, hid, ["contributor"], name="Reader")
    planner, _ = member_session(conn, hid, ["planner"], name="Planner")
    c = client(conn)
    assert c.put(f"/weeks/{WEEK}/nights/3", headers=reader, json={"title": "Tacos"}).status_code == 403
    assert c.delete(f"/weeks/{WEEK}/nights/3", headers=reader).status_code == 403
    assert c.put(f"/weeks/{WEEK}/nights/3", headers=planner, json={"title": "Tacos"}).status_code == 200
    assert c.get(f"/weeks/{WEEK}/nights", headers=reader).json() == [{"day": 3, "title": "Tacos", "items": []}]
    # store items stay a purchaser's (cart.build), like any added item
    assert c.post("/extras", headers=planner, json={"week": WEEK, "day": 3, "term": "frozen pizza",
                                                    "product_code": "PIZ"}).status_code == 403
    assert c.delete(f"/weeks/{WEEK}/nights/3", headers=planner).status_code == 204


def test_households_never_see_each_others_nights(conn):
    p = pcx(); seen(conn, p)
    nights.set_title(conn, WK[0], 4, "Pizza night")
    xid = extras.add_extra(conn, WK[0], "frozen pizza", "PIZ", 1, day=4)["id"]
    other = new_household(conn)
    b, _ = member_session(conn, other, ALL_ROLES)
    c = client(conn, p)
    assert c.get(f"/weeks/{WEEK}/nights", headers=b).json() == []
    assert c.patch(f"/extras/{xid}", headers=b, json={"day": 1}).status_code == 404
    # their own name for the same night is theirs; ours is unchanged and theirs can be cleared without touching ours
    assert c.put(f"/weeks/{WEEK}/nights/4", headers=b, json={"title": "Their night"}).status_code == 200
    assert c.get(f"/weeks/{WEEK}/nights", headers=b).json() == [{"day": 4, "title": "Their night", "items": []}]
    assert c.delete(f"/weeks/{WEEK}/nights/4", headers=b).status_code == 204
    db.scope(conn, db.first_household(conn))
    [n] = nights.list_nights(conn, WK[0])
    assert (n["title"], [i["id"] for i in n["items"]], n["items"][0]["day"]) == ("Pizza night", [xid], 4)
    with db.unscoped(conn):
        assert conn.execute("SELECT count(*) FROM nights").fetchone()[0] == 1


def test_clean_title():
    assert nights.clean_title(None) is None and nights.clean_title(" \t ") is None
    assert nights.clean_title(" Taco\nTuesday ") == "Taco Tuesday"


def test_a_recipe_night_can_have_a_name_too(conn):
    rid = db.save_recipe(conn, Recipe(title="Soup", source="nyt", source_url="https://cooking.nytimes.com/recipes/9-s",
                                      ingredients=[], steps=[]))
    eid = db.add_to_week(conn, WK[0], rid)
    db.update_entry(conn, eid, day=2)
    nights.set_title(conn, WK[0], 2, "Soup night")
    assert [(e.day, e.title) for e in db.get_week(conn, WK[0])] == [(2, "Soup")]
    assert nights.list_nights(conn, WK[0]) == [{"day": 2, "title": "Soup night", "items": []}]


def test_week_summaries_count_added_items_and_named_nights(conn):
    p = pcx(); seen(conn, p)
    c = client(conn, p)
    nxt = "2026-10-18"
    assert c.get("/cart/default-week", headers=H).json() == {"week": None}
    extras.add_extra(conn, date(2026, 10, 18), "frozen pizza", "PIZ", 1, day=5)     # only store items, no recipe
    extras.add_extra(conn, date(2026, 10, 18), "caesar salad kit", "KIT", 1)
    nights.set_title(conn, date(2026, 10, 18), 5, "Pizza night")
    weeks = c.get("/weeks", headers=H, params={"from": WEEK, "count": 2}).json()
    assert weeks == [{"week": WEEK, "entries": 0, "extras": 0, "named_nights": 0, "carted": False, "cart_stale": False},
                     {"week": nxt, "entries": 0, "extras": 2, "named_nights": 1, "carted": False, "cart_stale": False}]
    # a week with only store items is something to shop for: the cart's default week
    assert c.get("/cart/default-week", headers=H).json() == {"week": nxt}
