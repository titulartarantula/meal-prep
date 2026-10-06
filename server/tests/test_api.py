import os, threading, time
from datetime import date
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from mealprep.api import create_app
from mealprep.config import Settings
from mealprep.ai.base import AIError
from mealprep.models import Product
import mealprep.api as api_mod

FIX = Path(__file__).parent / "fixtures/recipe_page.html"
NYT = "https://cooking.nytimes.com/recipes/1015819-x"


class AI:
    def __init__(self, fail=False): self.fail, self.n = fail, 0
    def complete_json(self, prompt, images=None):
        self.n += 1
        if self.fail: raise AIError("boom")
        return []  # structure fallback → raw names


class NoPcx:
    def search(self, t, size=8): return []
    def create_cart(self): return "unused"
    def add(self, c, e): pass


def client(conn, today=date(2026, 10, 7), **kw):
    # background draft builds open their own connection, so give them the real test DSN
    s = Settings(dsn=os.environ.get("MEALPREP_TEST_DSN", "unused"), token="secret", provider="fake")
    return TestClient(create_app(s, provider=kw.get("ai", AI()), pcx=kw.get("pcx", NoPcx()), conn=conn,
                                 today=lambda: today))


H = {"Authorization": "Bearer secret"}


@pytest.fixture
def nyt(monkeypatch):
    monkeypatch.setattr(api_mod, "fetch_nyt", lambda url: FIX.read_text())


def share(c, week):
    return c.post("/recipes/share", headers=H, json={"text": NYT, "week": week})


