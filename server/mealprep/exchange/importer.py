"""Recipe import, the DB side: read a file (JSON-LD, a saved web page, or a recipe document: PDF, Word, text),
preview what an import would do (new / duplicate / failed, nothing written), then apply the user's choices in a
background job.

A document's recipes are found by the AI (importers/document.py), which takes a while and wouldn't give the same items
twice: its first preview starts a read job (choices_sha 'read'; GET /imports/{id} until done, whose report is then
the preview), and the recipes found are kept in document_reads by the file's sha. Later previews and the apply build
the same nodes from there, so preview and apply keys match.

De-duplication, in order: the stable id (uid), the source link, title + the same source; a title match where one side
has no source is only flagged ("add anyway"). A same-id recipe whose content changed can be replaced ("update").
Applying re-checks the id and link under an advisory lock, one transaction per recipe, so one bad recipe never blocks
the rest and two imports never add the same recipe twice. Ingredient lines from other apps are tidied by the AI on
apply (the same path as NYT / photo imports); when the AI fails the deterministic clean-up from the preview is kept
and the item says so. Our own exports (with the mealprep block) keep their lines as they are."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import json
import logging
import math
import re
import unicodedata

import psycopg
from psycopg.types.json import Jsonb

from .. import db
from ..importers import document as docsplit
from ..importers.structure import structure_ingredients
from ..models import Recipe
from . import documents, extract, jsonld
from .safe import Unreadable, decode, loads_limited

log = logging.getLogger(__name__)

MAX_RECIPES = 2000
RECENT = "10 minutes"   # a retried apply of the same file + choices within this returns the same job
NO_RECIPES = "No recipes found in this file."
NOT_A_RECIPE_FILE = documents.NOT_A_RECIPE_FILE
NOT_JSON = "This file isn't valid JSON."
READ_AGAIN = "Read this document again before adding its recipes."
READ = "read"            # choices_sha of a document's read job
DOC_KINDS = ("pdf", "docx", "text")
KEEP_READS = "14 days"
COULDNT_READ = "Couldn't read this recipe"
COULDNT_SAVE = "Couldn't save this recipe"
FILE_CHANGED = "The file changed since the preview; preview it again."
CANT_UPDATE = "Only a recipe that changed since it was exported can be replaced."
NO_AI = "Ingredients tidied without AI (the AI didn't answer)"
INTERRUPTED = "Not imported: the server restarted. Import the file again (recipes already added are skipped)."
ACTIONS = ("add", "skip", "update")


class BadChoices(ValueError):
    pass


class NeedsReading(Exception):
    """A document whose recipes haven't been found yet (no cached read): preview → start_read; apply → 422."""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


# --- reading the file -------------------------------------------------------------------------------------------

def kind_of(data: bytes) -> tuple[str, str | None]:
    """(kind, decoded text for json/html) by content (documents.sniff)."""
    kind = documents.sniff(data)
    if kind in ("json", "html"):
        return kind, decode(data)
    return kind, None


def read_file(data: bytes, conn=None, name: str | None = None) -> tuple[str, list[dict], list[str]]:
    """(format "jsonld" | "html" | "pdf" | "docx" | "text", Recipe nodes, file warnings), detected by content (not
    the file name). A document comes from its cached read (NeedsReading when there is none); `name` (the file's name
    on the phone) is its recipes' source when the document doesn't name one. Anything else, or a file without a
    recipe → Unreadable (422)."""
    kind, text = kind_of(data)
    warnings: list[str] = []
    if kind == "json":
        try:
            fmt, nodes = "jsonld", extract.find_recipes(loads_limited(text))
        except Unreadable as e:   # "[From the recipe box] …" is a text document; broken JSON stays a JSON error
            if str(e) != NOT_JSON or not text.lstrip().startswith("[") or documents.looks_like_json(text):
                raise
            kind = "text"
    elif kind == "html":
        fmt, nodes = "html", extract.from_html(text)
    if kind in DOC_KINDS:
        cached = cached_read(conn, hashlib.sha256(data).hexdigest()) if conn is not None else None
        if cached is None:
            raise NeedsReading(kind)
        fmt, recipes, warnings = cached
        title = docsplit.doc_title(name)
        nodes = [docsplit.to_node(r, title) for r in recipes]
        if not nodes:
            raise Unreadable(docsplit.NO_RECIPES)
    elif not nodes:
        raise Unreadable(NO_RECIPES)
    warnings = list(warnings)
    if len(nodes) > MAX_RECIPES:
        warnings.append(f"Only the first {MAX_RECIPES} of {len(nodes)} recipes can be imported from one file.")
        nodes = nodes[:MAX_RECIPES]
    return fmt, nodes, warnings


