"""Households + RBAC: the route sweep (every route is mapped and scoped), permissions per role, cross-household
isolation (404 / invisible rows), the role rules, AI metering and the seed."""
import json, re
from datetime import date

import psycopg
import pytest
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient

from mealprep import auth, db, households, rbac, seed
from mealprep.ai.base import AIError
from mealprep.ai.metered import Meter, Metered
from mealprep.api import create_app
from mealprep.config import Settings
from mealprep.models import Recipe

H = {"Authorization": "Bearer secret"}
WEEK = "2026-10-12"
ALL_ROLES = ["owner", "approver", "planner", "contributor"]


class AI:
    model = "fake-model"

    def __init__(self):
        self.n = 0

    def complete_json(self, prompt, images=None):
        self.n += 1
        return []


class NoPcx:
    def search(self, t, size=8): return []
    def create_cart(self): return "unused"
    def add(self, c, e): pass


def app(conn, ai=None):
    s = Settings(dsn="unused", token="secret", provider="fake")
    return create_app(s, provider=ai or AI(), pcx=NoPcx(), conn=conn, today=lambda: date(2026, 10, 7))


def routes(a):
    for r in a.routes:
        if isinstance(r, APIRoute):
            for m in sorted(r.methods - {"HEAD", "OPTIONS"}):
                yield m, r


def calls(dep, out=None):
    out = set() if out is None else out
    for d in dep.dependencies:
        out.add(getattr(d.call, "__name__", ""))
        calls(d, out)
    return out


def member_session(conn, hid, roles, kind="account", name=None):
    """A signed-in member of hid with these roles → bearer headers (X-Household set)."""
    n = name or f"M{conn.execute('SELECT count(*) FROM members').fetchone()[0]}"
    mid = households.add_member(conn, hid, n, kind, roles=roles)
    uid = conn.execute("INSERT INTO users(google_sub, email, email_verified) VALUES(%s,%s,true) RETURNING id",
                       (f"sub-{n}-{hid}", f"{n.lower()}@example.com")).fetchone()[0]
    conn.execute("UPDATE members SET user_id=%s WHERE id=%s", (uid, mid))
    token, _ = auth.create_session(conn, uid, hid, "app", 30)
    return {"Authorization": f"Bearer {token}", "X-Household": str(hid)}, mid


def new_household(conn, name="Other"):
    return conn.execute("INSERT INTO households(name) VALUES(%s) RETURNING id", (name,)).fetchone()[0]


def fill(path):
    return re.sub(r"\{(\w+)(:path)?\}", lambda m: WEEK if m.group(1) == "week" else
                  "k" if m.group(1) in ("key", "task_id") else "1", path)


# --- the sweep --------------------------------------------------------------------------------------------------

def test_every_route_is_mapped_and_household_routes_are_scoped():
    a = create_app(Settings(dsn="unused", token="secret", provider="fake"), provider=object(), pcx=object(),
                   conn=object())
    seen = set()
    for method, r in routes(a):
        key = (method, r.path)
        seen.add(key)
        deps = calls(r.dependant)
        if key in rbac.PUBLIC:
            assert "principal" not in deps and "household_scope" not in deps, key
        elif key in rbac.SIGNED_IN:
            assert "principal" in deps and "household_scope" not in deps, key
        else:
            assert key in rbac.ROUTES, f"{method} {r.path} has no permission in rbac.ROUTES"
            assert "household_scope" in deps, f"{method} {r.path} isn't household-scoped"
    stale = (set(rbac.ROUTES) | rbac.PUBLIC | rbac.SIGNED_IN) - seen
    assert not stale, f"rbac lists routes that don't exist: {stale}"


