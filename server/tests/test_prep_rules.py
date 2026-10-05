import pytest
from mealprep.models import MealIn, RawPrepPlan
from mealprep.prep_rules import DAYS, apply_rules, is_hot, shelf_rule


def meal(eid, day, title="Chili", label=None):
    return MealIn(entry_id=eid, recipe_id=eid * 10, title=title, week="2026-10-11", day=day, multiplier=1,
                  date=None if day is None else f"2026-10-{11 + day}", offset=day,
                  label=label or (DAYS[day] if day is not None else "no night yet"), factor=1, ingredients=[], steps=[])


def raw(**sections):
    return RawPrepPlan.model_validate(sections)


@pytest.mark.parametrize("text", ["Boil the rice", "Roast the squash cubes", "Dice onions, then sauté until soft",
                                  "Mix the rub. Bake the cookies", "Toast and grind the cumin", "Cook pasta al dente"])
def test_hot_tasks_detected(text):
    assert is_hot(text)


@pytest.mark.parametrize("text", ["Mix spice rub for roasting", "Measure flour and brown sugar",
                                  "Toasted sesame oil: measure 2 tbsp", "Dice 3 onions: 2 Tue chili, 1 Thu soup",
                                  "Jar the stir-fry sauce"])
def test_cold_tasks_not_hot(text):
    assert not is_hot(text)


@pytest.mark.parametrize("text,section", [("Dice 2 avocados", "knife"), ("Peel and cube 4 potatoes", "knife"),
                                          ("Slice 2 apples for the slaw", "knife"), ("Chop 1 bunch cilantro", "knife"),
                                          ("Chop parsley into the dressing", "sauces"), ("Tear basil leaves", "knife")])
def test_fragile_produce_and_herbs_are_day_of(text, section):
    shelf, thaw, reason = shelf_rule(text, section, [2])
    assert shelf == "day_of" and thaw is None and reason


@pytest.mark.parametrize("text,section", [("Whisk apple cider vinegar dressing", "sauces"),
                                          ("Mince garlic; whisk with apple cider vinegar", "sauces"),
                                          ("Mix spice blend: cumin, dried mint, chili", "sauces"),
                                          ("Pack basil, cilantro and limes into the THU kit", "pack"),
                                          ("Slice dill pickles", "knife"), ("Wash and dry the potatoes", "knife"),
                                          ("Measure 2 cups chicken stock", "sauces"),
                                          ("Whisk fish sauce, lime and sugar", "sauces")])
def test_no_false_positives(text, section):
    assert shelf_rule(text, section, [5]) is None


def test_raw_fish_late_in_week_is_frozen():
    shelf, thaw, _ = shelf_rule("Portion 600 g salmon fillets", "proteins", [4])
    assert shelf == "freeze_then_thaw" and thaw == "Freeze the salmon Sunday; move it to the fridge Wed night."


def test_raw_fish_early_in_week_is_ok():
    assert shelf_rule("Peel and devein 1 lb shrimp", "proteins", [2]) is None


def test_ground_meat_late_is_frozen():
    shelf, thaw, _ = shelf_rule("Portion 1 lb ground beef into a bag", "proteins", [5])
    assert shelf == "freeze_then_thaw" and "Thu night" in thaw


def test_unplaced_fish_is_frozen_with_generic_thaw():
    shelf, thaw, _ = shelf_rule("Portion cod", "proteins", [None])
    assert shelf == "freeze_then_thaw" and "night before you cook it" in thaw


def test_raw_chicken_late_frozen_only_in_proteins():
    assert shelf_rule("Trim 8 chicken thighs", "proteins", [5])[0] == "freeze_then_thaw"
    assert shelf_rule("Trim 8 chicken thighs", "proteins", [2]) is None
    assert shelf_rule("THU – chicken soup kit", "pack", [4]) is None


def test_acid_marinade_on_fish_after_sunday_is_day_of():
    assert shelf_rule("Marinate shrimp in lime juice and garlic", "proteins", [1])[0] == "day_of"
    assert shelf_rule("Marinate shrimp in lime juice and garlic", "proteins", [0]) is None


