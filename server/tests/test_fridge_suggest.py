"""POST /fridge/suggest: a greedy set of dinners that uses the fridge (use soon first) with the fewest extras."""
from datetime import date, timedelta

from hypothesis import given, settings, strategies as st

from mealprep import db, fridge
from mealprep.fridge import Candidate
from mealprep.models import Ingredient, Recipe
from test_api import H, client
from test_fridge_match import F, L

TODAY = date(2026, 10, 7)


def C(rid, *lines, title=None, avg=None, company=None, last=None):
    return Candidate(recipe_id=rid, title=title or f"R{rid}", lines=list(lines), avg_family=avg, company=company,
                     last_cooked=last)


def ids(out):
    return [p["recipe_id"] for p in out["picks"]]


def test_greedy_prefers_two_recipes_sharing_the_fridge_and_extras_over_one_big_user():
    items = [F(1, "Cilantro", 2, "bunch")]
    a = C(1, L("cilantro", 1, "bunch"), L("lime", 2))
    b = C(2, L("cilantro", 1, "bunch"), L("lime", 1))
    big = C(3, L("cilantro", 2, "bunch"), *[L(n, 1) for n in ("pork", "rice", "bean", "corn", "tortilla")])
    assert ids(fridge.select([big, a, b], items, nights=2)) == [1, 2]
    one = fridge.select([big, a, b], items, nights=1)
    assert ids(one) == [1] and one["fridge_left"] == [{"item_id": 1, "name": "Cilantro", "amount": "1 bunch Cilantro"}]


def test_use_soon_outweighs_a_plain_item():
    items = [F(1, "Kale", 1, "bunch"), F(2, "Spinach", 1, "bag", use_soon=True, soon=True)]
    assert ids(fridge.select([C(1, L("kale", 1, "bunch")), C(2, L("spinach", 1, "bag"))], items, nights=1)) == [2]


def test_a_typed_amount_outweighs_an_estimate():
    items = [F(1, "Kale", 1, "bunch", qty_estimated=True), F(2, "Spinach", 1, "bag")]
    assert ids(fridge.select([C(1, L("kale", 1, "bunch")), C(2, L("spinach", 1, "bag"))], items, nights=1)) == [2]


def test_tie_breakers_in_order():
    items = [F(1, "Eggs", 12)]
    eggs = L("egg", 2)
    assert ids(fridge.select([C(1, eggs), C(2, eggs, avg=4.0), C(3, eggs, avg=4.5)], items, 1)) == [3]
    assert ids(fridge.select([C(1, eggs, avg=4.0), C(2, eggs, avg=4.0, company="yes")], items, 1)) == [2]
    old, new = TODAY - timedelta(days=60), TODAY - timedelta(days=3)
    assert ids(fridge.select([C(1, eggs, last=new), C(2, eggs, last=old)], items, 1)) == [2]
    assert ids(fridge.select([C(1, eggs, last=old), C(2, eggs)], items, 1)) == [2]   # never cooked = oldest
    assert ids(fridge.select([C(2, eggs), C(1, eggs)], items, 1)) == [1]


def test_planned_recipes_use_the_fridge_first():
    items = [F(1, "Eggs", 6), F(2, "Kale", 1, "bunch")]
    cands = [C(1, L("egg", 6)), C(2, L("kale", 1, "bunch"))]
    out = fridge.select(cands, items, nights=2, planned=[[L("egg", 6)]])
    assert ids(out) == [2] and out["thin_library"]


def test_stops_early_when_nothing_else_uses_the_fridge():
    out = fridge.select([C(1, L("egg", 2)), C(2, L("pork", 1)), C(3, L("rice", 1))], [F(1, "Eggs", 6)], nights=3)
    assert ids(out) == [1] and out["thin_library"]
    assert fridge.select([C(1, L("pork", 1))], [], nights=2)["picks"] == []


def test_texts_uses_still_need_and_stale_flags():
    items = [F(1, "eggs", 6, days_since_checked=9), F(2, "cilantro", None), F(3, "Whole milk", 1, "l")]
    many = [L(n, 1) for n in ("chicken thigh", "lime", "onion", "tomato", "jalapeno", "avocado", "corn", "bean",
                              "tortilla", "radish")]
    r = C(1, L("egg", 4), L("cilantro", 1, "bunch"), L("whole milk", 1, "cup"), L("salt", None, likely=True), *many,
          title="Huevos")
    out = fridge.select([r], items, nights=1)
    [p] = out["picks"]
    assert [u["amount"] for u in p["uses"]] == ["4 eggs", "cilantro", "0.24 l Whole milk"]
    assert [u["stale"] for u in p["uses"]] == [True, False, False]
    assert p["still_need"] == ["chicken thigh", "lime", "onion", "tomato", "jalapeno", "avocado", "corn", "bean"]
    assert p["still_need_more"] == 2
    assert out["stale"] == ["eggs: last checked 9 days ago"]
    assert {x["item_id"] for x in out["fridge_left"]} == {1, 3}