def test_every_household_route_refuses_the_unauthenticated_and_the_unpermitted(conn):
    """Each household route: 401 without credentials; 403 for a signed-in member without the permission (a member
    with no roles, or a Contributor for planner/purchaser routes)."""
    a = app(conn)
    c = TestClient(a)
    hid = db.first_household(conn)
    bare, _ = member_session(conn, hid, [], name="Bare")
    contrib, reader = member_session(conn, hid, ["contributor"], name="Reader")
    contributor_perms = households.permissions(conn, reader)
    checked = 0
    for method, r in routes(a):
        key = (method, r.path)
        if key in rbac.PUBLIC or key in rbac.SIGNED_IN:
            continue
        url = fill(r.path)
        assert c.request(method, url).status_code == 401, key
        need = rbac.ROUTES[key]
        if need == rbac.MEMBER:
            continue
        assert c.request(method, url, headers=bare).status_code == 403, key
        if need not in contributor_perms:
            assert c.request(method, url, headers=contrib).status_code == 403, key
        checked += 1
    assert checked > 60


def test_roles_grant_what_the_plan_says(conn):
    perms = {k: households.permissions(conn, households.add_member(conn, db.first_household(conn), k, kind, roles=[k]))
             for k, kind in (("owner", "account"), ("approver", "account"), ("planner", "account"),
                             ("contributor", "account"), ("child", "child"))}
    assert perms == {
        "owner": {"members.manage", "roles.assign", "household.settings", "recipe.company"},
        "approver": {"request.approve", "cart.build", "cart.send"},
        "planner": {"plan.edit", "recipe.edit", "fridge.edit", "rate.for_children"},
        "contributor": {"recipe.read", "plan.read", "rate.self", "request.create"},
        "child": set()}
    assert households.all_permissions(conn) == rbac.PERMISSIONS


def test_contributor_reads_and_rates_but_doesnt_plan_or_shop(conn):
    hid = db.first_household(conn)
    rid = db.save_recipe(conn, Recipe(title="Soup", source="photo", ingredients=[], steps=[]))
    eid = db.add_to_week(conn, date(2026, 10, 12), rid)
    b, _ = member_session(conn, hid, ["contributor"])
    c = TestClient(app(conn))
    assert [r["title"] for r in c.get("/recipes", headers=b).json()] == ["Soup"]
    assert c.get(f"/weeks/{WEEK}", headers=b).status_code == 200
    assert c.put(f"/plan/{eid}/rating", headers=b, json={"family": 4}).status_code == 204
    assert c.post(f"/weeks/{WEEK}/entries", headers=b, json={"recipe_id": rid}).status_code == 403
    assert c.patch(f"/recipes/{rid}", headers=b, json={"title": "X"}).status_code == 403
    assert c.post("/drafts", headers=b, json={"items": [], "weeks": [WEEK]}).status_code == 403
    assert c.post("/fridge", headers=b, json={"name": "eggs"}).status_code == 403
    assert c.post("/recipes/share", headers=b, json={"text": "x", "week": WEEK}).status_code == 403


