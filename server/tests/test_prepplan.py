from datetime import date
import threading
import pytest
from mealprep import db, prepplan
from mealprep.ai.base import AIError
from mealprep.models import Ingredient, Recipe

WK = date(2026, 10, 11)
TODAY = date(2026, 10, 7)


def recipe(conn, title, servings=None, ings=(("onion", 2, None),), steps=("Cook it.",)):
    r = Recipe(title=title, source="photo", servings=servings, steps=list(steps),
               ingredients=[Ingredient(raw=f"{q} {n}", name=n, qty=q, unit=u) for n, q, u in ings])
    return db.save_recipe(conn, r)


def entry(conn, rid, day, week=WK, mult=None):
    eid = db.add_to_week(conn, week, rid)
    db.update_entry(conn, eid, day=day, **({"multiplier": mult} if mult else {}))
    return eid


def test_gather_meals_scales_labels_and_notes(conn):
    chili = recipe(conn, "Chili", servings=2, ings=(("onion", 2, None), ("cumin", 1.5, "tsp")))
    cookies = recipe(conn, "Cookies")                       # no servings → as written × multiplier
    old = entry(conn, chili, 2, week=date(2026, 9, 27))
    db.set_rating(conn, old, 4, note="less salt")
    e1 = entry(conn, chili, 2, mult=1.5)
    e2 = db.add_to_week(conn, WK, cookies)                   # not placed
    db.update_entry(conn, e2, multiplier=2)
    m1, m2 = prepplan.gather_meals(conn, [date(2026, 10, 14)], TODAY)
    assert (m1.entry_id, m1.label, m1.offset, m1.date, m1.factor) == (e1, "Tue", 2, "2026-10-13", 3.0)
    assert m1.ingredients == ["6 onion", "4.5 tsp cumin"] and m1.steps == ["Cook it."]
    assert m1.notes == ["last time: less salt"]
    assert (m2.entry_id, m2.day, m2.label, m2.offset, m2.factor) == (e2, None, "no night yet", None, 2.0)


def test_gather_multi_week_offsets_and_dated_labels(conn):
    r = recipe(conn, "Chili")
    a = entry(conn, r, 2)
    b = entry(conn, r, 2, week=date(2026, 10, 18))
    m = prepplan.gather_meals(conn, [date(2026, 10, 18), WK], TODAY)
    assert [(x.entry_id, x.offset, x.label) for x in m] == [(a, 2, "Tue 13 Oct"), (b, 9, "Tue 20 Oct")]


class FakeAI:
    """Routes on the prompt: PREP PLAN → `plan`; COOK CARD → a fixed card (or `card`)."""
    def __init__(self, plan, fail_plan=False, fail_card_for=None, card=None):
        self.plan, self.fail_plan, self.fail_card_for, self.card = plan, fail_plan, fail_card_for, card
        self.prompts, self.lock = [], threading.Lock()

    def complete_json(self, prompt, images=None):
        with self.lock:
            self.prompts.append(prompt)
        if "PREP PLAN" in prompt:
            if self.fail_plan:
                raise AIError("boom")
            return self.plan
        if self.fail_card_for and self.fail_card_for in prompt:
            raise AIError("card boom")
        return self.card or {"steps": [{"text": "Heat the oven", "minutes": 5},
                                       {"text": "Bake", "minutes": 20, "timer_minutes": 20}], "total_minutes": 25}


def plan_for(chili, soup):
    return {"knife": [{"text": "Dice 3 onions: 2 Tue chili, 1 Thu soup", "serves": [chili, soup], "est_minutes": 6},
                      {"text": "Dice 1 avocado", "serves": [soup], "est_minutes": 2}],
            "sauces": [{"text": "Boil the rice", "serves": [chili], "est_minutes": 20}],
            "proteins": [],
            "pack": [{"text": "TUE – chili kit", "serves": [chili], "contents": ["2 diced onions"], "est_minutes": 3},
                     {"text": "THU – soup kit", "serves": [soup], "contents": ["1 diced onion"], "est_minutes": 3}],
            "warnings": ["Soup is late in the week"]}


def two_meals(conn):
    return entry(conn, recipe(conn, "Chili"), 2), entry(conn, recipe(conn, "Soup"), 4)


def build(conn, ai, weeks=(WK,), workers=4):
    pid = prepplan.create_prep_plan(conn, list(weeks), TODAY, ai_provider="fake")
    prepplan.build_prep_plan(conn, pid, ai, workers=workers)
    return pid


