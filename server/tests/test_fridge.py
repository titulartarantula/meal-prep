"""Fridge list (0.14.0): fridge_items, the one-time name mapping, CRUD."""
from datetime import date, timedelta

import pytest

from mealprep import db, fridge, staples
from mealprep.ai.base import AIError
from mealprep.models import Ingredient, Recipe

TODAY = date(2026, 10, 23)


class MapAI:
    """Answers the mapping prompt with `match` (or raises); records every call."""
    def __init__(self, match=None, fail=None):
        self.match, self.fail, self.prompts = match, fail, []

    def complete_json(self, prompt, images=None):
        self.prompts.append(prompt)
        if self.fail:
            raise self.fail
        return self.match if isinstance(self.match, dict) else {"match": self.match}


VOCAB = ["egg", "green onion", "scallion", "whole milk", "cilantro", "spinach"]


def R(title, *lines, servings=4):
    ings = [Ingredient(raw=l, name=n, qty=q, unit=u) for l, n, q, u in lines]
    return Recipe(title=title, source="photo", servings=servings, ingredients=ings, steps=[])


# --- map_name (pure) -------------------------------------------------------------------------------------------

def test_a_vocabulary_name_skips_the_ai():
    ai = MapAI(match="cilantro")
    assert fridge.map_name(ai, "Large Eggs", VOCAB) == ("egg", "list")
    assert fridge.map_name(ai, "Green onions", VOCAB) == ("green onion", "list")
    assert fridge.map_name(ai, "  SPINACH ", VOCAB) == ("spinach", "list")
    assert ai.prompts == []


def test_from_list_skips_the_ai_even_off_the_vocabulary():
    ai = MapAI(match="cilantro")
    assert fridge.map_name(ai, "Kale", VOCAB, from_list=True) == ("kale", "rule")
    assert ai.prompts == []


def test_one_ai_call_maps_to_a_vocabulary_name():
    ai = MapAI(match="scallion")
    assert fridge.map_name(ai, "spring onions", VOCAB) == ("scallion", "ai")
    assert len(ai.prompts) == 1 and "spring onions" in ai.prompts[0] and "scallion" in ai.prompts[0]


def test_an_ai_answer_outside_the_vocabulary_falls_back_to_the_rule():
    assert fridge.map_name(MapAI(match="arugula"), "Baby kale", VOCAB) == ("baby kale", "rule")
    assert fridge.map_name(MapAI(match=None), "Baby kale", VOCAB) == ("baby kale", "rule")
    assert fridge.map_name(MapAI(match={"oops": 1}), "Baby kale", VOCAB) == ("baby kale", "rule")
    assert fridge.map_name(MapAI(match=["x"]), "Baby kale", VOCAB) == ("baby kale", "rule")


def test_ai_errors_and_timeouts_fall_back_to_the_rule():
    assert fridge.map_name(MapAI(fail=AIError("Anthropic timed out")), "Tomatoes", VOCAB) == ("tomato", "rule")
    assert fridge.map_name(MapAI(fail=TimeoutError()), "Tomatoes", VOCAB) == ("tomato", "rule")
    assert fridge.map_name(None, "Tomatoes", VOCAB) == ("tomato", "rule")


def test_map_names_is_one_call_for_many_names():
    class Many(MapAI):
        def complete_json(self, prompt, images=None):
            self.prompts.append(prompt)
            return {"matches": [{"name": "spring onions", "match": "scallion"}, {"name": "kale", "match": "beet"}]}
    ai = Many()
    out = fridge.map_names(ai, ["Eggs", "spring onions", "kale"], VOCAB)
    assert out == [("egg", "list"), ("scallion", "ai"), ("kale", "rule")]
    assert len(ai.prompts) == 1 and "Eggs" not in ai.prompts[0]


# --- vocabulary --------------------------------------------------------------------------------------------------

def test_vocabulary_from_library_lines_and_staples_most_used_first(conn):
    db.save_recipe(conn, R("A", ("2 eggs", "eggs", 2, None), ("1 cup milk", "whole milk", 1, "cup"),
                           ("cilantro", "cilantro", None, None)))
    db.save_recipe(conn, R("B", ("3 large eggs", "large eggs", 3, None), ("cilantro, chopped", "cilantro, chopped", None, None)))
    db.save_recipe(conn, R("C", ("4 eggs", "egg", 4, None)))
    conn.execute("INSERT INTO staples(name, qty) VALUES('2% milk', 1)")
    v = fridge.vocabulary(conn)
    assert v[:2] == ["egg", "cilantro"]
    assert set(v) == {"egg", "cilantro", "whole milk", "2% milk"}


def test_an_empty_vocabulary_needs_no_ai_call(conn):
    ai = MapAI(match="kale")
    assert add(conn, "Kale", ai=ai).key_source == "rule" and ai.prompts == []


def test_vocabulary_skips_expanded_reference_lines(conn):
    r = R("Crepes", ("Batter for 24 crepes, page 191", "batter for 24 crepe", None, None))
    r.ingredients[0].expanded = True
    db.save_recipe(conn, r)
    assert fridge.vocabulary(conn) == []


# --- CRUD (DB) ---------------------------------------------------------------------------------------------------

def add(conn, name, qty=None, unit=None, ai=None, today=TODAY, **kw):
    return fridge.create_item(conn, ai or MapAI(), name, qty, unit, today=today, **kw)


def test_create_list_order_soon_first_then_name(conn):
    add(conn, "Spinach", 1, "bag")
    add(conn, "eggs", 6)
    add(conn, "Cilantro", use_soon=True)
    add(conn, "milk", 2, "l", use_by=TODAY + timedelta(days=3))
    add(conn, "yogurt", 1, "container", use_by=TODAY + timedelta(days=4))
    names = [i.name for i in fridge.list_items(conn, TODAY)]
    assert names == ["Cilantro", "milk", "eggs", "Spinach", "yogurt"]
    milk = next(i for i in fridge.list_items(conn, TODAY) if i.name == "milk")
    assert milk.soon and not milk.use_soon and milk.unit == "l" and milk.qty == 2


