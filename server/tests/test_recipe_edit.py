"""Editing a recipe (2026-10-11): PATCH /recipes/{id} with title, servings, ingredient lines, steps, notes … and what an
edit means for carts, prep plans and cook cards. Invented recipes only."""
from datetime import date

import pytest

from mealprep import db, drafts, prepplan, recipe_edit
from mealprep.ai.base import AIError
from mealprep.models import Ingredient, Recipe
from test_api import H, client
from test_drafts import FakeAI as CartAI, FakePcx, build, item, prod
from test_prepplan import FakeAI as PrepAI, TODAY, WK, plan_for

ON = "2026-10-11"


class LineAI:
    """Structures ingredient lines like the real prompt answers; records the lines it was asked about."""
    def __init__(self, fail=False):
        self.fail, self.asked = fail, []

    def complete_json(self, prompt, images=None):
        import json, re
        lines = json.loads(prompt[prompt.index("Lines:") + 6:])
        self.asked.append(lines)
        if self.fail:
            raise AIError("boom")
        out = []
        for l in lines:
            m = re.match(r"(\d+)\s+(cups?|tbsp)?\s*(.*)", l)
            out.append({"name": (m.group(3) if m else l).lower().rstrip("s"), "qty": m.group(1) if m else None,
                        "unit": m.group(2) if m else None, "prep": None, "likely_on_hand": "salt" in l})
        return out


def ing(raw, name, qty=None, unit=None, **kw):
    return Ingredient(raw=raw, name=name, qty=qty, unit=unit, **kw)


def stew():
    return Recipe(title="Test Stew", source="photo", servings=4, steps=["Brown the beef.", "Simmer."],
                  ingredients=[ing("2 cups stock", "stock", 2, "cup"), ing("1 onoin", "onoin", 1),
                               ing("Salt", "salt", likely_on_hand=True)])


def crepes():
    """A recipe with an attached sub-recipe: the expanded reference line, then the sub-recipe's own lines."""
    return Recipe(title="Test Gâteau", source="photo", servings=4, steps=["Fill."], ingredients=[
        ing("1 lb spinach", "spinach", 1, "lb"),
        ing("Batter for 24 crêpes, page 191", "crêpe batter", expanded=True, sub_recipe="Batter for 24 crêpes"),
        ing("1 cup flour", "flour", 1, "cup", sub_recipe="Batter for 24 crêpes"),
        ing("3 eggs", "egg", 3, sub_recipe="Batter for 24 crêpes"),
        ing("2 cups milk", "milk", 2, "cup")])


# --- the ingredient lines -------------------------------------------------------------------------------------

def test_unchanged_lines_keep_everything_and_only_changed_ones_are_read_again():
    old = stew().ingredients
    ai = LineAI()
    out = recipe_edit.edit_ingredients(ai, old, ["2 cups stock", "1 onion", "Salt", "3 carrots"])
    assert ai.asked == [["1 onion", "3 carrots"]]                                  # one AI call, changed lines only
    assert out[0] == old[0] and out[2] == old[2]                                    # kept as they were
    assert (out[1].raw, out[1].name, out[1].qty) == ("1 onion", "onion", 1.0)
    assert (out[3].name, out[3].qty) == ("carrot", 3.0)
    assert recipe_edit.edit_ingredients(ai, old, [i.raw for i in old]) == old and len(ai.asked) == 1   # nothing new: no AI


def test_lines_are_cleaned_blank_ones_dropped_and_order_follows_the_text():
    old = stew().ingredients
    out = recipe_edit.edit_ingredients(LineAI(), old, ["  Salt ", "", "2   cups stock", "   "])
    assert [i.raw for i in out] == ["Salt", "2 cups stock"] and out == [old[2], old[0]]


def test_a_line_saved_with_odd_spacing_is_still_the_same_line():
    old = [ing("2  cups\tstock", "stock", 2, "cup")]
    ai = LineAI()
    assert recipe_edit.edit_ingredients(ai, old, ["2 cups stock", "1 onion"])[0] == old[0] and ai.asked == [["1 onion"]]


