from datetime import date
import itertools
import httpx
from mealprep import db
from mealprep.models import ListItem, Product
from mealprep.matcher import fill_cart

WK = [date(2026, 10, 11)]
_ids = itertools.count(1)


class FakePcx:
    def __init__(self, results, fail=()): self.results, self.added, self.fail = results, {}, fail
    def search(self, term, size=8):
        if term in self.fail:
            raise httpx.ConnectError("boom")
        return self.results.get(term, [])
    def create_cart(self): return f"c{next(_ids)}"   # real cart ids are unique
    def add(self, cid, entries): self.added.update(entries)


class FakeAI:
    def __init__(self, out): self.out, self.n = out, 0
    def complete_json(self, prompt, images=None): self.n += 1; return self.out


P1 = Product(code="A", name="Chicken Thighs 1kg", stock="OK", price=15)
P2 = Product(code="B", name="Chicken Thighs Club", stock="OK", price=21)


def item(name, needed=True):
    return ListItem(key=f"{name}|each", name=name, qty=1, unit=None, needed=needed)


def fc(items, pcx, ai, conn):
    return fill_cart(items, pcx, ai, conn, weeks=WK, provider_name="fake")


def test_llm_choice_is_added_and_remembered(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1, P2]}), FakeAI({"code": "B", "quantity": 1})
    res = fc([item("chicken thigh")], pcx, ai, conn)
    assert pcx.added == {"B": 1} and res.lines[0].status == "added"
    assert db.get_pick(conn, "chicken thigh|each") == "B"


def test_remembered_pick_skips_llm(conn):
    db.set_pick(conn, "chicken thigh|each", "A")
    pcx, ai = FakePcx({"chicken thigh": [P1, P2]}), FakeAI({"code": "B", "quantity": 1})
    fc([item("chicken thigh")], pcx, ai, conn)
    assert pcx.added == {"A": 1} and ai.n == 0


def test_no_results_is_unmatched_and_rest_still_added(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1]}), FakeAI({"code": "A", "quantity": 1})
    res = fc([item("sumac"), item("chicken thigh")], pcx, ai, conn)
    assert [l.status for l in res.lines] == ["unmatched", "added"] and pcx.added == {"A": 1}


def test_search_error_is_unmatched_and_rest_still_added(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1]}, fail={"sumac"}), FakeAI({"code": "A", "quantity": 1})
    res = fc([item("sumac"), item("chicken thigh")], pcx, ai, conn)
    assert [l.status for l in res.lines] == ["unmatched", "added"] and pcx.added == {"A": 1}


def test_out_of_stock_remembered_pick_falls_back(conn):
    db.set_pick(conn, "chicken thigh|each", "A")
    for status in ("OUT", "OUT_OF_STOCK"):    # live API says "OUT"
        oos = P1.model_copy(update={"stock": status})
        pcx, ai = FakePcx({"chicken thigh": [oos, P2]}), FakeAI({"code": "B", "quantity": 1})
        fc([item("chicken thigh")], pcx, ai, conn)
        assert pcx.added == {"B": 1}
        db.set_pick(conn, "chicken thigh|each", "A")


def test_unneeded_items_skipped(conn):
    pcx = FakePcx({}); res = fc([item("salt", needed=False)], pcx, FakeAI({}), conn)
    assert res.lines == [] and pcx.added == {}


def test_llm_invalid_code_is_unmatched(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1]}), FakeAI({"code": "ZZZ", "quantity": 1})
    res = fc([item("chicken thigh")], pcx, ai, conn)
    assert res.lines[0].status == "unmatched" and pcx.added == {}


def test_llm_non_dict_answer_is_unmatched(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1]}), FakeAI(["A"])
    res = fc([item("chicken thigh")], pcx, ai, conn)
    assert res.lines[0].status == "unmatched"


def test_search_results_recorded_as_price_observations(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1, P2]}), FakeAI({"code": "B", "quantity": 1})
    fc([item("chicken thigh")], pcx, ai, conn)
    assert conn.execute("SELECT code, price::float FROM price_observations ORDER BY code").fetchall() == [("A", 15.0), ("B", 21.0)]


