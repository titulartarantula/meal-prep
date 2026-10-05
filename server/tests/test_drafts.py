from datetime import date
import itertools, threading, time
import httpx
import pytest
from mealprep import db, drafts
from mealprep.models import ListItem, Product

WK = [date(2026, 10, 11)]
_ids = itertools.count(1)


class FakePcx:
    def __init__(self, results, fail=(), delay=0.0):
        self.results, self.fail, self.delay = results, fail, delay
        self.created, self.adds, self.searches = [], [], []
        self.active = self.max_active = 0
        self.lock = threading.Lock()

    def search(self, term, size=8):
        with self.lock:
            self.searches.append(term); self.active += 1; self.max_active = max(self.max_active, self.active)
        try:
            time.sleep(self.delay)
            if term in self.fail:
                raise httpx.ConnectError("boom")
            return list(self.results.get(term, []))
        finally:
            with self.lock:
                self.active -= 1

    def create_cart(self):
        cid = f"pcx{next(_ids)}"; self.created.append(cid); return cid

    def add(self, cid, entries): self.adds.append((cid, dict(entries)))


class FakeAI:
    """Picks by term: {name: code}; quantity 1."""
    def __init__(self, picks): self.picks, self.n = picks, 0
    def complete_json(self, prompt, images=None):
        self.n += 1
        for name, code in self.picks.items():
            if f" {name}\n" in prompt or f" {name} (" in prompt:
                return {"code": code, "quantity": 1}
        return {"code": None}


def prod(code, price=1.0, stock="OK"):
    return Product(code=code, name=f"P{code}", brand="PC", package_size="1 ea", price=price, stock=stock)


def item(name, needed=True, qty=1):
    return ListItem(key=f"{name}|each", name=name, qty=qty, unit=None, needed=needed, recipes=["Chili"])


def build(conn, items, pcx, ai, workers=6):
    did = drafts.create_draft(conn, items, WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, pcx, ai, store_id="1092", workers=workers)
    return did


def carted(conn):
    return db.week_summaries(conn, WK[0], 1)[0]["carted"]


def test_draft_builds_lines_with_alternatives(conn):
    cands = [prod("A", 3), prod("OUT1", stock="OUT"), prod("B", 2), prod("C"), prod("D"), prod("E"), prod("F"), prod("G")]
    pcx, ai = FakePcx({"chicken thigh": cands}), FakeAI({"chicken thigh": "B"})
    did = build(conn, [item("chicken thigh"), item("salt", needed=False)], pcx, ai)
    d = drafts.get_draft(conn, did)
    assert d["status"] == "ready" and d["progress"] == {"done": 1, "total": 1} and d["pcx_cart_id"] is None
    assert d["weeks"] == ["2026-10-11"]
    [line] = d["lines"]                                         # unneeded items are not draft lines
    assert line["item_key"] == "chicken thigh|each" and line["name"] == "chicken thigh" and line["recipes"] == ["Chili"]
    assert line["product"]["code"] == "B" and line["product"]["price"] == 2 and line["source"] == "ai"
    assert line["quantity"] == 1 and line["removed"] is False
    alts = [a["code"] for a in line["alternatives"]]
    assert alts == ["A", "C", "D", "E", "F"]                    # in stock, not the chosen one, max 5
    assert d["estimated_total"] == 2.0
    assert not carted(conn) and pcx.created == []              # nothing sent to PC Express yet


def test_build_respects_concurrency_bound(conn):
    names = [f"item{i}" for i in range(12)]
    pcx = FakePcx({n: [prod(n)] for n in names}, delay=0.05)
    did = build(conn, [item(n) for n in names], pcx, FakeAI({n: n for n in names}), workers=4)
    assert 2 <= pcx.max_active <= 4
    assert drafts.get_draft(conn, did)["status"] == "ready"


def test_concurrent_build_is_faster_than_sequential(conn):
    names = [f"item{i}" for i in range(12)]
    pcx = FakePcx({n: [prod(n)] for n in names}, delay=0.1)
    t = time.monotonic()
    build(conn, [item(n) for n in names], pcx, FakeAI({n: n for n in names}), workers=6)
    assert time.monotonic() - t < 0.6                          # sequential would be ≥ 1.2 s


def test_line_order_follows_list_order(conn):
    names = ["b", "a", "c"]
    pcx = FakePcx({n: [prod(n)] for n in names})
    did = build(conn, [item(n) for n in names], pcx, FakeAI({n: n for n in names}))
    assert [l["name"] for l in drafts.get_draft(conn, did)["lines"]] == names