def test_acid_marinade_on_chicken():
    assert shelf_rule("Marinate chicken thighs in lemon and garlic", "proteins", [2]) is None
    shelf, thaw, _ = shelf_rule("Marinate chicken thighs in lemon and garlic", "proteins", [4])
    assert shelf == "freeze_then_thaw" and thaw == "Freeze it in the marinade Sunday; move it to the fridge Wed night."


def test_non_acid_marinade_on_wings_is_ok():
    assert shelf_rule("Toss 24 wings in green curry paste and coconut milk; bag", "proteins", [2]) is None


def test_raw_plan_coerces_llm_quirks():
    p = RawPrepPlan.model_validate({"knife": [{"text": "x", "serves": "3", "est_minutes": 7.6, "shelf_life": "Day-of"},
                                              {"text": "y", "serves": None, "est_minutes": None,
                                               "shelf_life": "freeze, thaw Wed"}],
                                    "pack": None, "warnings": None})
    a, b = p.knife
    assert (a.serves, a.est_minutes, a.shelf_life) == ([3], 8, "day_of")
    assert (b.serves, b.est_minutes, b.shelf_life) == ([], 0, "freeze_then_thaw")
    assert p.pack == [] and p.warnings == []


def test_hot_task_moves_to_cook_card():
    res = apply_rules(raw(knife=[{"text": "Dice 2 onions", "serves": [1], "est_minutes": 5}],
                          sauces=[{"text": "Cook the rice", "serves": [1], "est_minutes": 20}]), [meal(1, 2)])
    assert [t.text for t in res.tasks if t.section != "pack"] == ["Dice 2 onions"]
    assert res.day_of[1] == ["Cook the rice"]
    assert any("Sunday is prep only" in w for w in res.warnings)


def test_rules_tighten_but_never_loosen():
    res = apply_rules(raw(knife=[{"text": "Dice 2 avocados", "serves": [1], "shelf_life": "ok"},
                                 {"text": "Dice 2 onions", "serves": [1], "shelf_life": "day_of"}],
                          proteins=[{"text": "Portion salmon", "serves": [1], "shelf_life": "freeze_then_thaw"}]),
                      [meal(1, 4)])
    by = {t.text: t for t in res.tasks}
    assert by["Dice 2 avocados"].shelf_life == "day_of" and by["Dice 2 avocados"].flags
    assert by["Dice 2 onions"].shelf_life == "day_of" and not by["Dice 2 onions"].flags
    assert by["Portion salmon"].thaw == "Freeze the salmon Sunday; move it to the fridge Wed night."
    assert res.day_of[1] == ["Dice 2 avocados", "Dice 2 onions"]
    assert res.thaw[1] == ["Freeze the salmon Sunday; move it to the fridge Wed night."]


def test_ids_sections_and_unknown_entries():
    res = apply_rules(raw(knife=[{"text": "Dice 3 onions: 2 Tue chili, 1 Thu soup", "serves": [1, 2, 99], "est_minutes": "6"},
                                 {"text": "  "}, {"text": "Mince garlic", "serves": [2]}],
                          pack=[{"text": "TUE – chili kit", "serves": [1], "contents": ["2 diced onions"]},
                                {"text": "THU – soup kit", "serves": [2], "contents": ["1 diced onion", "garlic"]}]),
                      [meal(1, 2), meal(2, 4, "Soup")])
    assert [t.id for t in res.tasks] == ["knife-1", "knife-2", "pack-1", "pack-2"]
    t = res.tasks[0]
    assert [s.entry_id for s in t.serves] == [1, 2] and t.serves[1].night == "Thu" and t.est_minutes == 6
    assert res.tasks[2].contents == ["2 diced onions"]


def test_missing_kit_is_added_one_per_night():
    res = apply_rules(raw(knife=[{"text": "Shred cabbage", "serves": [3]}],
                          pack=[{"text": "TUE – chili + salad kit", "serves": [1, 2]}]),
                      [meal(1, 2), meal(2, 2, "Salad"), meal(3, 5, "Tacos")])
    packs = [t for t in res.tasks if t.section == "pack"]
    assert [p.text for p in packs] == ["TUE – chili + salad kit", "FRI – Tacos kit"]
    assert packs[1].contents == ["Shred cabbage"] and packs[1].id == "pack-2"
    assert any("Added a kit for Fri" in w for w in res.warnings)


