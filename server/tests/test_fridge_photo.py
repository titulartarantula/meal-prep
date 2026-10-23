"""Fridge photos (0.14.0): a background read proposes changes; nothing saves until the review is applied."""
import os, tempfile, time
from datetime import date, timedelta

import pytest

from mealprep import fridge
from mealprep.ai.base import AIError
from mealprep.importers import fridge_photo
from test_api import H, client
from test_fridge import MapAI
from test_fridge_match import F

TODAY = date(2026, 10, 7)
JPEG = b"\xff\xd8\xff\xe0" + b"invented test photo" * 10
PNG = b"\x89PNG\r\n\x1a\n" + b"invented" * 10


class PhotoAI:
    """The photo read answers `answer` (a dict, or an exception to raise; a list = one per call); the name mapping
    (no images) answers `match`."""
    def __init__(self, answer, match=None, delay=0.0):
        self.answers = answer if isinstance(answer, list) else [answer]
        self.match, self.delay, self.reads, self.maps, self.paths = match, delay, 0, 0, []

    def complete_json(self, prompt, images=None):
        if images:
            self.paths.append([(p, os.path.exists(p)) for p in images])
            a = self.answers[min(self.reads, len(self.answers) - 1)]
            self.reads += 1
            time.sleep(self.delay)
            if isinstance(a, Exception):
                raise a
            return a
        self.maps += 1
        return {"matches": [{"name": n, "match": m} for n, m in (self.match or {}).items()]}


# --- the read's coercion (pure) ---------------------------------------------------------------------------------

def test_read_coerces_the_answer():
    items = [F(1, "Eggs", 6), F(2, "Milk", 1, "l")]
    ai = PhotoAI({"seen": [{"id": 1, "estimate": {"qty": "about 4", "unit": None}, "confidence": "HIGH", "where": "door"},
                           {"id": 1, "estimate": None, "confidence": "low"},          # a repeat: the first wins
                           {"id": 99, "estimate": None},                              # not on the list: dropped
                           {"id": "2", "estimate": {"qty": 20000, "unit": "furlongs"}, "confidence": "??"}],
                  "new": [{"name": "  Baby spinach ", "estimate": {"qty": 1, "unit": "bags"}, "confidence": "medium",
                           "where": "crisper drawer" * 10},
                          {"name": "baby spinach", "estimate": None},                   # same food again
                          {"name": "", "estimate": None}, {"estimate": {"qty": 1}}, "junk"]})
    out = fridge_photo.read(ai, ["/x/p1.jpg"], items, ["egg", "spinach"])
    assert out["seen"] == [{"id": 1, "estimate": {"qty": 4.0, "unit": None}, "confidence": "high", "where": "door"},
                           {"id": 2, "estimate": {"qty": 10000.0, "unit": None}, "confidence": "medium", "where": None}]
    assert out["new"] == [{"name": "Baby spinach", "estimate": {"qty": 1.0, "unit": "bag"}, "confidence": "medium",
                           "where": ("crisper drawer" * 10)[:40]}]


def test_read_retries_bad_json_once_then_fails():
    ai = PhotoAI([AIError("no JSON in model output"), {"seen": [], "new": [{"name": "kale", "estimate": None}]}])
    assert fridge_photo.read(ai, ["/x/p.jpg"], [], [])["new"][0]["name"] == "Kale"
    ai = PhotoAI([["not", "an", "object"], AIError("no JSON")])
    with pytest.raises(fridge_photo.ReadFailed) as e:
        fridge_photo.read(ai, ["/x/p.jpg"], [], [])
    assert str(e.value) == "Couldn't read the fridge in those photos." and ai.reads == 2


def test_not_a_fridge_fails():
    with pytest.raises(fridge_photo.ReadFailed):
        fridge_photo.read(PhotoAI({"seen": [], "new": [], "not_a_fridge": True}), ["/x/p.jpg"], [], [])


def test_the_prompt_carries_the_list_and_the_vocabulary():
    seen = {}

    class Spy(PhotoAI):
        def complete_json(self, prompt, images=None):
            seen["prompt"] = prompt
            return {"seen": [], "new": []}
    fridge_photo.read(Spy(None), ["/x/p.jpg"], [F(7, "Eggs", 6)], ["green onion"])
    assert '"id": 7' in seen["prompt"] and "Eggs" in seen["prompt"] and "green onion" in seen["prompt"]


# --- proposals (pure) -----------------------------------------------------------------------------------------

def P(items, seen=(), new=(), mapped=None, today=TODAY):
    mapped = mapped or [(fridge.rule_key(n["name"]), "rule") for n in new]
    return fridge.proposals(items, {"seen": list(seen), "new": list(new)}, mapped, today)


def S(iid, qty=None, unit=None, confidence="high"):
    return {"id": iid, "estimate": None if qty is None else {"qty": qty, "unit": unit}, "confidence": confidence,
            "where": None}