def test_households_are_isolated(conn):
    """Rows of the first household are invisible to another household's members, whatever their roles: reads by id
    are 404, lists are empty, writes by id are 404 (deletes are idempotent no-ops), and nothing changes."""
    first = db.first_household(conn)
    rid = db.save_recipe(conn, Recipe(title="Ours", source="nyt", source_url="https://cooking.nytimes.com/recipes/1-a",
                                      ingredients=[], steps=[]))
    eid = db.add_to_week(conn, date(2026, 10, 12), rid)
    db.set_rating(conn, eid, 5)
    sid = conn.execute("INSERT INTO staples(name) VALUES('milk') RETURNING id").fetchone()[0]
    fid = conn.execute("INSERT INTO fridge_items(name, key, key_source) VALUES('eggs','egg','rule') RETURNING id"
                       ).fetchone()[0]
    xid = conn.execute("INSERT INTO extras(week, term, key, product_code, quantity) VALUES(%s,'x','x','P',1) "
                       "RETURNING id", (WEEK,)).fetchone()[0]
    did = conn.execute("INSERT INTO carts(status, weeks) VALUES('ready', %s) RETURNING id", ([date(2026, 10, 12)],)
                       ).fetchone()[0]
    lid = conn.execute("INSERT INTO cart_lines(cart_id, item_key, source, status) VALUES(%s,'k','ai','added') "
                       "RETURNING id", (did,)).fetchone()[0]
    jid = conn.execute("INSERT INTO import_jobs(file_sha, choices_sha, report) VALUES('a','b','{}') RETURNING id"
                       ).fetchone()[0]
    rdid = conn.execute("INSERT INTO fridge_reads(photos_sha, photo_count, status) VALUES('s',1,'ready') RETURNING id"
                        ).fetchone()[0]
    conn.execute("INSERT INTO picks(key, product_code, chosen_by) VALUES('k','P','user')")

    other = new_household(conn)
    b, _ = member_session(conn, other, ALL_ROLES)
    c = TestClient(app(conn))
    for url in (f"/recipes/{rid}", f"/recipes/{rid}/export", f"/drafts/{did}",
                f"/imports/{jid}", f"/fridge/reads/{rdid}", f"/weeks/{WEEK}/draft"):
        assert c.get(url, headers=b).status_code == 404, url
    for method, url, body in (("PATCH", f"/recipes/{rid}", {"title": "Theirs"}),
                              ("PUT", f"/plan/{eid}/rating", {"family": 1}), ("DELETE", f"/plan/{eid}/rating", None),
                              ("PATCH", f"/staples/{sid}", {"name": "x"}), ("PATCH", f"/fridge/{fid}", {"qty": 1}),
                              ("PATCH", f"/extras/{xid}", {"quantity": 3}),
                              ("PATCH", f"/drafts/{did}/lines/{lid}", {"removed": True}),
                              ("POST", f"/drafts/{did}/send", None), ("POST", f"/weeks/{WEEK}/entries", {"recipe_id": rid}),
                              ("POST", f"/fridge/reads/{rdid}/dismiss", None)):
        assert c.request(method, url, headers=b, json=body).status_code == 404, (method, url)
    for method, url in (("DELETE", f"/plan/{eid}"), ("DELETE", f"/staples/{sid}"), ("DELETE", f"/fridge/{fid}"),
                        ("DELETE", f"/extras/{xid}"), ("PATCH", f"/plan/{eid}")):
        assert c.request(method, url, headers=b, json={"day": 1} if method == "PATCH" else None).status_code == 204
    assert c.get("/recipes", headers=b).json() == []
    assert c.get(f"/weeks/{WEEK}", headers=b).json() == []
    assert c.get("/staples", headers=b).json() == [] and c.get("/fridge", headers=b).json() == []
    assert c.get("/extras", headers=b, params={"week": WEEK}).json() == []
    assert c.get("/recipes/sources", headers=b).json() == []
    assert c.get("/fridge/reads", headers=b).json() == {"read": None}
    # the same NYT link can be in both libraries (per-household unique)
    with db.unscoped(conn):
        db.scope(conn, other)
        db.save_recipe(conn, Recipe(title="Theirs", source="nyt", source_url="https://cooking.nytimes.com/recipes/1-a",
                                    ingredients=[], steps=[]))
        conn.execute("INSERT INTO picks(key, product_code, chosen_by) VALUES('k','Q','user')")
    # nothing of the first household changed
    db.scope(conn, first)
    r = db.get_recipe(conn, rid)
    assert r.title == "Ours" and [e.rating.family for e in db.get_week(conn, date(2026, 10, 12))] == [5]
    assert conn.execute("SELECT name FROM staples").fetchall() == [("milk",)]
    assert conn.execute("SELECT count(*) FROM fridge_items").fetchone()[0] == 1
    assert conn.execute("SELECT quantity FROM extras").fetchone()[0] == 1
    assert conn.execute("SELECT removed FROM cart_lines").fetchone()[0] is False
    assert db.get_pick(conn, "k") == "P"
    with db.unscoped(conn):
        assert conn.execute("SELECT count(*) FROM recipes").fetchone()[0] == 2