# --- de-duplication -----------------------------------------------------------------------------------------------

def title_key(t: str | None) -> str:
    s = unicodedata.normalize("NFKD", t or "")
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).casefold()
    return " ".join(re.sub(r"[^\w]+", " ", s).split())


def _source_key(kind, title):
    if kind == "nyt":
        return ("nyt",)
    return (kind, title_key(title)) if title else None


def source_label(r: Recipe) -> str:
    if r.source_kind == "nyt":
        return "NYT Cooking"
    if r.source_kind == "book":
        return f"Book · {r.source_title}" if r.source_title else "Unknown book"
    return f"Other · {r.source_title}" if r.source_title else "Other"


class Library:
    """The recipes already in the library (and the file's earlier items, so a file can't add one recipe twice)."""

    def __init__(self, rows):
        self.by_uid, self.by_link, self.by_title = {}, {}, {}
        for rid, uid, title, url, kind, stitle in rows:
            self._add({"recipe_id": rid, "title": title}, uid, url, title, kind, stitle)

    def _add(self, ref, uid, url, title, kind, stitle):
        self.by_uid.setdefault(uid, ref)
        if url:
            self.by_link.setdefault(url, ref)
        self.by_title.setdefault(title_key(title), []).append((ref, _source_key(kind, stitle)))

    def add_item(self, index: int, inc: jsonld.Incoming):
        r = inc.recipe
        self._add({"recipe_id": None, "title": r.title, "index": index}, r.uid, inc.link, r.title, r.source_kind,
                  r.source_title)

    def match(self, inc: jsonld.Incoming) -> dict | None:
        r = inc.recipe
        if r.uid in self.by_uid:
            return {**self.by_uid[r.uid], "by": "id"}
        if inc.link and inc.link in self.by_link:
            return {**self.by_link[inc.link], "by": "link"}
        mine = _source_key(r.source_kind, r.source_title)
        candidates = self.by_title.get(title_key(r.title), [])
        for ref, theirs in candidates:
            if mine and mine == theirs:
                return {**ref, "by": "title_source"}
        for ref, theirs in candidates:
            if not mine or not theirs:
                return {**ref, "by": "title"}
        return None


def _content(r: Recipe, origin: str) -> dict:
    """What "changed since it was exported" compares. Lines from other apps are compared as written (their
    structure is re-derived on every import)."""
    d = r.model_dump(include={"title", "servings", "steps", "description", "prep_minutes", "cook_minutes",
                              "total_minutes", "yield_text", "image"})
    if origin == "mealprep":
        d.update(r.model_dump(include={"ingredients", "notes", "schema_extra"}))
    else:
        d["lines"] = [i.raw for i in r.ingredients]
    return d


def _has_source(r: Recipe) -> bool:
    return bool(r.source_kind == "nyt" or r.source_title or r.source_url)


# --- preview ------------------------------------------------------------------------------------------------------

@dataclass
class Analysed:
    item: dict
    incoming: jsonld.Incoming | None


def analyse(conn, nodes: list[dict]) -> list[Analysed]:
    """One item per node with the default action; nothing is written."""
    lib = Library(db.recipe_keys(conn))
    out = []
    for i, node in enumerate(nodes):
        key = f"{i}:{jsonld.node_hash(node)[:8]}"
        item = {"key": key, "index": i, "title": None, "status": "failed", "action": "skip", "can_update": False,
                "can_add_anyway": False, "match": None, "source": None, "ingredients": 0, "steps": 0, "ratings": 0,
                "reasons": [], "ai_tidy": False, "recipe_id": None}
        try:
            inc = jsonld.from_jsonld(node)
        except jsonld.NotUsable as e:
            item.update(title=jsonld.clean_text(node.get("name"), 200) if isinstance(node, dict) else None,
                        reasons=[str(e)])
            out.append(Analysed(item, None))
            continue
        except Exception:   # never a 500 over one odd recipe
            log.exception("import: mapping item %s failed", i)
            item.update(reasons=[COULDNT_READ])
            out.append(Analysed(item, None))
            continue
        r = inc.recipe
        item.update(title=r.title, source=source_label(r), ingredients=len(r.ingredients), steps=len(r.steps),
                    ratings=len(inc.ratings), reasons=list(inc.warnings),
                    ai_tidy=inc.origin == "schema.org" and bool(r.ingredients))
        m = lib.match(inc)
        if m is None:
            item.update(status="new", action="add")
        else:
            item.update(status="duplicate", action="skip",
                        match={"recipe_id": m["recipe_id"], "title": m["title"], "by": m["by"]})
            if m["by"] == "title":
                item["can_add_anyway"] = True
            elif m["by"] == "id" and m["recipe_id"] is not None:
                have = db.get_recipe(conn, m["recipe_id"])
                item["can_update"] = have is not None and _content(have, inc.origin) != _content(r, inc.origin)
        lib.add_item(i, inc)
        out.append(Analysed(item, inc))
    return out


