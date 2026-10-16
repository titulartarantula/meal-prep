"""Bulk packs (0.13.0): club-pack search when no pack covers the need, a bigger pack taken when it costs no more,
a hint on a remembered pick, cover cost for the AI and the chooser. Invented products and prices."""
from mealprep import bulk, drafts
from mealprep.models import ListItem, Product

from test_chooser import AI, Pcx, build


def beef(qty=1.5, unit="kg", **kw):
    return ListItem(key="lean ground beef|mass", name="lean ground beef", qty=qty, unit=unit, recipes=["Chili"], **kw)


def tray(code="TRAY", price=11.0, size="450 g", name="Lean Ground Beef", stock="OK", **kw):
    return Product(code=code, name=name, brand="PC", package_size=size, price=price, stock=stock,
                   unit_price=round(price / 4.5, 2), unit_price_unit="100 g", **kw)


def club(code="CLUB", price=42.46, unit_price=24.22, name="Lean Ground Beef, Club Pack", stock="OK", **kw):
    """Priced by weight, sold by the tray: no package size, weight = price ÷ unit price (≈ 1.75 kg)."""
    return Product(code=code, name=name, package_size=None, price=price, stock=stock, unit_price=unit_price,
                   unit_price_unit="1 kg", sold_by="SOLD_BY_EACH_PRICED_BY_WEIGHT", **kw)


def test_kind_ignores_pack_words_and_plurals():
    assert bulk.kind("Lean Ground Beef, Club Pack") == bulk.kind("Lean Ground Beef") == {"lean", "ground", "beef"}
    assert bulk.kind("Chicken Thighs Family Size") == {"chicken", "thigh"}
    assert bulk.same_item("chicken thighs", Product(code="1", name="Chicken Thighs, Club Pack"))
    assert not bulk.same_item("chicken thighs", Product(code="2", name="Chicken Breasts, Club Pack"))


def test_estimated_weight_of_a_club_pack_without_a_size():
    fam, g = bulk.est_amount(club())
    assert fam == "mass" and abs(g - 1753) < 2
    assert bulk.size_text(club()) == "≈ 1.75 kg"
    assert bulk.est_amount(Product(code="d", name="Deli Ham", price=2.0, unit_price=2.0, unit_price_unit="100 g",
                                   weighted=True)) == (None, None)       # counter by weight: any amount
    assert bulk.est_amount(Product(code="x", name="x", price=3.0)) == (None, None)


def test_cover_counts_packs_and_cost():
    assert bulk.cover(beef(), tray()) == (4, 44.0)          # 1.5 kg in 450 g trays
    assert bulk.cover(beef(), club()) == (1, 42.46)
    assert bulk.cover(beef(qty=None, unit=None), tray()) is None
    assert bulk.cover(beef(), Product(code="n", name="n", package_size="450 g")) is None   # no price


def test_wanted_when_no_pack_covers_it_or_a_staple():
    assert bulk.wanted(beef(), [tray()])
    assert not bulk.wanted(beef(qty=400, unit="g"), [tray()])
    assert not bulk.wanted(beef(qty=None, unit=None), [tray()])           # nothing to compare
    assert bulk.wanted(beef(qty=None, unit=None, staple=True, staple_packs=1), [tray()])


def test_better_cover_needs_same_kind_fewer_packs_and_no_more_money():
    it = beef()
    pricey = club(code="PRICEY", price=48.0, unit_price=27.4)
    pork = club(code="PORK", name="Lean Ground Beef & Pork, Club Pack", price=27.82, unit_price=17.62)
    big, saving = bulk.better_cover(it, tray(), [tray(), club(), pricey, pork])
    assert big.code == "CLUB" and saving == 1.54
    assert bulk.better_cover(it, tray(), [tray(), pricey]) is None       # costs more than four trays
    assert bulk.better_cover(beef(qty=400, unit="g"), tray(), [tray(), club()]) is None   # one tray covers it
    assert bulk.better_cover(it, tray(), [tray(), club(stock="OUT")]) is None


