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