def test_auth_required(conn):
    c = client(conn)
    assert c.get("/recipes").status_code == 401
    assert c.get("/recipes", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_health_no_auth(conn):
    assert client(conn).get("/health").json() == {"ok": True, "provider": "fake"}


def test_share_nyt_into_week(conn, nyt):
    r = client(conn).post("/recipes/share", headers=H, json={"text": "Try this https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies?smid=x", "week": "2026-10-14"})
    assert r.status_code == 200
    assert r.json()["recipe"]["title"] == "Best Chocolate Chip Cookies"
    assert r.json()["entry"]["week"] == "2026-10-11"          # Wednesday → its Sunday
    assert conn.execute("SELECT ai_provider, source_url FROM recipes").fetchone() == ("fake", "https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies")


def test_share_without_week_saves_to_library_only(conn, nyt):
    c = client(conn, today=date(2026, 10, 7))
    r = c.post("/recipes/share", headers=H, json={"text": NYT})
    assert r.status_code == 200
    body = r.json()
    assert body["entry"] is None and body["existing"] is False
    assert body["recipe"]["title"] == "Best Chocolate Chip Cookies" and body["recipe"]["planned_weeks"] == []
    assert conn.execute("SELECT count(*) FROM plan").fetchone()[0] == 0
    assert [x["id"] for x in c.get("/recipes", headers=H).json()] == [body["recipe"]["id"]]


def test_reshare_without_week_finds_library_recipe_with_ratings(conn, nyt):
    c = client(conn, today=date(2026, 10, 14))
    first = share(c, "2026-10-04").json()                     # an older app adds it to a week
    c.patch(f"/plan/{first['entry']['id']}", headers=H, json={"day": 2})
    c.put(f"/plan/{first['entry']['id']}/rating", headers=H, json={"family": 5, "note": "more chips"})
    again = c.post("/recipes/share", headers=H, json={"text": NYT}).json()
    assert again["existing"] is True and again["entry"] is None and again["recipe"]["id"] == first["recipe"]["id"]
    assert again["recipe"]["ratings"]["last_family"] == 5 and again["recipe"]["ratings"]["notes"][0]["note"] == "more chips"
    assert conn.execute("SELECT count(*) FROM plan").fetchone()[0] == 1   # nothing added


def test_reshare_with_week_still_adds_entry(conn, nyt):
    """Apps up to 0.4.1 always send a week: the recipe still goes into it."""
    c = client(conn, today=date(2026, 10, 7))
    c.post("/recipes/share", headers=H, json={"text": NYT})
    r = share(c, "2026-10-18").json()
    assert r["existing"] is True and r["entry"]["week"] == "2026-10-18" and r["recipe"]["planned_weeks"] == ["2026-10-18"]


def test_default_week():
    assert api_mod.default_week(date(2026, 10, 4)) == date(2026, 10, 4)    # Sunday → this week
    assert api_mod.default_week(date(2026, 10, 5)) == date(2026, 10, 11)   # Monday → upcoming Sunday
    assert api_mod.default_week(date(2026, 10, 10)) == date(2026, 10, 11)  # Saturday


def test_multi_week_list_combines(conn, nyt):
    c = client(conn)
    share(c, "2026-10-11")
    rid = c.get("/recipes", headers=H).json()[0]["id"]
    assert c.post("/weeks/2026-10-18/entries", headers=H, json={"recipe_id": rid}).status_code == 200
    one = c.post("/list", headers=H, json={"weeks": ["2026-10-11"]}).json()
    two = c.post("/list", headers=H, json={"weeks": ["2026-10-11", "2026-10-18"]}).json()
    assert len(one) == len(two) == 11                     # same lines, merged — not duplicated
    for a, b in zip(one, two):
        assert b["recipes"] == a["recipes"]               # recipe title listed once
        assert (a["qty"] is None and b["qty"] is None) or b["qty"] == 2 * a["qty"]


def test_add_entry_unknown_recipe_404(conn):
    assert client(conn).post("/weeks/2026-10-18/entries", headers=H, json={"recipe_id": 999}).status_code == 404


def test_place_on_night_and_scale(conn, nyt):
    c = client(conn)
    eid = share(c, "2026-10-11").json()["entry"]["id"]
    assert c.patch(f"/plan/{eid}", headers=H, json={"day": 2, "multiplier": 2}).status_code == 204
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["day"] == 2 and e["multiplier"] == 2
    assert c.patch(f"/plan/{eid}", headers=H, json={"day": 7, "multiplier": 1}).status_code == 422
    assert c.delete(f"/plan/{eid}", headers=H).status_code == 204
    assert c.get("/weeks/2026-10-11", headers=H).json() == []


def test_share_not_nyt(conn):
    assert client(conn).post("/recipes/share", headers=H, json={"text": "hello"}).status_code == 422


def test_share_fetch_failure_is_502_and_saves_nothing(conn, monkeypatch):
    def boom(url): raise RuntimeError("network down")
    monkeypatch.setattr(api_mod, "fetch_nyt", boom)
    c = client(conn)
    assert share(c, "2026-10-11").status_code == 502
    assert c.get("/recipes", headers=H).json() == []


def test_photo_ai_failure_saves_nothing(conn):
    c = client(conn, ai=AI(fail=True))
    r = c.post("/recipes/photo", headers=H, files=[("files", ("p1.jpg", b"\xff\xd8x", "image/jpeg")),
                                                   ("files", ("p2.jpg", b"\xff\xd8y", "image/jpeg"))])
    assert r.status_code == 502 and c.get("/recipes", headers=H).json() == []


def test_photo_too_many_pages(conn):
    files = [("files", (f"p{i}.jpg", b"x", "image/jpeg")) for i in range(11)]
    assert client(conn).post("/recipes/photo", headers=H, files=files).status_code == 422


def test_photo_pages_passed_in_order(conn):
    seen = {}
    class Rec:
        def complete_json(self, prompt, images=None):
            if images: seen["n"] = len(images); seen["order"] = [Path(i).read_bytes() for i in images]
            return {"title": "Dal", "servings": 4, "ingredients": [], "steps": []} if images else []
    c = client(conn, ai=Rec())
    r = c.post("/recipes/photo", headers=H, data={"week": "2026-10-11"},
               files=[("files", ("a.jpg", b"page1", "image/jpeg")), ("files", ("b.jpg", b"page2", "image/jpeg"))])
    assert r.status_code == 200 and seen["order"] == [b"page1", b"page2"]
    assert r.json()["entry"]["week"] == "2026-10-11" and r.json()["recipe"]["source"] == "photo"


def test_photo_without_week_saves_to_library_only(conn):
    class Rec:
        def complete_json(self, prompt, images=None):
            return {"title": "Lentil Soup", "servings": 4, "ingredients": [], "steps": ["Simmer."]} if images else []
    c = client(conn, ai=Rec())
    r = c.post("/recipes/photo", headers=H, files=[("files", ("a.jpg", b"page1", "image/jpeg"))])
    assert r.status_code == 200
    assert r.json()["entry"] is None and r.json()["recipe"]["title"] == "Lentil Soup"
    assert conn.execute("SELECT count(*) FROM plan").fetchone()[0] == 0


class ShopPcx:
    def __init__(self): self.added, self.n = {}, 0
    def search(self, t, size=8):
        return [Product(code="A", name="A", stock="OK", price=1.0), Product(code="B", name="B", stock="OK", price=2.0)]
    def create_cart(self):
        self.n += 1; return f"cart{self.n}"
    def add(self, c, e): self.added = dict(e)


class PickAI:
    def __init__(self): self.n = 0
    def complete_json(self, prompt, images=None):
        if "Candidates" in prompt:
            self.n += 1; return {"code": "B", "quantity": 1}
        return []


def test_list_then_cart_records_week(conn, nyt):
    c = client(conn, pcx=ShopPcx(), ai=PickAI())
    share(c, "2026-10-11")
    items = c.post("/list", headers=H, json={"weeks": ["2026-10-11"]}).json()
    assert len(items) == 11
    res = c.post("/cart", headers=H, json={"items": items, "weeks": ["2026-10-11"]}).json()
    assert res["cart_id"] == "cart1" and all(l["status"] == "added" for l in res["lines"])
    weeks = c.get("/weeks", headers=H, params={"from": "2026-10-04", "count": 3}).json()
    assert weeks == [{"week": "2026-10-04", "entries": 0, "carted": False, "cart_stale": False},
                     {"week": "2026-10-11", "entries": 1, "carted": True, "cart_stale": False},
                     {"week": "2026-10-18", "entries": 0, "carted": False, "cart_stale": False}]


def test_same_week_carted_twice(conn, nyt):
    c = client(conn, pcx=ShopPcx(), ai=PickAI())
    share(c, "2026-10-11")
    items = c.post("/list", headers=H, json={"weeks": ["2026-10-11"]}).json()
    for _ in range(2):
        assert c.post("/cart", headers=H, json={"items": items, "weeks": ["2026-10-11"]}).status_code == 200
    assert conn.execute("SELECT count(*) FROM cart_weeks WHERE week='2026-10-11'").fetchone()[0] == 2
    [wk] = c.get("/weeks", headers=H, params={"from": "2026-10-11", "count": 1}).json()
    assert wk["carted"] is True


def test_cart_default_week_skips_carted(conn, nyt):
    c = client(conn, today=date(2026, 10, 7), pcx=ShopPcx(), ai=PickAI())
    assert c.get("/cart/default-week", headers=H).json() == {"week": None}
    share(c, "2026-10-11")
    rid = c.get("/recipes", headers=H).json()[0]["id"]
    c.post("/weeks/2026-09-27/entries", headers=H, json={"recipe_id": rid})   # past week: ignored
    c.post("/weeks/2026-10-25/entries", headers=H, json={"recipe_id": rid})
    assert c.get("/cart/default-week", headers=H).json() == {"week": "2026-10-11"}
    c.post("/cart", headers=H, json={"items": [], "weeks": ["2026-10-11"]})
    assert c.get("/cart/default-week", headers=H).json() == {"week": "2026-10-25"}


def test_user_pick_override_wins_next_time(conn, nyt):
    ai, pcx = PickAI(), ShopPcx()
    c = client(conn, pcx=pcx, ai=ai)
    item = {"key": "chicken thigh|each", "name": "chicken thigh", "qty": 1, "unit": None}
    c.post("/cart", headers=H, json={"items": [item], "weeks": ["2026-10-11"]})
    assert pcx.added == {"B": 1} and ai.n == 1
    assert c.put("/picks/chicken thigh|each", headers=H, json={"product_code": "A"}).status_code == 204
    c.post("/cart", headers=H, json={"items": [item], "weeks": ["2026-10-11"]})
    assert pcx.added == {"A": 1} and ai.n == 1
    assert conn.execute("SELECT chosen_by FROM picks WHERE key='chicken thigh|each'").fetchone() == ("user",)


def test_patch_only_changes_sent_fields(conn, nyt):
    c = client(conn)
    eid = share(c, "2026-10-11").json()["entry"]["id"]
    c.patch(f"/plan/{eid}", headers=H, json={"day": 2, "multiplier": 2})
    c.patch(f"/plan/{eid}", headers=H, json={"day": 4})
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["day"] == 4 and e["multiplier"] == 2
    c.patch(f"/plan/{eid}", headers=H, json={"multiplier": 1.5})
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["day"] == 4 and e["multiplier"] == 1.5
    c.patch(f"/plan/{eid}", headers=H, json={"day": None})
    [e] = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["day"] is None and e["multiplier"] == 1.5


def test_photo_import_does_not_block_event_loop(conn):
    import asyncio
    seen = {}
    class Rec:
        def complete_json(self, prompt, images=None):
            try:
                asyncio.get_running_loop(); seen["on_loop"] = True    # blocking call on the event loop
            except RuntimeError:
                seen["on_loop"] = False                               # worker thread
            return {"title": "Dal", "ingredients": [], "steps": []} if images else []
    c = client(conn, ai=Rec())
    r = c.post("/recipes/photo", headers=H, files=[("files", ("a.jpg", b"x", "image/jpeg"))])
    assert r.status_code == 200 and seen["on_loop"] is False


class GatePcx:
    """Searches block until the test opens the gate; out-of-stock and extra candidates for alternatives."""
    def __init__(self, fail=False):
        self.gate, self.fail, self.created, self.adds = threading.Event(), fail, [], []
    def search(self, t, size=8):
        assert self.gate.wait(5)
        if self.fail:
            import httpx; raise httpx.ConnectError("down")
        return [Product(code="A", name="A", stock="OK", price=1.0), Product(code="B", name="B", stock="OK", price=2.0),
                Product(code="X", name="X", stock="OUT", price=0.5), Product(code="C", name="C", stock="OK", price=3.0)]
    def create_cart(self):
        self.created.append(f"pcx{len(self.created) + 1}"); return self.created[-1]
    def add(self, c, e): self.adds.append((c, dict(e)))


ITEMS = [{"key": f"{n}|each", "name": n, "qty": 1, "unit": None, "recipes": ["Chili"]} for n in ("onion", "beef", "rice")]


def wait_status(c, did, want, timeout=5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        d = c.get(f"/drafts/{did}", headers=H).json()
        if d["status"] != "building":
            assert d["status"] == want, d
            return d
        time.sleep(0.02)
    raise AssertionError(f"draft {did} still building")


def test_draft_returns_immediately_then_ready(conn):
    pcx = GatePcx()
    c = client(conn, pcx=pcx, ai=PickAI())
    r = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS})
    assert r.status_code == 202 and r.json()["status"] == "building"      # searches still blocked
    did = r.json()["id"]
    d = c.get(f"/drafts/{did}", headers=H).json()
    assert d["status"] == "building" and d["progress"] == {"done": 0, "total": 3}
    pcx.gate.set()
    d = wait_status(c, did, "ready")
    assert d["progress"] == {"done": 3, "total": 3} and d["pcx_cart_id"] is None and d["weeks"] == ["2026-10-11"]
    assert [l["name"] for l in d["lines"]] == ["onion", "beef", "rice"]
    line = d["lines"][0]
    assert line["product"]["code"] == "B" and line["source"] == "ai" and line["recipes"] == ["Chili"]
    assert [a["code"] for a in line["alternatives"]] == ["A", "C"]           # no OOS, no chosen
    assert d["estimated_total"] == 6.0 and pcx.created == []