def test_a_scoped_connection_cant_write_into_another_household(conn):
    other = new_household(conn)
    with pytest.raises(psycopg.errors.InsufficientPrivilege):   # row-level security's WITH CHECK
        conn.execute("INSERT INTO staples(household_id, name) VALUES(%s, 'sneaky')", (other,))
    with pytest.raises(psycopg.Error):
        with db.unscoped(conn):
            conn.execute("RESET ROLE")
            conn.execute("SELECT set_config('mealprep.household_id', '', false)")
            conn.execute("INSERT INTO staples(name) VALUES('no household')")   # no default without a scope


def test_unscoped_app_role_sees_nothing(conn):
    conn.execute("INSERT INTO staples(name) VALUES('milk')")
    conn.execute("SELECT set_config('mealprep.household_id', '', false)")
    assert conn.execute("SELECT count(*) FROM staples").fetchone()[0] == 0


# --- the rules ------------------------------------------------------------------------------------------------------

def test_child_role_is_exclusive_and_only_for_child_profiles(conn):
    hid = db.first_household(conn)
    households.add_member(conn, hid, "Owner", "account", roles=ALL_ROLES)
    kid = households.add_member(conn, hid, "Kid", "child", birth_year=2016)
    assert households.member_roles(conn, kid) == ["child"]
    with pytest.raises(households.RuleError):
        households.set_roles(conn, hid, kid, ["child", "contributor"])
    with pytest.raises(households.RuleError):
        households.set_roles(conn, hid, kid, ["contributor"])
    teen = households.add_member(conn, hid, "Teen", "account")
    with pytest.raises(households.RuleError):
        households.set_roles(conn, hid, teen, ["child"])
    with pytest.raises(households.RuleError):
        households.add_member(conn, hid, "Kid2", "child", roles=["planner"])
    with pytest.raises(households.RuleError):
        households.add_member(conn, hid, "Kid3", "child", email="k@example.com")
    child_role = households.role_ids(conn)["child"][0]
    with pytest.raises(psycopg.errors.CheckViolation):          # the trigger, for SQL that bypasses the code
        conn.execute("INSERT INTO member_roles VALUES(%s,%s)", (teen, child_role))
    with pytest.raises(psycopg.errors.CheckViolation):
        conn.execute("INSERT INTO member_roles VALUES(%s,%s)", (kid, households.role_ids(conn)["planner"][0]))


def test_last_owner_and_last_approver_stay(conn):
    hid = db.first_household(conn)
    a = households.add_member(conn, hid, "A", "account", roles=["owner", "approver"])
    b = households.add_member(conn, hid, "B", "account", roles=["owner"])
    with pytest.raises(households.RuleError, match="Approver"):
        households.set_roles(conn, hid, a, ["owner"])
    households.set_roles(conn, hid, a, ["approver"])                      # B is still an Owner
    with pytest.raises(households.RuleError, match="Owner"):
        households.set_roles(conn, hid, b, ["contributor"])
    with pytest.raises(households.RuleError, match="Owner"):
        households.remove_member(conn, hid, b)
    with pytest.raises(households.RuleError, match="Approver"):
        households.remove_member(conn, hid, a)
    assert households.member_roles(conn, a) == ["approver"] and households.member_roles(conn, b) == ["owner"]
    households.add_member(conn, hid, "C", "account", roles=["owner", "approver"])
    households.remove_member(conn, hid, a)
    households.set_roles(conn, hid, b, ["contributor"])
    assert {m["name"] for m in households.list_members(conn, hid)} == {"B", "C"}
    assert conn.execute("SELECT count(*) FROM audit_log WHERE household_id=%s AND action LIKE 'roles.%%'",
                        (hid,)).fetchone()[0] >= 2


