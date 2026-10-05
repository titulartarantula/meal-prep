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
    a = R("A", 4, Ingredient(raw="", name="milk", qty=1, unit="cup"))
    b = R("B", 4, Ingredient(raw="", name="milk", qty=4, unit="tbsp"))
    [item] = build_list([(a, 1), (b, 1)])
    assert item.unit == "tbsp" and item.qty == 20


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