def test_draft_edit_send_flow(conn):
    pcx = GatePcx(); pcx.gate.set()
    c = client(conn, pcx=pcx, ai=PickAI())
    did = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    onion, beef, rice = wait_status(c, did, "ready")["lines"]
    r = c.patch(f"/drafts/{did}/lines/{onion['id']}", headers=H, json={"product_code": "A"})
    assert r.status_code == 200 and r.json()["product"]["code"] == "A" and r.json()["source"] == "user"
    assert c.patch(f"/drafts/{did}/lines/{beef['id']}", headers=H, json={"removed": True}).json()["removed"] is True
    assert c.patch(f"/drafts/{did}/lines/{rice['id']}", headers=H, json={"product_code": "NOPE"}).status_code == 422
    assert c.patch(f"/drafts/{did}/lines/{rice['id']}", headers=H, json={"quantity": -1}).status_code == 422
    r = c.post(f"/drafts/{did}/lines/{rice['id']}/search", headers=H, json={"term": "basmati rice"})
    assert r.status_code == 200 and [p["code"] for p in r.json()] == ["A", "B", "C"]
    assert c.patch(f"/drafts/{did}/lines/{rice['id']}", headers=H, json={"quantity": 2}).json()["quantity"] == 2
    wk = lambda: c.get("/weeks", headers=H, params={"from": "2026-10-11", "count": 1}).json()[0]["carted"]
    assert wk() is False                                                    # not carted until sent
    r = c.post(f"/drafts/{did}/send", headers=H)
    assert r.status_code == 200 and r.json() == {"pcx_cart_id": "pcx1"}
    assert pcx.adds == [("pcx1", {"A": 1, "B": 2})]                          # beef removed
    assert c.post(f"/drafts/{did}/send", headers=H).json() == {"pcx_cart_id": "pcx1"} and pcx.created == ["pcx1"]
    assert wk() is True
    d = c.get(f"/drafts/{did}", headers=H).json()
    assert d["status"] == "sent" and d["pcx_cart_id"] == "pcx1"
    assert c.patch(f"/drafts/{did}/lines/{rice['id']}", headers=H, json={"quantity": 1}).status_code == 409
    assert c.post(f"/drafts/{did}/lines/{rice['id']}/search", headers=H, json={"term": "x"}).status_code == 409