def test_member_and_role_endpoints(conn):
    hid = db.first_household(conn)
    b, me = member_session(conn, hid, ALL_ROLES, name="Head")
    c = TestClient(app(conn))
    kid = c.post("/household/members", headers=b, json={"name": "Kid", "kind": "child", "birth_year": 2016})
    assert kid.status_code == 201 and kid.json()["roles"] == ["child"] and not kid.json()["signed_in"]
    teen = c.post("/household/members", headers=b, json={"name": "Teen", "kind": "account", "email": "T@x.example"})
    assert teen.json()["roles"] == ["contributor"] and teen.json()["email_set"]
    assert "email" not in teen.json()
    tid = teen.json()["id"]
    assert c.put(f"/household/members/{tid}/roles", headers=b, json={"roles": ["planner", "contributor"]}).json() \
        == {"roles": ["planner", "contributor"]}
    assert c.put(f"/household/members/{tid}/roles", headers=b, json={"roles": ["child"]}).status_code == 409
    assert c.put(f"/household/members/{me}/roles", headers=b, json={"roles": ["contributor"]}).status_code == 409
    assert c.patch(f"/household/members/{tid}", headers=b, json={"name": "Teenager", "birth_year": 2013}).json()["name"] \
        == "Teenager"
    assert c.patch("/household", headers=b, json={"name": "Renamed"}).json()["name"] == "Renamed"
    assert c.get("/household", headers=b).json()["store_id"] == "1092"
    assert [m["name"] for m in c.get("/household/members", headers=b).json()] == ["Head", "Kid", "Teenager"]
    other = new_household(conn)
    ob, om = member_session(conn, other, ALL_ROLES, name="Else")
    assert c.put(f"/household/members/{om}/roles", headers=b, json={"roles": ["contributor"]}).status_code == 404
    assert c.delete(f"/household/members/{om}", headers=b).status_code == 404
    assert c.delete(f"/household/members/{tid}", headers=b).status_code == 204
    # without roles.assign: a member can be added (default role only), roles can't be changed
    households.set_roles(conn, hid, households.add_member(conn, hid, "Co", "account", roles=["owner", "approver"]),
                         ["owner", "approver"])
    conn.execute("DELETE FROM role_permissions WHERE permission='roles.assign'")
    try:
        assert c.post("/household/members", headers=b, json={"name": "P", "kind": "account",
                                                             "roles": ["planner"]}).status_code == 403
        assert c.post("/household/members", headers=b, json={"name": "P", "kind": "account"}).status_code == 201
        assert c.put(f"/household/members/{me}/roles", headers=b, json={"roles": ALL_ROLES}).status_code == 403
    finally:
        conn.execute("INSERT INTO role_permissions SELECT id, 'roles.assign' FROM roles WHERE key='owner'")


# --- AI metering ------------------------------------------------------------------------------------------------------

def test_ai_calls_are_metered_per_household_with_a_daily_budget(conn):
    from contextlib import nullcontext
    hid = db.first_household(conn)
    meter = Meter(lambda: nullcontext(conn), "fake")

    class Usage:
        input_tokens, output_tokens = 120, 30

    class P:
        model, last_usage = "m-1", Usage()

        def complete_json(self, prompt, images=None):
            if prompt == "boom":
                raise AIError("down")
            return {"ok": True}

    m = Metered(P(), meter, hid, None, "share")
    assert m.complete_json("hi") == {"ok": True} and m.model == "m-1"
    with pytest.raises(AIError):
        m.complete_json("boom")
    rows = conn.execute("SELECT feature, provider, model, input_tokens, output_tokens, ok FROM ai_usage "
                        "ORDER BY id").fetchall()
    assert rows == [("share", "fake", "m-1", 120, 30, True), ("share", "fake", "m-1", None, None, False)]
    conn.execute("UPDATE households SET ai_daily_budget=2 WHERE id=%s", (hid,))
    with pytest.raises(AIError, match="allowance"):
        m.complete_json("hi")
    assert conn.execute("SELECT count(*) FROM ai_usage").fetchone()[0] == 2     # a refused call isn't counted


def test_routes_meter_their_ai_calls(conn):
    hid = db.first_household(conn)
    rid = db.save_recipe(conn, Recipe(title="Soup", source="photo", ingredients=[], steps=[]))
    ai = AI()
    c = TestClient(app(conn, ai))
    r = c.patch(f"/recipes/{rid}", headers=H, json={"ingredients": ["2 eggs"]})
    assert r.status_code == 200 and ai.n == 1
    assert conn.execute("SELECT household_id, feature, ok FROM ai_usage").fetchall() == [(hid, "edit", True)]
    usage = c.get("/household/ai-usage", headers=H).json()
    assert usage["budget"] is None and usage["days"][0]["feature"] == "edit" and usage["days"][0]["calls"] == 1



