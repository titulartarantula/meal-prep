"""Live prep-plan smoke — run ON the server:

    cd ~/meal-prep/server && set -a && . ../.env && set +a && .venv/bin/python deploy/smoke_prep.py 2026-11-01 3:2 1:4

Adds each recipe_id:day to an EMPTY week, POST /prep-plans, polls to ready (timed), prints the plan and every cook
card, ticks one task on and off, then deletes the entries it added and the prep plan (+ its task events; cards
cascade). --keep leaves them. Creates no ratings."""
import json, os, sys, time
import httpx, psycopg

args = [a for a in sys.argv[1:] if not a.startswith("--")]
week, specs = args[0], args[1:]
U = os.environ.get("MEALPREP_URL", "http://127.0.0.1:8790")
c = httpx.Client(base_url=U, headers={"Authorization": f"Bearer {os.environ['MEALPREP_TOKEN']}"}, timeout=60)


def ok(r):
    r.raise_for_status()
    return r.json()


def show(x):
    print(json.dumps(x, indent=1, ensure_ascii=False))


assert not ok(c.get(f"/weeks/{week}")), f"week {week} already has entries — use an empty test week"
added, pid = [], None
try:
    for spec in specs:
        rid, day = map(int, spec.split(":"))
        e = ok(c.post(f"/weeks/{week}/entries", json={"recipe_id": rid}))
        added.append(e["id"])
        c.patch(f"/plan/{e['id']}", json={"day": day}).raise_for_status()
    t0 = time.monotonic()
    pid = ok(c.post("/prep-plans", json={"weeks": [week]}))["id"]
    while (p := ok(c.get(f"/prep-plans/{pid}")))["status"] == "building":
        time.sleep(2)
    print(f"prep plan {pid}: {p['status']} in {time.monotonic() - t0:.1f} s wall "
          f"(server {p['generation_seconds']} s), progress {p['progress']}")
    if p["status"] != "ready":
        sys.exit(f"FAILED: {p['error']}")
    show({k: p[k] for k in ("total_minutes", "warnings", "checklist", "sections")})
    for eid in added:
        show(ok(c.get(f"/plan/{eid}/card")))
    first = next(t for s in p["sections"] for t in s["tasks"])
    assert ok(c.patch(f"/prep-plans/{pid}/tasks/{first['id']}", json={"done": True}))["done"] is True
    assert ok(c.patch(f"/prep-plans/{pid}/tasks/{first['id']}", json={"done": False}))["done"] is False
    assert ok(c.get(f"/weeks/{week}/prep-plan"))["id"] == pid
    print("checklist PATCH + week lookup OK")
finally:
    if "--keep" not in sys.argv:
        for eid in added:
            c.delete(f"/plan/{eid}")
        if pid:
            with psycopg.connect(os.environ["MEALPREP_DSN"], autocommit=True) as db:
                db.execute("DELETE FROM prep_task_events WHERE prep_plan_id=%s", (pid,))
                db.execute("DELETE FROM prep_plans WHERE id=%s", (pid,))
        print(f"cleaned up: entries {added}, prep plan {pid}")