def test_build_ready_with_sections_cards_and_rules(conn):
    chili, soup = two_meals(conn)
    ai = FakeAI(plan_for(chili, soup))
    pid = build(conn, ai)
    p = prepplan.get_prep_plan(conn, pid)
    assert p["status"] == "ready" and p["error"] is None and p["progress"] == {"done": 3, "total": 3}
    assert [s["key"] for s in p["sections"]] == ["knife", "sauces", "proteins", "pack"]
    knife = p["sections"][0]["tasks"]
    assert [t["text"] for t in knife] == ["Dice 3 onions: 2 Tue chili, 1 Thu soup", "Dice 1 avocado"]
    assert knife[0]["serves"][0] == {"entry_id": chili, "recipe_id": knife[0]["serves"][0]["recipe_id"],
                                     "title": "Chili", "night": "Tue", "date": "2026-10-13"}
    assert knife[1]["shelf_life"] == "day_of" and knife[1]["flags"]
    assert p["sections"][1]["tasks"] == []                          # "Boil the rice" moved to the card
    assert p["total_minutes"] == 6 + 3 + 3                           # day_of tasks aren't Sunday work
    assert "Soup is late in the week" in p["warnings"] and any("Sunday is prep only" in w for w in p["warnings"])
    assert (p["checklist"]["total"], p["checklist"]["done"]) == (3, 0)
    assert p["stale"] is False and p["weeks"] == ["2026-10-11"] and p["generation_seconds"] is not None
    card = prepplan.get_card(conn, chili)
    assert card["kit"] == ["2 diced onions"] and card["day_of"] == ["Boil the rice"] and card["night"] == "Tue"
    assert card["steps"][1]["timer_minutes"] == 20 and card["total_minutes"] == 25 and card["prep_plan_id"] == pid
    assert card["stale"] is False and card["title"] == "Chili"
    assert prepplan.get_card(conn, soup)["day_of"] == ["Dice 1 avocado"]
    cards = [x for x in ai.prompts if "COOK CARD" in x]
    assert len(cards) == 2 and any("Boil the rice" in x for x in cards)


def test_unplaced_entry_in_plan_input_but_no_card(conn):
    chili = entry(conn, recipe(conn, "Chili"), 2)
    curry = db.add_to_week(conn, WK, recipe(conn, "Curry"))
    ai = FakeAI({"knife": [{"text": "Dice onion", "serves": [chili, curry]}],
                 "pack": [{"text": "TUE – chili kit", "serves": [chili]}]})
    p = prepplan.get_prep_plan(conn, build(conn, ai))
    assert p["status"] == "ready" and p["progress"]["total"] == 2
    [plan_prompt] = [x for x in ai.prompts if "PREP PLAN" in x]
    assert '"night": "no night yet"' in plan_prompt and "Curry" in plan_prompt
    assert prepplan.get_card(conn, curry) is None and prepplan.get_card(conn, chili)
    assert any("Curry has no night yet" in w for w in p["warnings"])
    assert {e["entry_id"]: e["night"] for e in p["entries"]} == {chili: "Tue", curry: "no night yet"}


def test_card_carries_rating_notes(conn):
    rid = recipe(conn, "Chili")
    db.set_rating(conn, entry(conn, rid, 2, week=date(2026, 9, 27)), 3, note="less salt")
    eid = entry(conn, rid, 2)
    ai = FakeAI({"pack": [{"text": "TUE – chili kit", "serves": [eid]}]})
    build(conn, ai)
    assert prepplan.get_card(conn, eid)["rating_notes"] == ["last time: less salt"]
    assert any("less salt" in x for x in ai.prompts if "COOK CARD" in x)


def test_regenerating_replaces_cards(conn):
    chili, soup = two_meals(conn)
    first = build(conn, FakeAI(plan_for(chili, soup)))
    second = build(conn, FakeAI(plan_for(chili, soup), card={"steps": [{"text": "New way"}], "total_minutes": 15}))
    card = prepplan.get_card(conn, chili)
    assert card["prep_plan_id"] == second and card["steps"][0]["text"] == "New way"
    assert conn.execute("SELECT count(*) FROM cook_cards WHERE prep_plan_id=%s", (first,)).fetchone()[0] == 0
    assert prepplan.get_prep_plan(conn, first)["status"] == "ready"          # old plan kept for history
    assert prepplan.latest_for_week(conn, WK)["id"] == second


