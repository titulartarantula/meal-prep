from datetime import date
import pytest
from mealprep import db, prepplan
from mealprep.ai.base import AIError
from mealprep.importers.photo import import_subrecipe
from mealprep.models import Ingredient, Recipe
from mealprep.shopping import build_list
from mealprep.subrecipe import AlreadyAttached, attach_subrecipe, choose_ref_line
from test_api import H, client


def gateau():
    ings = [Ingredient(raw="1 lb spinach, page 468", name="spinach", qty=1, unit="lb", ref_page=468),
            Ingredient(raw="Batter for 24 crêpes, page 191", name="crêpe batter", ref_page=191),
            Ingredient(raw="2 cups milk", name="milk", qty=2, unit="cup")]
    return Recipe(title="Gâteau de Crêpes", source="photo", servings=4, ingredients=ings, steps=["Fill the crêpes."])


class SubAI:
    def __init__(self, page):
        self.page, self.calls = page, []

    def complete_json(self, prompt, images=None):
        self.calls.append((prompt, images))
        if images:
            return self.page
        return [{"name": "flour", "qty": "1", "unit": "cup"}, {"name": "egg", "qty": "3", "unit": "each"}]


BATTER = {"title": "Crêpe batter", "ingredients": ["1 cup flour", "3 eggs"], "steps": ["Whisk."]}


def test_choose_ref_line():
    r = gateau()
    with pytest.raises(LookupError, match="several lines"):
        choose_ref_line(r, None)
    assert choose_ref_line(r, 1) == 1 and choose_ref_line(r, 2) == 2       # any line may be named explicitly
    with pytest.raises(LookupError, match="out of range"):
        choose_ref_line(r, 3)
    assert choose_ref_line(r.model_copy(update={"ingredients": r.ingredients[1:]}), None) == 0
    with pytest.raises(LookupError, match="no ingredient line"):
        choose_ref_line(r.model_copy(update={"ingredients": r.ingredients[2:]}), None)


def test_attach_inserts_marked_ingredients_and_steps():
    batter = [Ingredient(raw="1 cup flour", name="flour", qty=2, unit="cup"), Ingredient(raw="3 eggs", name="egg", qty=6)]
    out = attach_subrecipe(gateau(), 1, "Crêpe batter", batter, ["Whisk.", "Rest 2 hours."])
    assert [i.name for i in out.ingredients] == ["spinach", "crêpe batter", "flour", "egg", "milk"]
    ref = out.ingredients[1]
    assert ref.ref_page is None and ref.expanded and ref.sub_recipe == "Crêpe batter"
    assert [i.sub_recipe for i in out.ingredients[2:4]] == ["Crêpe batter"] * 2 and out.ingredients[0].ref_page == 468
    assert out.steps == ["Crêpe batter: Whisk.", "Crêpe batter: Rest 2 hours.", "Fill the crêpes."]
    with pytest.raises(AlreadyAttached):
        choose_ref_line(out, 1)
    assert choose_ref_line(out, None) == 0                                  # only the spinach ref is left


def test_shopping_list_skips_expanded_line():
    out = attach_subrecipe(gateau(), 1, "Crêpe batter", [Ingredient(raw="1 cup flour", name="flour", qty=1, unit="cup")], [])
    names = [i.name for i in build_list([(out, 1)])]
    assert "crêpe batter" not in names and "flour" in names


def test_import_subrecipe_scales_by_factor():
    ai = SubAI({**BATTER, "factor": 2})
    name, ings, steps = import_subrecipe(ai, ["/tmp/p191.jpg"], "Batter for 24 crêpes, page 191", 191)
    assert name == "Crêpe batter" and steps == ["Whisk."]
    assert [(i.name, i.qty, i.unit) for i in ings] == [("flour", 2.0, "cup"), ("egg", 6.0, None)]
    prompt, images = ai.calls[0]
    assert "Batter for 24 crêpes, page 191" in prompt and images == ["/tmp/p191.jpg"]


def test_import_subrecipe_bad_factor_means_one_and_missing_page_is_error():
    _, ings, _ = import_subrecipe(SubAI({**BATTER, "factor": "lots"}), ["/tmp/x.jpg"], "Batter, page 191", 191)
    assert ings[0].qty == 1.0
    with pytest.raises(AIError, match="not found"):
        import_subrecipe(SubAI({"title": None}), ["/tmp/x.jpg"], "Batter, page 191", 191)


def test_prep_input_uses_attached_subrecipe(conn):
    r = attach_subrecipe(gateau(), 1, "Crêpe batter", [Ingredient(raw="1 cup flour", name="flour", qty=1, unit="cup")], [])
    rid = db.save_recipe(conn, r)
    eid = db.add_to_week(conn, date(2026, 10, 11), rid)
    db.update_entry(conn, eid, day=2)
    [m] = prepplan.gather_meals(conn, [date(2026, 10, 11)], date(2026, 10, 7))
    assert m.ingredients == ["1 lb spinach", "1 cup flour (for the Crêpe batter)", "2 cup milk"]


def test_attach_pages_endpoint(conn):
    rid = db.save_recipe(conn, gateau())
    c = client(conn, ai=SubAI(BATTER))
    f = [("files", ("p191.jpg", b"\xff\xd8x", "image/jpeg"))]
    r = c.post(f"/recipes/{rid}/pages", headers=H, files=f)
    assert r.status_code == 422 and "several lines" in r.json()["detail"]
    r = c.post(f"/recipes/{rid}/pages", headers=H, files=f, data={"for_line": "1"})
    assert r.status_code == 200
    body = r.json()
    assert [i["name"] for i in body["ingredients"]][:4] == ["spinach", "crêpe batter", "flour", "egg"]
    assert body["ingredients"][2]["sub_recipe"] == "Crêpe batter" and "ratings" in body and body["id"] == rid
    assert db.get_recipe(conn, rid).ingredients[1].expanded
    assert c.post(f"/recipes/{rid}/pages", headers=H, files=f, data={"for_line": "1"}).status_code == 409
    assert c.post("/recipes/999/pages", headers=H, files=f).status_code == 404
    assert c.post(f"/recipes/{rid}/pages", headers=H, files=f * 11, data={"for_line": "0"}).status_code == 422
    assert c.post(f"/recipes/{rid}/pages", files=f).status_code == 401


def test_attach_pages_ai_failure_leaves_recipe(conn):
    rid = db.save_recipe(conn, gateau())
    c = client(conn, ai=SubAI({"title": None}))
    r = c.post(f"/recipes/{rid}/pages", headers=H, files=[("files", ("p.jpg", b"x", "image/jpeg"))],
               data={"for_line": "1"})
    assert r.status_code == 502 and db.get_recipe(conn, rid).ingredients[1].ref_page == 191
