"""Purchase planner: package sizes, needs, and the fewest packs that cover a need (no DB)."""
import pytest

from mealprep import planner
from mealprep.models import ListItem, Product, Recipe, Ingredient
from mealprep.shopping import build_list


def prod(size, code="X", sold_by=None, name="Thing"):
    return Product(code=code, name=name, package_size=size, price=1.0, stock="OK", sold_by=sold_by)


def li(name, qty, unit=None, prep=None):
    return ListItem(key=f"{name}|x", name=name, qty=qty, unit=unit, prep=prep)


# --- package sizes ----------------------------------------------------------------------------------------------

@pytest.mark.parametrize("text, family, amount, units", [
    ("2 L", "vol", 2000, 1), ("1 l", "vol", 1000, 1), ("946 mL", "vol", 946, 1), ("1.89 l", "vol", 1890, 1),
    ("500 g", "mass", 500, 1), ("1 kg", "mass", 1000, 1), ("454 g", "mass", 454, 1), ("2.5 kg", "mass", 2500, 1),
    ("1 lb", "mass", 453.6, 1), ("6 x 355 mL", "vol", 355, 6), ("12x355.0 ml", "vol", 355, 12),
    ("4x113.5 g", "mass", 113.5, 4), ("6 × 355 mL", "vol", 355, 6),
    ("12 count", "count", 12, 1), ("12 ea", "count", 12, 1), ("1 ea", "count", 1, 1), ("each", "count", 1, 1),
    ("ea", "count", 1, 1), ("3 pack", "count", 3, 1), ("12 ct", "count", 12, 1), ("dozen", "count", 12, 1),
    ("1 dozen", "count", 12, 1), ("2 dozen", "count", 24, 1),
])
def test_parse_pack_sizes(text, family, amount, units):
    p = planner.parse_pack(text)
    assert (p.family, p.units, p.by_weight) == (family, units, False)
    assert p.amount == pytest.approx(amount, rel=0.01)


@pytest.mark.parametrize("text", ["per kg", "/100 g", "$4.40/kg", "priced by weight", "avg. 1.2 kg"])
def test_parse_pack_priced_by_weight(text):
    assert planner.parse_pack(text).by_weight


@pytest.mark.parametrize("text", [None, "", "  ", "family size", "assorted", "1/2 bushel"])
def test_parse_pack_unknown_never_raises(text):
    p = planner.parse_pack(text)
    assert p.family is None and not p.by_weight


def test_pricing_type_from_pc_express():
    assert planner.parse_pack("", sold_by="SOLD_BY_WEIGHT").by_weight
    each = planner.parse_pack("", sold_by="SOLD_BY_EACH_PRICED_BY_WEIGHT")     # a red onion: sold each
    assert (each.family, each.amount, each.priced_by_weight) == ("count", 1, True)


# --- packs_min ----------------------------------------------------------------------------------------------------

def test_milk_five_sixths_cup():
    it = li("whole milk", 0.83, "cup")
    for size in ("1 L", "2 L", "4 L"):
        p = planner.plan(it, prod(size))
        assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)
    assert planner.plan(it, prod("1 L")).why(1) == "Need ⅚ cup → 1 × 1 L"


def test_milk_more_than_one_pack():
    p = planner.plan(li("whole milk", 6, "cup"), prod("1 L"))
    assert p.packs_min == 2 and p.why(2) == "Need 6 cups → 2 × 1 L"


def test_multi_recipe_needs_are_summed_by_the_list():
    def recipe(title, raw, qty):
        return Recipe(title=title, source="nyt", servings=4, steps=[],
                      ingredients=[Ingredient(raw=raw, name="whole milk", qty=qty, unit="cup")])
    [it] = build_list([(recipe("Pancakes", "3 cups whole milk", 3), 1), (recipe("Chowder", "2 cups whole milk", 2), 1),
                       (recipe("Pudding", "1/2 cup whole milk", 0.5), 1)])
    assert planner.plan(it, prod("1 L")).packs_min == 2         # 5½ cups ≈ 1.3 L
    assert planner.plan(it, prod("2 L")).packs_min == 1


