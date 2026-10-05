from datetime import date, datetime, timedelta, timezone

from mealprep import db, drafts, planner, staples
from mealprep.models import Ingredient, ListItem, Product, Recipe, Staple
from mealprep.shopping import build_list
from test_api import H, client
from test_drafts import FakePcx, QtyAI, WK, build, sized


def S(name, qty=1, unit=None, sid=1):
    return Staple(id=sid, name=name, qty=qty, unit=unit, created_at="2026-10-05T00:00:00+00:00")


def R(title, *ings, servings=4):
    return Recipe(title=title, source="photo", servings=servings, ingredients=list(ings), steps=[])


# --- schema ---------------------------------------------------------------------------------------------------

def test_seed_runs_once_and_migration_is_idempotent(conn):
    conn.execute("DELETE FROM seeds WHERE name='staples'")
    conn.execute(db.SCHEMA)
    names = lambda: [n for (n,) in conn.execute("SELECT name FROM staples ORDER BY position")]
    assert names() == ["2% milk", "eggs", "lemonade"]
    assert conn.execute("SELECT qty::float, unit, weekly FROM staples WHERE name='eggs'").fetchone() == (1.0, None, True)
    conn.execute(db.SCHEMA)                       # every startup re-applies the schema
    assert names() == ["2% milk", "eggs", "lemonade"]
    conn.execute("DELETE FROM staples")
    conn.execute(db.SCHEMA)                       # a household that removed them all keeps an empty list
    assert names() == []


def test_seed_skips_a_table_that_already_has_rows(conn):
    conn.execute("DELETE FROM seeds WHERE name='staples'")
    conn.execute("INSERT INTO staples(name, qty) VALUES('oat milk', 2)")
    conn.execute(db.SCHEMA)
    assert [n for (n,) in conn.execute("SELECT name FROM staples")] == ["oat milk"]


# --- CRUD -----------------------------------------------------------------------------------------------------

def test_crud(conn):
    c = client(conn)
    assert c.get("/staples", headers=H).json() == []
    r = c.post("/staples", headers=H, json={"name": "  Oat   milk ", "qty": 2})
    assert r.status_code == 201
    a = r.json()
    assert a["name"] == "Oat milk" and a["qty"] == 2 and a["unit"] is None and a["weekly"] is True
    assert a["position"] == 0 and a["last_bought"] is None
    b = c.post("/staples", headers=H, json={"name": "butter", "qty": 454, "unit": "Grams", "weekly": False}).json()
    assert (b["unit"], b["position"], b["weekly"]) == ("g", 1, False)
    r = c.patch(f"/staples/{a['id']}", headers=H, json={"qty": None, "unit": "L", "weekly": False})
    assert r.status_code == 200 and (r.json()["qty"], r.json()["unit"], r.json()["weekly"]) == (None, "l", False)
    assert c.patch(f"/staples/{a['id']}", headers=H, json={"name": "soy milk"}).json()["name"] == "soy milk"
    assert [s["name"] for s in c.get("/staples", headers=H).json()] == ["soy milk", "butter"]
    assert c.delete(f"/staples/{a['id']}", headers=H).status_code == 204
    assert c.delete(f"/staples/{a['id']}", headers=H).status_code == 204     # idempotent
    assert [s["name"] for s in c.get("/staples", headers=H).json()] == ["butter"]


def test_validation_and_errors(conn):
    c = client(conn)
    sid = c.post("/staples", headers=H, json={"name": "eggs"}).json()["id"]
    assert c.post("/staples", headers=H, json={"name": "Eggs"}).status_code == 409      # same item
    assert c.post("/staples", headers=H, json={"name": "  "}).status_code == 422
    assert c.post("/staples", headers=H, json={"name": "x", "unit": "dozen"}).status_code == 422
    assert c.post("/staples", headers=H, json={"name": "x", "qty": 0}).status_code == 422
    assert c.post("/staples", headers=H, json={"name": "x", "weekly": "yes"}).status_code == 422
    assert c.patch("/staples/999", headers=H, json={"qty": 2}).status_code == 404
    assert c.patch(f"/staples/{sid}", headers=H, json={"weekly": None}).status_code == 422
    assert c.patch(f"/staples/{sid}", headers=H, json={"name": None}).status_code == 422
    other = c.post("/staples", headers=H, json={"name": "bread"}).json()["id"]
    assert c.patch(f"/staples/{other}", headers=H, json={"name": "egg"}).status_code == 409
    assert c.get("/staples").status_code == 401


def test_reorder_by_position(conn):
    c = client(conn)
    ids = [c.post("/staples", headers=H, json={"name": n}).json()["id"] for n in ("a", "b", "c", "d")]
    order = lambda: [s["name"] for s in c.get("/staples", headers=H).json()]
    assert c.patch(f"/staples/{ids[3]}", headers=H, json={"position": 0}).json()["position"] == 0
    assert order() == ["d", "a", "b", "c"]
    c.patch(f"/staples/{ids[3]}", headers=H, json={"position": 99})            # past the end = last
    assert order() == ["a", "b", "c", "d"]
    c.patch(f"/staples/{ids[0]}", headers=H, json={"position": 2})
    assert order() == ["b", "c", "a", "d"]
    assert [s["position"] for s in c.get("/staples", headers=H).json()] == [0, 1, 2, 3]
    assert c.patch(f"/staples/{ids[0]}", headers=H, json={"position": -1}).status_code == 422


