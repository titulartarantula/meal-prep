"""POST /list {fridge: true}: the shopping list leaves out what is in the fridge (only when units convert)."""
from datetime import date

from hypothesis import given, settings, strategies as st

from mealprep import drafts
from mealprep.models import Ingredient, Recipe, Staple
from mealprep.shopping import build_list
from test_api import H, client
from test_fridge_match import F


def I(name, qty=None, unit=None, likely=False):
    return Ingredient(raw="", name=name, qty=qty, unit=unit, likely_on_hand=likely)


def R(title, *ings, servings=4):
    return Recipe(title=title, source="nyt", servings=servings, ingredients=list(ings), steps=[])


def S(name, qty=1, unit=None, sid=1):
    return Staple(id=sid, name=name, qty=qty, unit=unit, created_at="2026-10-05T00:00:00+00:00")


def by_name(items):
    return {it.name: it for it in items}


def test_covered_line_moves_to_probably_have_with_its_amount():
    [it] = build_list([(R("Frittata", I("egg", 4)), 1)], fridge=[F(1, "Eggs", 6)])
    assert (it.fridge, it.qty, it.fridge_qty, it.needed, it.fridge_items) == ("covered", 4, 4, False, [1])


def test_partial_line_keeps_the_remainder():
    [it] = build_list([(R("Quiche", I("egg", 12)), 1)], fridge=[F(1, "Eggs", 6)])
    assert (it.fridge, it.qty, it.fridge_qty, it.needed) == ("partial", 6, 6, True)


def test_partial_in_another_unit_of_the_same_family():
    [it] = build_list([(R("Pudding", I("whole milk", 2, "cup")), 1)], fridge=[F(1, "Whole milk", 250, "ml")])
    assert it.fridge == "partial" and it.unit == "cup" and it.fridge_qty == round(250 * 0.2029 / 48, 2)
    assert abs(it.qty + it.fridge_qty - 2) <= 0.011


def test_units_that_dont_convert_are_a_check():
    [it] = build_list([(R("Salsa", I("cilantro", 1)), 1)], fridge=[F(1, "Cilantro", 1, "bunch")])
    assert (it.fridge, it.qty, it.fridge_qty, it.needed) == ("check", 1, None, False)
    [it] = build_list([(R("Cake", I("butter", 2, "tbsp")), 1)], fridge=[F(1, "Butter", 454, "g")])
    assert (it.fridge, it.qty, it.needed) == ("check", 2, False)


def test_an_item_with_no_amount_is_a_check():
    [it] = build_list([(R("Salsa", I("cilantro", 0.25, "cup")), 1)], fridge=[F(1, "Cilantro", None)])
    assert (it.fridge, it.qty, it.needed) == ("check", 0.25, False)


def test_a_used_up_item_changes_nothing():
    [it] = build_list([(R("Quiche", I("egg", 4)), 1)], fridge=[F(1, "Eggs", 0)])
    assert (it.fridge, it.qty, it.needed) == (None, 4, True)


def test_two_weeks_merge_then_subtract_once():
    a, b = R("A", I("egg", 4)), R("B", I("egg", 4))
    [it] = build_list([(a, 1), (b, 1)], fridge=[F(1, "Eggs", 6)])
    assert (it.fridge, it.qty, it.fridge_qty, it.recipes) == ("partial", 2, 6, ["A", "B"])


def test_two_items_of_the_same_food_add_up():
    [it] = build_list([(R("A", I("whole milk", 4, "cup")), 1)],
                      fridge=[F(1, "Whole milk", 1, "cup"), F(2, "Whole milk", 500, "ml")])
    assert it.fridge == "partial" and it.fridge_items == [1, 2]
    assert abs(it.qty - (4 - 1 - 500 * 0.2029 / 48)) <= 0.011


def test_scaled_to_people():
    [it] = build_list([(R("A", I("egg", 2), servings=2), 1)], people=4, fridge=[F(1, "Eggs", 3)])
    assert (it.qty, it.fridge_qty) == (1, 3)


def test_other_lines_of_the_same_food_are_a_check():
    lines = build_list([(R("A", I("garlic", 3, "clove"), I("garlic", 1, "tsp")), 1)], fridge=[F(1, "Garlic", 10, "clove")])
    states = sorted((it.unit, it.fridge) for it in lines)
    assert states == [("clove", "covered"), ("tsp", "check")]


def test_a_staple_keeps_its_floor_and_stays_needed():
    [it] = build_list([(R("Pudding", I("whole milk", 1, "cup")), 1)], staples=[S("whole milk")],
                      fridge=[F(1, "Whole milk", 4, "cup")])
    assert it.staple and it.needed and it.staple_packs == 1
    assert (it.fridge, it.qty, it.fridge_qty) == ("partial", 0, 1)


def test_a_measured_staple_is_not_lowered():
    [it] = build_list([(R("Pudding", I("whole milk", 1, "cup")), 1)], staples=[S("whole milk", 1, "l")],
                      fridge=[F(1, "Whole milk", 3, "cup")])
    assert it.staple and it.needed and it.fridge == "partial" and it.fridge_qty == 1
    assert abs(it.qty - 202.9 / 48) <= 0.011          # the litre is still bought


def test_a_line_that_is_only_a_staple_is_untouched():
    [it] = build_list([], staples=[S("lemonade")], fridge=[F(1, "Lemonade", 2, "l")])
    assert it.fridge is None and it.needed and it.staple_packs == 1
    [it] = build_list([], staples=[S("whole milk", 2, "l")], fridge=[F(1, "Whole milk", 2, "l")])
    assert it.fridge is None and it.qty == 2