def test_swap_records_user_pick_and_next_draft_uses_it(conn):
    pcx, ai = FakePcx({"chicken thigh": [prod("A"), prod("B")]}), FakeAI({"chicken thigh": "A"})
    did = build(conn, [item("chicken thigh")], pcx, ai)
    [line] = drafts.get_draft(conn, did)["lines"]
    out = drafts.update_line(conn, did, line["id"], product_code="B")
    assert out["product"]["code"] == "B" and out["source"] == "user"
    assert [a["code"] for a in out["alternatives"]] == ["A"]   # old pick becomes an alternative
    assert db.get_pick_full(conn, "chicken thigh|each") == ("B", "user")
    assert conn.execute("SELECT product_code, chosen_by FROM pick_history ORDER BY id DESC LIMIT 1").fetchone() == ("B", "user")
    did2 = build(conn, [item("chicken thigh")], pcx, ai)
    [line2] = drafts.get_draft(conn, did2)["lines"]
    assert line2["product"]["code"] == "B" and line2["source"] == "memory" and ai.n == 1


def test_unknown_product_rejected(conn):
    pcx = FakePcx({"x": [prod("A")]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    [line] = drafts.get_draft(conn, did)["lines"]
    with pytest.raises(drafts.UnknownProduct):
        drafts.update_line(conn, did, line["id"], product_code="NOPE")


def test_free_text_search_records_prices_and_allows_pick(conn):
    pcx = FakePcx({"x": [], "thighs bone-in": [prod("T1", 9), prod("T2", 7, stock="OUT"), prod("T3", 8)]})
    did = build(conn, [item("x")], pcx, FakeAI({}))
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"] is None and line["source"] == "none" and line["quantity"] is None
    cands = drafts.search_line(conn, did, line["id"], "thighs bone-in", pcx, store_id="1092")
    assert [c["code"] for c in cands] == ["T1", "T3"]
    assert conn.execute("SELECT count(*) FROM price_observations WHERE code LIKE 'T%'").fetchone()[0] == 3
    out = drafts.update_line(conn, did, line["id"], product_code="T3")
    assert out["product"]["price"] == 8 and out["quantity"] == 1 and out["source"] == "user"


def test_removed_and_zero_qty_lines_not_sent(conn):
    names = ["a", "b", "c", "d"]
    pcx = FakePcx({n: [prod(n.upper())] for n in names})
    did = build(conn, [item(n) for n in names], pcx, FakeAI({n: n.upper() for n in names}))
    a, b, c, d = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, a["id"], removed=True)
    out = drafts.update_line(conn, did, b["id"], quantity=0)
    assert out["removed"] is True
    drafts.update_line(conn, did, c["id"], quantity=3)
    assert drafts.get_draft(conn, did)["estimated_total"] == 4.0
    cid = drafts.send_draft(conn, did, pcx)
    assert pcx.adds == [(cid, {"C": 3, "D": 1})]
    rows = conn.execute("SELECT item_name, status, removed FROM cart_lines WHERE cart_id=%s ORDER BY position", (did,)).fetchall()
    assert rows == [("a", "matched", True), ("b", "matched", True), ("c", "added", False), ("d", "added", False)]


def test_send_is_idempotent_and_marks_week_carted(conn):
    pcx = FakePcx({"x": [prod("A", 5)]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    assert not carted(conn)
    assert db.default_cart_week(conn, date(2026, 10, 7)) is None   # no plan entries; sanity
    cid = drafts.send_draft(conn, did, pcx)
    assert drafts.send_draft(conn, did, pcx) == cid and pcx.created == [cid] and len(pcx.adds) == 1
    d = drafts.get_draft(conn, did)
    assert d["status"] == "sent" and d["pcx_cart_id"] == cid
    assert carted(conn)


def test_price_at_add_is_price_at_send_time(conn):
    pcx = FakePcx({"x": [prod("A", 5)]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    db.record_products(conn, "1092", [prod("A", 4.5)])            # price moved before the user hit send
    drafts.send_draft(conn, did, pcx)
    assert conn.execute("SELECT price_at_add::float, status FROM cart_lines").fetchone() == (4.5, "added")


def test_default_week_only_counts_sent_drafts(conn):
    from mealprep.models import Recipe
    rid = db.save_recipe(conn, Recipe(title="A", source="photo", ingredients=[], steps=[]))
    db.add_to_week(conn, WK[0], rid)
    pcx = FakePcx({"x": [prod("A")]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    assert db.default_cart_week(conn, date(2026, 10, 7)) == WK[0]
    drafts.send_draft(conn, did, pcx)
    assert db.default_cart_week(conn, date(2026, 10, 7)) is None


def test_total_outage_fails_and_is_not_carted(conn):
    pcx = FakePcx({}, fail={"a", "b"})
    did = build(conn, [item("a"), item("b")], pcx, FakeAI({}))
    d = drafts.get_draft(conn, did)
    assert d["status"] == "failed" and "PC Express" in d["error"]
    with pytest.raises(drafts.DraftStateError):
        drafts.send_draft(conn, did, pcx)
    assert not carted(conn) and pcx.created == []


def test_partial_outage_still_ready(conn):
    pcx = FakePcx({"b": [prod("B")]}, fail={"a"})
    did = build(conn, [item("a"), item("b")], pcx, FakeAI({"b": "B"}))
    d = drafts.get_draft(conn, did)
    assert d["status"] == "ready" and [l["product"] and l["product"]["code"] for l in d["lines"]] == [None, "B"]


def test_edits_rejected_unless_ready(conn):
    pcx = FakePcx({"x": [prod("A"), prod("B")]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.send_draft(conn, did, pcx)
    for kw in ({"product_code": "B"}, {"quantity": 2}, {"removed": True}):
        with pytest.raises(drafts.DraftStateError):
            drafts.update_line(conn, did, line["id"], **kw)
    with pytest.raises(drafts.DraftStateError):
        drafts.search_line(conn, did, line["id"], "x", pcx, store_id="1092")
    building = drafts.create_draft(conn, [item("x")], WK, store_id="1092", ai_provider="fake")
    with pytest.raises(drafts.DraftStateError):
        drafts.send_draft(conn, building, pcx)


def test_missing_draft_or_line(conn):
    assert drafts.get_draft(conn, 999) is None
    pcx = FakePcx({"x": [prod("A")]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    with pytest.raises(drafts.DraftNotFound):
        drafts.update_line(conn, did, 999, quantity=1)
    with pytest.raises(drafts.DraftNotFound):
        drafts.send_draft(conn, 999, pcx)


def test_interrupted_builds_marked_failed(conn):
    did = drafts.create_draft(conn, [item("x")], WK, store_id="1092", ai_provider="fake")
    drafts.fail_interrupted(conn)
    d = drafts.get_draft(conn, did)
    assert d["status"] == "failed" and "restart" in d["error"]


def test_positive_quantity_restores_removed_line(conn):
    pcx = FakePcx({"x": [prod("A")]})
    did = build(conn, [item("x")], pcx, FakeAI({"x": "A"}))
    [line] = drafts.get_draft(conn, did)["lines"]
    drafts.update_line(conn, did, line["id"], quantity=0)
    out = drafts.update_line(conn, did, line["id"], quantity=2)
    assert out["removed"] is False and out["quantity"] == 2
    out = drafts.update_line(conn, did, line["id"], removed=True)
    out = drafts.update_line(conn, did, line["id"], removed=False)
    assert out["removed"] is False and out["quantity"] == 2


def test_pick_under_pre_cleanup_key_still_used(conn):
    """A pick remembered under an old, uncleaned key ("parsnip, chopped|each") carries over to "parsnip|each"."""
    db.set_pick(conn, "parsnip, chopped|each", "B", chosen_by="user")
    pcx, ai = FakePcx({"parsnip": [prod("A"), prod("B")]}), FakeAI({"parsnip": "A"})
    did = build(conn, [item("parsnip")], pcx, ai)
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"]["code"] == "B" and line["source"] == "memory" and ai.n == 0
    assert db.get_pick_full(conn, "parsnip|each") == ("B", "user")          # copied forward, no new history row
    assert conn.execute("SELECT count(*) FROM pick_history").fetchone()[0] == 1


def test_exact_pick_wins_over_old_keys(conn):
    db.set_pick(conn, "fresh parsnip|each", "A", chosen_by="user")
    db.set_pick(conn, "parsnip|each", "B", chosen_by="ai")
    assert pick(conn, "parsnip|each") == ("B", "ai")
    assert pick(conn, "turnip|each") is None


def pick(conn, key):
    found = drafts.find_pick(conn, key)
    return found[:2] if found else None


def test_search_term_has_no_prep_words(conn):
    pcx = FakePcx({"whole milk": [prod("M")]})
    build(conn, [item("whole milk warmed"), item("finely grated parmesan")], pcx, FakeAI({}))
    assert sorted(pcx.searches) == ["parmesan", "whole milk"]