def test_unplaced_entry_is_flagged_and_gets_no_kit():
    res = apply_rules(raw(knife=[{"text": "Dice 1 onion", "serves": [1]}]), [meal(1, None, "Curry")])
    assert not [t for t in res.tasks if t.section == "pack"]
    assert any("Curry has no night yet" in w for w in res.warnings)
    assert res.tasks[0].serves[0].night == "no night yet"


# --- final review fixes ---

from pydantic import ValidationError
from mealprep.models import RawCookCard


def test_multi_night_freeze_names_portions_and_thaws_per_entry():
    res = apply_rules(raw(proteins=[{"text": "Portion 2 lb ground beef: 1 lb Tue tacos, 1 lb Fri chili", "serves": [1, 2]}]),
                      [meal(1, 2, "Tacos"), meal(2, 5, "Chili")])
    t = res.tasks[0]
    assert t.shelf_life == "freeze_then_thaw"
    assert t.thaw == ("Freeze the Fri portion of the ground beef Sunday; move it to the fridge Thu night. "
                      "Keep the Tue portion in the fridge.")
    assert 1 not in res.thaw and res.thaw[2] == ["Freeze the ground beef Sunday; move it to the fridge Thu night."]


@pytest.mark.parametrize("text", ["Hard-boil 6 eggs", "Precook the quinoa", "Bring the marinade to a boil",
                                  "Microwave the butter", "Brown the beef", "Pan-sear the tofu",
                                  "Squash – roast 25 min", "Onions & sauté", "Warm the honey"])
def test_more_hot_phrasings_detected(text):
    assert is_hot(text)


@pytest.mark.parametrize("text", ["Measure 1 cup brown rice and brown sugar", "Mix flour and warm water"])
def test_brown_and_warm_ingredients_not_hot(text):
    assert not is_hot(text)


@pytest.mark.parametrize("text", ["Form 24 meatballs", "Portion swordfish steaks", "Shuck 12 oysters",
                                  "Shape 4 burger patties", "Portion 1 lb Italian sausage"])
def test_more_raw_proteins_frozen_when_late(text):
    assert shelf_rule(text, "proteins", [5])[0] == "freeze_then_thaw"


def test_smoked_sausage_not_frozen():
    assert shelf_rule("Slice smoked sausage", "proteins", [5]) is None


def test_toss_in_acid_counts_as_marinade():
    assert shelf_rule("Toss shrimp in lime juice; refrigerate", "proteins", [1])[0] == "day_of"


def test_acid_marinade_split_across_tasks():
    res = apply_rules(raw(sauces=[{"text": "Whisk lime-garlic marinade for Mon cod", "serves": [1]}],
                          proteins=[{"text": "Add the cod to the marinade; bag", "serves": [1]}]), [meal(1, 1, "Cod")])
    by = {t.text: t for t in res.tasks}
    assert by["Whisk lime-garlic marinade for Mon cod"].shelf_life == "ok"
    assert by["Add the cod to the marinade; bag"].shelf_life == "day_of"


@pytest.mark.parametrize("text,section", [("Dice 3 onions: 2 Tue tacos, 1 Fri salmon", "knife"),
                                          ("Mix glaze for Thu ground beef tacos", "sauces"),
                                          ("Whisk lemon marinade for Thu chicken", "sauces"),
                                          ("FRI – salmon kit", "pack")])
def test_dish_names_outside_proteins_not_frozen(text, section):
    assert shelf_rule(text, section, [5]) is None


def test_knife_cutting_raw_fish_still_frozen():
    assert shelf_rule("Cut 600 g salmon into 4 portions", "knife", [4])[0] == "freeze_then_thaw"


def test_kit_contents_checked():
    res = apply_rules(raw(pack=[{"text": "THU – taco kit", "serves": [1],
                                 "contents": ["2 diced avocados", "3 cups cooked rice", "1 jar salsa", "1 lb ground beef"]}]),
                      [meal(1, 4, "Tacos")])
    kit = res.tasks[0]
    assert kit.contents == ["1 jar salsa", "1 lb ground beef (frozen — see thaw)"]
    assert res.day_of[1] == ["2 diced avocados", "3 cups cooked rice"]
    assert res.thaw[1] == ["Freeze the ground beef Sunday; move it to the fridge Wed night."]
    assert kit.flags and kit.shelf_life == "ok"


