"""Quantity check: the AI's last pass over a built draft corrects clearly wrong quantities, with a note."""
import json
import re

from mealprep import drafts, qtycheck
from mealprep.ai.base import AIError
from mealprep.models import ListItem, Product

from test_drafts import FakePcx, WK


def onion():
    return Product(code="GO", name="Green Onion", package_size="1 ea", price=2.0, stock="OK", sold_by="SOLD_BY_EACH",
                   description="Green onions have a firm white base.")


def deli():
    return Product(code="HAM", name="Black Forest Ham", price=2.49, stock="OK", sold_by="SOLD_BY_WEIGHT",
                   weighted=True, min_qty=100, interval=100, max_qty=2000, price_quantity=100)


class CheckAI:
    """Picks the only candidate (the quantity the build gives it); answers the check with `changes`."""
    def __init__(self, changes=None, fail=False):
        self.changes, self.fail, self.seen = changes or (lambda lines: []), fail, None

    def complete_json(self, prompt, images=None):
        if "Candidates" in prompt:
            code = re.search(r'"code": "(\w+)"', prompt).group(1)
            return {"code": code, "quantity": 1}
        if self.fail:
            raise AIError("boom")
        self.seen = json.loads(re.search(r"Lines \(JSON\): (\[.*\])\n", prompt, re.S).group(1))
        return {"changes": self.changes(self.seen)}


def build(conn, items, results, ai):
    did = drafts.create_draft(conn, items, WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, FakePcx(results), ai, store_id="1092")
    return drafts.get_draft(conn, did)


def scallions(qty=6):
    return ListItem(key="scallion|each", name="scallion", qty=qty, unit=None, prep="thinly sliced", recipes=["Soup"])


def test_check_corrects_a_quantity_and_says_why(conn, monkeypatch):
    monkeypatch.setattr(qtycheck.planner, "BUNCH", [])       # the planner alone would buy 6 bunches
    ai = CheckAI(lambda lines: [{"id": lines[0]["id"], "quantity": 1, "reason": "one bunch has 6–8 green onions"}])
    d = build(conn, [scallions()], {"scallion": [onion()]}, ai)
    assert d["status"] == "ready"
    line = d["lines"][0]
    assert line["quantity"] == 1
    assert line["note"] == "Quantity checked: 6 → 1 — one bunch has 6–8 green onions."
    sent = ai.seen[0]
    assert sent["need"] == "6 scallion" and sent["product"] == "Green Onion" and sent["package_size"] == "1 ea"
    assert sent["quantity"] == 6 and sent["prep"] == "thinly sliced" and "description" in sent


def test_reasonable_cart_is_left_alone(conn):
    d = build(conn, [scallions()], {"scallion": [onion()]}, CheckAI())
    assert d["status"] == "ready" and d["lines"][0]["quantity"] == 1 and d["lines"][0]["note"] is None


def test_ai_failure_leaves_the_draft_as_built(conn, monkeypatch):
    monkeypatch.setattr(qtycheck.planner, "BUNCH", [])
    d = build(conn, [scallions()], {"scallion": [onion()]}, CheckAI(fail=True))
    assert d["status"] == "ready" and d["lines"][0]["quantity"] == 6 and d["lines"][0]["note"] is None


def test_bad_answers_are_ignored(conn, monkeypatch):
    monkeypatch.setattr(qtycheck.planner, "BUNCH", [])
    answers = [lambda lines: [{"id": 999999, "quantity": 1}], lambda lines: [{"id": "x", "quantity": 1}],
               lambda lines: [{"quantity": 2}], lambda lines: ["nope"], lambda lines: "nope",
               lambda lines: [{"id": lines[0]["id"], "quantity": 0}]]
    for changes in answers:
        d = build(conn, [scallions()], {"scallion": [onion()]}, CheckAI(changes))
        assert d["status"] == "ready" and d["lines"][0]["quantity"] == 6 and d["lines"][0]["note"] is None


def test_raise_is_capped(conn):
    ai = CheckAI(lambda lines: [{"id": lines[0]["id"], "quantity": 40}])    # 1 → 40: implausible jump
    d = build(conn, [scallions()], {"scallion": [onion()]}, ai)
    assert d["lines"][0]["quantity"] == 1


def test_weighed_line_stays_within_order_limits(conn):
    ham = ListItem(key="ham|g", name="ham", qty=300, unit="g", recipes=["Sandwiches"])
    ai = CheckAI(lambda lines: [{"id": lines[0]["id"], "quantity": 450, "reason": "a bit extra for 4 sandwiches"}])
    d = build(conn, [ham], {"ham": [deli()]}, ai)
    assert ai.seen[0]["weighed"] is True and ai.seen[0]["quantity"] == 300
    assert d["lines"][0]["quantity"] == 500                                  # rounded to the 100 g interval
    assert d["lines"][0]["note"] == "Quantity checked: 300 g → 500 g — a bit extra for 4 sandwiches."
