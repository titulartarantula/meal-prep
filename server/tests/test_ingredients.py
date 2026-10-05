"""Deterministic ingredient clean-up: amounts from the raw line, prep words out of the name. Invented lines only."""
import pytest

from mealprep.ingredients import clean_ingredient, item_key, parse_amount, split_prep
from mealprep.models import Ingredient


# --- amounts -------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("raw, qty, unit", [
    ("1/3 cup oat milk", 1 / 3, "cup"),
    ("⅓ cup oat milk", 1 / 3, "cup"),
    ("1 1/2 cups rice", 1.5, "cup"),
    ("1½ cups rice", 1.5, "cup"),
    ("1 ½ cups rice", 1.5, "cup"),
    ("1-2 tablespoons honey", 2, "tbsp"),
    ("1 to 2 tablespoons honey", 2, "tbsp"),
    ("1–2 tsp chili flakes", 2, "tsp"),
    ("scant 1/2 cup sugar", 0.5, "cup"),
    ("0.5 l stock", 0.5, "l"),
    ("250 g mushrooms", 250, "g"),
    ("2 tbsp. soy sauce", 2, "tbsp"),
    ("3 cups of spinach", 3, "cup"),
    ("2 lbs carrots", 2, "lb"),
])
def test_amount_forms_keep_their_unit(raw, qty, unit):
    a = parse_amount(raw)
    assert a.qty == pytest.approx(qty) and a.unit == unit


def test_count_without_unit():
    a = parse_amount("3 shallots")
    assert a.qty == 3 and a.unit is None and a.rest == "shallots"


def test_package_count_with_size():
    a = parse_amount("2 (14 oz) cans black beans, drained")
    assert a.qty == 2 and a.unit == "can" and a.rest == "black beans, drained" and "14 oz" in a.notes
    b = parse_amount("1 15-ounce can chickpeas")
    assert b.qty == 1 and b.unit == "can" and b.rest == "chickpeas" and "15-ounce" in b.notes


def test_plus_and_minus_amounts_are_combined():
    a = parse_amount("1/3 cup plus 1 tablespoon/80 milliliters oat milk")
    assert a.unit == "cup" and a.qty == pytest.approx(1 / 3 + 1 / 16) and a.compound
    assert a.rest == "oat milk"
    b = parse_amount("2 cups minus 2 tablespoons pastry flour")
    assert b.unit == "cup" and b.qty == pytest.approx(2 - 1 / 8) and b.rest == "pastry flour"


def test_metric_alternative_after_slash_is_skipped():
    a = parse_amount("3 tablespoons/20 grams cocoa powder")
    assert a.qty == 3 and a.unit == "tbsp" and a.rest == "cocoa powder"


def test_qualifier_goes_to_notes():
    a = parse_amount("heaping 1 teaspoon cumin")
    assert a.qty == 1 and a.unit == "tsp" and a.notes == ["heaping"]


def test_no_amount():
    a = parse_amount("Salt and pepper")
    assert a.qty is None and a.unit is None and a.rest == "Salt and pepper"
    assert parse_amount("").rest == ""


@pytest.mark.parametrize("raw", ["", "   ", "1/0 cup water", "½½ cup", "(((", "1 cup", "cup", "/", "—", "1 1/2/3 cups x"])
def test_odd_input_never_crashes(raw):
    parse_amount(raw)
    clean_ingredient(Ingredient(raw=raw, name=raw.lower().strip() or "x"))


# --- prep words ------------------------------------------------------------------------------------------------

def sp(s, unit=None):
    name, prep = split_prep(s, unit)
    return name, prep


@pytest.mark.parametrize("word", ["warmed", "melted", "softened", "chopped", "diced", "minced", "sliced", "grated",
                                  "shredded", "drained", "rinsed", "thawed", "peeled", "seeded", "crushed", "toasted",
                                  "beaten", "cooled", "trimmed", "halved", "quartered", "cubed", "zested", "juiced",
                                  "mashed", "packed", "sifted"])
def test_participle_after_comma_and_before_noun(word):
    assert sp(f"parsnip, {word}") == ("parsnip", word)
    assert sp(f"{word} parsnip") == ("parsnip", word)


def test_participle_after_noun_without_comma():
    assert sp("oat milk warmed") == ("oat milk", "warmed")
    assert sp("oat milk warmed to 110 degrees") == ("oat milk", "warmed to 110 degrees")


@pytest.mark.parametrize("phrase", ["finely grated", "roughly chopped", "thinly sliced", "coarsely chopped",
                                    "lightly packed", "firmly packed", "finely chopped"])
def test_adverb_participle_combos(phrase):
    assert sp(f"{phrase} celeriac") == ("celeriac", phrase)
    assert sp(f"celeriac, {phrase}") == ("celeriac", phrase)


def test_freshly_ground_pepper_is_pepper():
    assert sp("freshly ground black pepper") == ("black pepper", "freshly ground")


@pytest.mark.parametrize("name", ["ground beef", "ground cinnamon", "ground turkey"])
def test_ground_alone_is_a_product(name):
    assert sp(name) == (name, None)