def kinds(props):
    return [(p["kind"], p.get("item_id"), p["default_on"]) for p in props]


def old(days=10):
    return (TODAY - timedelta(days=days)).isoformat()


def test_classification():
    items = [F(1, "Eggs", 6, amount_as_of=old()), F(2, "Milk", 1, "l", amount_as_of=old()),
             F(3, "Cilantro", 1, "bunch", amount_as_of=old()), F(4, "Yogurt", None, amount_as_of=old()),
             F(5, "Butter", 454, "g", amount_as_of=old())]
    props = P(items, seen=[S(1, 4), S(2, 0.9, "l"), S(3), S(4, 2, "container"), S(5, 2, "stick")])
    assert kinds(props) == [("amount", 1, True), ("seen", 2, True), ("seen", 3, True), ("amount", 4, True),
                            ("seen", 5, True)]
    assert props[0]["current"]["qty"] == 6 and props[0]["estimate"] == {"qty": 4, "unit": None}
    assert len({p["pid"] for p in props}) == len(props)


def test_milk_off_by_more_than_15_percent_is_an_amount():
    items = [F(2, "Milk", 1, "l", amount_as_of=old())]
    assert kinds(P(items, seen=[S(2, 0.8, "l")])) == [("amount", 2, True)]
    assert kinds(P(items, seen=[S(2, 4, "cup")])) == [("seen", 2, True)]      # 0.95 l


def test_not_seen_and_low_confidence_start_off():
    items = [F(1, "Eggs", 6, amount_as_of=old()), F(2, "Cilantro", 1, "bunch", amount_as_of=old())]
    props = P(items, seen=[S(1, 2, confidence="low")], new=[{"name": "Kale", "estimate": None, "confidence": "low",
                                                              "where": None}])
    assert kinds(props) == [("amount", 1, False), ("new", None, False), ("not_seen", 2, False)]


def test_an_amount_typed_in_the_last_two_days_starts_off():
    items = [F(1, "Eggs", 6, amount_as_of=(TODAY - timedelta(days=2)).isoformat()),
             F(2, "Kale", 2, "bunch", amount_as_of=(TODAY - timedelta(days=1)).isoformat(), qty_estimated=True)]
    assert kinds(P(items, seen=[S(1, 3), S(2, 1, "bunch")])) == [("amount", 1, False), ("amount", 2, True)]


def test_a_new_name_for_an_item_on_the_list_folds_into_it():
    items = [F(1, "Eggs", 6, amount_as_of=old()), F(2, "Scallions", 1, "bunch", key="scallion", amount_as_of=old())]
    new = [{"name": "Large eggs", "estimate": {"qty": 3, "unit": None}, "confidence": "high", "where": "door"},
           {"name": "Green onions", "estimate": None, "confidence": "medium", "where": None},
           {"name": "Spinach", "estimate": {"qty": 1, "unit": "bag"}, "confidence": "high", "where": None}]
    props = P(items, new=new, mapped=[("egg", "list"), ("scallion", "ai"), ("spinach", "rule")])
    assert kinds(props) == [("amount", 1, True), ("seen", 2, True), ("new", None, True)]
    assert props[2]["key"] == "spinach" and props[2]["name"] == "Spinach"
    props = P(items, seen=[S(1)], new=new[:1], mapped=[("egg", "list")])     # already seen: no second proposal
    assert kinds(props) == [("seen", 1, True), ("not_seen", 2, False)]


def test_a_read_that_sees_nothing_only_offers_removals_switched_off():
    items = [F(1, "Eggs", 6), F(2, "Milk", 1, "l")]
    assert kinds(P(items)) == [("not_seen", 1, False), ("not_seen", 2, False)]


# --- the job, apply, dismiss (DB + API) ---------------------------------------------------------------------------

READ = {"seen": [{"id": 1, "estimate": {"qty": 4, "unit": None}, "confidence": "high", "where": "door"},
                 {"id": 2, "estimate": None, "confidence": "high", "where": "top shelf"}],
        "new": [{"name": "Spinach", "estimate": {"qty": 1, "unit": "bag"}, "confidence": "medium", "where": "drawer"},
                {"name": "Spring onions", "estimate": None, "confidence": "low", "where": "door"}]}


@pytest.fixture
def tmpdir_root(tmp_path, monkeypatch):
    monkeypatch.setattr(tempfile, "tempdir", str(tmp_path))
    return tmp_path


def setup(conn, today=TODAY - timedelta(days=10)):
    c0 = client(conn, today=today, ai=MapAI())
    ids = [c0.post("/fridge", headers=H, json=b).json()["id"] for b in (
        {"name": "Eggs", "qty": 6}, {"name": "Milk", "qty": 1, "unit": "l"}, {"name": "Cilantro", "qty": 1, "unit": "bunch"})]
    assert ids == [1, 2, 3]


