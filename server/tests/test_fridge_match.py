"""What a recipe uses from the fridge (pure core, shared by the list, suggestions and the nudge)."""
from hypothesis import given, settings, strategies as st

from mealprep import fridge
from mealprep.ingredients import BASE
from mealprep.models import FridgeItem, Ingredient, Recipe


def F(iid, name, qty, unit=None, key=None, **kw):
    return FridgeItem(id=iid, name=name, key=key or fridge.rule_key(name), key_source="rule", qty=qty, unit=unit,
                      amount_as_of="2026-10-23", **kw)


def L(name, qty, unit=None, likely=False):
    return fridge.Line(key=fridge.rule_key(name), name=name, qty=qty, unit=unit, likely_on_hand=likely)


def one(lines, items, **kw):
    out = fridge.uses(lines, items, **kw)
    assert len(out) == 1, out
    return out[0]


def test_eggs_count_with_count():
    u = one([L("egg", 4)], [F(1, "Eggs", 6)])
    assert (u.item_id, u.convertible, u.need, u.used) == (1, True, (4, None), (4, None))
    u = one([L("egg", 8)], [F(1, "Eggs", 6)])
    assert u.need == (8, None) and u.used == (6, None)


def test_milk_cup_with_ml():
    u = one([L("whole milk", 1, "cup")], [F(1, "Whole milk", 500, "ml")])
    assert u.convertible and u.need[1] == "ml" and abs(u.need[0] - 48 / 0.2029) < 1e-6
    assert u.used == u.need


def test_two_lines_of_the_same_item_add_up():
    u = one([L("whole milk", 1, "cup"), L("whole milk", 2, "tbsp")], [F(1, "Whole milk", 2, "cup")])
    assert abs(u.need[0] - (1 + 6 / 48)) < 1e-9 and u.need[1] == "cup"


def test_butter_tbsp_with_grams_never_converts():
    u = one([L("butter", 2, "tbsp")], [F(1, "Butter", 454, "g")])
    assert (u.convertible, u.need, u.used) == (False, None, None)


def test_cilantro_bunch_with_each_never_converts():
    u = one([L("cilantro", 1)], [F(1, "Cilantro", 1, "bunch")])
    assert not u.convertible and u.used is None


def test_garlic_cloves_convert_only_with_cloves():
    assert one([L("garlic", 3, "clove")], [F(1, "Garlic", 10, "clove")]).used == (3, "clove")
    assert not one([L("garlic", 3, "clove")], [F(1, "Garlic", 1, "head")]).convertible


def test_an_item_with_no_amount_is_never_convertible():
    u = one([L("spinach", 200, "g")], [F(1, "Spinach", None)])
    assert not u.convertible and u.used is None


def test_an_unmeasured_line_is_a_check():
    u = one([L("cilantro", None)], [F(1, "Cilantro", 1, "bunch")])
    assert not u.convertible


def test_a_used_up_item_is_left_out():
    assert fridge.uses([L("egg", 2)], [F(1, "Eggs", 0)]) == []
    assert fridge.uses([L("egg", 2)], [F(1, "Eggs", 6)], have={1: 0}) == []


def test_other_items_and_lines_dont_match():
    assert fridge.uses([L("red onion", 1)], [F(1, "Onion", 3)]) == []
    assert [u.item_id for u in fridge.uses([L("egg", 2), L("kale", 1, "bunch")],
                                           [F(1, "Kale", 1, "bunch"), F(2, "Eggs", 6), F(3, "Leeks", 2)])] == [1, 2]


def test_have_overrides_the_stored_amount():
    assert one([L("egg", 4)], [F(1, "Eggs", 6)], have={1: 3}).used == (3, None)


def test_recipe_lines_scale_clean_and_skip_expanded():
    ings = [Ingredient(raw="2 large eggs", name="large eggs", qty=2),
            Ingredient(raw="1 cup whole milk, warmed", name="whole milk, warmed", qty=1, unit="cup"),
            Ingredient(raw="Batter, page 9", name="batter", expanded=True),
            Ingredient(raw="salt", name="salt", likely_on_hand=True)]
    r = Recipe(title="T", source="photo", servings=2, ingredients=ings, steps=[])
    lines = fridge.recipe_lines(r, 2.0)
    assert [(x.key, x.qty, x.unit) for x in lines] == [("egg", 4, None), ("whole milk", 2, "cup"), ("salt", None, None)]
    assert lines[2].likely_on_hand


def test_amount_in_base():
    assert fridge.amount_in_base(2, "cup") == ("vol", 96)
    assert fridge.amount_in_base(1, "kg") == ("mass", 1000)
    assert fridge.amount_in_base(3, "clove") == ("clove", 3)
    assert fridge.amount_in_base(6, None) == ("each", 6)
    assert fridge.amount_in_base(None, "g") is None


CONV = sorted(BASE)
qty = st.floats(min_value=0.01, max_value=5000, allow_nan=False)


@settings(max_examples=400, deadline=None)
@given(need=qty, need_unit=st.sampled_from(CONV), have=qty, have_unit=st.sampled_from(CONV))
def test_used_never_exceeds_the_need_or_what_is_there(need, need_unit, have, have_unit):
    out = fridge.uses([L("milk", need, need_unit)], [F(1, "Milk", have, have_unit)])
    u = out[0]
    if BASE[need_unit][0] != BASE[have_unit][0]:            # volume vs mass: never a number
        assert not u.convertible and u.used is None and u.need is None
        return
    assert u.convertible and u.need[1] == u.used[1] == have_unit
    n = fridge.amount_in_base(*u.need)[1]
    used = fridge.amount_in_base(*u.used)[1]
    have_b = fridge.amount_in_base(have, have_unit)[1]
    assert used <= n + 1e-6 and used <= have_b + 1e-6 and n - used >= -1e-6
    assert abs(n - fridge.amount_in_base(need, need_unit)[1]) <= 1e-6 * max(1, n)


@settings(max_examples=300, deadline=None)
@given(q=qty, unit=st.sampled_from(CONV + ["clove", "bunch", None]))
def test_unit_round_trip(q, unit):
    dim, b = fridge.amount_in_base(q, unit)
    assert abs(fridge.from_base(b, unit) - q) <= 1e-6 * max(1, q)


@settings(max_examples=200, deadline=None)
@given(need=qty, nu=st.sampled_from(["clove", "bunch", "can", None]), have=qty,
       hu=st.sampled_from(["clove", "bunch", "can", None, "g", "cup"]))
def test_count_units_convert_only_with_themselves(need, nu, have, hu):
    u = fridge.uses([L("garlic", need, nu)], [F(1, "Garlic", have, hu)])[0]
    assert u.convertible == (nu == hu)
    if not u.convertible:
        assert u.used is None
