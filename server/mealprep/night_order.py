"""One-off data change (2026-11-14): several recipes on a night, in the order they were put on it.

    python -m mealprep.night_order [--apply] [--log FILE]     # dry run unless --apply; prints the plan as JSON
    python -m mealprep.night_order --revert FILE              # clear the positions that run set (old order: by id)

plan.position (schema.sql adds it) is a recipe's place on its night; the server sets it whenever a recipe is put on a
night. Rows placed before the column existed have none: this numbers them 1, 2, … per night in id order (the order
they showed in before), so nothing on the plan moves. Rows in the tray (no night) keep NULL. The log lists every row
it numbered; --revert sets exactly those back to NULL (a row moved since keeps its newer position). Runs as the login
role (every household) in one transaction; the log is written before the commit. Safe with the server running.
"""
import argparse
import json
import sys
from datetime import datetime, timezone

import psycopg

from .config import Settings


def plan(conn) -> dict:
    """The rows on a night with no position yet, numbered per night after any already numbered there."""
    rows = conn.execute(
        "SELECT id, week, day, (SELECT COALESCE(max(o.position), 0) FROM plan o WHERE o.week = p.week AND o.day = p.day) "
        "+ row_number() OVER (PARTITION BY week, day ORDER BY id) FROM plan p "
        "WHERE day IS NOT NULL AND position IS NULL ORDER BY week, day, id").fetchall()
    nights: dict[tuple, int] = {}
    for _, w, d, _ in rows:
        nights[(w, d)] = nights.get((w, d), 0) + 1
    return {"at": datetime.now(timezone.utc).isoformat(), "direction": "number",
            "plan_rows": conn.execute("SELECT count(*) FROM plan").fetchone()[0],
            "numbered": len(rows), "nights": len(nights),
            "nights_with_several": sum(1 for n in nights.values() if n > 1),
            "rows": [{"id": i, "week": w.isoformat(), "day": d, "position": pos} for i, w, d, pos in rows]}


def apply(conn, log: dict) -> None:
    for r in log["rows"]:
        conn.execute("UPDATE plan SET position=%s WHERE id=%s AND position IS NULL", (r["position"], r["id"]))


def revert(conn, log: dict) -> dict:
    n = 0
    for r in log["rows"]:
        n += conn.execute("UPDATE plan SET position=NULL WHERE id=%s AND position=%s AND day=%s",
                          (r["id"], r["position"], r["day"])).rowcount
    return {"at": datetime.now(timezone.utc).isoformat(), "direction": "revert", "cleared": n,
            "logged": len(log["rows"])}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m mealprep.night_order")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--revert", metavar="LOG", help="clear the positions a run logged in LOG")
    ap.add_argument("--log", help="write the change log (JSON) here")
    a = ap.parse_args(argv)
    with psycopg.connect(Settings().dsn) as conn:
        conn.execute("LOCK TABLE plan IN EXCLUSIVE MODE")
        if a.revert:
            with open(a.revert) as f:
                log = revert(conn, json.load(f))
        else:
            log = plan(conn)
            if a.apply:
                apply(conn, log)
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
