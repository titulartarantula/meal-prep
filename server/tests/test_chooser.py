"""Product chooser (0.12.0): choices order and labels, paging, better value, pins, order limits, old drafts."""
from datetime import date
import itertools
import httpx
from fastapi.testclient import TestClient
from psycopg.types.json import Jsonb
from mealprep import db, drafts
from mealprep.api import create_app
from mealprep.config import Settings
from mealprep.models import ListItem, Product

WK = [date(2026, 10, 11)]
_ids = itertools.count(1)


class Pcx:
    """search / search_page by term ({term: [Product]}, paged by `size`), product(code) from `catalog`."""
    def __init__(self, results=None, catalog=None, total=None, fail_detail=False):
        self.results, self.catalog, self.total, self.fail_detail = results or {}, catalog or {}, total, fail_detail
        self.pages, self.fetched, self.created, self.adds = [], [], [], []

    def search(self, term, size=8):
        return self.search_page(term, 0, size)[0]

    def search_page(self, term, page=0, size=20):
        self.pages.append((term, page))
        res = self.results.get(term, [])
        return list(res[page * size:(page + 1) * size]), self.total if self.total is not None else len(res)

    def product(self, code):
        self.fetched.append(code)
        if self.fail_detail:
            raise httpx.ConnectError("down")
        return self.catalog.get(code)

    def create_cart(self):
        cid = f"pcx{next(_ids)}"; self.created.append(cid); return cid

    def add(self, cid, entries): self.adds.append((cid, dict(entries)))


class AI:
    def __init__(self, code=None): self.code, self.n = code, 0
    def complete_json(self, prompt, images=None):
        self.n += 1
        return {"code": self.code, "quantity": 1}


def P(code, price=5.0, name="Old Cheddar Cheese", size="400 g", stock="OK", unit_price=None, unit="100 g", **kw):
    return Product(code=code, name=name, brand="PC", package_size=size, price=price, stock=stock,
                   unit_price=unit_price, unit_price_unit=unit if unit_price is not None else None, **kw)


def item(name="cheddar", qty=None, unit=None, **kw):
    return ListItem(key=f"{name}|{unit or 'each'}", name=name, qty=qty, unit=unit, recipes=["Mac"], **kw)


def build(conn, pcx, ai, items=None):
    did = drafts.create_draft(conn, items or [item()], WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, pcx, ai, store_id="1092", workers=1)
    return did


def sent_before(conn, pcx, code, when, price=None, it=None):
    """A sent cart that bought `code` for the cheddar line, sent at `when`."""
    did = build(conn, pcx, AI(code), [it or item()])
    drafts.send_draft(conn, did, pcx)
    conn.execute("UPDATE carts SET sent_at=%s WHERE id=%s", (when, did))
    if price is not None:
        conn.execute("UPDATE cart_lines SET price_at_add=%s WHERE cart_id=%s", (price, did))
    conn.execute("DELETE FROM picks")                       # the next build chooses afresh
    return did


# --- order, labels, last time ---------------------------------------------------------------------------------

def test_choices_order_current_bought_deal_rest_then_out_of_stock(conn):
    res = [P("ADS", sponsored=True), P("PLAIN"), P("SALE", sale_ends="2026-10-21", was_price=6.0),
           P("OPT", optimum_text="PC Optimum Offer", optimum_points=4000, optimum_ends="2026-11-04"),
           P("OLD"), P("NEW"), P("GONE", stock="OUT"), P("CUR")]
    pcx = Pcx({"cheddar": res})
    sent_before(conn, pcx, "OLD", "2026-09-01T12:00Z", price=5.5)
    sent_before(conn, pcx, "NEW", "2026-09-28T12:00Z", price=4.5)
    did = build(conn, pcx, AI("CUR"))
    [line] = drafts.get_draft(conn, did)["lines"]
    out = drafts.choices(conn, did, line["id"], pcx)
    order = [(c["product"]["code"], c["group"]) for c in out["choices"]]
    assert order == [("CUR", "current"), ("NEW", "bought"), ("OLD", "bought"), ("SALE", "deal"), ("OPT", "deal"),
                     ("ADS", "other"), ("PLAIN", "other"), ("GONE", "out")]
    by = {c["product"]["code"]: c for c in out["choices"]}
    assert by["CUR"]["current"] and not by["NEW"]["current"]
    assert by["ADS"]["product"]["sponsored"] is True
    assert by["NEW"]["last_bought"]["date"].startswith("2026-09-28") and by["NEW"]["last_bought"]["price"] == 4.5
    assert by["NEW"]["price_change"] == 0.5 and by["OLD"]["price_change"] == -0.5     # now $5.00
    assert by["PLAIN"]["last_bought"] is None and by["PLAIN"]["price_change"] is None
    assert out["term"] == "cheddar" and out["page"] == 0 and out["has_more"] is False


