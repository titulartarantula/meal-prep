"""A product named after the ingredient isn't the ingredient: "Sharp Cheddar" by Kraft is a boxed macaroni & cheese
dinner, not cheese. The AI pick sees the description and may answer "none of these"; the cart check rejects a wrong
product (also a remembered one) and forgets the AI's pick, but never a product the household chose."""
import json
import re

from mealprep import db, drafts, qtycheck
from mealprep.models import ListItem, Product

from test_drafts import FakePcx, WK

KD = Product(code="KD", name="Sharp Cheddar", brand="Kraft", package_size="175 g", price=1.99, stock="OK",
             description="Kraft Dinner Sharp Cheddar Macaroni & Cheese combines sharp cheddar KD cheese with noodles.")
BLOCK = Product(code="OLD", name="Old Cheddar Cheese", brand="Black Diamond", package_size="400 g", price=7.99,
                stock="OK", description="Aged cheddar cheese block with a sharp, tangy taste.")
KEY = "sharp cheddar|mass"


def sharp():
    return ListItem(key=KEY, name="sharp cheddar", qty=4, unit="oz", recipes=["Skillet Rice"])


class AI:
    """`pick` answers the pick prompt (gets the candidates); `check` answers the cart check (gets the lines)."""
    def __init__(self, pick, check=lambda lines: []):
        self.pick, self.check, self.cands, self.lines = pick, check, None, None

    def complete_json(self, prompt, images=None):
        if prompt.startswith(qtycheck.MARKER):
            self.lines = json.loads(re.search(r"Lines \(JSON\): (\[.*\])\n", prompt, re.S).group(1))
            return {"changes": self.check(self.lines)}
        self.cands = json.loads(re.search(r"\): (\[.*\])\nThe product must", prompt, re.S).group(1))
        return self.pick(self.cands)


def build(conn, ai, results=None):
    did = drafts.create_draft(conn, [sharp()], WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, FakePcx(results or {"sharp cheddar": [KD, BLOCK]}), ai, store_id="1092")
    return drafts.get_draft(conn, did)


def wrong(lines):
    return [{"id": l["id"], "wrong_item": True, "reason": "it's a boxed macaroni & cheese dinner"}
            for l in lines if l["product"] == "Sharp Cheddar"]


def test_pick_sees_what_each_product_is(conn):
    ai = AI(lambda c: {"code": "OLD"})
    d = build(conn, ai)
    assert {c["code"]: c["description"] for c in ai.cands}["KD"].startswith("Kraft Dinner Sharp Cheddar Macaroni")
    assert d["lines"][0]["product"]["code"] == "OLD" and db.get_pick(conn, KEY) == "OLD"


def test_long_descriptions_are_cut(conn):
    long = KD.model_copy(update={"description": "x" * 1000})
    ai = AI(lambda c: {"code": "OLD"})
    build(conn, ai, {"sharp cheddar": [long, BLOCK]})
    assert len(ai.cands[0]["description"]) == 160


def test_none_fit_leaves_the_line_for_the_household(conn):
    d = build(conn, AI(lambda c: {"code": None}), {"sharp cheddar": [KD]})
    line = d["lines"][0]
    assert line["status"] == "unmatched" and line["product"] is None and line["quantity"] is None
    assert line["note"] == "None of the products found is sharp cheddar itself (only foods named after it) — choose one."
    assert [a["code"] for a in line["alternatives"]] == ["KD"]
    assert db.get_pick(conn, KEY) is None


def test_ai_failure_is_not_none_fit(conn):
    d = build(conn, AI(lambda c: "nope"))
    assert d["lines"][0]["status"] == "unmatched" and d["lines"][0]["note"] is None


def test_check_rejects_a_wrong_product_and_forgets_the_ai_pick(conn):
    ai = AI(lambda c: {"code": "KD"}, wrong)
    d = build(conn, ai)
    assert ai.lines[0]["description"].startswith("Kraft Dinner")
    line = d["lines"][0]
    assert d["status"] == "ready" and line["status"] == "unmatched" and line["product"] is None
    assert line["quantity"] is None and line["source"] == "none"
    assert line["note"] == ("Not added: Kraft Sharp Cheddar is a different food — it's a boxed macaroni & cheese "
                            "dinner. Choose the right product.")
    assert db.get_pick(conn, KEY) is None


def test_check_catches_a_remembered_wrong_pick(conn):
    db.set_pick(conn, KEY, "KD", chosen_by="ai")
    ai = AI(lambda c: {"code": "OLD"}, wrong)
    d = build(conn, ai)
    assert ai.cands is None                     # the remembered pick skipped the AI pick
    assert d["lines"][0]["status"] == "unmatched" and db.get_pick(conn, KEY) is None
    d = build(conn, ai)                         # next cart: picked fresh
    assert d["lines"][0]["product"]["code"] == "OLD" and db.get_pick(conn, KEY) == "OLD"


def test_check_never_rejects_the_households_choice(conn):
    db.set_pick(conn, KEY, "KD", chosen_by="user")
    d = build(conn, AI(lambda c: {"code": "OLD"}, wrong))
    line = d["lines"][0]
    assert line["product"]["code"] == "KD" and line["status"] == "matched" and line["note"] is None
    assert db.get_pick(conn, KEY) == "KD"


def test_check_never_rejects_a_pinned_product(conn):
    db.set_pick(conn, KEY, "KD", chosen_by="ai")
    db.set_pinned(conn, KEY, True)
    d = build(conn, AI(lambda c: {"code": "OLD"}, wrong))
    assert d["lines"][0]["product"]["code"] == "KD" and db.get_pick(conn, KEY) == "KD"


def test_bad_wrong_item_answers_are_ignored(conn):
    for check in (lambda l: [{"wrong_item": True}], lambda l: [{"id": "x", "wrong_item": True}],
                  lambda l: [{"id": 999999, "wrong_item": True}], lambda l: [{"id": l[0]["id"], "wrong_item": "yes"}]):
        d = build(conn, AI(lambda c: {"code": "OLD"}, check))
        assert d["lines"][0]["product"]["code"] == "OLD" and d["lines"][0]["status"] == "matched"
