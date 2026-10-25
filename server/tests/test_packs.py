"""What one pack holds (pack_contents): saving, the household's number winning, the cart line's Pack sizes."""
import pytest

from mealprep import db, drafts, packs
from mealprep.models import ListItem, Product

from test_drafts import FakePcx, WK
from test_qtycheck import CheckAI, build, onion, scallions


def test_save_lookup_forget_and_user_wins(conn):
    assert packs.save(conn, "GO", 6, " Bunch ", "check", "about 6")
    assert packs.lookup(conn, "GO") == (6, "bunch", "check")
    assert packs.save(conn, "GO", 8, "bunch", "user")
    assert not packs.save(conn, "GO", 5, "bunch", "check")                  # a check never overwrites the household
    assert packs.lookup(conn, "GO") == (8, "bunch", "user")
    assert packs.save(conn, "GO", 7, "!!", "user") and packs.lookup(conn, "GO") == (7, "pack", "user")
    assert packs.known(conn) == {"GO": (7, "pack", "user")}
    packs.forget(conn, "GO")
    assert packs.lookup(conn, "GO") is None
    for bad in (0, 101):
        with pytest.raises(ValueError):
            packs.save(conn, "GO", bad, "bunch", "user")


def test_record_products_leaves_learned_pieces_out(conn):
    p = onion().model_copy(update={"pieces": 6, "pieces_word": "bunch", "pieces_source": "check"})
    db.record_products(conn, "1092", [p])
    detail = conn.execute("SELECT detail FROM products WHERE code='GO'").fetchone()[0]
    assert "pieces" not in detail and "pieces_word" not in detail


def test_build_plans_with_what_was_learned(conn, monkeypatch):
    monkeypatch.setattr(drafts.planner, "BUNCH", [])
    packs.save(conn, "GO", 6, "bunch", "check")
    ai = CheckAI()
    d = build(conn, [scallions(6)], {"scallion": [onion()]}, ai)
    line = d["lines"][0]
    assert line["quantity"] == 1 and line["why"] == "Need 6 → 1 × 1 bunch (≈ 6)"
    assert line["pack"] == {"pieces": 6, "word": "bunch", "source": "check"} and line["pack_editable"] is True
    assert ai.seen[0]["pack_holds"] == "about 6 (bunch)"


def test_pack_on_a_line_is_saved_and_replans_the_draft(conn, monkeypatch):
    monkeypatch.setattr(drafts.planner, "BUNCH", [])
    d = build(conn, [scallions(6)], {"scallion": [onion()]}, CheckAI())
    line = d["lines"][0]
    assert line["quantity"] == 6 and line["pack"] is None and line["pack_editable"] is True
    d = drafts.set_pack(conn, d["id"], line["id"], 6, "bunch")
    line = d["lines"][0]
    assert line["quantity"] == 1 and line["pack"] == {"pieces": 6, "word": "bunch", "source": "user"}
    assert packs.lookup(conn, "GO") == (6, "bunch", "user")
    d = drafts.set_pack(conn, d["id"], line["id"], None)                     # forget: back to piece by piece
    assert d["lines"][0]["quantity"] == 6 and d["lines"][0]["pack"] is None
    assert packs.lookup(conn, "GO") is None


def test_pack_keeps_a_quantity_the_household_changed(conn, monkeypatch):
    monkeypatch.setattr(drafts.planner, "BUNCH", [])
    d = build(conn, [scallions(6)], {"scallion": [onion()]}, CheckAI())
    lid = d["lines"][0]["id"]
    drafts.update_line(conn, d["id"], lid, quantity=3)
    d = drafts.set_pack(conn, d["id"], lid, 6, "bunch")
    assert d["lines"][0]["quantity"] == 3


def test_pack_only_for_products_sold_by_the_piece(conn):
    milk = Product(code="M", name="Milk", package_size="2 l", price=5.0, stock="OK")
    it = ListItem(key="milk|vol", name="milk", qty=1, unit="cup", recipes=["Pancakes"])
    d = build(conn, [it], {"milk": [milk]}, CheckAI())
    line = d["lines"][0]
    assert line["pack"] is None and line["pack_editable"] is False
    with pytest.raises(ValueError):
        drafts.set_pack(conn, d["id"], line["id"], 6, "bunch")


def test_guess_is_shown_as_a_guess(conn):
    d = build(conn, [scallions(6)], {"scallion": [onion()]}, CheckAI())
    assert d["lines"][0]["pack"] == {"pieces": 6, "word": "bunch", "source": "guess"}


def test_chooser_suggests_with_what_was_learned(conn, monkeypatch):
    monkeypatch.setattr(drafts.planner, "BUNCH", [])

    class Pcx(FakePcx):
        def search_page(self, term, page, size):
            r = self.search(term); return r, len(r)
    organic = onion().model_copy(update={"code": "GO2", "name": "Organic Green Onion"})
    pcx = Pcx({"scallion": [onion()], "green onion": [onion(), organic]})
    did = drafts.create_draft(conn, [scallions(6)], WK, store_id="1092", ai_provider="fake")
    drafts.build_draft(conn, did, pcx, CheckAI(), store_id="1092")
    lid = drafts.get_draft(conn, did)["lines"][0]["id"]
    packs.save(conn, "GO2", 6, "bunch", "user")
    by = {c["product"]["code"]: c for c in drafts.choices(conn, did, lid, pcx, term="green onion")["choices"]}
    assert by["GO2"]["product"]["pieces"] == 6 and by["GO2"]["suggested_quantity"] == 1
    assert by["GO"]["product"]["pieces"] is None and by["GO"]["suggested_quantity"] == 6   # the line's own number