def test_draft_outage_fails_and_week_not_carted(conn):
    pcx = GatePcx(fail=True); pcx.gate.set()
    c = client(conn, pcx=pcx, ai=PickAI())
    did = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    d = wait_status(c, did, "failed")
    assert "PC Express" in d["error"]
    assert c.post(f"/drafts/{did}/send", headers=H).status_code == 409
    assert c.get("/weeks", headers=H, params={"from": "2026-10-11", "count": 1}).json()[0]["carted"] is False


def test_draft_not_found_and_auth(conn):
    c = client(conn)
    assert c.get("/drafts/999", headers=H).status_code == 404
    assert c.post("/drafts/999/send", headers=H).status_code == 404
    assert c.patch("/drafts/999/lines/1", headers=H, json={"quantity": 1}).status_code == 404
    assert c.get("/drafts/999").status_code == 401
    assert c.post("/drafts", json={"weeks": [], "items": []}).status_code == 401


def test_week_entries_carry_title(conn, nyt):
    c = client(conn)
    share(c, "2026-10-11")
    (e,) = c.get("/weeks/2026-10-11", headers=H).json()
    assert e["title"] == "Best Chocolate Chip Cookies"


def test_week_draft_lookup(conn):
    pcx = GatePcx(); pcx.gate.set()
    c = client(conn, pcx=pcx, ai=PickAI())
    assert c.get("/weeks/2026-10-11/draft").status_code == 401
    assert c.get("/weeks/2026-10-11/draft", headers=H).status_code == 404
    first = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    wait_status(c, first, "ready")
    second = c.post("/drafts", headers=H, json={"weeks": ["2026-10-13", "2026-10-18"], "items": ITEMS}).json()["id"]
    wait_status(c, second, "ready")
    d = c.get("/weeks/2026-10-14/draft", headers=H).json()          # Wednesday -> its Sunday
    assert d["id"] == second and d["status"] == "ready" and len(d["lines"]) == 3
    assert c.get("/weeks/2026-10-18/draft", headers=H).json()["id"] == second
    assert c.get("/weeks/2026-10-25/draft", headers=H).status_code == 404