def upload(c, *photos):
    return c.post("/fridge/photos", headers=H, files=[("files", (f"p{i}.jpg", p, "image/jpeg")) for i, p in enumerate(photos)])


def wait(c, rid, status=("ready", "failed")):
    for _ in range(100):
        r = c.get(f"/fridge/reads/{rid}", headers=H).json()
        if r["status"] in status:
            return r
        time.sleep(0.05)
    raise AssertionError(r)


def test_upload_reads_in_the_background_and_deletes_the_photos(conn, tmpdir_root):
    setup(conn)
    ai = PhotoAI(READ, match={"Spring onions": None}, delay=0.2)
    c = client(conn, ai=ai)
    r = upload(c, JPEG, PNG)
    assert r.status_code == 202
    body = r.json()
    assert (body["status"], body["existing"], body["photo_count"]) == ("reading", False, 2)
    assert body["progress"] == "Reading 2 photos…"
    assert c.get("/fridge/reads?open=true", headers=H).json()["read"]["id"] == body["id"]
    done = wait(c, body["id"])
    assert done["status"] == "ready" and done["progress"] is None
    assert [(p["kind"], p["item_id"], p["default_on"]) for p in done["proposals"]] == [
        ("amount", 1, True), ("seen", 2, True), ("new", None, True), ("new", None, False), ("not_seen", 3, False)]
    assert done["counts"] == {"new": 2, "amount": 1, "seen": 1, "not_seen": 1}
    assert [os.path.basename(p) for p, _ in ai.paths[0]] == ["photo01.jpg", "photo02.png"]
    assert all(existed for _, existed in ai.paths[0])
    assert list(tmpdir_root.iterdir()) == []                       # photos gone after the read
    assert [i["qty"] for i in c.get("/fridge", headers=H).json()] == [1, 6, 1]   # nothing saved yet
    assert c.get("/fridge/reads?open=true", headers=H).json()["read"]["id"] == body["id"]


def test_a_resend_of_the_same_photos_returns_the_same_read(conn, tmpdir_root):
    setup(conn)
    ai = PhotoAI(READ)
    c = client(conn, ai=ai)
    first = upload(c, JPEG).json()
    again = upload(c, JPEG).json()
    assert again["id"] == first["id"] and again["existing"] is True
    wait(c, first["id"])
    assert upload(c, JPEG).json()["id"] == first["id"]
    assert upload(c, JPEG, PNG).json()["id"] != first["id"]      # other photos: a new read
    assert ai.reads in (1, 2)


def test_failure_is_readable_and_photos_are_deleted(conn, tmpdir_root):
    setup(conn)
    c = client(conn, ai=PhotoAI([AIError("no JSON"), AIError("no JSON")]))
    rid = upload(c, JPEG).json()["id"]
    r = wait(c, rid)
    assert (r["status"], r["error"]) == ("failed", "Couldn't read the fridge in those photos.")
    assert list(tmpdir_root.iterdir()) == []
    assert c.get("/fridge/reads?open=true", headers=H).json()["read"] is None
    again = upload(c, JPEG).json()                                   # Try again re-sends: a new read
    assert again["id"] != rid and again["existing"] is False


def test_bad_uploads(conn):
    c = client(conn, ai=PhotoAI(READ))
    assert c.post("/fridge/photos", headers=H, files=[]).status_code == 422
    assert upload(c, *[JPEG] * 7).status_code == 422
    assert upload(c, b"").status_code == 422
    assert upload(c, b"%PDF-1.4 not a photo").status_code == 422
    assert upload(c, b"\x00\x00\x00\x18ftypheic" + b"x" * 20).status_code == 422
    assert upload(c, JPEG + b"x" * (7 * 1024 * 1024)).status_code == 413
    assert c.post("/fridge/photos", files=[("files", ("p.jpg", JPEG, "image/jpeg"))]).status_code == 401
    assert conn.execute("SELECT count(*) FROM fridge_reads").fetchone()[0] == 0


def test_restart_marks_reading_reads_failed(conn):
    conn.execute("INSERT INTO fridge_reads(photos_sha, photo_count) VALUES('x', 1)")
    fridge.fail_interrupted(conn)
    assert conn.execute("SELECT status, error FROM fridge_reads").fetchone() == ("failed", "interrupted by a server restart")


def ready_read(conn, c, tmpdir_root, answer=READ, match=None):
    setup(conn)
    c = client(conn, ai=PhotoAI(answer, match=match))
    rid = upload(c, JPEG).json()["id"]
    return c, wait(c, rid)


def pids(read):
    return {(p["kind"], p["item_id"] or p["name"]): p["pid"] for p in read["proposals"]}


def fridge_now(c):
    return {i["name"]: (i["qty"], i["unit"], i["qty_estimated"], i["amount_as_of"]) for i in c.get("/fridge", headers=H).json()}