def test_flour_cups_against_a_bag_uses_density():
    it = li("all-purpose flour", 5, "cup")
    p = planner.plan(it, prod("2.5 kg"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)
    assert p.why(1).startswith("Need 5 cups (≈") and p.why(1).endswith("g) → 1 × 2.5 kg")
    assert planner.plan(li("all-purpose flour", 10, "cup"), prod("1 kg")).packs_min == 2


def test_brown_sugar_is_not_white_sugar_and_peanut_butter_is_not_butter():
    assert planner.density(li("brown sugar", 1, "cup")) > planner.density(li("granulated sugar", 1, "cup"))
    assert planner.density(li("peanut butter", 1, "cup")) != planner.density(li("unsalted butter", 1, "cup"))


def test_eggs_by_count():
    for size in ("12 ea", "12 count", "dozen"):
        assert planner.plan(li("egg", 3), prod(size)).packs_min == 1
    p = planner.plan(li("large egg", 14), prod("12 ea"))
    assert (p.packs_min, p.why(2)) == (2, "Need 14 → 2 × 12 ea")


def test_butter_tablespoons_against_a_pound():
    p = planner.plan(li("unsalted butter", 6, "tbsp"), prod("454 g"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)
    assert planner.plan(li("unsalted butter", 3, "cup"), prod("454 g")).packs_min == 2   # ~690 g


def test_butter_sticks():
    assert planner.plan(li("unsalted butter", 5, "stick"), prod("454 g")).packs_min == 2


def test_multipack_cans():
    assert planner.plan(li("club soda", 4, "cup"), prod("6 x 355 mL")).packs_min == 1       # 946 ml ≤ 2130 ml
    assert planner.plan(li("club soda", 10, "cup"), prod("6 x 355 mL")).packs_min == 2
    assert planner.plan(li("black beans", 3, "can"), prod("540 ml")).packs_min == 3        # one can per pack
    assert planner.plan(li("tuna", 6, "can"), prod("4x113.5 g")).packs_min == 2


def test_unparseable_size_floor_one_and_needs_check():
    for size in (None, "", "family size"):
        p = planner.plan(li("whole milk", 2, "cup"), prod(size))
        assert (p.packs_min, p.enforce, p.needs_check) == (1, False, True)
        assert "check" in p.why(1)


def test_mixed_families_without_a_conversion_need_check():
    p = planner.plan(li("chicken breast", 2), prod("600 g"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, False, True)


def test_priced_by_weight_produce_keeps_ai_quantity():
    p = planner.plan(li("red onion", 500, "g"), prod("", sold_by="SOLD_BY_EACH_PRICED_BY_WEIGHT"))
    assert (p.enforce, p.needs_check) == (False, True)
    assert planner.floor_quantity(li("red onion", 500, "g"), prod("", sold_by="SOLD_BY_EACH_PRICED_BY_WEIGHT"), 3) == 3
    p = planner.plan(li("beef brisket", 2, "lb"), prod("per kg"))
    assert (p.enforce, p.needs_check) == (False, True)


def test_produce_sold_each_counts_by_the_piece():
    p = planner.plan(li("red onion", 2), prod("", sold_by="SOLD_BY_EACH_PRICED_BY_WEIGHT"))
    assert (p.packs_min, p.enforce, p.needs_check) == (2, True, False)


def test_each_weights_for_common_produce_against_a_bag():
    p = planner.plan(li("yellow onion", 3), prod("1.36 kg"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)


def test_garlic_cloves():
    assert planner.plan(li("garlic", 4, "clove"), prod("1 ea")).packs_min == 1
    assert planner.plan(li("garlic", 14, "clove"), prod("1 ea")).packs_min == 2
    assert planner.plan(li("garlic", 14, "clove"), prod("3 ea")).packs_min == 1
    assert planner.plan(li("garlic", 6, "clove"), prod("227 g")).packs_min == 1


def test_bunches_and_heads_count_against_each():
    assert planner.plan(li("cilantro", 2, "bunch"), prod("1 ea")).packs_min == 2
    p = planner.plan(li("cilantro", 2, "bunch"), prod("28 g"))
    assert p.needs_check and not p.enforce


def test_pinch_is_negligible():
    p = planner.plan(li("kosher salt", 1, "pinch"), prod("1 kg"))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)


def test_no_amount_is_one_pack():
    p = planner.plan(li("olive oil", None), prod("1 L"))
    assert (p.packs_min, p.needs_check) == (1, False) and p.why(1) == "No amount given → 1 × 1 L"


def test_absurd_pack_count_is_flagged_not_enforced():
    p = planner.plan(li("chicken thigh", 3, "kg"), prod("100 g"))
    assert p.needs_check and not p.enforce and p.packs_min == 30


def test_floor_quantity_raises_too_few_and_keeps_too_many():
    it, milk = li("whole milk", 6, "cup"), prod("1 L")
    assert planner.floor_quantity(it, milk, 1) == 2
    assert planner.floor_quantity(it, milk, 3) == 3
    assert planner.floor_quantity(it, milk, None) == 2
    assert planner.floor_quantity(it, None, 2) == 2


def test_why_marks_a_short_quantity():
    assert planner.plan(li("whole milk", 6, "cup"), prod("1 L")).why(1) == "Need 6 cups → 1 × 1 L (short)"


def test_plan_accepts_a_product_dict():
    assert planner.plan(li("whole milk", 6, "cup"), {"code": "X", "package_size": "1 l"}).packs_min == 2
    assert planner.plan(li("whole milk", 6, "cup"), None) is None


def test_hint_for_prompt():
    cands = [prod("1 L", code="A"), prod("4 L", code="B"), prod("", code="C")]
    assert planner.min_packs(li("whole milk", 6, "cup"), cands) == {"A": 2, "B": 1, "C": 1}


def test_odd_data_never_raises():
    planner.plan(li("x", float("nan"), "cup"), prod("1 L"))      # no exception
    assert planner.floor_quantity(li("x", 1e308, "cup"), prod("1 ml"), 2) >= 2
    assert planner.plan(li("x", 2, "zz"), prod("1 L")).needs_check


@pytest.mark.parametrize("name, unit, qty, size", [
    ("sage", "tbsp", 2, "12 g"), ("bread crumbs", "cup", 0.5, "300 g"), ("orzo", "cup", 1.5, "340 g"),
    ("instant yeast", "tsp", 2.25, "24 g"), ("bittersweet chocolate", "cup", 1, "170 g"),
])
def test_common_volume_to_weight_conversions(name, unit, qty, size):
    p = planner.plan(li(name, qty, unit), prod(size))
    assert (p.packs_min, p.enforce, p.needs_check) == (1, True, False)


def test_density_name_matching_is_word_based():
    assert planner.density(li("sugar snap peas", 1, "cup")) is None
    assert planner.density(li("almond milk", 1, "cup")) == planner.density(li("whole milk", 1, "cup"))
    assert planner.density(li("buttermilk", 1, "cup")) != planner.density(li("butter", 1, "cup"))
    assert planner.density(li("ground beef", 1, "cup")) is None