def test_without_the_ai_changed_lines_get_the_deterministic_clean_up():
    ai = LineAI(fail=True)
    out = recipe_edit.edit_ingredients(ai, stew().ingredients, ["2 cups stock", "1 onion, diced", "1 tsp kosher salt"])
    assert ai.asked                                                                  # asked, failed: still saved
    onion, salt = out[1], out[2]
    assert (onion.name, onion.qty, onion.prep) == ("onion", 1.0, "diced")
    assert (salt.qty, salt.unit, salt.likely_on_hand) == (1.0, "tsp", True)
    assert recipe_edit.edit_ingredients(None, stew().ingredients, ["1 onion"])[0].name == "onion"   # no AI at all


def test_heading_lines_name_the_sub_recipe_of_the_lines_after_them():
    out = recipe_edit.edit_ingredients(LineAI(), stew().ingredients,
                                       ["2 cups stock", "For the sauce:", "1 tbsp butter", "Salt", "TOPPING:", "1 cup cream"])
    assert [(i.raw, i.sub_recipe) for i in out] == [("2 cups stock", None), ("1 tbsp butter", "Sauce"),
                                                   ("Salt", "Sauce"), ("1 cup cream", "Topping")]


def test_an_attached_sub_recipe_round_trips_as_the_screen_shows_it():
    old = crepes().ingredients
    # The screen (and the editor) shows the sub-recipe's name as a heading and leaves the expanded line out.
    shown = ["1 lb spinach", "Batter for 24 crêpes:", "1 cup flour", "3 eggs", "2 cups milk"]
    ai = LineAI()
    assert recipe_edit.edit_ingredients(ai, old, shown) == old and ai.asked == []
    out = recipe_edit.edit_ingredients(ai, old, ["1 lb spinach", "Batter for 24 crêpes:", "1 cup flour", "4 eggs", "2 cups milk"])
    assert out[1] == old[1] and out[1].expanded                                     # the reference line stays hidden
    assert (out[3].raw, out[3].sub_recipe, out[3].qty) == ("4 eggs", "Batter for 24 crêpes", 4.0)


def test_deleting_a_sub_recipes_lines_brings_its_reference_line_back():
    out = recipe_edit.edit_ingredients(LineAI(), crepes().ingredients, ["1 lb spinach", "2 cups milk"])
    back = next(i for i in out if i.raw.startswith("Batter"))
    assert not back.expanded and back.ref_page == 191 and back.sub_recipe is None   # bought again, page prompt again
    assert [i.raw for i in out] == ["1 lb spinach", "Batter for 24 crêpes, page 191", "2 cups milk"]


def test_an_expanded_line_without_a_sub_recipe_name_stays_where_it_was():
    old = [i.model_copy(update={"sub_recipe": None}) if i.expanded else i for i in crepes().ingredients]
    out = recipe_edit.edit_ingredients(LineAI(), old, ["1 lb spinach", "Batter for 24 crêpes:", "1 cup flour", "3 eggs",
                                                       "2 cups milk", "1 lemon"])
    assert [i.raw for i in out][:2] == ["1 lb spinach", "Batter for 24 crêpes, page 191"] and out[1].expanded


def test_a_changed_line_mentioning_a_page_gets_its_reference():
    out = recipe_edit.edit_ingredients(LineAI(), stew().ingredients, ["Pastry, page 12"])
    assert out[0].ref_page == 12 and not out[0].expanded


# --- PATCH /recipes/{id} --------------------------------------------------------------------------------------

def saved(conn, r=None):
    return db.save_recipe(conn, r or stew())


def patch(c, rid, **body):
    return c.patch(f"/recipes/{rid}", headers=H, json=body)


