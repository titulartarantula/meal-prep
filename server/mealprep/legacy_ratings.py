"""One-off data change (2026-10-31): the phones' ratings are the legacy member's, and Good for company is one household verdict.

    python -m mealprep.legacy_ratings [--apply] [--log FILE]      # dry run unless --apply; prints the plan as JSON
    python -m mealprep.legacy_ratings --revert FILE               # undo an applied run from its log

For every household with a legacy member (households.legacy_member_id, else its first Owner — the member the shared
token acts as):
1. "Family" ratings (member_id NULL) in ratings / recipe_ratings, and their rating_history rows, get member_id = the
   legacy member. A time cooked the legacy member already rated keeps both (the Family row is left, logged as a
   conflict) — the unique (plan, member) wouldn't allow two.
2. Every recipe without a Good for company verdict gets one from the old company answers (ratings, recipe_ratings,
   imported_ratings): the newest answer wins (source 'migrated'; set_by = the member who answered when they may set
   it, i.e. hold recipe.company, else NULL); recipes whose answers disagree are logged.
Scores, notes and dates are not touched; the log lists every changed row so --revert puts member_id back to NULL and
removes the verdicts it created. Runs as the login role (no household scope), in one transaction.
"""
import argparse
import json
import sys
from datetime import datetime, timezone

import psycopg

from .config import Settings
from .households import legacy_member

TABLES = ("ratings", "recipe_ratings")


def _answers(conn, household_id: int) -> dict[int, list[tuple]]:
    """{recipe_id: [(rated_at, company, table, row id, member), …]} newest first — every old company answer."""
    rows = conn.execute(
        "SELECT p.recipe_id, r.rated_at, r.company, 'ratings', r.id, r.member_id FROM ratings r "
        "JOIN plan p ON p.id = r.plan_id WHERE r.household_id=%(h)s AND r.company IS NOT NULL "
        "UNION ALL SELECT recipe_id, rated_at, company, 'recipe_ratings', id, member_id FROM recipe_ratings "
        "WHERE household_id=%(h)s AND company IS NOT NULL "
        "UNION ALL SELECT recipe_id, COALESCE(rated_at, cooked_on::timestamptz), company, 'imported_ratings', id, "
        "NULL::int FROM imported_ratings WHERE household_id=%(h)s AND company IS NOT NULL "
        "ORDER BY 2 DESC NULLS LAST, 5 DESC", {"h": household_id})
    out: dict[int, list[tuple]] = {}
    for rid, at, company, table, i, member in rows:
        out.setdefault(rid, []).append((at.isoformat() if at else None, company, table, i, member))
    return out


def plan(conn) -> dict:
    """What --apply would change (and does, inside its transaction)."""
    log = {"at": datetime.now(timezone.utc).isoformat(), "households": []}
    for (hid,) in conn.execute("SELECT id FROM households ORDER BY id").fetchall():
        mid = legacy_member(conn, hid)
        h = {"household_id": hid, "legacy_member_id": mid, "reattributed": {t: [] for t in TABLES},
             "history": [], "conflicts": [], "verdicts": [], "verdict_conflicts": []}
        if mid is not None:
            for i, pid in conn.execute("SELECT id, plan_id FROM ratings r WHERE household_id=%s AND member_id IS NULL "
                                       "ORDER BY id", (hid,)).fetchall():
                if conn.execute("SELECT 1 FROM ratings WHERE plan_id=%s AND member_id=%s", (pid, mid)).fetchone():
                    h["conflicts"].append({"table": "ratings", "id": i})
                else:
                    h["reattributed"]["ratings"].append(i)
            for i, rid, d in conn.execute("SELECT id, recipe_id, cooked_on FROM recipe_ratings WHERE household_id=%s "
                                          "AND member_id IS NULL ORDER BY id", (hid,)).fetchall():
                if conn.execute("SELECT 1 FROM recipe_ratings WHERE household_id=%s AND recipe_id=%s AND cooked_on=%s "
                                "AND member_id=%s", (hid, rid, d, mid)).fetchone():
                    h["conflicts"].append({"table": "recipe_ratings", "id": i})
                else:
                    h["reattributed"]["recipe_ratings"].append(i)
            h["history"] = [i for (i,) in conn.execute(
                "SELECT id FROM rating_history WHERE household_id=%s AND member_id IS NULL AND "
                "(plan_id IS NULL OR plan_id <> ALL(%s)) AND "
                "(recipe_rating_id IS NULL OR recipe_rating_id <> ALL(%s)) ORDER BY id",
                (hid, _conflict_ids(conn, h, "ratings", "plan_id"),
                 _conflict_ids(conn, h, "recipe_ratings", "id")))]
        decided = {r for (r,) in conn.execute("SELECT recipe_id FROM recipe_company WHERE household_id=%s", (hid,))}
        owners = {m for (m,) in conn.execute(
            "SELECT DISTINCT mr.member_id FROM member_roles mr JOIN role_permissions rp ON rp.role_id = mr.role_id "
            "JOIN members m ON m.id = mr.member_id WHERE m.household_id=%s AND rp.permission='recipe.company'", (hid,))}
        for rid, answers in _answers(conn, hid).items():
            if rid in decided:
                continue
            member = answers[0][4]
            h["verdicts"].append({"recipe_id": rid, "verdict": answers[0][1], "from": list(answers[0][2:4]),
                                  "answered_by": member, "set_by": member if member in owners else None})
            if len({a[1] for a in answers}) > 1:
                h["verdict_conflicts"].append({"recipe_id": rid, "taken": answers[0][1],
                                               "answers": [list(a) for a in answers]})
        log["households"].append(h)
    return log