# --- last bought ----------------------------------------------------------------------------------------------

def _cart(conn, key, sent_at=None, status="sent", line_status="added", removed=False):
    cid = conn.execute("INSERT INTO carts(status, weeks, sent_at) VALUES(%s,%s,%s) RETURNING id",
                       (status, [date(2026, 10, 4)], sent_at)).fetchone()[0]
    conn.execute("INSERT INTO cart_lines(cart_id, item_key, item_name, status, source, removed) "
                 "VALUES(%s,%s,%s,%s,'ai',%s)", (cid, key, key.split("|")[0], line_status, removed))


def test_last_bought_comes_from_sent_carts_only(conn):
    sid = staples.create_staple(conn, "Eggs").id
    assert staples.get_staple(conn, sid).last_bought is None
    noon = lambda d: datetime(d.year, d.month, d.day, 12, tzinfo=timezone.utc)
    _cart(conn, "egg|each", noon(date(2026, 9, 20)))
    _cart(conn, "egg|each", noon(date(2026, 9, 27)))
    _cart(conn, "egg|each", status="ready", line_status="matched")             # a draft never sent
    _cart(conn, "egg|each", noon(date(2026, 10, 1)), removed=True, line_status="matched")   # removed before send
    _cart(conn, "egg yolk|each", noon(date(2026, 10, 2)))                       # another item
    assert staples.get_staple(conn, sid).last_bought == "2026-09-27"
    _cart(conn, "egg, beaten|each", noon(date(2026, 9, 29)))                    # pre-clean-up key, same item
    assert staples.get_staple(conn, sid).last_bought == "2026-09-29"


def test_last_bought_matches_any_unit_and_prefers_a_later_manual_hint(conn):
    sid = staples.create_staple(conn, "2% milk").id
    _cart(conn, "2% milk|vol", datetime(2026, 9, 27, 12, tzinfo=timezone.utc))
    assert staples.list_staples(conn)[0].last_bought == "2026-09-27"
    c = client(conn)
    assert c.patch(f"/staples/{sid}", headers=H, json={"last_bought": "2026-09-01"}).json()["last_bought"] == "2026-09-27"
    assert c.patch(f"/staples/{sid}", headers=H, json={"last_bought": "2026-10-03"}).json()["last_bought"] == "2026-10-03"


def test_last_bought_via_a_real_draft_send(conn):
    sid = staples.create_staple(conn, "lemonade").id
    [it] = build_list([], staples=[staples.get_staple(conn, sid)])
    did = build(conn, [it], FakePcx({"lemonade": [sized("LM", "2 l")]}), QtyAI("LM", 1))
    assert staples.get_staple(conn, sid).last_bought is None                     # ready, not sent
    drafts.send_draft(conn, did, FakePcx({}))
    assert staples.get_staple(conn, sid).last_bought == datetime.now().astimezone().date().isoformat()


# --- the list -------------------------------------------------------------------------------------------------

def test_pack_staple_stands_alone():
    [it] = build_list([], staples=[S("2% Milk")])
    assert (it.key, it.name, it.qty, it.unit) == ("2% milk|each", "2% milk", None, None)
    assert it.staple and it.staple_packs == 1 and it.needed and not it.likely_on_hand and it.recipes == ["Staples"]


def test_pack_staple_merges_into_a_recipe_line_of_any_unit():
    milk = Ingredient(raw="1 cup 2% milk", name="2% milk", qty=1, unit="cup")
    out = build_list([(R("Pancakes", milk), 1)], staples=[S("2% milk", qty=2)])
    [it] = out
    assert it.key == "2% milk|vol" and it.qty == 1 and it.unit == "cup"
    assert it.staple and it.staple_packs == 2 and it.recipes == ["Pancakes", "Staples"]


def test_pack_staple_makes_a_likely_on_hand_line_needed():
    eggs = Ingredient(raw="2 eggs", name="eggs", qty=2, likely_on_hand=True)
    [it] = build_list([(R("Cake", eggs), 1)], staples=[S("Eggs")])
    assert it.key == "egg|each" and it.qty == 2 and it.needed and not it.likely_on_hand and it.staple_packs == 1


def test_pack_staple_joins_an_unmeasured_line():
    salt = Ingredient(raw="salt, to taste", name="salt")
    [it] = build_list([(R("Soup", salt), 1)], staples=[S("salt")])
    assert it.qty is None and it.staple_packs == 1 and it.recipes == ["Soup", "Staples"]