cand_st = st.lists(st.lists(st.tuples(st.sampled_from(["egg", "kale", "milk", "pork", "rice"]),
                                      st.integers(1, 6), st.sampled_from([None, "cup", "bunch"])), min_size=1, max_size=4),
                   min_size=0, max_size=8)
item_st = st.lists(st.tuples(st.sampled_from(["egg", "kale", "milk"]), st.one_of(st.none(), st.integers(0, 10)),
                             st.sampled_from([None, "cup", "bunch"]), st.booleans()), max_size=4)


@settings(max_examples=300, deadline=None)
@given(cs=cand_st, its=item_st, nights=st.integers(1, 7))
def test_selection_is_bounded_unique_deterministic_and_never_overuses(cs, its, nights):
    cands = [C(i + 1, *[L(n, q, u) for n, q, u in lines]) for i, lines in enumerate(cs)]
    items, seen = [], set()
    for i, (n, q, u, soon) in enumerate(its):
        if (n, u) not in seen:
            seen.add((n, u))
            items.append(F(i + 1, n, q, u, use_soon=soon, soon=soon))
    out = fridge.select(cands, items, nights)
    got = ids(out)
    assert len(got) <= nights and len(set(got)) == len(got)
    assert out == fridge.select(list(reversed(cands)), items, nights)
    for it in items:
        if it.qty is None:
            continue
        used = sum(u["qty"] for p in out["picks"] for u in p["uses"] if u["item_id"] == it.id and u["qty"] is not None)
        assert used <= it.qty + 1e-6


# --- DB + API ---------------------------------------------------------------------------------------------------

def R(title, *lines, servings=4):
    return Recipe(title=title, source="photo", servings=servings, steps=[],
                  ingredients=[Ingredient(raw=raw, name=n, qty=q, unit=u) for raw, n, q, u in lines])


def test_suggest_endpoint(conn):
    a = db.save_recipe(conn, R("Frittata", ("6 eggs", "egg", 6, None), ("spinach", "spinach", 1, "bag")))
    b = db.save_recipe(conn, R("Shakshuka", ("4 eggs", "egg", 4, None), ("2 cans tomatoes", "crushed tomato", 2, "can")))
    p = db.save_recipe(conn, R("Omelette", ("2 eggs", "egg", 2, None)))
    db.save_recipe(conn, R("Empty", servings=4))
    db.save_recipe(conn, R("Pork", ("pork", "pork shoulder", 1, "kg")))
    db.add_to_week(conn, date(2026, 10, 11), p)
    c = client(conn, today=TODAY)
    c.post("/fridge", headers=H, json={"name": "Eggs", "qty": 8})
    c.post("/fridge", headers=H, json={"name": "Spinach", "qty": 1, "unit": "bag", "use_soon": True})
    r = c.post("/fridge/suggest", headers=H, json={"nights": 3, "week": "2026-10-11"})
    assert r.status_code == 200
    out = r.json()
    assert [x["recipe_id"] for x in out["picks"]] == [a] and out["thin_library"] is True
    assert {u["amount"] for u in out["picks"][0]["uses"]} == {"6 Eggs", "1 bag Spinach"}
    assert out["picks"][0]["ratings"]["times_rated"] == 0
    out = c.post("/fridge/suggest", headers=H, json={"nights": 2, "exclude": [a]}).json()
    assert [x["recipe_id"] for x in out["picks"]] == [b, p]
    out = c.post("/fridge/suggest", headers=H, json={"nights": 1, "people": 2, "exclude": [a, b]}).json()
    assert out["picks"][0]["uses"][0]["amount"] == "1 Eggs"   # the Omelette for 2
    for bad in ({"nights": 0}, {"nights": 8}, {"nights": 2, "people": 0}, {"nights": 2, "people": 21}, {}):
        assert c.post("/fridge/suggest", headers=H, json=bad).status_code == 422, bad
    assert c.post("/fridge/suggest", json={"nights": 1}).status_code == 401