def test_plan_ai_failure_marks_failed(conn):
    two_meals(conn)
    p = prepplan.get_prep_plan(conn, build(conn, FakeAI({}, fail_plan=True)))
    assert p["status"] == "failed" and p["error"] == "prep plan: AI call failed: boom" and p["finished_at"]


def test_plan_bad_shape_marks_failed(conn):
    two_meals(conn)
    p = prepplan.get_prep_plan(conn, build(conn, FakeAI(["not", "a", "plan"])))
    assert p["status"] == "failed" and p["error"].startswith("prep plan: AI returned an unexpected shape")


def test_card_failure_marks_failed_and_keeps_old_cards(conn):
    chili, soup = two_meals(conn)
    good = build(conn, FakeAI(plan_for(chili, soup)))
    bad = build(conn, FakeAI(plan_for(chili, soup), fail_card_for="Soup"))
    p = prepplan.get_prep_plan(conn, bad)
    assert p["status"] == "failed" and "cook card for Thu Soup: AI call failed: card boom" in p["error"]
    assert prepplan.get_card(conn, chili)["prep_plan_id"] == good
    w = prepplan.latest_for_week(conn, WK)
    assert w["id"] == bad and w["last_ready_id"] == good


def test_nothing_planned(conn):
    with pytest.raises(prepplan.NothingPlanned, match="no recipes planned for 2026-10-11"):
        prepplan.create_prep_plan(conn, [WK], TODAY)


def test_restart_fails_building_plans(conn):
    two_meals(conn)
    pid = prepplan.create_prep_plan(conn, [WK], TODAY)
    prepplan.fail_interrupted(conn)
    p = prepplan.get_prep_plan(conn, pid)
    assert p["status"] == "failed" and "restart" in p["error"]


def test_task_checklist_and_history(conn):
    chili, soup = two_meals(conn)
    pid = build(conn, FakeAI(plan_for(chili, soup)))
    t = prepplan.set_task_done(conn, pid, "knife-1", True)
    assert t["done"] is True and t["done_at"] and t["id"] == "knife-1"
    prepplan.set_task_done(conn, pid, "pack-1", True)
    prepplan.set_task_done(conn, pid, "pack-1", False)
    c = prepplan.get_prep_plan(conn, pid)["checklist"]
    assert (c["done"], c["total"], c["est_minutes"], c["actual_minutes"]) == (1, 3, 12, None)
    for tid in ("pack-1", "pack-2"):
        prepplan.set_task_done(conn, pid, tid, True)
    c = prepplan.get_prep_plan(conn, pid)["checklist"]
    assert c["done"] == 3 and c["actual_minutes"] is not None and c["first_done_at"] <= c["last_done_at"]
    assert conn.execute("SELECT count(*) FROM prep_task_events WHERE prep_plan_id=%s", (pid,)).fetchone()[0] == 5
    with pytest.raises(prepplan.PrepNotFound):
        prepplan.set_task_done(conn, pid, "nope-9", True)
    with pytest.raises(prepplan.PrepNotFound):
        prepplan.set_task_done(conn, 999, "knife-1", True)


def test_task_patch_needs_ready_plan(conn):
    two_meals(conn)
    pid = prepplan.create_prep_plan(conn, [WK], TODAY)
    with pytest.raises(prepplan.PrepStateError):
        prepplan.set_task_done(conn, pid, "knife-1", True)


def test_moving_or_deleting_an_entry_after_generation(conn):
    chili, soup = two_meals(conn)
    pid = build(conn, FakeAI(plan_for(chili, soup)))
    db.update_entry(conn, chili, day=3)
    assert prepplan.get_prep_plan(conn, pid)["stale"] is True
    assert prepplan.get_card(conn, chili)["stale"] is True and prepplan.get_card(conn, soup)["stale"] is False
    db.remove_entry(conn, soup)
    assert prepplan.get_card(conn, soup) is None


def test_cards_written_concurrently_and_one_worker_works(conn):
    chili, soup = two_meals(conn)
    p = prepplan.get_prep_plan(conn, build(conn, FakeAI(plan_for(chili, soup)), workers=1))
    assert p["status"] == "ready" and p["progress"] == {"done": 3, "total": 3}


def test_plan_with_no_known_sections_fails(conn):
    two_meals(conn)
    p = prepplan.get_prep_plan(conn, build(conn, FakeAI({"steps": ["x"]})))
    assert p["status"] == "failed" and "unexpected shape" in p["error"]