def test_retired_prep_routes_answer_410_and_call_no_ai(conn):
    """Sunday prep + cook cards were retired 2026-10-31: every old route says so (an older app shows it), nothing is
    written and no AI is asked; rows from before stay in the tables."""
    db.add_to_week(conn, date(2026, 10, 12), db.save_recipe(conn, Recipe(title="Soup", source="photo", ingredients=[], steps=[])))
    pid = conn.execute("INSERT INTO prep_plans(weeks, status, input) VALUES(%s,'ready','{}') RETURNING id",
                       ([date(2026, 10, 12)],)).fetchone()[0]
    ai = AI()
    c = TestClient(app(conn, ai))
    for method, url, body in (("POST", "/prep-plans", {"weeks": [WEEK]}), ("GET", f"/prep-plans/{pid}", None),
                              ("GET", f"/weeks/{WEEK}/prep-plan", None),
                              ("PATCH", f"/prep-plans/{pid}/tasks/t1", {"done": True}), ("GET", "/plan/1/card", None)):
        r = c.request(method, url, headers=H, json=body)
        assert r.status_code == 410 and "retired" in r.json()["detail"], (method, url)
    assert ai.n == 0 and conn.execute("SELECT count(*) FROM ai_usage").fetchone()[0] == 0
    assert conn.execute("SELECT count(*) FROM prep_plans").fetchone()[0] == 1

# --- seeding -------------------------------------------------------------------------------------------------------------

def test_seed_sets_up_and_resyncs_the_first_household(conn, monkeypatch, tmp_path):
    monkeypatch.setenv("MEALPREP_SEED_TEEN_EMAIL", "Teen@Example.com")
    spec = {"household": "Testers", "members": [
        {"name": "Parent A", "kind": "account", "roles": ALL_ROLES, "legacy_token": True},
        {"name": "Parent B", "kind": "account", "roles": ["owner", "planner", "contributor"], "email": "b@example.com"},
        {"name": "Teen", "kind": "account", "roles": ["contributor"], "birth_year": 2013,
         "email_env": "MEALPREP_SEED_TEEN_EMAIL"},
        {"name": "Kid", "kind": "child", "birth_year": 2016}]}
    hid = db.first_household(conn)
    out = seed.seed(conn, spec)
    assert [(m["name"], m["roles"], m["email_set"]) for m in out] == [
        ("Parent A", ALL_ROLES, False), ("Parent B", ["owner", "planner", "contributor"], True),
        ("Teen", ["contributor"], True), ("Kid", ["child"], False)]
    assert households.get_household(conn, hid)["name"] == "Testers"
    assert conn.execute("SELECT email FROM members WHERE display_name='Teen'").fetchone()[0] == "teen@example.com"
    assert households.legacy_member(conn, hid) == out[0]["id"]
    again = seed.seed(conn, spec)                                     # idempotent
    assert [m["id"] for m in again] == [m["id"] for m in out] and len(households.list_members(conn, hid)) == 4
    with pytest.raises(ValueError, match="Approver"):
        seed.seed(conn, {"members": [{"name": "Parent A", "kind": "account", "roles": ["owner"]}]})
    assert households.member_roles(conn, out[0]["id"]) == ALL_ROLES       # the failed run changed nothing
    with pytest.raises(households.RuleError):
        seed.seed(conn, {"members": [{"name": "Kid", "kind": "child", "roles": ["contributor"]}]})
    # the shared token now acts as Parent A
    me = TestClient(app(conn)).get("/auth/me", headers=H).json()
    assert me["member_id"] == out[0]["id"] and me["household"]["name"] == "Testers"
    f = tmp_path / "h.json"
    f.write_text(json.dumps(spec))
    assert seed.main(["seed"]) == 2