def test_cart_weeks_and_lines_recorded_incl_unmatched(conn):
    db.set_pick(conn, "chicken thigh|each", "A")
    pcx, ai = FakePcx({"chicken thigh": [P1], "beef": [P2]}), FakeAI({"code": "B", "quantity": 2})
    res = fill_cart([item("sumac"), item("chicken thigh"), item("beef")], pcx, ai, conn,
                    weeks=[date(2026, 10, 14), date(2026, 10, 18)], provider_name="claude-cli")
    assert conn.execute("SELECT pcx_cart_id, store_id, ai_provider FROM carts").fetchall() == [(res.cart_id, "1092", "claude-cli")]
    assert [w.isoformat() for (w,) in conn.execute("SELECT week FROM cart_weeks ORDER BY week")] == ["2026-10-11", "2026-10-18"]
    rows = conn.execute("SELECT item_key, item_name, need_qty, product_code, quantity, price_at_add::float, status, source "
                        "FROM cart_lines ORDER BY id").fetchall()
    assert rows == [("sumac|each", "sumac", 1.0, None, None, None, "unmatched", "none"),
                    ("chicken thigh|each", "chicken thigh", 1.0, "A", 1, 15.0, "added", "memory"),
                    ("beef|each", "beef", 1.0, "B", 2, 21.0, "added", "ai")]


def test_same_week_carted_twice_records_two_carts(conn):
    for _ in range(2):
        fc([item("chicken thigh")], FakePcx({"chicken thigh": [P1]}), FakeAI({"code": "A", "quantity": 1}), conn)
    assert conn.execute("SELECT count(*) FROM carts").fetchone()[0] == 2
    assert conn.execute("SELECT count(*) FROM cart_weeks WHERE week='2026-10-11'").fetchone()[0] == 2


def test_user_pick_survives_out_of_stock_week(conn):
    db.set_pick(conn, "chicken thigh|each", "A", chosen_by="user")
    oos = P1.model_copy(update={"stock": "OUT"})
    pcx, ai = FakePcx({"chicken thigh": [oos, P2]}), FakeAI({"code": "B", "quantity": 1})
    fc([item("chicken thigh")], pcx, ai, conn)
    assert pcx.added == {"B": 1}                                   # substitute this week
    assert db.get_pick(conn, "chicken thigh|each") == "A"          # user's choice kept
    pcx = FakePcx({"chicken thigh": [P1, P2]})
    fc([item("chicken thigh")], pcx, FakeAI({"code": "B", "quantity": 1}), conn)
    assert pcx.added == {"A": 1}                                   # back next week


def test_ai_pick_out_of_stock_is_kept_for_next_week(conn):
    db.set_pick(conn, "chicken thigh|each", "A")
    oos = P1.model_copy(update={"stock": "OUT"})
    fc([item("chicken thigh")], FakePcx({"chicken thigh": [oos, P2]}), FakeAI({"code": "B", "quantity": 1}), conn)
    assert db.get_pick(conn, "chicken thigh|each") == "A"


def test_ai_pick_no_longer_listed_is_replaced(conn):
    db.set_pick(conn, "chicken thigh|each", "GONE")
    fc([item("chicken thigh")], FakePcx({"chicken thigh": [P1, P2]}), FakeAI({"code": "B", "quantity": 1}), conn)
    assert db.get_pick(conn, "chicken thigh|each") == "B"


def test_remembered_pick_scales_quantity_from_last_cart(conn):
    pcx, ai = FakePcx({"chicken thigh": [P1, P2]}), FakeAI({"code": "B", "quantity": 2})
    it = ListItem(key="chicken thigh|each", name="chicken thigh", qty=2, unit=None)
    fc([it], pcx, ai, conn)                                        # AI: 2 packs for qty 2
    assert pcx.added == {"B": 2}
    pcx = FakePcx({"chicken thigh": [P1, P2]})
    fc([it.model_copy(update={"qty": 4})], pcx, ai, conn)          # remembered: double the need → 4 packs
    assert pcx.added == {"B": 4} and ai.n == 1


def test_total_search_outage_raises_and_sends_nothing(conn):
    import pytest
    from mealprep.drafts import DraftFailed
    pcx = FakePcx({}, fail={"a", "b"})
    with pytest.raises(DraftFailed, match="PC Express"):
        fc([item("a"), item("b")], pcx, FakeAI({}), conn)
    assert conn.execute("SELECT status, pcx_cart_id FROM carts").fetchall() == [("failed", None)]
    assert conn.execute("SELECT count(*) FROM cart_weeks").fetchone()[0] == 0 and pcx.added == {}
