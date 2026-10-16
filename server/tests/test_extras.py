"""Items added by search (0.13.0): the chooser without a line, added items per week, their cart lines (build, open
cart, Add an item on a cart), mirrored edits. Invented products."""
from datetime import date

from fastapi.testclient import TestClient

from mealprep import db, drafts, extras
from mealprep.api import create_app
from mealprep.config import Settings
from mealprep.models import Product

from test_chooser import AI, Pcx, WK, build, item

H = {"Authorization": "Bearer secret"}


def towels(code="TOW", stock="OK", price=8.99, **kw):
    return Product(code=code, name="Paper Towels", brand="Bounty", package_size="6 rolls", price=price, stock=stock, **kw)


def client(conn, pcx, ai=None):
    s = Settings(dsn="unused", token="secret", provider="fake")
    return TestClient(create_app(s, provider=ai or AI(), pcx=pcx, conn=conn, today=lambda: date(2026, 10, 7)))


def seen(conn, pcx, term="paper towels"):
    """The chooser search that makes the products known (as the app does before adding one)."""
    return drafts.search_choices(conn, pcx, term)


def test_extra_key_is_the_search_as_an_item():
    assert extras.extra_key("Paper  Towels") == "paper towel|each"


def test_search_choices_have_no_current_and_show_bought_before(conn):
    pcx = Pcx({"paper towels": [towels("A"), towels("B", price=7.5, sale_ends="2026-10-21")]})
    out = seen(conn, pcx)
    assert [(c["product"]["code"], c["group"], c["current"]) for c in out["choices"]] == [
        ("B", "deal", False), ("A", "other", False)]
    e = extras.add_extra(conn, WK[0], "paper towels", "A", 1)
    pcx.catalog["A"] = towels("A")
    did = build(conn, pcx, AI(), extras.items_for(conn, WK))
    drafts.send_draft(conn, did, pcx)
    pcx.results["paper towels"] = [towels("B", price=7.5)]                # A dropped out of the search
    out = seen(conn, pcx)
    assert [(c["product"]["code"], c["group"]) for c in out["choices"]] == [("A", "bought"), ("B", "other")]
    assert out["choices"][0]["last_bought"]["price"] == 8.99 and e["key"] == "paper towel|each"


def test_add_extra_needs_a_seen_product_and_keeps_order_limits(conn):
    pcx = Pcx({"deli ham": [Product(code="HAM", name="Black Forest Ham", price=2.49, stock="OK", weighted=True,
                                    min_qty=100, max_qty=999, interval=100, price_quantity=100)]})
    try:
        extras.add_extra(conn, WK[0], "deli ham", "HAM", 1)
        assert False, "unknown product"
    except drafts.UnknownProduct:
        pass
    seen(conn, pcx, "deli ham")
    e = extras.add_extra(conn, date(2026, 10, 14), "  deli   ham ", "HAM", 250)
    assert (e["week"], e["term"], e["quantity"], e["product"]["code"]) == ("2026-10-11", "deli ham", 300, "HAM")
    assert db.get_pick_full(conn, "deli ham|each") == ("HAM", "user")
    assert [x["id"] for x in extras.list_extras(conn, WK)] == [e["id"]]
    assert extras.list_extras(conn, [date(2026, 10, 18)]) == []


def test_draft_lines_use_the_chosen_product_and_quantity(conn):
    pcx = Pcx({"cheddar": [Product(code="CH", name="Old Cheddar", package_size="400 g", price=5.0, stock="OK")],
               "paper towels": [towels()]}, catalog={"TOW": towels()})
    seen(conn, pcx)
    extras.add_extra(conn, WK[0], "paper towels", "TOW", 2)
    c = client(conn, pcx)
    body = {"items": [item().model_dump()], "weeks": ["2026-10-11"]}
    did = drafts.create_draft(conn, [item(), *extras.items_for(conn, WK)], WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, pcx, AI("CH"), workers=1)
    lines = drafts.get_draft(conn, did)["lines"]
    assert [(l["name"], l["product"]["code"], l["quantity"], l["source"], l["recipes"]) for l in lines] == [
        ("cheddar", "CH", 1, "ai", ["Mac"]), ("paper towels", "TOW", 2, "user", ["Added"])]
    assert lines[1]["extra_id"] is not None and lines[0]["extra_id"] is None
    assert c.post("/drafts", headers=H, json=body).status_code == 202      # the API adds them itself


def test_api_draft_includes_the_weeks_added_items(conn):
    pcx = Pcx({"paper towels": [towels()]}, catalog={"TOW": towels()})
    seen(conn, pcx)
    extras.add_extra(conn, WK[0], "paper towels", "TOW", 1)
    # The background build can't connect (dsn "unused"), so the line stays pending.
    did = client(conn, pcx).post("/drafts", headers=H, json={"items": [], "weeks": ["2026-10-11"]}).json()["id"]
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["name"] == "paper towels" and line["status"] == "pending" and line["extra_id"] is not None