def test_same_night_kits_are_merged_and_missing_entries_added():
    res = apply_rules(raw(knife=[{"text": "Shred lettuce", "serves": [3]}],
                          pack=[{"text": "TUE – chili kit", "serves": [1], "contents": ["onions"]},
                                {"text": "TUE – rice kit", "serves": [2], "contents": ["rice"]}]),
                      [meal(1, 2), meal(2, 2, "Rice"), meal(3, 2, "Salad")])
    [kit] = [t for t in res.tasks if t.section == "pack"]
    assert kit.id == "pack-1" and [s.entry_id for s in kit.serves] == [1, 2, 3]
    assert kit.contents == ["onions", "rice", "Shred lettuce"]
    assert any("Merged" in w for w in res.warnings) and any("Salad" in w for w in res.warnings)


def test_kit_spanning_two_nights_is_split():
    res = apply_rules(raw(pack=[{"text": "Pack all kits", "serves": [1, 2], "contents": ["onions"], "est_minutes": 6}]),
                      [meal(1, 2), meal(2, 4, "Soup")])
    packs = [t for t in res.tasks if t.section == "pack"]
    assert [(p.id, p.text, [s.entry_id for s in p.serves], p.est_minutes) for p in packs] == [
        ("pack-1", "TUE – Pack all kits", [1], 3), ("pack-2", "THU – Pack all kits", [2], 3)]


def test_raw_plan_unwraps_and_rejects_unknown_shapes():
    assert RawPrepPlan.model_validate({"plan": {"knife": [{"text": "x"}]}}).knife[0].text == "x"
    p = RawPrepPlan.model_validate({"sections": [{"key": "knife", "tasks": ["Dice onions"]}, {"key": "pack", "tasks": []}]})
    assert p.knife[0].text == "Dice onions"
    with pytest.raises(ValidationError):
        RawPrepPlan.model_validate({"tasks": [{"text": "x"}]})


def test_raw_shapes_coerced():
    p = RawPrepPlan.model_validate({"knife": ["Dice onions", {"text": "y", "serves": [{"entry_id": 4}, {"id": 5}]}]})
    assert p.knife[0].text == "Dice onions" and p.knife[1].serves == [4, 5]
    c = RawCookCard.model_validate({"steps": ["Preheat oven", {"text": "Bake", "minutes": "20"}]})
    assert [s.text for s in c.steps] == ["Preheat oven", "Bake"] and c.steps[1].minutes == 20


def test_task_with_only_unknown_entries_warns():
    res = apply_rules(raw(knife=[{"text": "Dice onion", "serves": [99]}]), [meal(1, 2)])
    assert any("doesn't match any planned meal" in w for w in res.warnings)



def test_raw_chicken_keeps_in_fridge_through_wednesday():
    # 2026-10-04: relax raw chicken to Thursday onward (fish/ground meat stay Wednesday onward)
    assert shelf_rule("Trim 8 chicken thighs", "proteins", [3]) is None
    shelf, thaw, _ = shelf_rule("Trim 8 chicken thighs", "proteins", [4])
    assert shelf == "freeze_then_thaw" and "Wed night" in thaw
    assert shelf_rule("Portion cod", "proteins", [3])[0] == "freeze_then_thaw"
    assert shelf_rule("Portion 1 lb ground beef", "proteins", [3])[0] == "freeze_then_thaw"


def test_chicken_wed_and_thu_freezes_only_thursday_portion():
    res = apply_rules(raw(proteins=[{"text": "Trim 2 lb chicken thighs: 1 lb Wed curry, 1 lb Thu tacos",
                                     "serves": [1, 2]}]),
                      [meal(1, 3, "Curry"), meal(2, 4, "Tacos")])
    [t] = [t for t in res.tasks if t.section == "proteins"]
    assert t.shelf_life == "freeze_then_thaw"
    assert 1 not in res.thaw and 2 in res.thaw          # only Thursday's portion is frozen
    assert "Wed" in t.thaw and "fridge" in t.thaw