def test_apply_writes_only_accepted_lines(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root)
    p = pids(read)
    before = fridge_now(c)
    lines = [{"pid": p[("amount", 1)], "accept": True},
             {"pid": p[("seen", 2)], "accept": True},
             {"pid": p[("new", "Spinach")], "accept": True, "use_soon": True},
             {"pid": p[("new", "Spring onions")], "accept": False},
             {"pid": p[("not_seen", 3)], "accept": False}]
    r = c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": lines})
    assert r.status_code == 200 and r.json()["status"] == "applied"
    assert r.json()["result"] == {"added": 1, "changed": 1, "seen": 1, "removed": 0}
    now = fridge_now(c)
    assert now["Eggs"] == (4, None, True, TODAY.isoformat())
    assert now["Milk"] == (1, "l", False, TODAY.isoformat())
    assert now["Cilantro"] == before["Cilantro"]
    assert now["Spinach"] == (1, "bag", True, TODAY.isoformat())
    assert "Spring onions" not in now
    assert [i["use_soon"] for i in c.get("/fridge", headers=H).json() if i["name"] == "Spinach"] == [True]
    again = c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": lines})
    assert again.status_code == 409 and again.json()["read"]["result"] == r.json()["result"]
    assert fridge_now(c) == now
    assert c.get("/fridge/reads?open=true", headers=H).json()["read"] is None


def test_an_edited_number_is_not_an_estimate_and_not_seen_removes_only_when_ticked(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root)
    p = pids(read)
    lines = [{"pid": p[("amount", 1)], "accept": True, "qty": 5},
             {"pid": p[("new", "Spinach")], "accept": True, "name": "Baby spinach", "qty": 200, "unit": "g"},
             {"pid": p[("not_seen", 3)], "accept": True}]
    r = c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": lines})
    assert r.json()["result"] == {"added": 1, "changed": 1, "seen": 0, "removed": 1}
    now = fridge_now(c)
    assert now["Eggs"][:3] == (5, None, False) and now["Baby spinach"][:3] == (200, "g", False)
    assert "Cilantro" not in now and "Milk" in now


def test_applying_the_defaults_never_removes_anything(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root, answer={"seen": [], "new": []})
    assert {p["kind"] for p in read["proposals"]} == {"not_seen"}
    lines = [{"pid": x["pid"], "accept": x["default_on"]} for x in read["proposals"]]
    c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": lines})
    assert set(fridge_now(c)) == {"Eggs", "Milk", "Cilantro"}


def test_apply_is_one_transaction(conn, tmpdir_root, monkeypatch):
    c, read = ready_read(conn, None, tmpdir_root)
    before = fridge_now(c)
    p = pids(read)
    real = fridge._apply_line
    calls = []

    def boom(*a, **kw):
        calls.append(1)
        if len(calls) == 2:
            raise RuntimeError("forced")
        return real(*a, **kw)
    monkeypatch.setattr(fridge, "_apply_line", boom)
    lines = [{"pid": p[("amount", 1)], "accept": True}, {"pid": p[("new", "Spinach")], "accept": True}]
    with pytest.raises(RuntimeError):
        fridge.apply_read(conn, None, read["id"], lines, TODAY)
    assert fridge_now(c) == before and fridge.get_read(conn, read["id"])["status"] == "ready"


def test_a_new_item_already_added_meanwhile_is_updated_not_doubled(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root)
    c.post("/fridge", headers=H, json={"name": "Spinach", "qty": 2, "unit": "bag"})
    p = pids(read)
    c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": [{"pid": p[("new", "Spinach")], "accept": True}]})
    assert [i["qty"] for i in c.get("/fridge", headers=H).json() if i["name"] == "Spinach"] == [1]


def test_dismiss(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root)
    r = c.post(f"/fridge/reads/{read['id']}/dismiss", headers=H)
    assert r.status_code == 200 and r.json()["status"] == "dismissed"
    assert c.post(f"/fridge/reads/{read['id']}/dismiss", headers=H).status_code == 200
    assert c.post(f"/fridge/reads/{read['id']}/apply", headers=H, json={"lines": []}).status_code == 409
    assert c.get("/fridge/reads?open=true", headers=H).json()["read"] is None
    assert c.get("/fridge/reads/999", headers=H).status_code == 404
    assert c.post("/fridge/reads/999/apply", headers=H, json={"lines": []}).status_code == 404


def test_old_ready_reads_are_dismissed_when_a_new_read_starts(conn, tmpdir_root):
    c, read = ready_read(conn, None, tmpdir_root)
    conn.execute("UPDATE fridge_reads SET created_at = now() - interval '15 days'")
    upload(c, PNG)
    assert fridge.get_read(conn, read["id"])["status"] == "dismissed"