def test_out_of_stock_usual_product_goes_last_but_stays_marked(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    pcx.results["cheddar"] = [P("A", stock="OUT"), P("B")]
    out = drafts.choices(conn, did, line["id"], pcx)
    assert [(c["product"]["code"], c["group"], c["current"]) for c in out["choices"]] == [
        ("B", "other", False), ("A", "out", True)]


def test_bought_products_missing_from_the_search_are_fetched_on_page_0_only(conn):
    pcx = Pcx({"cheddar": [P("A"), P("X")]}, catalog={"X": P("X"), "Y": P("Y", price=4.0)})
    sent_before(conn, pcx, "X", "2026-09-28T12:00Z")
    pcx.results["cheddar"] = [P("A")]                     # X dropped out of the search this week
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    out = drafts.choices(conn, did, line["id"], pcx)
    assert [c["product"]["code"] for c in out["choices"]] == ["A", "X"] and pcx.fetched == ["X"]
    pcx.fetched.clear()
    drafts.choices(conn, did, line["id"], pcx, term="aged cheddar")       # a typed search: just the search
    drafts.choices(conn, did, line["id"], pcx, page=1)
    assert pcx.fetched == []


def test_a_failed_fetch_of_a_bought_product_is_skipped(conn):
    pcx = Pcx({"cheddar": [P("A"), P("X")]})
    sent_before(conn, pcx, "X", "2026-09-28T12:00Z")
    pcx.results["cheddar"] = [P("A")]
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    pcx.fail_detail = True
    assert [c["product"]["code"] for c in drafts.choices(conn, did, line["id"], pcx)["choices"]] == ["A"]


def test_paging_passes_the_page_number_and_says_when_there_is_more(conn):
    res = [P(f"C{i}") for i in range(45)]
    pcx = Pcx({"cheddar": res})
    did = build(conn, pcx, AI("C0"))
    [line] = drafts.get_draft(conn, did)["lines"]
    p0 = drafts.choices(conn, did, line["id"], pcx)
    assert len(p0["choices"]) == 20 and p0["has_more"] and p0["total"] == 45
    p2 = drafts.choices(conn, did, line["id"], pcx, page=2)
    assert [c["product"]["code"] for c in p2["choices"]] == ["C40", "C41", "C42", "C43", "C44"] and not p2["has_more"]
    assert pcx.pages[-1] == ("cheddar", 2)
    p1 = drafts.choices(conn, did, line["id"], pcx, term="  ", page=1)          # blank term = the item name
    assert p1["term"] == "cheddar" and p1["has_more"]
    # the current product isn't on page 1, and isn't added there either
    assert "C0" not in [c["product"]["code"] for c in p1["choices"]]


def test_current_product_missing_from_page_0_comes_from_the_last_snapshot(conn):
    pcx = Pcx({"cheddar": [P("A", image="https://img.invalid/a.png")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    out = drafts.choices(conn, did, line["id"], pcx, term="aged cheddar")
    assert out["choices"][0]["product"]["code"] == "A" and out["choices"][0]["current"]
    assert out["choices"][0]["product"]["image"] == "https://img.invalid/a.png"


def test_choices_unknown_line_raises(conn):
    pcx = Pcx({"cheddar": [P("A")]})
    did = build(conn, pcx, AI("A"))
    try:
        drafts.choices(conn, did, 9999, pcx)
    except drafts.DraftNotFound:
        return
    raise AssertionError("expected DraftNotFound")


# --- better value -----------------------------------------------------------------------------------------------

def test_better_value_same_kind_same_unit_and_no_bigger_pack():
    it = item("old cheddar")
    p = P("A", price=8.99, unit_price=2.25)
    cheaper_bar = P("B", price=6.5, name="Old Cheddar Cheese Bar", unit_price=1.63)
    cheapest = P("C", price=4.88, unit_price=1.22)
    other_kind = P("D", price=4.0, name="Medium Cheddar Cheese", unit_price=1.0)
    other_unit = P("E", price=4.0, unit_price=1.0, unit="1 kg")
    out = P("F", price=1.0, unit_price=0.25, stock="OUT")
    hardly = P("G", price=8.9, unit_price=2.20)                               # < 3 % cheaper
    assert drafts.better_value(it, p, [p, cheaper_bar, cheapest, other_kind, other_unit, out, hardly]).code == "C"
    assert drafts.better_value(it, cheapest, [p, cheaper_bar, cheapest]) is None
    assert drafts.better_value(it, P("N", unit_price=None), [cheapest]) is None


def test_better_value_bigger_pack_only_when_it_would_be_used():
    small = P("S", price=8.99, unit_price=2.25)                     # 400 g
    big = P("B", price=10.99, size="700 g", unit_price=1.57)
    assert drafts.better_value(item("old cheddar", qty=200, unit="g"), small, [big]) is None    # 200 g: 1 small does
    assert drafts.better_value(item("old cheddar", qty=600, unit="g"), small, [big]).code == "B"  # 2 small cost more
    assert drafts.better_value(item("old cheddar", staple=True), small, [big]).code == "B"       # a staple gets used
    assert drafts.better_value(item("old cheddar"), small, [big]) is None                         # no amount: unsure


def test_choices_carry_the_better_value_suggestion_without_swapping(conn):
    pcx = Pcx({"cheddar": [P("A", price=8.99, unit_price=2.25), P("C", price=4.88, unit_price=1.22)]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    by = {c["product"]["code"]: c for c in drafts.choices(conn, did, line["id"], pcx)["choices"]}
    assert by["A"]["better_value"]["code"] == "C" and by["A"]["better_value"]["unit_price"] == 1.22
    assert by["C"]["better_value"] is None
    assert drafts.get_draft(conn, did)["lines"][0]["product"]["code"] == "A"


# --- order limits, weighed products ---------------------------------------------------------------------------

DELI = dict(name="Oven Roasted Turkey Breast", size=None, price=4.5, weighted=True, price_quantity=100,
            min_qty=100, max_qty=999, interval=100, sold_by="SOLD_BY_WEIGHT")


def test_order_quantity_limits():
    deli = P("T", **DELI)
    assert drafts.order_quantity(deli, 1) == 100 and drafts.order_quantity(deli, 250) == 300
    assert drafts.order_quantity(deli, 5000) == 900 and drafts.order_quantity(deli, 0) == 0
    each = P("E", min_qty=1, max_qty=24, interval=1)
    assert drafts.order_quantity(each, 30) == 24 and drafts.order_quantity(each, 3) == 3
    assert drafts.order_quantity({"code": "old"}, 7) == 7                        # an old snapshot: no limits
    assert drafts.line_cost(deli, 300) == 13.5 and drafts.line_cost(each, 2) == 10.0


def test_weighed_product_builds_in_grams_and_totals_per_100_g(conn):
    pcx = Pcx({"turkey": [P("T", **DELI)]})
    did = build(conn, pcx, AI("T"), [item("turkey", qty=0.5, unit="lb")])
    d = drafts.get_draft(conn, did)
    [line] = d["lines"]
    assert line["quantity"] == 300 and d["estimated_total"] == 13.5            # 227 g → 300 g at $4.50/100 g
    assert line["why"] is None                                                 # "1 × priced by weight" would mislead
    line = drafts.update_line(conn, did, line["id"], quantity=450)
    assert line["quantity"] == 500
    line = drafts.update_line(conn, did, line["id"], quantity=0)
    assert line["removed"] is True


def test_swap_to_a_weighed_product_starts_from_the_need_in_grams(conn):
    pcx = Pcx({"turkey": [P("A", price=7.0, size="175 g"), P("T", **DELI)]})
    did = build(conn, pcx, AI("A"), [item("turkey", qty=200, unit="g")])
    [line] = drafts.get_draft(conn, did)["lines"]
    line = drafts.update_line(conn, did, line["id"], product_code="T")
    assert line["quantity"] == 200 and line["product"]["weighted"] is True
    line = drafts.update_line(conn, did, line["id"], product_code="A")        # back to packs
    assert line["quantity"] == 2


def test_patch_product_and_quantity_together_respects_the_limits(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B", min_qty=1, max_qty=6, interval=1)]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    line = drafts.update_line(conn, did, line["id"], product_code="B", quantity=9)
    assert line["product"]["code"] == "B" and line["quantity"] == 6 and line["source"] == "user"


# --- pins ("Always buy this") -----------------------------------------------------------------------------------

def test_pin_and_unpin_through_the_line(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    line = drafts.update_line(conn, did, line["id"], product_code="B", quantity=2, pinned=True)
    assert line["pinned"] and line["product"]["code"] == "B" and line["quantity"] == 2
    assert db.pinned_code(conn, "cheddar|each") == "B"
    line = drafts.update_line(conn, did, line["id"], pinned=False)
    assert not line["pinned"] and db.pinned_code(conn, "cheddar|each") is None
    line = drafts.update_line(conn, did, line["id"], pinned=True)             # pins the line's own product
    assert line["pinned"]
    drafts.update_line(conn, did, line["id"], product_code="A")               # another product: the pin goes
    assert db.pinned_code(conn, "cheddar|each") is None


def test_pin_on_a_line_without_a_product_is_refused(conn):
    pcx = Pcx({})
    did = build(conn, pcx, AI(None))
    [line] = drafts.get_draft(conn, did)["lines"]
    try:
        drafts.update_line(conn, did, line["id"], pinned=True)
    except ValueError:
        return
    raise AssertionError("expected ValueError")


def test_pinned_product_is_used_even_when_the_search_lacks_it(conn):
    pcx = Pcx({"cheddar": [P("A"), P("PIN")]}, catalog={"PIN": P("PIN", price=7.0)})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, line["id"], product_code="PIN", pinned=True)
    pcx.results["cheddar"] = [P("A")]                                          # not in this week's top results
    ai = AI("A")
    did2 = build(conn, pcx, ai)
    [line] = drafts.get_draft(conn, did2)["lines"]
    assert line["product"]["code"] == "PIN" and line["pinned"] and line["source"] == "memory" and line["note"] is None
    assert ai.n == 0 and pcx.fetched == ["PIN"]


def test_pinned_product_out_of_stock_falls_back_with_a_note(conn):
    pcx = Pcx({"cheddar": [P("A"), P("PIN", name="Aged White Cheddar")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, line["id"], product_code="PIN", pinned=True)
    pcx.results["cheddar"] = [P("A"), P("PIN", name="Aged White Cheddar", stock="OUT")]
    [line] = drafts.get_draft(conn, build(conn, pcx, AI("A")))["lines"]
    assert line["product"]["code"] == "A" and not line["pinned"] and line["source"] == "ai"
    assert line["note"] == "Your usual Aged White Cheddar is out of stock this week — matched another."
    assert db.pinned_code(conn, "cheddar|each") == "PIN"                       # still pinned for next week


def test_pinned_product_no_longer_sold_falls_back_with_a_note(conn):
    pcx = Pcx({"cheddar": [P("A"), P("PIN", name="Aged White Cheddar")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, line["id"], product_code="PIN", pinned=True)
    pcx.results["cheddar"] = [P("A")]                                          # gone from search, 404 by code
    [line] = drafts.get_draft(conn, build(conn, pcx, AI("A")))["lines"]
    assert line["product"]["code"] == "A"
    assert line["note"] == "Your usual Aged White Cheddar isn't sold at Loblaws any more — matched another."


def test_remembered_pick_out_of_stock_gets_a_note_and_a_swap_clears_it(conn):
    pcx = Pcx({"cheddar": [P("A", name="Mild Cheddar"), P("B")]})
    build(conn, pcx, AI("A"))
    pcx.results["cheddar"] = [P("A", name="Mild Cheddar", stock="OUT"), P("B")]
    did2 = build(conn, pcx, AI("B"))
    [line] = drafts.get_draft(conn, did2)["lines"]
    assert line["product"]["code"] == "B"
    assert line["note"] == "Your usual Mild Cheddar is out of stock this week — matched another."
    line = drafts.update_line(conn, did2, line["id"], product_code="B")
    assert line["note"] is None


def test_choices_mark_the_pinned_product(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B")]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, line["id"], pinned=True)
    by = {c["product"]["code"]: c for c in drafts.choices(conn, did, line["id"], pcx)["choices"]}
    assert by["A"]["pinned"] and not by["B"]["pinned"]


# --- drafts from before 0.12.0 ----------------------------------------------------------------------------------

def test_old_snapshot_lines_still_read_and_get_a_photo_once_seen(conn):
    pcx = Pcx({"cheddar": [P("A")]})
    did = build(conn, pcx, AI("A"))
    old = {"code": "A", "name": "Old Cheddar Cheese", "brand": "PC", "package_size": "400 g", "price": 5.0,
           "stock": "OK", "sold_by": None}
    conn.execute("UPDATE cart_lines SET product=%s, note=NULL WHERE cart_id=%s", (Jsonb(old), did))
    conn.execute("UPDATE products SET detail=NULL")
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"] == old and line["pinned"] is False and line["note"] is None
    Product(**line["product"])                                                 # the model still loads it
    db.record_products(conn, "1092", [P("A", image="https://img.invalid/a.png")])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"]["image"] == "https://img.invalid/a.png" and line["product"]["price"] == 5.0
    line = drafts.update_line(conn, did, line["id"], quantity=2)                # no limits known: as asked
    assert line["quantity"] == 2


def test_swap_keeps_the_snapshot_fields(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B", image="https://img.invalid/b.png", min_qty=1, max_qty=24, interval=1,
                                      unit_price=1.5)]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    line = drafts.update_line(conn, did, line["id"], product_code="B")
    assert line["product"]["image"] == "https://img.invalid/b.png" and line["product"]["max_qty"] == 24
    assert line["product"]["unit_price"] == 1.5


# --- API --------------------------------------------------------------------------------------------------------

H = {"Authorization": "Bearer secret"}


def _client(conn, pcx):
    s = Settings(dsn="unused", token="secret", provider="fake")
    return TestClient(create_app(s, provider=AI(), pcx=pcx, conn=conn, today=lambda: date(2026, 10, 7)))


def test_api_choices_and_pin(conn):
    pcx = Pcx({"cheddar": [P("A"), P("B", sponsored=True)]})
    did = build(conn, pcx, AI("A"))
    [line] = drafts.get_draft(conn, did)["lines"]
    c = _client(conn, pcx)
    r = c.get(f"/drafts/{did}/lines/{line['id']}/choices", headers=H)
    assert r.status_code == 200 and [x["product"]["code"] for x in r.json()["choices"]] == ["A", "B"]
    assert r.json()["choices"][1]["product"]["sponsored"] is True
    r = c.get(f"/drafts/{did}/lines/{line['id']}/choices", headers=H, params={"term": "aged", "page": 1})
    assert r.status_code == 200 and r.json()["term"] == "aged" and pcx.pages[-1] == ("aged", 1)
    assert c.get(f"/drafts/{did}/lines/999/choices", headers=H).status_code == 404
    assert c.get(f"/drafts/{did}/lines/{line['id']}/choices", headers=H, params={"page": -1}).status_code == 422
    assert c.get(f"/drafts/{did}/lines/{line['id']}/choices").status_code == 401
    r = c.patch(f"/drafts/{did}/lines/{line['id']}", headers=H, json={"product_code": "B", "quantity": 3, "pinned": True})
    assert r.status_code == 200 and r.json()["pinned"] is True and r.json()["quantity"] == 3


def test_api_choices_search_outage_is_502_and_pin_without_product_422(conn):
    class Down(Pcx):
        def search_page(self, term, page=0, size=20):
            raise httpx.ConnectError("down")
    pcx = Pcx({})
    did = build(conn, pcx, AI(None))
    [line] = drafts.get_draft(conn, did)["lines"]
    assert _client(conn, Down()).get(f"/drafts/{did}/lines/{line['id']}/choices", headers=H).status_code == 502
    assert _client(conn, pcx).patch(f"/drafts/{did}/lines/{line['id']}", headers=H,
                                    json={"pinned": True}).status_code == 422