def counts(items: list[dict], keys) -> dict:
    out = {k: 0 for k in keys}
    for it in items:
        out[it["status"]] = out.get(it["status"], 0) + 1
    return out


def preview(conn, data: bytes, name: str | None = None) -> tuple[dict, list[Analysed]]:
    fmt, nodes, warnings = read_file(data, conn, name)
    analysed = analyse(conn, nodes)
    items = [a.item for a in analysed]
    report = {"dry_run": True, "format": fmt, "file_sha": hashlib.sha256(data).hexdigest(), "warnings": warnings,
              "counts": counts(items, PREVIEW_KEYS), "items": items}
    return report, analysed


# --- apply ----------------------------------------------------------------------------------------------------------

def parse_choices(raw: str | None) -> dict[str, str]:
    """The form field `choices`: a JSON object {item key: "add" | "skip" | "update"} (absent / empty = defaults)."""
    if raw is None or not raw.strip():
        return {}
    try:
        v = json.loads(raw)
    except ValueError:
        raise BadChoices("choices must be a JSON object of item key → add / skip / update")
    if not isinstance(v, dict) or not all(isinstance(k, str) and a in ACTIONS for k, a in v.items()):
        raise BadChoices("choices must be a JSON object of item key → add / skip / update")
    return v


@dataclass
class Work:
    index: int
    action: str          # add | update
    incoming: jsonld.Incoming


def plan(analysed: list[Analysed], choices: dict[str, str]) -> tuple[list[dict], list[Work]]:
    """Resolve each item's action from the choices (else its default). A choice for an item index whose key differs
    means the file changed since the preview: that item fails (never applied to the wrong recipe)."""
    by_index: dict[str, str] = {}
    for k in choices:
        by_index.setdefault(k.split(":", 1)[0], k)
    items, work = [], []
    for a in analysed:
        it = dict(a.item)
        key, idx = it["key"], str(it["index"])
        if key not in choices and idx in by_index:
            it.update(status="failed", action="skip", reasons=it["reasons"] + [FILE_CHANGED])
        elif it["status"] == "failed":
            pass
        else:
            action = choices.get(key, it["action"])
            if action == "update" and not it["can_update"]:
                it.update(status="failed", action="skip", reasons=it["reasons"] + [CANT_UPDATE])
            elif action == "skip":
                it.update(status="duplicate" if it["status"] == "duplicate" else "skipped", action="skip")
            else:
                it.update(status="pending", action=action)
                work.append(Work(it["index"], action, a.incoming))
        items.append(it)
    return items, work


def choices_sha(choices: dict) -> str:
    return hashlib.sha256(json.dumps(choices, sort_keys=True).encode()).hexdigest()


APPLY_KEYS = ("added", "updated", "duplicate", "skipped", "failed", "pending")
PREVIEW_KEYS = ("new", "duplicate", "failed")


def create_job(conn, report: dict, total: int, cs: str) -> int:
    done = total == 0
    return conn.execute(
        "INSERT INTO import_jobs(file_sha, choices_sha, status, report, progress_total, finished_at) "
        "VALUES(%s,%s,%s,%s,%s,CASE WHEN %s THEN now() END) RETURNING id",
        (report["file_sha"], cs, "done" if done else "running", Jsonb(report), total, done)).fetchone()[0]


def recent_job(conn, file_sha: str, cs: str) -> int | None:
    row = conn.execute(f"SELECT id FROM import_jobs WHERE file_sha=%s AND choices_sha=%s AND (status='running' OR "
                       f"finished_at > now() - interval '{RECENT}') ORDER BY id DESC LIMIT 1", (file_sha, cs)).fetchone()
    return row[0] if row else None


def get_job(conn, job_id: int) -> dict | None:
    """An apply job, or a document's read job (report.dry_run true: its items, once done, are the preview)."""
    row = conn.execute("SELECT id, status, report, progress_done, progress_total, error, created_at, finished_at "
                       "FROM import_jobs WHERE id=%s", (job_id,)).fetchone()
    if row is None:
        return None
    jid, status, report, done, total, error, created, finished = row
    keys = PREVIEW_KEYS if report.get("dry_run") else APPLY_KEYS
    return {**report, "id": jid, "status": status, "progress": {"done": done, "total": total}, "error": error,
            "counts": counts(report["items"], keys), "created_at": created.isoformat(),
            "finished_at": finished.isoformat() if finished else None}