@pytest.mark.parametrize("name", ["frozen peas", "canned corn", "dried apricot", "smoked paprika", "unsalted butter",
                                  "salted butter", "whole milk", "skim milk", "2% milk"])
def test_product_words_are_kept(name):
    assert sp(name) == (name, None)


def test_keep_words_stop_leading_strip():
    assert sp("chopped frozen spinach") == ("frozen spinach", "chopped")


def test_fresh_moves_to_prep():
    assert sp("fresh dill") == ("dill", "fresh")
    assert sp("chopped fresh dill") == ("dill", "chopped, fresh")


def test_temperature_words():
    assert sp("cold unsalted butter, cubed") == ("unsalted butter", "cold, cubed")
    assert sp("egg, at room temperature") == ("egg", "at room temperature")
    assert sp("room temperature cream cheese") == ("cream cheese", "room temperature")


@pytest.mark.parametrize("tail", ["divided", "optional", "for serving", "for garnish", "for dusting",
                                  "plus more for the pan", "to taste", "or substitute maple syrup",
                                  "at room temperature"])
def test_trailing_notes_without_comma(tail):
    assert sp(f"golden syrup {tail}") == ("golden syrup", tail)


def test_parenthetical_note():
    assert sp("cooked farro (about 2 cups)") == ("farro", "cooked, about 2 cups")


def test_multi_part_tail():
    assert sp("leek, halved and thinly sliced, white parts only") == ("leek", "halved and thinly sliced, white parts only")
    assert sp("peeled and diced carrot") == ("carrot", "peeled and diced")


def test_cheese_keeps_product_name():
    assert sp("finely grated pecorino") == ("pecorino", "finely grated")
    assert sp("shredded mozzarella") == ("mozzarella", "shredded")


def test_product_phrases_with_participles_are_kept():
    assert sp("toasted sesame oil") == ("toasted sesame oil", None)
    assert sp("sliced almonds") == ("sliced almonds", None)
    assert sp("crushed red pepper") == ("crushed red pepper", None)
    assert sp("canned diced tomatoes") == ("canned diced tomatoes", None)
    assert sp("crushed tomatoes", "can") == ("crushed tomatoes", None)
    assert sp("diced tomatoes") == ("tomatoes", "diced")      # fresh, unless canned


def test_size_words():
    assert sp("large egg") == ("egg", "large")


def test_name_never_empty():
    assert sp("chopped") == ("chopped", None)
    assert sp("fresh, chopped") == ("fresh", "chopped")
    assert sp("(optional)")[0]
    assert sp("") == ("", None)


# --- whole ingredient ------------------------------------------------------------------------------------------

def test_missing_unit_restored_from_raw():
    ing = Ingredient(raw="⅓ cup plus 1 tablespoon/80 milliliters oat milk", name="oat milk", qty=1 / 3)
    out = clean_ingredient(ing)
    assert out.unit == "cup" and out.qty == pytest.approx(1 / 3 + 1 / 16) and out.name == "oat milk"


def test_compound_amount_overrides_same_dimension():
    ing = Ingredient(raw="2 cups minus 2 tablespoons pastry flour", name="pastry flour", qty=2, unit="cup",
                     prep="minus 2 tablespoons")
    out = clean_ingredient(ing)
    assert out.qty == pytest.approx(1.875) and out.unit == "cup"


def test_model_amount_kept_when_raw_has_a_package():
    ing = Ingredient(raw="1 (15-ounce) can chickpeas", name="chickpea", qty=15, unit="oz")
    out = clean_ingredient(ing)
    assert out.qty == 15 and out.unit == "oz"


def test_prep_moves_from_name_and_merges_with_existing():
    ing = Ingredient(raw="1/2 cup oat milk, warmed", name="oat milk warmed", qty=0.5, unit="cup", prep="warmed")
    out = clean_ingredient(ing)
    assert out.name == "oat milk" and out.prep == "warmed"


def test_fallback_name_is_cleaned():
    ing = Ingredient(raw="2 cups chopped kale, stems removed", name="2 cups chopped kale, stems removed")
    out = clean_ingredient(ing)
    assert (out.name, out.qty, out.unit, out.prep) == ("kale", 2, "cup", "chopped, stems removed")


def test_product_word_recovered_from_raw():
    ing = Ingredient(raw="3 tablespoons unsalted butter", name="butter", qty=3, unit="tbsp")
    assert clean_ingredient(ing).name == "unsalted butter"
    ing = Ingredient(raw="2 tablespoons bread or all-purpose flour", name="flour", qty=2, unit="tbsp")
    assert clean_ingredient(ing).name == "flour"


def test_clean_is_idempotent():
    ing = Ingredient(raw="1 cup finely grated pecorino (about 2 oz)", name="finely grated pecorino", qty=1, unit="cup")
    once = clean_ingredient(ing)
    assert clean_ingredient(once) == once


def test_item_key_singularizes():
    assert item_key("shallots", "each") == item_key("shallot", "each") == "shallot|each"
    assert item_key("tomatoes", "vol") == "tomato|vol"
    assert item_key("berries", "vol") == "berry|vol"
    assert item_key("molasses", "vol") == "molasses|vol"
    assert item_key("couscous", "vol") == "couscous|vol"
