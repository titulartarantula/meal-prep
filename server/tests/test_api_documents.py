"""POST /recipes/import with recipe documents (DB-backed): the first preview is a read job, later previews come from
the cached read, apply uses the same items; failures are 422s or a failed read job, never a 500. Invented text."""
import threading
import time

from docmaker import make_docx, make_locked_pdf, make_pdf, make_scanned_pdf
from mealprep import db
from mealprep.exchange import documents, importer
from mealprep.importers import document as dm
from test_api import H, client
from test_api_exchange import TidyAI
from test_document_split import PagesAI, SplitAI, recipe_text

DOC = ("Our summer cooking\n\n" + recipe_text(1) + "\n\n" + recipe_text(2)).encode()


class DocAI:
    """Finds recipes like SplitAI (text) or PagesAI (scans), tidies lines like TidyAI. `gate`: the split waits."""

    def __init__(self, pages=0, gate=None, split_fails=False, empty=False):
        self.split, self.tidy, self.pages = SplitAI(always_bad=split_fails), TidyAI(), PagesAI(pages)
        self.gate, self.empty, self.splits = gate, empty, 0

    def complete_json(self, prompt, images=None):
        if "Lines:\n" in prompt:
            return self.tidy.complete_json(prompt)
        self.splits += 1
        if self.gate is not None:
            assert self.gate.wait(10)
        if self.empty:
            return {"recipes": []}
        return self.pages.complete_json(prompt, images) if images else self.split.complete_json(prompt)


def post(c, data, dry_run=True, choices=None, name=None, filename="upload.bin"):
    import json
    form = {"dry_run": "true" if dry_run else "false"}
    if choices is not None:
        form["choices"] = json.dumps(choices)
    if name is not None:
        form["name"] = name
    return c.post("/recipes/import", headers=H, files={"file": (filename, data, "application/octet-stream")},
                  data=form)


def wait(c, job):
    for _ in range(200):
        if job["status"] != "running":
            return job
        time.sleep(0.05)
        job = c.get(f"/imports/{job['id']}", headers=H).json()
    raise AssertionError("job didn't finish")


def read(c, data, **kw):
    r = post(c, data, **kw)
    assert r.status_code == 202, r.text
    return wait(c, r.json())


def nrecipes(conn):
    return conn.execute("SELECT count(*) FROM recipes").fetchone()[0]


def test_text_document_read_preview_apply(conn):
    ai = DocAI()
    c = client(conn, ai=ai)
    first = post(c, DOC, name="Summer salads.txt")
    assert first.status_code == 202 and first.json()["dry_run"] is True and first.json()["format"] == "text"
    assert first.json()["existing"] is False
    job = wait(c, first.json())
    assert job["status"] == "done" and job["error"] is None and job["progress"]["done"] == 1
    assert job["counts"] == {"new": 2, "duplicate": 0, "failed": 0}
    a = job["items"][0]
    assert (a["title"], a["status"], a["action"], a["ingredients"], a["steps"], a["source"], a["ai_tidy"]) == (
        "Test Dish 1", "new", "add", 3, 2, "Other · Summer salads", True)
    assert nrecipes(conn) == 0 and ai.splits == 1

    again = post(c, DOC, name="Summer salads.txt")                  # from the cached read: instant, no AI
    assert again.status_code == 200 and ai.splits == 1
    body = again.json()
    assert body["format"] == "text" and [i["key"] for i in body["items"]] == [i["key"] for i in job["items"]]

    r = post(c, DOC, dry_run=False, name="Summer salads.txt", choices={i["key"]: "add" for i in body["items"]})
    assert r.status_code == 202
    done = wait(c, r.json())
    assert done["status"] == "done" and done["counts"]["added"] == 2 and ai.splits == 1
    rec = db.get_recipe(conn, done["items"][1]["recipe_id"])
    assert (rec.title, rec.source, rec.source_kind, rec.source_title, rec.source_url) == (
        "Test Dish 2", "import", "other", "Summer salads", None)
    assert rec.servings == 4 and [i.name for i in rec.ingredients][:2] == ["tidied 0", "tidied 1"]
    assert rec.ingredients[2].sub_recipe == "Glaze" and rec.steps == ["Warm the stock.", "Serve hot."]

    dup = post(c, DOC, name="Summer salads.txt").json()
    assert dup["counts"] == {"new": 0, "duplicate": 2, "failed": 0} and dup["items"][0]["match"]["by"] == "id"


def test_docx_and_pdf_documents(conn):
    c = client(conn, ai=DocAI())
    job = read(c, make_docx(lines=["Family favourites", ""] + recipe_text(3).split("\n")), name="Favourites.docx")
    assert job["format"] == "docx" and [i["title"] for i in job["items"]] == ["Test Dish 3"]
    assert job["items"][0]["source"] == "Other · Favourites"
    pdf = make_pdf([recipe_text(4).split("\n"), recipe_text(5).split("\n")])
    job = read(c, pdf, filename="Baking notes.pdf")                  # no name field: the upload's file name
    assert job["format"] == "pdf" and [i["title"] for i in job["items"]] == ["Test Dish 4", "Test Dish 5"]
    assert job["items"][0]["source"] == "Other · Baking notes"