def fail_interrupted(conn) -> None:
    """At startup: a job that was running when the process stopped will never finish."""
    for jid, report in conn.execute("SELECT id, report FROM import_jobs WHERE status='running'").fetchall():
        for it in report["items"]:
            if it["status"] == "pending":
                it.update(status="failed", reasons=it["reasons"] + [INTERRUPTED])
        conn.execute("UPDATE import_jobs SET status='failed', report=%s, finished_at=now(), "
                     "error='interrupted by a server restart' WHERE id=%s", (Jsonb(report), jid))


def tidy(ai, inc: jsonld.Incoming) -> tuple[Recipe, bool, str | None]:
    """(recipe, tidied by the AI?, warning). Lines from other apps go through the AI (names, units, prep, pantry);
    the headings' sub-recipe names are kept. On any AI trouble the deterministic lines stay."""
    r = inc.recipe
    if inc.origin != "schema.org" or not r.ingredients or ai is None:
        return r, False, None
    try:
        lines = structure_ingredients(ai, [i.raw for i in r.ingredients], strict=True)
    except Exception as e:   # AIError, timeouts, odd answers
        log.warning("import: AI tidy of %r failed: %s", r.title, e)
        return r, False, NO_AI
    lines = [a.model_copy(update={"sub_recipe": d.sub_recipe}) for a, d in zip(lines, r.ingredients)]
    return r.model_copy(update={"ingredients": lines}), True, None


def save_one(conn, w: Work, r: Recipe, provider: str | None) -> dict:
    """Save one recipe in its own transaction under the import lock; returns the item fields to update."""
    inc = w.incoming
    try:
        with conn.transaction():
            conn.execute("SELECT pg_advisory_xact_lock(hashtext('mealprep-import'))")
            same = conn.execute("SELECT id, title FROM recipes WHERE uid=%s FOR UPDATE", (r.uid,)).fetchone()
            if w.action == "update" and same:
                db.replace_recipe(conn, same[0], r, with_source=_has_source(r))
                db.insert_imported_ratings(conn, same[0], inc.ratings)
                return {"status": "updated", "recipe_id": same[0]}
            if same is None and inc.link:
                same = conn.execute("SELECT id, title FROM recipes WHERE source_url=%s", (inc.link,)).fetchone()
                by = "link"
            else:
                by = "id"
            if same:
                return {"status": "duplicate", "recipe_id": same[0],
                        "match": {"recipe_id": same[0], "title": same[1], "by": by}}
            rid = db.save_recipe(conn, r, ai_provider=provider)
            db.insert_imported_ratings(conn, rid, inc.ratings)
            return {"status": "added", "recipe_id": rid}
    except psycopg.errors.UniqueViolation:   # another import won the race
        return {"status": "duplicate"}
    except Exception:
        log.exception("import: saving %r failed", r.title)
        return {"status": "failed", "reasons_add": [COULDNT_SAVE]}


def run_job(conn, job_id: int, items: list[dict], work: list[Work], ai, workers: int, provider: str | None) -> None:
    """The background part of an apply: AI tidying in a pool, saves in file order on this connection, the report and
    progress written after each recipe."""
    by_index = {it["index"]: it for it in items}
    report = conn.execute("SELECT report FROM import_jobs WHERE id=%s", (job_id,)).fetchone()[0]
    report["items"] = items
    done = 0
    try:
        with ThreadPoolExecutor(max_workers=max(1, workers), thread_name_prefix=f"import-{job_id}") as pool:
            futures = [pool.submit(tidy, ai, w.incoming) for w in work]
            for w, fut in zip(work, futures):
                it = by_index[w.index]
                try:
                    r, used_ai, warning = fut.result()
                except Exception:
                    r, used_ai, warning = w.incoming.recipe, False, NO_AI
                res = save_one(conn, w, r, provider if used_ai else None)
                it["reasons"] = it["reasons"] + ([warning] if warning else []) + res.pop("reasons_add", [])
                it.update(res, ai_tidy=used_ai)
                done += 1
                conn.execute("UPDATE import_jobs SET report=%s, progress_done=%s WHERE id=%s",
                             (Jsonb(report), done, job_id))
        conn.execute("UPDATE import_jobs SET status='done', finished_at=now() WHERE id=%s", (job_id,))
    except Exception as e:
        log.exception("import job %s crashed", job_id)
        for it in items:
            if it["status"] == "pending":
                it.update(status="failed", reasons=it["reasons"] + [COULDNT_SAVE])
        conn.execute("UPDATE import_jobs SET status='failed', report=%s, error=%s, finished_at=now() WHERE id=%s",
                     (Jsonb(report), f"{type(e).__name__}: {str(e)[:200]}", job_id))