def test_edit_everything_the_recipe_holds(conn):
    rid = saved(conn)
    c = client(conn, ai=LineAI())
    before = c.get(f"/recipes/{rid}", headers=H).json()
    r = patch(c, rid, title="  Test   Beef Stew ", servings=6, ingredients=["2 cups stock", "1 onion", "Salt"],
              steps=["Brown the beef well.", " ", "Simmer  1 hour."], notes="Freezes well.\n\n\n Use chuck. ",
              description="A winter stew.", prep_minutes=15, cook_minutes=90, total_minutes=105, yield_text="Serves 6")
    assert r.status_code == 200
    b = r.json()
    assert b["title"] == "Test Beef Stew" and b["servings"] == 6
    assert [i["raw"] for i in b["ingredients"]] == ["2 cups stock", "1 onion", "Salt"] and b["ingredients"][1]["name"] == "onion"
    assert b["steps"] == ["Brown the beef well.", "Simmer 1 hour."]
    assert b["notes"] == "Freezes well.\n\nUse chuck." and b["description"] == "A winter stew."
    assert (b["prep_minutes"], b["cook_minutes"], b["total_minutes"], b["yield_text"]) == (15, 90, 105, "Serves 6")
    assert b["uid"] == before["uid"] and b["id"] == rid and b["source_kind"] == "book"
    assert b["revision"] and b["revision"] != before["revision"]
    assert c.get(f"/recipes/{rid}", headers=H).json()["title"] == "Test Beef Stew"
    # null / blank clears the optional fields; what isn't sent stays
    b2 = patch(c, rid, notes="  ", description=None, prep_minutes=None, servings=None).json()
    assert b2["notes"] is None and b2["description"] is None and b2["prep_minutes"] is None and b2["servings"] is None
    assert b2["cook_minutes"] == 90 and b2["title"] == "Test Beef Stew" and len(b2["steps"]) == 2


def test_title_only_still_works_as_before(conn):
    rid = saved(conn)
    c = client(conn, ai=LineAI())
    assert patch(c, rid, title="Test Ragout").json()["title"] == "Test Ragout"
    assert db.get_recipe(conn, rid).ingredients == stew().ingredients


@pytest.mark.parametrize("body, detail", [
    ({"title": "  "}, "title can't be blank"),
    ({"ingredients": None}, "ingredients can't be null"),
    ({"steps": None}, "steps can't be null"),
    ({"servings": 0}, None), ({"servings": 1001}, None), ({"servings": "four"}, None),
    ({"ingredients": ["x" * 501]}, None), ({"ingredients": ["1 egg"] * 301}, None),
    ({"steps": ["x" * 5001]}, None), ({"steps": ["Stir."] * 201}, None),
    ({"notes": "x" * 5001}, None), ({"prep_minutes": -1}, None), ({"cook_minutes": 100_000}, None),
    ({"yield_text": "x" * 201}, None), ({"ingredients": "1 egg"}, None),
])
def test_edit_validation(conn, body, detail):
    rid = saved(conn)
    r = patch(client(conn, ai=LineAI()), rid, **body)
    assert r.status_code == 422
    if detail:
        assert r.json()["detail"] == detail
    got, s = db.get_recipe(conn, rid), stew()
    assert (got.title, got.servings, got.ingredients, got.steps) == (s.title, s.servings, s.ingredients, s.steps)


def test_edit_unknown_recipe_and_auth(conn):
    c = client(conn, ai=LineAI())
    assert patch(c, 999, title="x").status_code == 404
    assert c.patch(f"/recipes/{saved(conn)}", json={"title": "x"}).status_code == 401


def test_expected_revision_refuses_an_edit_of_a_recipe_changed_meanwhile(conn):
    rid = saved(conn)
    c = client(conn, ai=LineAI())
    rev = c.get("/recipes", headers=H).json()[0]["revision"]
    assert rev == c.get(f"/recipes/{rid}", headers=H).json()["revision"]           # the list and the detail agree
    assert patch(c, rid, steps=["Simmer."], expected_revision=rev).status_code == 200   # the other phone's edit
    r = patch(c, rid, title="Test Old Copy", expected_revision=rev)
    assert r.status_code == 409 and "changed" in r.json()["detail"]
    assert db.get_recipe(conn, rid).title == "Test Stew"
    rev2 = c.get(f"/recipes/{rid}", headers=H).json()["revision"]
    assert patch(c, rid, title="Test New Copy", expected_revision=rev2).status_code == 200
    # the source isn't part of the revision: Edit source on one phone doesn't block an edit on the other
    patch(c, rid, source_title="Invented Stews")
    assert patch(c, rid, servings=5, expected_revision=c.get(f"/recipes/{rid}", headers=H).json()["revision"]).status_code == 200


