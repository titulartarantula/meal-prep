import json
import respx, httpx
from mealprep.pcx import Pcx, BASE


@respx.mock
def test_search_maps_products():
    route = respx.post(f"{BASE}/products/search").mock(return_value=httpx.Response(200, json={"results": [
        {"code": "21341017_EA", "name": "Extra Lean Boneless Skinless Chicken Thighs", "brand": "PC Blue Menu",
         "packageSize": "1 ea", "prices": {"price": {"value": 15.0}}, "stockStatus": "OK"}]}))
    [p] = Pcx(api_key="test-key").search("chicken thighs")
    assert p.code == "21341017_EA" and p.price == 15.0 and p.brand == "PC Blue Menu" and p.stock == "OK"
    req = route.calls[0].request
    assert req.headers["x-apikey"] == "test-key" and req.headers["Site-Banner"] == "loblaw"
    body = json.loads(req.content)
    assert body["storeId"] == "1092" and body["term"] == "chicken thighs"


@respx.mock
def test_search_no_results():
    respx.post(f"{BASE}/products/search").mock(return_value=httpx.Response(200, json={"results": []}))
    assert Pcx().search("unobtainium") == []


@respx.mock
def test_search_missing_price_is_none():
    respx.post(f"{BASE}/products/search").mock(return_value=httpx.Response(200, json={"results": [{"code": "X", "name": "n", "prices": None}]}))
    assert Pcx().search("x")[0].price is None


@respx.mock
def test_create_and_add():
    respx.post(f"{BASE}/carts").mock(return_value=httpx.Response(200, json={"id": "c1"}))
    add = respx.post(f"{BASE}/carts/c1").mock(return_value=httpx.Response(200, json={"cart": {"id": "c1"}}))
    p = Pcx()
    assert p.create_cart() == "c1"
    p.add("c1", {"21341017_EA": 2})
    body = add.calls[0].request.content
    assert b'"21341017_EA"' in body and b'"sellerId":"1092"' in body.replace(b" ", b"")


# --- 0.12.0: the richer product fields, from a scrubbed live capture (2026-10-15) -------------------------------

FIX = json.loads((__import__("pathlib").Path(__file__).parent / "fixtures" / "pcx_search.json").read_text())


def _mapped(term):
    from mealprep.pcx import to_product
    return {x["code"]: to_product(x) for x in FIX[term]["results"]}


def test_mapping_sale_with_unit_price_deal_and_photos():
    p = _mapped("cheddar cheese")["21289751_EA"]
    assert p.name == "Old Cheddar Cheese" and p.package_size == "400 g" and p.price == 4.88
    assert p.regular_price == 6.0 and p.was_price == 6.0 and p.sale_ends == "2026-10-21"
    assert p.unit_price == 1.22 and p.unit_price_unit == "100 g"
    assert p.image.endswith("_250.png") and p.images and all(u.endswith("_800.png") for u in p.images)
    assert p.deal_text and p.deal_text.startswith("SAVE") and p.deal_ends == "2026-10-21"
    assert (p.min_qty, p.max_qty, p.interval, p.weighted, p.price_quantity) == (1, 24, 1, False, None)
    assert p.sponsored is False and p.stock == "OK" and p.link.startswith("/")
    assert "<" not in p.description and "Old Cheddar" in p.description


def test_mapping_regular_price_and_sponsored_and_optimum():
    m = _mapped("cheddar cheese")
    reg = m["20975873_EA"]
    assert reg.regular_price == reg.price and reg.sale_ends is None and reg.was_price is None
    ad = m["20767493_EA"]
    assert ad.sponsored is True
    opt = m["21370317_EA"]
    assert opt.optimum_text == "PC Optimum Offer" and opt.optimum_points == 4000 and opt.optimum_ends == "2026-11-04"


def test_mapping_out_of_stock_and_weighed():
    assert _mapped("lean ground beef")["21615428_EA"].stock == "OUT"
    deli = _mapped("deli sliced turkey")["20059548_KG"]
    assert deli.weighted and deli.sold_by == "SOLD_BY_WEIGHT" and deli.price_quantity == 100
    assert (deli.min_qty, deli.max_qty, deli.interval) == (100, 999, 100)
    banana = _mapped("bananas")["20175355001_KG"]
    assert banana.sold_by == "SOLD_BY_EACH_PRICED_BY_WEIGHT" and not banana.weighted and banana.price_quantity is None
    assert banana.unit_price_unit == "1 kg"


def test_detail_shape_maps_offer_fields():
    from mealprep.pcx import to_product
    p = to_product(FIX["_detail"])
    assert p.code == "21289751_EA" and p.price == 4.88 and p.stock == "OK" and p.unit_price == 1.22
    assert p.sale_ends == "2026-10-21" and p.images


def test_text_from_html_paragraphs_and_bullets():
    from mealprep.pcx import text_from_html
    t = text_from_html("<p>Rich &amp; <b>tangy</b>.</p><br><br><ul><li>Slices clean</li><li> Grates&nbsp;well </li></ul>")
    assert t == "Rich & tangy.\n\n• Slices clean\n• Grates well"
    assert text_from_html("") is None and text_from_html(None) is None and text_from_html("<p> </p>") is None
    assert text_from_html("plain") == "plain"
    assert text_from_html("<p>a</p>" * 2000).count("a") <= 4000


@respx.mock
def test_search_page_sends_the_page_number_and_returns_the_total():
    route = respx.post(f"{BASE}/products/search").mock(return_value=httpx.Response(200, json={
        "results": FIX["cheddar cheese"]["results"][:2], "pagination": {"pageNumber": 2, "pageSize": 20, "totalResults": 307}}))
    found, total = Pcx().search_page("cheddar", page=2, size=20)
    assert total == 307 and len(found) == 2
    assert json.loads(route.calls[0].request.content)["pagination"] == {"from": 2, "size": 20}


@respx.mock
def test_product_by_code_and_unknown():
    respx.get(f"{BASE}/products/21289751_EA").mock(return_value=httpx.Response(200, json=FIX["_detail"]))
    respx.get(f"{BASE}/products/NOPE").mock(return_value=httpx.Response(404, json={"errors": []}))
    p = Pcx()
    assert p.product("21289751_EA").price == 4.88
    assert p.product("NOPE") is None