def test_likely_on_hand_lines_get_the_state_and_stay_unticked():
    out = by_name(build_list([(R("A", I("egg", 2, likely=True), I("butter", 1, "tbsp", likely=True)), 1)],
                             fridge=[F(1, "Eggs", 1), F(2, "Butter", 1, "cup")]))
    assert (out["egg"].fridge, out["egg"].qty, out["egg"].needed) == ("partial", 1, False)
    assert (out["butter"].fridge, out["butter"].needed) == ("covered", False)


def test_unrelated_lines_are_unchanged():
    out = by_name(build_list([(R("A", I("egg", 2), I("red onion", 1)), 1)], fridge=[F(1, "Onion", 5)]))
    assert out["red onion"].fridge is None and out["red onion"].qty == 1


FIXTURE = [(R("Chili", I("onion", 2), I("ground beef", 1, "lb"), I("whole milk", 0.25, "cup"), I("salt", likely=True),
              servings=8), 1),
           (R("Soup", I("onion", 1), I("whole milk", 4, "tbsp"), I("garlic", 3, "clove"), I("cilantro")), 2)]


def test_no_fridge_is_todays_list():
    plain = [it.model_dump() for it in build_list(FIXTURE, 4, [S("2% milk")])]
    assert [it.model_dump() for it in build_list(FIXTURE, 4, [S("2% milk")], fridge=[])] == plain
    assert all(it["fridge"] is None and it["fridge_qty"] is None and it["fridge_items"] == [] for it in plain)


NAMES = ["egg", "whole milk", "cilantro", "butter", "garlic"]
UNITS = [None, "cup", "tbsp", "ml", "g", "kg", "clove", "bunch"]
ing_st = st.builds(lambda n, q, u: I(n, q, u), st.sampled_from(NAMES),
                   st.one_of(st.none(), st.floats(0.1, 50).map(lambda x: round(x, 2))), st.sampled_from(UNITS))
fr_st = st.lists(st.tuples(st.sampled_from(NAMES), st.one_of(st.none(), st.floats(0, 100).map(lambda x: round(x, 2))),
                           st.sampled_from(UNITS)), max_size=4)


@settings(max_examples=300, deadline=None)
@given(recipes=st.lists(st.lists(ing_st, min_size=1, max_size=5), min_size=1, max_size=3), fr=fr_st,
       people=st.integers(1, 8))
def test_the_fridge_only_ever_lowers_convertible_lines(recipes, fr, people):
    entries = [(R(f"R{i}", *ings), 1) for i, ings in enumerate(recipes)]
    seen, items = set(), []
    for i, (n, q, u) in enumerate(fr):
        if (n, u) not in seen:
            seen.add((n, u))
            items.append(F(i + 1, n.title(), q, u))
    plain = {it.key: it for it in build_list(entries, people)}
    assert [x.model_dump() for x in build_list(entries, people, fridge=[])] == [x.model_dump() for x in plain.values()]
    out = build_list(entries, people, fridge=items)
    assert [it.key for it in out] == list(plain)
    for it in out:
        p = plain[it.key]
        if it.fridge == "partial":
            assert it.qty < p.qty + 1e-9 and abs(it.qty + it.fridge_qty - p.qty) <= 0.011 + 1e-9
        else:
            assert it.qty == p.qty
        if it.fridge in ("covered", "check"):
            assert not it.needed
        if it.fridge is None:
            assert it.model_dump() == p.model_dump()


# --- API + drafts ------------------------------------------------------------------------------------------------

def plan(conn, week, *recipes):
    from mealprep import db
    for r in recipes:
        db.add_to_week(conn, week, db.save_recipe(conn, r))


def test_post_list_with_the_fridge(conn):
    plan(conn, date(2026, 10, 11), R("Quiche", I("egg", 12), I("cilantro", 1), I("red onion", 1)))
    c = client(conn)
    c.post("/fridge", headers=H, json={"name": "Eggs", "qty": 6})
    c.post("/fridge", headers=H, json={"name": "Cilantro", "qty": 1, "unit": "bunch"})
    body = {"weeks": ["2026-10-11"]}
    plain = c.post("/list", headers=H, json=body).json()
    assert c.post("/list", headers=H, json={**body, "fridge": False}).json() == plain
    out = {it["name"]: it for it in c.post("/list", headers=H, json={**body, "fridge": True}).json()}
    assert (out["egg"]["fridge"], out["egg"]["qty"], out["egg"]["fridge_qty"]) == ("partial", 6, 6)
    assert (out["cilantro"]["fridge"], out["cilantro"]["needed"]) == ("check", False)
    assert out["red onion"]["fridge"] is None


def test_a_draft_from_a_fridge_list_buys_only_the_remainder(conn):
    items = build_list([(R("A", I("egg", 12), I("whole milk", 1, "cup"), I("cilantro", 1), I("red onion", 1)), 1)],
                       fridge=[F(1, "Eggs", 6), F(2, "Whole milk", 2, "cup"), F(3, "Cilantro", 1, "bunch")])
    did = drafts.create_draft(conn, items, [date(2026, 10, 11)], store_id="1092", ai_provider="fake")
    rows = conn.execute("SELECT item_name, need_qty::float, item->>'fridge', (item->>'fridge_qty')::float "
                        "FROM cart_lines WHERE cart_id=%s ORDER BY position", (did,)).fetchall()
    assert rows == [("egg", 6.0, "partial", 6.0), ("red onion", 1.0, None, None)]