def _conflict_ids(conn, h: dict, table: str, column: str) -> list[int]:
    """History rows of a conflicting (left as Family) rating stay Family too."""
    ids = [c["id"] for c in h["conflicts"] if c["table"] == table]
    if not ids:
        return []
    return [r for (r,) in conn.execute(f"SELECT {column} FROM {table} WHERE id = ANY(%s)", (ids,))]


def snapshot(conn) -> list[tuple]:
    """Scores, notes, dates and company answers of every rating row — must be equal before and after."""
    return conn.execute(
        "SELECT 'r', id, plan_id, family, company, note, rated_at FROM ratings UNION ALL "
        "SELECT 'rr', id, recipe_id, family, company, note, rated_at FROM recipe_ratings UNION ALL "
        "SELECT 'h', id, plan_id, family, company, note, at FROM rating_history ORDER BY 1, 2").fetchall()


def apply(conn, log: dict) -> None:
    for h in log["households"]:
        mid = h["legacy_member_id"]
        for t in TABLES:
            ids = h["reattributed"][t]
            if ids and conn.execute(f"UPDATE {t} SET member_id=%s WHERE id = ANY(%s) AND member_id IS NULL",
                                    (mid, ids)).rowcount != len(ids):
                raise RuntimeError(f"{t}: rows changed since the plan")
        if h["history"]:
            conn.execute("UPDATE rating_history SET member_id=%s WHERE id = ANY(%s) AND member_id IS NULL",
                         (mid, h["history"]))
        for v in h["verdicts"]:
            conn.execute("INSERT INTO recipe_company(recipe_id, household_id, verdict, set_by, source) "
                         "VALUES(%s,%s,%s,%s,'migrated')", (v["recipe_id"], h["household_id"], v["verdict"],
                                                             v.get("set_by")))


def revert(conn, log: dict) -> None:
    for h in log["households"]:
        mid = h["legacy_member_id"]
        for t in TABLES:
            if h["reattributed"][t]:
                conn.execute(f"UPDATE {t} SET member_id=NULL WHERE id = ANY(%s) AND member_id=%s",
                             (h["reattributed"][t], mid))
        if h["history"]:
            conn.execute("UPDATE rating_history SET member_id=NULL WHERE id = ANY(%s) AND member_id=%s",
                         (h["history"], mid))
        for v in h["verdicts"]:
            conn.execute("DELETE FROM recipe_company WHERE recipe_id=%s AND source='migrated' AND verdict=%s",
                         (v["recipe_id"], v["verdict"]))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m mealprep.legacy_ratings")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--log", help="write the change log (JSON) here")
    ap.add_argument("--revert", metavar="LOG")
    a = ap.parse_args(argv)
    with psycopg.connect(Settings().dsn) as conn:
        before = snapshot(conn)
        if a.revert:
            with open(a.revert) as f:
                revert(conn, json.load(f))
            log = {"reverted": a.revert}
        else:
            log = plan(conn)
            if a.apply:
                apply(conn, log)
        if snapshot(conn) != before:
            conn.rollback()
            print("scores/notes/dates changed — rolled back", file=sys.stderr)
            return 1
        if a.apply or a.revert:
            conn.commit()
        else:
            conn.rollback()
    text = json.dumps(log, indent=2)
    if a.log:
        with open(a.log, "w") as f:
            f.write(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
