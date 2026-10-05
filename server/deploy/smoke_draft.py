"""Live draft-flow smoke test — run ON the server:

    cd ~/meal-prep/server && set -a && . ../.env && set +a && .venv/bin/python deploy/smoke_draft.py 2026-10-11

List for the week (every line ticked) → POST /drafts → poll to ready (timed) → swap one line to an alternative →
remove one line → send (twice: idempotent) → read the anonymous PC Express cart back and compare entry counts.
Creates one anonymous PC Express cart and records the week as carted. No login, no checkout."""
import json, os, sys, time
import httpx
from mealprep.pcx import Pcx

U = os.environ.get("MEALPREP_URL", "http://127.0.0.1:8790")
week = sys.argv[1] if len(sys.argv) > 1 else "2026-10-11"
c = httpx.Client(base_url=U, headers={"Authorization": f"Bearer {os.environ['MEALPREP_TOKEN']}"}, timeout=60)


def ok(r):
    r.raise_for_status()
    return r.json()


items = ok(c.post("/list", json={"weeks": [week]}))
for it in items:
    it["needed"] = True
t0 = time.monotonic()
r = c.post("/drafts", json={"weeks": [week], "items": items})
created = ok(r)
post_secs = time.monotonic() - t0
did = created["id"]
while True:
    d = ok(c.get(f"/drafts/{did}"))
    if d["status"] != "building":
        break
    time.sleep(1)
build_secs = time.monotonic() - t0
assert d["status"] == "ready", d
lines = d["lines"]
matched = [l for l in lines if l["product"]]
swap = next(l for l in matched if l["alternatives"])
alt = swap["alternatives"][0]
swapped = ok(c.patch(f"/drafts/{did}/lines/{swap['id']}", json={"product_code": alt["code"]}))
assert swapped["product"]["code"] == alt["code"] and swapped["source"] == "user"
drop = next(l for l in reversed(matched) if l["id"] != swap["id"])
assert ok(c.patch(f"/drafts/{did}/lines/{drop['id']}", json={"removed": True}))["removed"] is True
d = ok(c.get(f"/drafts/{did}"))
expected = {}
for l in d["lines"]:
    if l["product"] and not l["removed"] and l["quantity"]:
        expected[l["product"]["code"]] = expected.get(l["product"]["code"], 0) + l["quantity"]
cart_id = ok(c.post(f"/drafts/{did}/send"))["pcx_cart_id"]
again = ok(c.post(f"/drafts/{did}/send"))["pcx_cart_id"]
entries = [e for o in Pcx().get_cart(cart_id)["orders"] for e in o["entries"]]
got = {e["offer"]["id"]: int(e["quantity"]) for e in entries}
weeks = ok(c.get("/weeks", params={"from": week, "count": 1}))
out = {
    "draft_id": did, "post_status": r.status_code, "post_secs": round(post_secs, 2), "build_secs": round(build_secs, 1),
    "lines": len(lines), "matched": len(matched), "sources": {s: sum(l["source"] == s for l in lines) for s in ("memory", "ai", "none")},
    "swapped": {"item": swap["name"], "from": swap["product"]["code"], "to": alt["code"]},
    "removed": {"item": drop["name"], "code": drop["product"]["code"]},
    "estimated_total": d["estimated_total"], "pcx_cart_id": cart_id, "second_send_same": again == cart_id,
    "expected_entries": len(expected), "pcx_entries": len(entries), "codes_and_qty_match": got == expected,
    "week": weeks[0],
}
print(json.dumps(out, indent=2))