def test_scanned_pdf_goes_as_page_images(conn):
    ai = DocAI(pages=3)
    c = client(conn, ai=ai)
    job = read(c, make_scanned_pdf(3), name="Scan 0001.pdf")
    assert job["status"] == "done" and job["format"] == "pdf"
    assert [i["title"] for i in job["items"]] == ["Test Page Dish 1", "Test Page Dish 2", "Test Page Dish 3"]
    assert job["items"][0]["source"] == "Other"                    # a scanner's file name isn't a source
    assert ai.pages.calls and all(n.endswith(".png") for n in ai.pages.calls[0][1])


def test_preview_while_reading_returns_the_same_job(conn):
    gate = threading.Event()
    ai = DocAI(gate=gate)
    c = client(conn, ai=ai)
    a = post(c, DOC).json()
    b = post(c, DOC)
    assert b.status_code == 202 and b.json()["id"] == a["id"] and b.json()["existing"] is True
    assert b.json()["status"] == "running" and b.json()["counts"] == {"new": 0, "duplicate": 0, "failed": 0}
    gate.set()
    assert wait(c, a)["status"] == "done" and ai.splits == 1


def test_apply_needs_a_read_first(conn):
    r = post(client(conn, ai=DocAI()), DOC, dry_run=False)
    assert r.status_code == 422 and r.json()["detail"] == importer.READ_AGAIN


def test_no_recipes_and_ai_failure_end_the_read_job(conn):
    c = client(conn, ai=DocAI(empty=True))
    job = read(c, DOC)
    assert job["status"] == "failed" and job["error"] == dm.NO_RECIPES and job["items"] == []
    r = post(c, DOC)                                                # not cached: the next preview reads again
    assert r.status_code == 202 and r.json()["existing"] is False and wait(c, r.json())["status"] == "failed"
    c = client(conn, ai=DocAI(split_fails=True))
    job = read(c, DOC + b"\nanother copy")
    assert job["status"] == "failed" and job["error"] == dm.AI_FAILED


def test_bad_documents_are_422_at_once(conn):
    c = client(conn, ai=DocAI())
    for data, detail in [(make_locked_pdf(), documents.LOCKED), (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\0" * 64,
                         documents.OLD_WORD), (b"\xff\xd8\xff\xe0" + b"\0" * 64, documents.PHOTO),
                         (b"hello", documents.NO_TEXT), (b"%PDF-1.4 broken", documents.DAMAGED),
                         (b'{"name": "Test Soup", ', importer.NOT_JSON)]:
        r = post(c, data)
        assert r.status_code == 422 and r.json()["detail"] == detail, (data[:20], r.text)
    assert conn.execute("SELECT count(*) FROM import_jobs").fetchone()[0] == 0


def test_text_starting_with_a_bracket_is_a_document(conn):
    c = client(conn, ai=DocAI())
    job = read(c, b"[From the recipe box]\n\n" + recipe_text(6).encode())
    assert job["format"] == "text" and [i["title"] for i in job["items"]] == ["Test Dish 6"]


def test_json_and_html_unchanged(conn):
    c = client(conn, ai=DocAI())
    r = post(c, b'{"@type": "Recipe", "name": "Test Toast", "recipeIngredient": ["1 slice bread"]}')
    assert r.status_code == 200 and r.json()["format"] == "jsonld"


def test_read_job_crash_while_building_the_preview_is_a_failed_job(conn, monkeypatch):
    def boom(*a):
        raise RuntimeError("bug")
    monkeypatch.setattr(importer, "analyse", boom)
    job = read(client(conn, ai=DocAI()), DOC)
    assert job["status"] == "failed" and job["error"] == "Couldn't read this file."


def test_interrupted_read_job_marked_failed(conn):
    c = client(conn)
    jid, doc = importer.start_read(conn, DOC, "text")
    assert doc is not None
    importer.fail_interrupted(conn)
    job = c.get(f"/imports/{jid}", headers=H).json()
    assert job["status"] == "failed" and job["error"] == "interrupted by a server restart" and job["dry_run"] is True


def test_old_reads_are_pruned(conn):
    conn.execute("INSERT INTO document_reads(file_sha, format, recipes, created_at) "
                 "VALUES('old', 'text', '[]', now() - interval '15 days'), ('new', 'text', '[]', now())")
    importer.start_read(conn, DOC, "text")
    assert [r[0] for r in conn.execute("SELECT file_sha FROM document_reads")] == ["new"]
