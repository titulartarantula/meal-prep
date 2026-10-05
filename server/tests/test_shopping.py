from mealprep.models import Recipe, Ingredient
from mealprep.shopping import build_list


def R(title, servings, *ings):
    return Recipe(title=title, source="nyt", servings=servings, ingredients=list(ings), steps=[])


def test_scales_to_four_and_merges_same_unit():
    a = R("Chili", 8, Ingredient(raw="", name="onion", qty=2))
    b = R("Soup", 4, Ingredient(raw="", name="onion", qty=1))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.qty == 2 and item.recipes == ["Chili", "Soup"]   # 2*4/8 + 1


def test_multiplier_for_leftovers():
    a = R("Dal", 4, Ingredient(raw="", name="red lentil", qty=1, unit="cup"))
    [item] = build_list([(a, 2)])
    assert item.qty == 2


def test_missing_servings_uses_multiplier_only():
    a = R("X", None, Ingredient(raw="", name="rice", qty=1, unit="cup"))
    [item] = build_list([(a, 1.5)])
    assert item.qty == 1.5


def test_convertible_units_merge_in_base_unit():
    a = R("A", 4, Ingredient(raw="", name="butter", qty=2, unit="tbsp"))
    b = R("B", 4, Ingredient(raw="", name="butter", qty=1, unit="tsp"))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.unit == "tsp" and item.qty == 7


def test_merged_qty_shown_in_smallest_unit_seen():
    a = R("A", 4, Ingredient(raw="", name="milk", qty=1 / 4, unit="cup"))
    b = R("B", 4, Ingredient(raw="", name="milk", qty=4, unit="tbsp"))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.unit == "tbsp" and item.qty == 8


def test_merged_qty_steps_up_to_a_larger_unit_seen_when_large():
    a = R("A", 4, Ingredient(raw="", name="milk", qty=1, unit="cup"))
    b = R("B", 4, Ingredient(raw="", name="milk", qty=4, unit="tbsp"))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.unit == "cup" and item.qty == 1.25


def test_incompatible_units_stay_separate():
    a = R("A", 4, Ingredient(raw="", name="onion", qty=1, unit="cup"))
    b = R("B", 4, Ingredient(raw="", name="onion", qty=2))
    assert len(build_list([(a, 1), (b, 1)])) == 2


def test_volume_and_mass_stay_separate():
    a = R("A", 4, Ingredient(raw="", name="flour", qty=1, unit="cup"))
    b = R("B", 4, Ingredient(raw="", name="flour", qty=100, unit="g"))
    assert {i.unit for i in build_list([(a, 1), (b, 1)])} == {"cup", "g"}


def test_pantry_items_default_unchecked():
    a = R("A", 4, Ingredient(raw="", name="salt", likely_on_hand=True))
    [item] = build_list([(a, 1)])
    assert item.needed is False and item.qty is None


# --- clean names, fractions, merging (invented lines) ---

def test_milk_regression_fraction_keeps_unit_and_merges_with_prep():
    a = R("Rolls", None, Ingredient(raw="1/3 cup whole milk", name="whole milk", qty=1 / 3),          # model dropped the unit
          Ingredient(raw="1/2 cup whole milk, warmed", name="whole milk warmed", qty=0.5, unit="cup"))
    [item] = build_list([(a, 1)])
    assert (item.name, item.unit, item.qty, item.prep) == ("whole milk", "cup", 0.83, "warmed")
    assert item.key == "whole milk|vol"


def test_unicode_fraction_with_plus_amount():
    a = R("Buns", None, Ingredient(raw="⅓ cup plus 1 tablespoon/80 milliliters oat milk", name="oat milk", qty=1 / 3),
          Ingredient(raw="½ cup/120 milliliters oat milk, warmed to 110 degrees", name="oat milk", qty=0.5, unit="cup",
                     prep="warmed to 110 degrees"))
    [item] = build_list([(a, 1)])
    assert item.unit == "cup" and item.qty == 0.9 and item.prep == "warmed to 110 degrees"


def test_prep_words_never_reach_the_name():
    a = R("A", 4, Ingredient(raw="1/4 cup finely chopped celeriac", name="finely chopped celeriac", qty=1 / 4, unit="cup"),
          Ingredient(raw="2 tbsp celeriac, roughly chopped", name="celeriac, roughly chopped", qty=2, unit="tbsp"))
    [item] = build_list([(a, 1)])
    assert item.name == "celeriac" and item.prep == "finely chopped; roughly chopped" and (item.unit, item.qty) == ("tbsp", 6)


def test_cleaned_names_merge_across_recipes():
    a = R("A", 4, Ingredient(raw="2 shallots, minced", name="shallots, minced", qty=2))
    b = R("B", 4, Ingredient(raw="1 shallot", name="shallot", qty=1))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.qty == 3 and item.name == "shallots" and item.recipes == ["A", "B"]


def test_count_unit_and_volume_stay_separate():
    a = R("A", 4, Ingredient(raw="2 cloves garlic", name="garlic", qty=2, unit="clove"))
    b = R("B", 4, Ingredient(raw="1 tbsp garlic, minced", name="garlic", qty=1, unit="tbsp", prep="minced"))
    assert {(i.unit, i.qty) for i in build_list([(a, 1), (b, 1)])} == {("clove", 2), ("tbsp", 1)}


def test_mass_units_convert_to_common_unit():
    a = R("A", 4, Ingredient(raw="1 lb carrots", name="carrot", qty=1, unit="lb"))
    b = R("B", 4, Ingredient(raw="8 oz carrots, diced", name="carrot", qty=8, unit="oz", prep="diced"))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.unit == "lb" and item.qty == 1.5 and item.prep == "diced"     # 24 oz


def test_unmeasured_line_joins_measured_one():
    a = R("A", 4, Ingredient(raw="1 tsp flaky salt", name="flaky salt", qty=1, unit="tsp", likely_on_hand=True))
    b = R("B", 4, Ingredient(raw="flaky salt, to taste", name="flaky salt", prep="to taste", likely_on_hand=True))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.qty == 1 and item.unit == "tsp" and item.recipes == ["A", "B"] and item.prep == "to taste"


def test_fallback_raw_name_is_parsed():
    a = R("A", 4, Ingredient(raw="2 cups chopped kale", name="2 cups chopped kale"))
    [item] = build_list([(a, 1)])
    assert (item.name, item.qty, item.unit, item.prep) == ("kale", 2, "cup", "chopped")