def test_out_of_stock_added_item_is_matched_by_its_term_with_a_note(conn):
    pcx = Pcx({"paper towels": [towels(), towels("OTHER", price=7.0)]}, catalog={"TOW": towels(stock="OUT")})
    seen(conn, pcx)
    extras.add_extra(conn, WK[0], "paper towels", "TOW", 2)
    did = build(conn, pcx, AI("OTHER"), extras.items_for(conn, WK))
    [line] = drafts.get_draft(conn, did)["lines"]
    assert (line["product"]["code"], line["quantity"], line["source"]) == ("OTHER", 2, "ai")
    assert line["note"] == "Paper Towels is out of stock this week — matched another."
    assert db.get_pick_full(conn, "paper towel|each") == ("TOW", "user")    # the substitute isn't remembered


def test_added_item_joins_the_open_cart_and_follows_edits(conn):
    pcx = Pcx({"cheddar": [Product(code="CH", name="Old Cheddar", package_size="400 g", price=5.0, stock="OK")],
               "paper towels": [towels(), towels("T2", price=6.0)]})
    did = build(conn, pcx, AI("CH"))
    seen(conn, pcx)
    e = extras.add_extra(conn, WK[0], "paper towels", "TOW", 1)
    lines = drafts.get_draft(conn, did)["lines"]
    assert [(l["name"], l["product"]["code"], l["quantity"]) for l in lines] == [("cheddar", "CH", 1), ("paper towels", "TOW", 1)]
    assert drafts.get_draft(conn, did)["progress"] == {"done": 2, "total": 2}
    extras.update_extra(conn, e["id"], quantity=3, product_code="T2")
    assert drafts.get_draft(conn, did)["lines"][1]["product"]["code"] == "T2"
    assert drafts.get_draft(conn, did)["lines"][1]["quantity"] == 3
    extras.delete_extra(conn, e["id"])
    assert drafts.get_draft(conn, did)["lines"][1]["removed"] is True and extras.list_extras(conn, WK) == []
    extras.delete_extra(conn, e["id"])                                    # idempotent
    drafts.send_draft(conn, did, pcx)
    extras.add_extra(conn, WK[0], "paper towels", "TOW", 1)               # a sent cart is left alone
    assert len(drafts.get_draft(conn, did)["lines"]) == 2


def test_add_an_item_on_a_cart_and_line_edits_mirror_the_item(conn):
    pcx = Pcx({"cheddar": [Product(code="CH", name="Old Cheddar", package_size="400 g", price=5.0, stock="OK")],
               "paper towels": [towels(), towels("T2", price=6.0)]})
    did = build(conn, pcx, AI("CH"))
    c = client(conn, pcx)
    out = c.get("/products/choices", headers=H, params={"term": "paper towels"}).json()
    assert [x["product"]["code"] for x in out["choices"]] == ["TOW", "T2"]
    r = c.post(f"/drafts/{did}/lines", headers=H, json={"term": "paper towels", "product_code": "TOW", "quantity": 2})
    assert r.status_code == 201
    line = r.json()
    assert (line["name"], line["quantity"], line["source"], line["recipes"]) == ("paper towels", 2, "user", ["Added"])
    [e] = c.get("/extras", headers=H, params={"week": "2026-10-11"}).json()
    assert (e["id"], e["quantity"], e["product"]["code"]) == (line["extra_id"], 2, "TOW")
    c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"quantity": 4, "product_code": "T2"})
    [e] = c.get("/extras", headers=H, params={"week": "2026-10-11"}).json()
    assert (e["quantity"], e["product"]["code"]) == (4, "T2")
    c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"removed": True})
    assert c.get("/extras", headers=H, params={"week": "2026-10-11"}).json() == []
    c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"removed": False})     # Undo
    [e] = c.get("/extras", headers=H, params={"week": "2026-10-11"}).json()
    assert (e["id"], e["quantity"], e["product"]["code"]) == (line["extra_id"], 4, "T2")


def test_extras_api_errors(conn):
    pcx = Pcx({"paper towels": [towels()]})
    c = client(conn, pcx)
    assert c.post("/extras", headers=H, json={"week": "2026-10-11", "term": "paper towels", "product_code": "TOW"}).status_code == 422
    seen(conn, pcx)
    r = c.post("/extras", headers=H, json={"week": "2026-10-11", "term": "paper towels", "product_code": "TOW"})
    assert r.status_code == 201 and r.json()["quantity"] == 1
    assert c.post("/extras", headers=H, json={"week": "2026-10-11", "term": " ", "product_code": "TOW"}).status_code == 422
    assert c.patch("/extras/999", headers=H, json={"quantity": 2}).status_code == 404
    assert c.patch(f"/extras/{r.json()['id']}", headers=H, json={"quantity": 0}).status_code == 422
    assert c.delete(f"/extras/{r.json()['id']}", headers=H).status_code == 204
    assert c.get("/extras", headers=H).status_code == 422                 # a week is required
    assert c.get("/products/choices", headers=H).status_code == 422
    did = build(conn, pcx, AI("TOW"))
    drafts.send_draft(conn, did, pcx)
    assert c.post(f"/drafts/{did}/lines", headers=H, json={"term": "x", "product_code": "TOW"}).status_code == 409