def start(conn, data: bytes, choices: dict[str, str], name: str | None = None) -> tuple[int, list[dict], list[Work], bool]:
    """Apply, the synchronous part: (job id, items, work to run, existing). A retried apply of the same file and
    choices (running, or finished in the last few minutes) returns that job: existing = True, nothing to run."""
    report, analysed = preview(conn, data, name)
    cs = choices_sha(choices)
    jid = recent_job(conn, report["file_sha"], cs)
    if jid is not None:
        return jid, [], [], True
    items, work = plan(analysed, choices)
    report.update(dry_run=False, items=items)
    report.pop("counts")
    return create_job(conn, report, len(work), cs), items, work, False


# --- documents: the read job and its cache --------------------------------------------------------------------------

def cached_read(conn, file_sha: str) -> tuple[str, list[dict], list[str]] | None:
    row = conn.execute("SELECT format, recipes, warnings FROM document_reads WHERE file_sha=%s", (file_sha,)).fetchone()
    return (row[0], row[1], row[2]) if row else None


def start_read(conn, data: bytes, kind: str) -> tuple[int, documents.Document | None]:
    """A document's first preview: its text is read now (a damaged, locked or empty file is Unreadable → 422 at
    once), then a read job is made. → (job id, the document to read; None = the same file is already being read:
    that job)."""
    doc = documents.read(data, kind)
    sha = hashlib.sha256(data).hexdigest()
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(hashtext('mealprep-read'))")
        row = conn.execute("SELECT id FROM import_jobs WHERE file_sha=%s AND choices_sha=%s AND status='running' "
                           "ORDER BY id DESC LIMIT 1", (sha, READ)).fetchone()
        if row:
            return row[0], None
        conn.execute(f"DELETE FROM document_reads WHERE created_at < now() - interval '{KEEP_READS}'")
        if doc.scanned:   # parts to read (an estimate; the split updates it)
            total = math.ceil(len(doc.pages) / docsplit.PAGES_PER_CALL)
        else:
            total = max(1, math.ceil(len(doc.text) / docsplit.CHUNK_CHARS))
        report = {"dry_run": True, "format": kind, "file_sha": sha, "warnings": list(doc.warnings), "items": []}
        return create_job(conn, report, total, READ), doc


def run_read(conn, job_id: int, doc: documents.Document, name: str | None, ai) -> None:
    """The background part of a document's first preview: the AI finds the recipes, they are cached by the file's sha,
    and the job's report becomes the preview (done), or the job fails with a sentence for the user."""
    report = conn.execute("SELECT report FROM import_jobs WHERE id=%s", (job_id,)).fetchone()[0]

    def progress(done: int, total: int) -> None:
        conn.execute("UPDATE import_jobs SET progress_done=%s, progress_total=%s WHERE id=%s", (done, total, job_id))

    def fail(message: str) -> None:
        conn.execute("UPDATE import_jobs SET status='failed', error=%s, finished_at=now() WHERE id=%s",
                     (message, job_id))

    try:
        recipes, warns = docsplit.split(ai, doc, progress)
    except docsplit.DocumentReadFailed as e:
        return fail(str(e))
    except Exception:
        log.exception("read job %s: the split crashed", job_id)
        return fail(docsplit.AI_FAILED)
    if not recipes:   # not cached: reading it again may go better
        return fail(docsplit.NO_RECIPES)
    try:
        warnings = report["warnings"] + warns
        conn.execute("INSERT INTO document_reads(file_sha, format, recipes, warnings) VALUES(%s,%s,%s,%s) "
                     "ON CONFLICT (file_sha) DO UPDATE SET format=EXCLUDED.format, recipes=EXCLUDED.recipes, "
                     "warnings=EXCLUDED.warnings, created_at=now()",
                     (report["file_sha"], report["format"], Jsonb(recipes), Jsonb(warnings)))
        title = docsplit.doc_title(name)
        items = [a.item for a in analyse(conn, [docsplit.to_node(r, title) for r in recipes])]
        report.update(warnings=warnings, items=items)
        conn.execute("UPDATE import_jobs SET status='done', report=%s, finished_at=now() WHERE id=%s",
                     (Jsonb(report), job_id))
    except Exception:
        log.exception("read job %s: building the preview crashed", job_id)
        fail("Couldn't read this file.")