def test_draft_accepts_items_omitting_qty_and_unit(conn):
    pcx = GatePcx(); pcx.gate.set()
    c = client(conn, pcx=pcx, ai=PickAI())
    items = [{"key": f"{n}|each", "name": n, "recipes": ["Chili"]} for n in ("onion", "beef", "rice")]
    r = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": items})
    assert r.status_code == 202, r.text
    wait_status(c, r.json()["id"], "ready")


def test_stale_cart_fields_and_send_refused(conn, nyt):
    """The reported case: a cart sent, then the week's recipes changed → the week is no longer carted, the old cart is
    stale, a ready draft of the old plan can't be sent, and a new cart for the week wins."""
    pcx = GatePcx(); pcx.gate.set()
    c = client(conn, today=date(2026, 10, 7), pcx=pcx, ai=PickAI())
    share(c, "2026-10-11")
    rid = c.get("/recipes", headers=H).json()[0]["id"]
    wk = lambda: c.get("/weeks", headers=H, params={"from": "2026-10-11", "count": 1}).json()[0]
    sent = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    assert wait_status(c, sent, "ready")["stale"] is False
    assert c.post(f"/drafts/{sent}/send", headers=H).status_code == 200
    assert wk() == {"week": "2026-10-11", "entries": 1, "carted": True, "cart_stale": False}
    ready = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    wait_status(c, ready, "ready")
    (e,) = c.get("/weeks/2026-10-11", headers=H).json()
    assert c.patch(f"/plan/{e['id']}", headers=H, json={"day": 3}).status_code == 204       # a night move: no change
    assert wk()["carted"] is True and c.get(f"/drafts/{sent}", headers=H).json()["stale"] is False
    c.post("/weeks/2026-10-11/entries", headers=H, json={"recipe_id": rid})                 # the week changed
    assert wk() == {"week": "2026-10-11", "entries": 2, "carted": False, "cart_stale": True}
    assert c.get("/cart/default-week", headers=H).json() == {"week": "2026-10-11"}
    assert c.get(f"/drafts/{sent}", headers=H).json()["stale"] is True
    d = c.get("/weeks/2026-10-11/draft", headers=H).json()
    assert d["id"] == ready and d["status"] == "ready" and d["stale"] is True             # newest, any status
    r = c.post(f"/drafts/{ready}/send", headers=H)
    assert r.status_code == 409 and "changed" in r.json()["detail"] and len(pcx.created) == 1
    new = c.post("/drafts", headers=H, json={"weeks": ["2026-10-11"], "items": ITEMS}).json()["id"]
    assert wait_status(c, new, "ready")["stale"] is False
    assert c.post(f"/drafts/{new}/send", headers=H).status_code == 200                        # latest wins
    assert wk() == {"week": "2026-10-11", "entries": 2, "carted": True, "cart_stale": False}
    assert c.get(f"/drafts/{sent}", headers=H).json()["stale"] is True                        # the old one stays stale