def test_measured_staple_adds_to_the_recipe_amount_unscaled():
    flour = Ingredient(raw="2 cups flour", name="flour", qty=2, unit="cup")
    out = build_list([(R("Bread", flour, servings=8), 1)], staples=[S("flour", qty=3, unit="cup")])
    [it] = out
    assert it.qty == 4 and it.unit == "cup" and it.staple and it.staple_packs is None   # 2 × 4/8 + 3 (not scaled)


def test_measured_staple_in_another_unit_family_is_its_own_line():
    flour = Ingredient(raw="2 cups flour", name="flour", qty=2, unit="cup")
    out = build_list([(R("Bread", flour), 1)], staples=[S("flour", qty=500, unit="g")])
    assert {(i.key, i.staple) for i in out} == {("flour|vol", False), ("flour|mass", True)}


def test_list_endpoint_merges_only_the_staples_asked_for(conn):
    c = client(conn)
    rid = db.save_recipe(conn, R("Pancakes", Ingredient(raw="1 cup milk", name="milk", qty=1, unit="cup")))
    db.add_to_week(conn, date(2026, 10, 11), rid)
    milk = c.post("/staples", headers=H, json={"name": "milk"}).json()["id"]
    lemon = c.post("/staples", headers=H, json={"name": "lemonade", "weekly": False}).json()["id"]
    plain = c.post("/list", headers=H, json={"weeks": ["2026-10-11"]}).json()
    assert [(i["key"], i["staple"]) for i in plain] == [("milk|vol", False)]
    out = c.post("/list", headers=H, json={"weeks": ["2026-10-11"], "staples": [milk, lemon, 999]}).json()
    assert [(i["key"], i["staple"], i["staple_packs"], i["recipes"]) for i in out] == [
        ("milk|vol", True, 1, ["Pancakes", "Staples"]), ("lemonade|each", True, 1, ["Staples"])]
    only = c.post("/list", headers=H, json={"weeks": ["2026-11-01"], "staples": [lemon]}).json()
    assert [i["key"] for i in only] == ["lemonade|each"]                # staples alone, nothing planned


# --- planner + drafts -----------------------------------------------------------------------------------------

def test_planner_pure_staple_is_exactly_its_packs():
    it = ListItem(key="2% milk|each", name="2% milk", staple=True, staple_packs=1)
    p = planner.plan(it, sized("M", "2 l"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)
    assert p.why(None) == "Need 1 pack (weekly staple) → 1 × 2 L"
    assert planner.default_quantity(it, sized("M", "2 l"), 4) == 1
    two = it.model_copy(update={"staple_packs": 2})
    assert planner.default_quantity(two, sized("E", "12 ea"), 1) == 2


def test_planner_merged_staple_is_a_floor():
    it = ListItem(key="2% milk|vol", name="2% milk", qty=6, unit="cup", staple=True, staple_packs=1)
    p = planner.plan(it, sized("M", "1 l"))
    assert p.packs_min == 2 and p.why(2) == "Need 6 cups; staple: at least 1 pack → 2 × 1 L"   # the recipe needs more
    it = it.model_copy(update={"qty": 1, "staple_packs": 3})
    assert planner.plan(it, sized("M", "2 l")).packs_min == 3                                   # the staple needs more


def test_planner_staple_with_an_uncomparable_amount_keeps_the_check():
    it = ListItem(key="salt|vol", name="salt", qty=1, unit="tsp", staple=True, staple_packs=1)
    p = planner.plan(it, sized("S", "1 kg"))
    assert p.packs_min == 1 and p.enforce and p.needs_check and "check:" in p.why(1)


def test_draft_uses_the_staple_floor_and_remembered_picks(conn):
    [it] = build_list([], staples=[S("eggs", qty=2)])
    ai = QtyAI("E12", 1)
    did = build(conn, [it], FakePcx({"eggs": [sized("E12", "12 ea"), sized("E18", "18 ea")]}), ai)
    line = drafts.get_draft(conn, did)["lines"][0]
    assert line["quantity"] == 2 and line["staple"] is True and line["staple_packs"] == 2
    assert line["why"] == "Need 2 packs (weekly staple) → 2 × 12 ea"
    assert "weekly staple: at least 2 packages" in ai.prompts[0]
    db.set_pick(conn, it.key, "E18", chosen_by="user")
    ai2 = QtyAI("E12", 1)
    did = build(conn, [it], FakePcx({"eggs": [sized("E12", "12 ea"), sized("E18", "18 ea")]}), ai2)
    line = drafts.get_draft(conn, did)["lines"][0]
    assert (line["source"], line["product"]["code"], line["quantity"]) == ("memory", "E18", 2) and ai2.prompts == []
    d = drafts.update_line(conn, did, line["id"], product_code="E12")              # swap keeps the staple floor
    assert d["quantity"] == 2


def test_draft_contract_unchanged_for_items_without_staple_fields(conn):
    old = ListItem(key="egg|each", name="egg", qty=3, recipes=["Cake"])
    did = build(conn, [old], FakePcx({"egg": [sized("E12", "12 ea")]}), QtyAI("E12", 1))
    line = drafts.get_draft(conn, did)["lines"][0]
    assert line["quantity"] == 1 and line["staple"] is False and line["staple_packs"] is None