def test_build_searches_club_packs_and_takes_the_bigger_pack(conn):
    pcx = Pcx({"lean ground beef": [tray(), tray("XLEAN", 12.0, name="Extra Lean Ground Beef")],
               "lean ground beef club pack": [club(), club(code="CHICK", name="Chicken Thighs, Club Pack")]})
    did = build(conn, pcx, AI("TRAY"), [beef()])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert [t for t, _ in pcx.pages] == ["lean ground beef", "lean ground beef club pack"]
    assert line["product"]["code"] == "CLUB" and line["quantity"] == 1 and line["source"] == "ai"
    assert line["note"] == "Bigger pack: 1 × ≈ 1.75 kg instead of 4 × 450 g — $1.54 less."
    assert "CHICK" not in {a["code"] for a in line["alternatives"]}      # another item's club pack is left out


def test_no_club_pack_search_when_one_pack_covers_the_need(conn):
    pcx = Pcx({"lean ground beef": [tray()]})
    did = build(conn, pcx, AI("TRAY"), [beef(qty=400, unit="g")])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert [t for t, _ in pcx.pages] == ["lean ground beef"] and line["product"]["code"] == "TRAY"
    assert line["note"] is None


def test_a_bigger_pack_that_costs_more_is_not_taken(conn):
    pcx = Pcx({"lean ground beef": [tray()],
               "lean ground beef club pack": [club(price=48.0, unit_price=27.4)]})
    did = build(conn, pcx, AI("TRAY"), [beef()])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"]["code"] == "TRAY" and line["quantity"] == 4 and line["note"] is None


def test_a_remembered_pick_is_kept_with_a_hint(conn):
    pcx = Pcx({"lean ground beef": [tray()], "lean ground beef club pack": [club(price=35.0, unit_price=20.0)]})
    did = build(conn, pcx, AI("TRAY"), [beef(qty=400, unit="g")])        # remembered: TRAY
    drafts.update_line(conn, did, drafts.get_draft(conn, did)["lines"][0]["id"], product_code="TRAY")
    did = build(conn, pcx, AI(None), [beef()])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["source"] == "memory" and line["product"]["code"] == "TRAY" and line["quantity"] == 4
    assert line["note"] == "A bigger pack (1 × ≈ 1.75 kg) would cover this for $9.00 less — Swap product to see it."


def test_club_pack_search_failure_keeps_the_plain_results(conn):
    import httpx

    class Flaky(Pcx):
        def search_page(self, term, page=0, size=20):
            if term.endswith("club pack"):
                raise httpx.ConnectError("down")
            return super().search_page(term, page, size)
    pcx = Flaky({"lean ground beef": [tray()]})
    did = build(conn, pcx, AI("TRAY"), [beef()])
    [line] = drafts.get_draft(conn, did)["lines"]
    assert line["product"]["code"] == "TRAY" and line["quantity"] == 4


def test_ai_prompt_gets_cover_cost_and_estimated_size():
    from mealprep import matcher
    seen = []

    class Spy:
        def complete_json(self, prompt, images=None):
            seen.append(prompt); return {"code": "CLUB", "quantity": 1}
    assert matcher._choose(Spy(), beef(), [tray(), club()]) == ("CLUB", 1)
    assert '"min_packs": 4, "cover_cost": 44.0' in seen[0]
    assert '"package_size": "≈ 1.75 kg", "price": 42.46, "min_packs": 1, "cover_cost": 42.46' in seen[0]


def test_chooser_adds_club_packs_with_cover_and_bulk_flag(conn):
    pcx = Pcx({"lean ground beef": [tray(), tray("XLEAN", 12.0, name="Extra Lean Ground Beef")],
               "lean ground beef club pack": [club(price=48.0, unit_price=27.4)]})
    did = build(conn, pcx, AI("TRAY"), [beef()])
    [line] = drafts.get_draft(conn, did)["lines"]
    out = drafts.choices(conn, did, line["id"], pcx)
    by = {c["product"]["code"]: c for c in out["choices"]}
    assert set(by) == {"TRAY", "XLEAN", "CLUB"}
    assert by["CLUB"]["bulk"] is True and by["TRAY"]["bulk"] is False and by["XLEAN"]["bulk"] is False
    assert by["CLUB"]["cover"] == {"packs": 1, "cost": 48.0, "size": "≈ 1.75 kg"}
    assert by["TRAY"]["cover"] == {"packs": 4, "cost": 44.0, "size": "450 g"}
    pcx.pages.clear()
    drafts.choices(conn, did, line["id"], pcx, term="beef")               # a typed search: no club-pack search
    assert [t for t, _ in pcx.pages] == ["beef"]
