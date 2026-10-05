import pytest
from mealprep.ai.base import AIError
from mealprep.importers.structure import parse_qty, structure_ingredients
from mealprep.importers.photo import import_photo


class Fake:
    def __init__(self, out): self.out, self.calls = out, []
    def complete_json(self, prompt, images=None):
        self.calls.append((prompt, images)); return self.out


def test_parse_qty_variants():
    assert parse_qty("1 1/2") == 1.5
    assert parse_qty("½") == 0.5
    assert parse_qty("1½") == 1.5
    assert parse_qty("2 to 3") == 3
    assert parse_qty("2-3") == 3
    assert parse_qty("a pinch") is None
    assert parse_qty(None) is None


def test_parse_qty_more_shapes():
    assert parse_qty(2) == 2.0
    assert parse_qty("3/4") == 0.75
    assert parse_qty("1 ½") == 1.5
    assert parse_qty("2–3") == 3
    assert parse_qty("1/2 to 3/4") == 0.75
    assert parse_qty("2") == 2.0


def test_structure_uses_provider_and_keeps_raw():
    p = Fake([{"name": "onion", "qty": "1 1/2", "unit": "each", "prep": "diced", "likely_on_hand": False}])
    out = structure_ingredients(p, ["1 1/2 onions, diced"])
    assert out[0].raw == "1 1/2 onions, diced" and out[0].qty == 1.5 and out[0].unit is None
    assert out[0].name == "onion" and out[0].prep == "diced"


def test_structure_normalizes_units():
    p = Fake([{"name": "flour", "qty": "2", "unit": "Cups"}, {"name": "salt", "qty": "1", "unit": "teaspoon", "likely_on_hand": True}])
    a, b = structure_ingredients(p, ["2 cups flour", "1 tsp salt"])
    assert a.unit == "cup" and b.unit == "tsp" and b.likely_on_hand is True


def test_structure_length_mismatch_falls_back_to_raw_names():
    p = Fake([])
    out = structure_ingredients(p, ["salt"])
    assert out[0].name == "salt" and out[0].qty is None


def test_structure_tolerates_non_dict_items():
    p = Fake(["oops"])
    out = structure_ingredients(p, ["Salt"])
    assert out[0].name == "salt"


def test_import_photo():
    p = Fake({"title": "Dal", "servings": 4, "ingredients": ["1 cup red lentils"], "steps": ["Rinse."]})
    r = import_photo(p, ["/tmp/p1.jpg", "/tmp/p2.jpg"])
    assert r.title == "Dal" and r.source == "photo" and p.calls[0][1] == ["/tmp/p1.jpg", "/tmp/p2.jpg"]


def test_import_photo_no_recipe_raises():
    with pytest.raises(AIError):
        import_photo(Fake({"title": None}), ["/tmp/p1.jpg"])


def test_import_photo_title_hint_goes_into_prompt():
    p = Fake({"title": "Dal", "servings": 4, "ingredients": [], "steps": []})
    import_photo(p, ["/tmp/p1.jpg"], title_hint="Red Lentil Dal")
    assert "Red Lentil Dal" in p.calls[0][0]


def test_import_photo_prompt_handles_mid_page_starts():
    p = Fake({"title": "Dal", "servings": 4, "ingredients": [], "steps": []})
    import_photo(p, ["/tmp/p1.jpg", "/tmp/p2.jpg"])
    assert "partway" in p.calls[0][0] and "page numbers" in p.calls[0][0]


def test_cookbook_tb_abbreviation_is_tablespoon():
    p = Fake([{"name": "butter", "qty": "4", "unit": "Tb"}])
    assert structure_ingredients(p, ["4 Tb butter"])[0].unit == "tbsp"


def test_page_cross_reference_is_flagged():
    p = Fake([{"name": "crêpe batter", "qty": None, "unit": None},
              {"name": "spinach", "qty": "1 1/2", "unit": "cups"},
              {"name": "salt", "qty": "1/4", "unit": "tsp"}])
    a, b, c = structure_ingredients(p, ["Batter for 24 crêpes 6½ inches in diameter, page 191",
                                        "1½ cups blanched chopped spinach, page 468", "¼ tsp salt"])
    assert a.ref_page == 191 and b.ref_page == 468 and c.ref_page is None


def test_photo_prompt_excludes_equipment_and_reads_for_n_people():
    from mealprep.importers.photo import PROMPT
    assert "equipment" in PROMPT.lower() and "For 4 to 6 people" in PROMPT


def test_photo_people_range_takes_lower_bound():
    p = Fake({"title": "Gâteau", "servings": "4 to 6", "ingredients": [], "steps": []})
    assert import_photo(p, ["/tmp/p1.jpg"]).servings == 4