def test_create_maps_once_and_stores_the_key(conn):
    ai = MapAI(match="scallion")
    conn.execute("INSERT INTO staples(name, qty) VALUES('scallion', 1)")
    it = add(conn, "Spring onions", 1, "bunch", ai=ai)
    assert (it.key, it.key_source) == ("scallion", "ai") and len(ai.prompts) == 1
    assert it.amount_as_of == TODAY.isoformat() and it.days_since_checked == 0 and not it.qty_estimated


def test_duplicate_key_and_unit_is_refused_with_the_existing_item(conn):
    a = add(conn, "Eggs", 6)
    with pytest.raises(fridge.FridgeExists) as e:
        add(conn, "large eggs", 12)
    assert e.value.item.id == a.id and str(e.value) == "Already in the fridge: 6 Eggs"
    add(conn, "eggs", 1, "carton")                 # another unit is another row


def test_patch_name_remaps_with_one_call_but_qty_does_not(conn):
    ai = MapAI(match=None)
    conn.execute("INSERT INTO staples(name, qty) VALUES('2% milk', 1)")   # an empty vocabulary needs no call
    it = add(conn, "Kale", 1, "bunch", ai=ai)
    assert len(ai.prompts) == 1
    it = fridge.update_item(conn, ai, it.id, today=TODAY, qty=2)
    assert len(ai.prompts) == 1 and it.qty == 2 and it.key == "kale"
    ai.match = "spinach"
    conn.execute("INSERT INTO staples(name, qty) VALUES('spinach', 1)")
    it = fridge.update_item(conn, ai, it.id, today=TODAY, name="Baby greens")
    assert len(ai.prompts) == 2 and (it.name, it.key, it.key_source) == ("Baby greens", "spinach", "ai")


def test_setting_an_amount_dates_it_and_clears_the_estimate(conn):
    it = add(conn, "Eggs", 6, today=TODAY - timedelta(days=9))
    conn.execute("UPDATE fridge_items SET qty_estimated = true WHERE id=%s", (it.id,))
    it = fridge.get_item(conn, it.id, TODAY)
    assert it.days_since_checked == 9 and it.qty_estimated
    it = fridge.update_item(conn, MapAI(), it.id, today=TODAY, qty=4)
    assert (it.qty, it.amount_as_of, it.qty_estimated) == (4, TODAY.isoformat(), False)


def test_confirm_moves_only_the_as_of_date(conn):
    it = add(conn, "Milk", 2, "l", today=TODAY - timedelta(days=12))
    conn.execute("UPDATE fridge_items SET qty_estimated = true WHERE id=%s", (it.id,))
    it = fridge.update_item(conn, MapAI(), it.id, today=TODAY, confirm=True)
    assert (it.qty, it.unit, it.qty_estimated, it.amount_as_of, it.days_since_checked) == \
        (2, "l", True, TODAY.isoformat(), 0)


def test_use_soon_and_use_by(conn):
    it = add(conn, "Yogurt", 1, "container", use_by=TODAY + timedelta(days=5))
    assert not fridge.get_item(conn, it.id, TODAY).soon
    assert fridge.get_item(conn, it.id, TODAY + timedelta(days=2)).soon
    it = fridge.update_item(conn, MapAI(), it.id, today=TODAY, use_by=None, use_soon=True)
    assert it.soon and it.use_by is None


def test_qty_zero_keeps_the_row(conn):
    it = add(conn, "Eggs", 6)
    it = fridge.update_item(conn, MapAI(), it.id, today=TODAY, qty=0)
    assert it.qty == 0 and [i.id for i in fridge.list_items(conn, TODAY)] == [it.id]


def test_rename_onto_another_item_is_refused(conn):
    add(conn, "Eggs", 6)
    b = add(conn, "Kale", 6)
    with pytest.raises(fridge.FridgeExists):
        fridge.update_item(conn, MapAI(), b.id, today=TODAY, name="egg")
    assert fridge.get_item(conn, b.id, TODAY).name == "Kale"


def test_delete_is_idempotent_and_unknown_ids_are_not_found(conn):
    it = add(conn, "Eggs", 6)
    fridge.delete_item(conn, it.id)
    fridge.delete_item(conn, it.id)
    assert fridge.list_items(conn, TODAY) == []
    with pytest.raises(fridge.FridgeNotFound):
        fridge.update_item(conn, MapAI(), it.id, today=TODAY, qty=1)


def test_migration_rerun_is_a_no_op(conn):
    add(conn, "Eggs", 6)
    conn.execute(db.SCHEMA)
    conn.execute(db.SCHEMA)
    assert [i.name for i in fridge.list_items(conn, TODAY)] == ["Eggs"]


def test_amount_text():
    assert fridge.amount_text(6, None, "eggs") == "6 eggs"
    assert fridge.amount_text(None, None, "cilantro") == "some cilantro"
    assert fridge.amount_text(2, "cup", "milk") == "2 cups milk"
    assert fridge.amount_text(1, "bunch", "cilantro") == "1 bunch cilantro"
    assert fridge.amount_text(2, "bunch", "cilantro") == "2 bunches cilantro"
    assert fridge.amount_text(1.5, "l", "milk") == "1.5 l milk"
    assert fridge.amount_text(0.5, "cup", "cream") == "½ cup cream"


def test_name_key_rule_matches_the_shopping_list():
    assert staples.name_key("Large Eggs") == "egg"
    assert fridge.rule_key("Green onions") == "green onion"
