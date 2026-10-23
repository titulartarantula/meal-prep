"""HTTP side of the fridge list (0.14.0)."""
from datetime import date

from mealprep import db
from test_api import H, client
from test_fridge import MapAI, R

TODAY = date(2026, 10, 7)


def test_auth_required(conn):
    c = client(conn)
    for method, path in (("get", "/fridge"), ("post", "/fridge"), ("patch", "/fridge/1"), ("delete", "/fridge/1"),
                         ("get", "/fridge/names?q=e")):
        assert getattr(c, method)(path).status_code == 401, path


def test_crud(conn):
    c = client(conn, ai=MapAI())
    assert c.get("/fridge", headers=H).json() == []
    r = c.post("/fridge", headers=H, json={"name": "  Large   eggs ", "qty": 6})
    assert r.status_code == 201
    a = r.json()
    assert (a["name"], a["key"], a["key_source"], a["qty"], a["unit"]) == ("Large eggs", "egg", "rule", 6, None)
    assert a["amount_as_of"] == TODAY.isoformat() and a["days_since_checked"] == 0 and a["soon"] is False
    b = c.post("/fridge", headers=H, json={"name": "Milk", "qty": 1, "unit": "Litres", "use_soon": True}).json()
    assert b["unit"] == "l" and b["soon"] is True
    assert [i["name"] for i in c.get("/fridge", headers=H).json()] == ["Milk", "Large eggs"]
    r = c.patch(f"/fridge/{a['id']}", headers=H, json={"qty": 4})
    assert r.status_code == 200 and r.json()["qty"] == 4
    r = c.patch(f"/fridge/{a['id']}", headers=H, json={"confirm": True})
    assert r.status_code == 200 and r.json()["qty"] == 4
    assert c.delete(f"/fridge/{a['id']}", headers=H).status_code == 204
    assert c.delete(f"/fridge/{a['id']}", headers=H).status_code == 204
    assert c.patch(f"/fridge/{a['id']}", headers=H, json={"qty": 1}).status_code == 404


def test_duplicate_is_409_with_the_existing_id(conn):
    c = client(conn, ai=MapAI())
    a = c.post("/fridge", headers=H, json={"name": "Eggs", "qty": 6}).json()
    r = c.post("/fridge", headers=H, json={"name": "egg", "qty": 2})
    assert r.status_code == 409
    assert r.json() == {"detail": "Already in the fridge: 6 Eggs", "id": a["id"]}


def test_validation(conn):
    c = client(conn, ai=MapAI())
    bad = [{"name": ""}, {"name": "   "}, {"name": "x" * 81}, {"name": "eggs", "qty": -1},
           {"name": "eggs", "qty": 10001}, {"name": "eggs", "unit": "furlong"}, {"name": "eggs", "use_by": "2028-01-01"},
           {"name": "eggs", "use_by": "2025-01-01"}, {"name": "eggs", "use_soon": "maybe"}, {}]
    for body in bad:
        assert c.post("/fridge", headers=H, json=body).status_code == 422, body
    a = c.post("/fridge", headers=H, json={"name": "eggs", "qty": 0}).json()
    assert a["qty"] == 0
    for body in ({"name": None}, {"name": ""}, {"qty": -2}, {"unit": "parsec"}, {"use_soon": None}, {"confirm": "x"}):
        assert c.patch(f"/fridge/{a['id']}", headers=H, json=body).status_code == 422, body
    assert c.patch(f"/fridge/{a['id']}", headers=H, json={"qty": None, "unit": None}).json()["qty"] is None


def test_from_list_skips_the_mapping_call(conn):
    ai = MapAI(match="whole milk")
    db.save_recipe(conn, R("A", ("1 cup milk", "whole milk", 1, "cup")))
    c = client(conn, ai=ai)
    c.post("/fridge", headers=H, json={"name": "Whole milk", "qty": 1, "unit": "l", "from_list": True})
    c.post("/fridge", headers=H, json={"name": "Kale", "from_list": True})
    assert ai.prompts == []
    c.post("/fridge", headers=H, json={"name": "Chard"})
    assert len(ai.prompts) == 1


def test_names_suggestions(conn):
    db.save_recipe(conn, R("A", ("2 eggs", "eggs", 2, None), ("eggplant", "eggplant", 1, None),
                           ("1 cup milk", "whole milk", 1, "cup")))
    db.save_recipe(conn, R("B", ("1 egg", "egg", 1, None)))
    c = client(conn)
    assert c.get("/fridge/names?q=", headers=H).json() == []
    assert c.get("/fridge/names?q=e", headers=H).json() == ["egg", "eggplant", "whole milk"]
    assert c.get("/fridge/names?q=MILK", headers=H).json() == ["whole milk"]
    for i in range(20):
        db.save_recipe(conn, R(f"X{i}", (f"thing{i}", f"thing{i}", 1, None)))
    assert len(c.get("/fridge/names?q=t", headers=H).json()) == 12
    assert c.get("/fridge/names?q=" + "x" * 81, headers=H).status_code == 422
