import time
from test_api import H, client
from test_prepplan import FakeAI, plan_for, two_meals


def wait(c, pid, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        p = c.get(f"/prep-plans/{pid}", headers=H).json()
        if p["status"] != "building":
            return p
        time.sleep(0.05)
    raise AssertionError("prep plan still building")


def test_prep_endpoints_need_auth(conn):
    c = client(conn)
    assert c.post("/prep-plans", json={"weeks": ["2026-10-11"]}).status_code == 401
    assert c.get("/prep-plans/1").status_code == 401
    assert c.get("/weeks/2026-10-11/prep-plan").status_code == 401
    assert c.patch("/prep-plans/1/tasks/knife-1", json={"done": True}).status_code == 401
    assert c.get("/plan/1/card").status_code == 401


def test_prep_plan_flow(conn):
    chili, soup = two_meals(conn)
    c = client(conn, ai=FakeAI(plan_for(chili, soup)))
    r = c.post("/prep-plans", headers=H, json={"weeks": ["2026-10-14"]})
    assert r.status_code == 202 and r.json()["status"] == "building"
    pid = r.json()["id"]
    p = wait(c, pid)
    assert p["status"] == "ready" and p["weeks"] == ["2026-10-11"] and p["progress"] == {"done": 3, "total": 3}
    assert c.get("/weeks/2026-10-13/prep-plan", headers=H).json()["id"] == pid
    r = c.patch(f"/prep-plans/{pid}/tasks/knife-1", headers=H, json={"done": True})
    assert r.status_code == 200 and r.json()["done"] is True
    assert c.patch(f"/prep-plans/{pid}/tasks/nope", headers=H, json={"done": True}).status_code == 404
    assert c.patch(f"/prep-plans/{pid}/tasks/knife-1", headers=H, json={"done": "yes"}).status_code == 422
    card = c.get(f"/plan/{chili}/card", headers=H).json()
    assert card["title"] == "Chili" and card["kit"] == ["2 diced onions"] and card["day_of"] == ["Boil the rice"]


def test_prep_plan_errors(conn):
    c = client(conn, ai=FakeAI({}, fail_plan=True))
    r = c.post("/prep-plans", headers=H, json={"weeks": ["2026-10-11"]})
    assert r.status_code == 422 and "no recipes planned" in r.json()["detail"]
    assert c.post("/prep-plans", headers=H, json={"weeks": []}).status_code == 422
    assert c.get("/prep-plans/999", headers=H).status_code == 404
    assert c.get("/weeks/2026-10-11/prep-plan", headers=H).status_code == 404
    assert c.get("/plan/999/card", headers=H).status_code == 404
    assert c.patch("/prep-plans/999/tasks/knife-1", headers=H, json={"done": True}).status_code == 404
    two_meals(conn)
    pid = c.post("/prep-plans", headers=H, json={"weeks": ["2026-10-11"]}).json()["id"]
    p = wait(c, pid)
    assert p["status"] == "failed" and p["error"] == "prep plan: AI call failed: boom"
    assert c.patch(f"/prep-plans/{pid}/tasks/knife-1", headers=H, json={"done": True}).status_code == 409
    w = c.get("/weeks/2026-10-11/prep-plan", headers=H).json()
    assert w["id"] == pid and w["last_ready_id"] is None
