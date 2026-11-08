"""One-off data change (2026-11-08): recipe sources are Website, Book or Other (sources.py).

    python -m mealprep.three_sources [--apply] [--log FILE]      # dry run unless --apply; prints the plan as JSON
    python -m mealprep.three_sources --revert FILE               # undo an applied run from its log

1. NYT recipes (source_kind 'nyt') become websites: source_kind 'web', source_title "NYT Cooking"; the link, photo,
   import method (source = 'nyt') and everything else stay. The NYT importer kept no author, so none is invented.
2. "Other" recipes with an http(s) link (source_url) become websites, named like the web importer names a site when
   nothing better is known: NYT Cooking for an NYT link, else the publisher saved with the recipe
   (schema_extra.publisher), else the link's registrable domain; the author = schema_extra.author when saved. No page
   is fetched. The old name / note go into the log (a website recipe keeps no note).
Books, and Other recipes without a link, are left as they are. Every changed row is logged with its old and new
source columns; --apply re-reads each row in the same transaction and stops if it changed since the plan, --revert puts
the old columns back on rows that still hold what the run wrote. Runs as the login role (every household), in one
transaction; the log is written before the commit.
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from urllib.parse import urlsplit

import psycopg

from .config import Settings
from .exchange.jsonld import _names
from .importers.web import registrable_domain
from .sources import NYT_SITE, is_nyt_url

COLS = ("source_kind", "source_title", "source_ref", "source_author", "source_isbn")


def _site(url: str, extra: dict) -> tuple[str, str | None]:
    """(site, author) for a link with no page fetched: NYT Cooking / saved publisher / domain; saved author."""
    author = _names(extra.get("author")) if isinstance(extra, dict) else None
    if is_nyt_url(url):
        site = NYT_SITE
    else:
        site = (_names(extra.get("publisher")) if isinstance(extra, dict) else None) or \
            registrable_domain(urlsplit(url).hostname or "")
    if author and site and author.casefold() == site.casefold():
        author = None
    return site[:200], (author[:200] if author else None)


def _row(conn, rid: int, lock: bool = False):
    return conn.execute("SELECT source_kind, source_title, source_ref, source_author, source_isbn FROM recipes "
                        "WHERE id=%s" + (" FOR UPDATE" if lock else ""), (rid,)).fetchone()


def plan(conn) -> dict:
    """What --apply would change (and does, inside its transaction)."""
    log = {"at": datetime.now(timezone.utc).isoformat(), "nyt_to_web": [], "other_to_web": [], "unchanged": {}}
    rows = conn.execute("SELECT id, household_id, source_kind, source_title, source_ref, source_author, source_isbn, "
                        "source_url, data->'schema_extra' FROM recipes ORDER BY id").fetchall()
    for rid, hid, kind, title, ref, author, isbn, url, extra in rows:
        before = dict(zip(COLS, (kind, title, ref, author, isbn)))
        link = url if isinstance(url, str) and url.lower().startswith(("http://", "https://")) else None
        if kind == "nyt":
            after = {"source_kind": "web", "source_title": title or NYT_SITE, "source_ref": None,
                     "source_author": author, "source_isbn": None}
            log["nyt_to_web"].append({"id": rid, "household_id": hid, "url": url, "before": before, "after": after})
        elif kind == "other" and link:
            site, by = _site(link, extra or {})
            after = {"source_kind": "web", "source_title": site, "source_ref": None, "source_author": by,
                     "source_isbn": None}
            log["other_to_web"].append({"id": rid, "household_id": hid, "url": url, "before": before, "after": after})
        else:
            log["unchanged"][kind] = log["unchanged"].get(kind, 0) + 1
    log["counts"] = {"nyt_to_web": len(log["nyt_to_web"]), "other_to_web": len(log["other_to_web"]),
                     "unchanged": sum(log["unchanged"].values())}
    return log


def _set(conn, rid: int, expect: dict, new: dict) -> None:
    cur = _row(conn, rid, lock=True)
    if cur is None or dict(zip(COLS, cur)) != expect:
        raise RuntimeError(f"recipe {rid}: source changed since the plan ({cur!r}) — nothing applied")
    conn.execute("UPDATE recipes SET source_kind=%s, source_title=%s, source_ref=%s, source_author=%s, source_isbn=%s "
                 "WHERE id=%s", (*[new[c] for c in COLS], rid))


def apply(conn, log: dict) -> None:
    for e in log["nyt_to_web"] + log["other_to_web"]:
        _set(conn, e["id"], e["before"], e["after"])


def revert(conn, log: dict) -> dict:
    """Old columns back on every logged row that still holds what the run wrote; others are reported, not touched."""
    done, skipped = [], []
    for e in log["nyt_to_web"] + log["other_to_web"]:
        cur = _row(conn, e["id"], lock=True)
        if cur is None or dict(zip(COLS, cur)) != e["after"]:
            skipped.append(e["id"])
            continue
        conn.execute("UPDATE recipes SET source_kind=%s, source_title=%s, source_ref=%s, source_author=%s, "
                     "source_isbn=%s WHERE id=%s", (*[e["before"][c] for c in COLS], e["id"]))
        done.append(e["id"])
    return {"reverted": done, "skipped_changed_since": skipped}


def snapshot(conn) -> list[tuple]:
    """Everything but the source columns — must be equal before and after."""
    return conn.execute("SELECT id, household_id, title, source, source_url, data, uid FROM recipes ORDER BY id").fetchall()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m mealprep.three_sources")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--log", help="write the change log (JSON) here")
    ap.add_argument("--revert", metavar="LOG")
    a = ap.parse_args(argv)
    with psycopg.connect(Settings().dsn) as conn:
        before = snapshot(conn)
        if a.revert:
            with open(a.revert) as f:
                log = {"revert_of": a.revert, **revert(conn, json.load(f))}
        else:
            log = plan(conn)
            if a.apply:
                apply(conn, log)
        if snapshot(conn) != before:
            conn.rollback()
            print("other recipe columns changed — rolled back", file=sys.stderr)
            return 1
        text = json.dumps(log, indent=2, default=str)
        if a.log:
            with open(a.log, "w") as f:
                f.write(text + "\n")
        if a.apply or a.revert:
            conn.commit()
        else:
            conn.rollback()
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