def test_an_edit_keeps_plan_entries_ratings_and_shows_everywhere(conn):
    rid = saved(conn)
    c = client(conn, ai=LineAI(), today=date(2026, 10, 14))
    eid = db.add_to_week(conn, WK, rid)
    db.update_entry(conn, eid, day=1)
    c.put(f"/plan/{eid}/rating", headers=H, json={"family": 5, "note": "more onion"})
    patch(c, rid, title="Test Onion Stew", ingredients=["2 cups stock", "3 onions", "Salt"])
    [e] = c.get(f"/weeks/{ON}", headers=H).json()
    assert e["title"] == "Test Onion Stew" and e["rating"]["family"] == 5
    detail = c.get(f"/recipes/{rid}", headers=H).json()
    assert detail["ratings"]["times_rated"] == 1 and detail["planned_weeks"] == [ON]
    names = {i["name"]: i for i in c.post("/list", headers=H, json={"weeks": [ON]}).json()}
    assert "onion" in names and "onoin" not in names                                # the list is read from the recipe
    ex = c.get(f"/recipes/{rid}/export", headers=H).json()
    assert ex["name"] == "Test Onion Stew" and ex["identifier"] == f"urn:uuid:{db.get_recipe(conn, rid).uid}"


def test_the_ai_answer_is_not_saved_over_a_recipe_changed_while_it_read(conn):
    rid = saved(conn)

    class Racing(LineAI):
        def complete_json(self, prompt, images=None):
            out = super().complete_json(prompt, images)
            db.update_recipe(conn, rid, db.get_recipe(conn, rid).model_copy(update={"ingredients": crepes().ingredients}))
            return out

    r = patch(client(conn, ai=Racing()), rid, ingredients=["2 cups stock", "1 onion"])
    assert r.status_code == 409
    assert db.get_recipe(conn, rid).ingredients == crepes().ingredients


# --- carts: a cart made before an edit of what it buys is stale ---------------------------------------------

def carted(conn, rid):
    db.add_to_week(conn, WK, rid)
    pcx = FakePcx({"x": [prod("A")]})
    did = build(conn, [item("x")], pcx, CartAI({"x": "A"}))
    drafts.send_draft(conn, did, pcx)
    return did


def week(conn):
    return db.week_summaries(conn, WK, 1)[0]


def test_editing_what_a_carted_recipe_buys_makes_the_cart_stale(conn):
    rid = saved(conn)
    did = carted(conn, rid)
    c = client(conn, ai=LineAI())
    assert week(conn)["carted"] is True
    patch(c, rid, title="Test Renamed Stew", steps=["Simmer all day."], notes="Good cold.", description="Hearty.")
    assert week(conn)["carted"] is True and drafts.get_draft(conn, did)["stale"] is False   # nothing to buy changed
    patch(c, rid, ingredients=["2 cups stock", "1 onoin", "Salt", "3 carrots"])
    assert week(conn) == {"week": ON, "entries": 1, "carted": False, "cart_stale": True}
    assert drafts.get_draft(conn, did)["stale"] is True
    patch(c, rid, ingredients=["2 cups stock", "1 onoin", "Salt"])                  # undone: the cart fits again
    assert week(conn)["carted"] is True
    patch(c, rid, servings=8)                                                       # amounts scale with servings
    assert week(conn)["cart_stale"] is True


def test_fingerprints_cover_bought_lines_not_their_wording(conn):
    rid = saved(conn)
    db.add_to_week(conn, WK, rid)
    fp = db.plan_fingerprint(conn, [WK])
    assert fp.startswith("2:") and len(fp) == 34
    r = db.get_recipe(conn, rid)
    reworded = [i.model_copy(update={"raw": i.raw.upper(), "prep": "whatever"}) for i in r.ingredients]
    db.update_recipe(conn, rid, r.model_copy(update={"ingredients": reworded, "title": "x", "steps": []}))
    assert db.plan_fingerprint(conn, [WK]) == fp
    on_hand = [i.model_copy(update={"likely_on_hand": not i.likely_on_hand}) for i in reworded]
    db.update_recipe(conn, rid, r.model_copy(update={"ingredients": on_hand}))
    assert db.plan_fingerprint(conn, [WK]) != fp                                    # ticked on the list or not


def test_carts_from_before_recipe_edits_counted_keep_their_meaning(conn):
    """0.8.1–0.10 carts have a v1 fingerprint (week, recipe, scale only): still compared that way, so they don't all go
    stale when the server is updated, and a recipe edit doesn't make them stale."""
    rid = saved(conn)
    did = carted(conn, rid)
    v1 = db.plan_fingerprint(conn, [WK], version=1)
    assert len(v1) == 32 and ":" not in v1
    conn.execute("UPDATE carts SET plan_fingerprint=%s WHERE id=%s", (v1, did))
    assert week(conn)["carted"] is True
    patch(client(conn, ai=LineAI()), rid, ingredients=["5 carrots"])
    assert week(conn)["carted"] is True and drafts.get_draft(conn, did)["stale"] is False
    db.add_to_week(conn, WK, rid)                                                   # a v1 change still counts
    assert week(conn)["cart_stale"] is True


# --- prep plans and cook cards: written from the recipe as it was ---------------------------------------------

def prepped(conn, rid, day=2):
    eid = db.add_to_week(conn, WK, rid)
    db.update_entry(conn, eid, day=day)
    other = db.add_to_week(conn, WK, db.save_recipe(conn, Recipe(title="Test Soup", source="photo", ingredients=[
        ing("1 leek", "leek", 1)], steps=["Cook."])))
    db.update_entry(conn, other, day=4)
    pid = prepplan.create_prep_plan(conn, [WK], TODAY, ai_provider="fake")
    prepplan.build_prep_plan(conn, pid, PrepAI(plan_for(eid, other)))
    return pid, eid, other


def test_editing_a_prepped_recipe_makes_its_plan_and_card_stale(conn):
    rid = saved(conn)
    pid, eid, other = prepped(conn, rid)
    c = client(conn, ai=LineAI())
    assert prepplan.get_prep_plan(conn, pid)["stale"] is False and prepplan.get_card(conn, eid)["stale"] is False
    patch(c, rid, title="Test Renamed Stew", notes="n", description="d")             # the cook's work didn't change
    assert prepplan.get_prep_plan(conn, pid)["stale"] is False and prepplan.get_card(conn, eid)["stale"] is False
    patch(c, rid, steps=["Brown the beef.", "Simmer 3 hours."])
    assert prepplan.get_prep_plan(conn, pid)["stale"] is True
    assert prepplan.get_card(conn, eid)["stale"] is True and prepplan.get_card(conn, other)["stale"] is False
    patch(c, rid, steps=["Brown the beef.", "Simmer."])                             # undone
    assert prepplan.get_prep_plan(conn, pid)["stale"] is False and prepplan.get_card(conn, eid)["stale"] is False
    patch(c, rid, ingredients=["2 cups stock", "2 onions", "Salt"])
    assert prepplan.get_prep_plan(conn, pid)["stale"] is True and prepplan.get_card(conn, eid)["stale"] is True


def test_prep_plans_from_before_recipe_keys_are_not_stale_by_content(conn):
    rid = saved(conn)
    pid, eid, _ = prepped(conn, rid)
    conn.execute("UPDATE prep_plans SET input = (SELECT jsonb_agg(m - 'recipe_key') FROM jsonb_array_elements(input) m) "
                 "WHERE id=%s", (pid,))
    patch(client(conn, ai=LineAI()), rid, steps=["Something else."])
    assert prepplan.get_prep_plan(conn, pid)["stale"] is False and prepplan.get_card(conn, eid)["stale"] is False
    db.update_entry(conn, eid, day=5)                                               # the old rules still apply
    assert prepplan.get_prep_plan(conn, pid)["stale"] is True and prepplan.get_card(conn, eid)["stale"] is True
