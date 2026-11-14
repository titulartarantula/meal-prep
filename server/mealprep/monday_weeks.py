"""One-off data change (2026-11-14): weeks start on Monday instead of Sunday.

    python -m mealprep.monday_weeks [--apply] [--log FILE]      # dry run unless --apply; prints the plan as JSON
    python -m mealprep.monday_weeks --revert [--log FILE]       # back to Sunday weeks (old server code)

A week was named by its Sunday and a night's `day` was 0 = Sun … 6 = Sat; now a week is named by its Monday and
0 = Mon … 6 = Sun (db.week_start). Every dinner keeps its calendar date (week + day):
  - a row with a night: date = week + day → week = that date's Monday, day = date.weekday(). Mon–Sat nights stay in
    the same span (week + 1 day, day − 1); a Sunday night joins the week before (Monday 6 days earlier, day 6);
  - a row without a night (on the week, not a night): week + 1 day (the Monday of the same Sunday–Saturday span,
    which holds six of its seven days).
Tables: plan, extras, nights, rating_history, cook_cards (week + day); cart_weeks, carts.weeks, prep_plans.weeks
(weeks only: + 1 day). Cart lines' item->'day' (a night of an added item) is left: it only labels a line.
A cart's plan_fingerprint covers its weeks' ids, so every cart that was up to date before (its fingerprint matched)
gets its fingerprint recomputed on the new ids; a stale one stays stale.

Refuses to run unless every stored week id is a Sunday (--revert: a Monday), so it can't run twice. --revert is the
exact inverse (date → its Sunday, day = days since that Sunday; no night: − 1 day). Runs as the login role (every
household) in one transaction; the log is written before the commit. Stop the server while it runs, and deploy the
matching code (Monday weeks forward, the old code for a revert) before starting it again.
"""
import argparse
import json
import sys
from datetime import datetime, timezone

import psycopg

from . import db
from .config import Settings

WEEK_DAY = ("plan", "extras", "nights", "rating_history", "cook_cards")
WEEK_ONLY = ("cart_weeks",)
WEEK_ARRAYS = (("carts", "weeks"), ("prep_plans", "weeks"))

# Postgres: extract(dow) 0 = Sun … 6 = Sat; extract(isodow) 1 = Mon … 7 = Sun
_FWD = {"day_week": "(week + day::int) - (extract(isodow FROM week + day::int)::int - 1)",
        "day_day": "extract(isodow FROM week + day::int)::int - 1",
        "shift": 1, "from_dow": 0}
_REV = {"day_week": "(week + day::int) - extract(dow FROM week + day::int)::int",
        "day_day": "extract(dow FROM week + day::int)::int",
        "shift": -1, "from_dow": 1}


def _ids(conn) -> list:
    """Every stored week id (with the table it is in)."""
    out = []
    for t in WEEK_DAY + WEEK_ONLY:
        out += [(t, w) for (w,) in conn.execute(f"SELECT DISTINCT week FROM {t} WHERE week IS NOT NULL")]
    for t, col in WEEK_ARRAYS:
        out += [(t, w) for (w,) in conn.execute(f"SELECT DISTINCT unnest({col}) FROM {t}") if w is not None]
    return out


def _check(conn, from_dow: int) -> None:
    """Every week id must be a Sunday (forward) / a Monday (revert); dow: 0 = Sun, 1 = Mon."""
    want = "Sunday" if from_dow == 0 else "Monday"
    bad = [(t, w.isoformat()) for t, w in _ids(conn) if (w.isoweekday() % 7) != from_dow]
    if bad:
        raise SystemExit(f"not every week id is a {want} (already changed, or mixed): {bad[:10]}")


def _carts(conn) -> list:
    return conn.execute("SELECT id, weeks, plan_fingerprint FROM carts WHERE plan_fingerprint IS NOT NULL "
                        "AND weeks IS NOT NULL ORDER BY id").fetchall()


def plan(conn, revert: bool = False) -> dict:
    """What the run changes: counts per table, the rows with a night that change week, the carts kept fresh."""
    m = _REV if revert else _FWD
    _check(conn, m["from_dow"])
    log = {"at": datetime.now(timezone.utc).isoformat(), "direction": "revert" if revert else "monday",
           "tables": {}, "moved_rows": {}, "carts": []}
    for t in WEEK_DAY:
        rows = conn.execute(f"SELECT ctid::text, week, day, CASE WHEN day IS NULL THEN week + %s "
                            f"ELSE {m['day_week']} END, CASE WHEN day IS NULL THEN NULL ELSE {m['day_day']} END "
                            f"FROM {t} ORDER BY week, day", (m["shift"],)).fetchall()
        log["tables"][t] = len(rows)
        log["moved_rows"][t] = [{"week": w.isoformat(), "day": d, "new_week": nw.isoformat(), "new_day": nd}
                                for _, w, d, nw, nd in rows]
    for t in WEEK_ONLY:
        log["tables"][t] = conn.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
    for t, col in WEEK_ARRAYS:
        log["tables"][t] = conn.execute(f"SELECT count(*) FROM {t} WHERE {col} IS NOT NULL").fetchone()[0]
    for cid, weeks, fp in _carts(conn):
        v = db._fingerprint_version(fp)
        fresh = db.plan_fingerprint(conn, weeks, v, normalise=False) == fp
        log["carts"].append({"id": cid, "weeks": [w.isoformat() for w in weeks], "fingerprint": fp, "fresh": fresh})
    return log


def apply(conn, log: dict) -> None:
    revert = log["direction"] == "revert"
    m = _REV if revert else _FWD
    for t in WEEK_DAY:
        conn.execute(f"UPDATE {t} SET week = CASE WHEN day IS NULL THEN week + %s ELSE {m['day_week']} END, "
                     f"day = CASE WHEN day IS NULL THEN NULL ELSE {m['day_day']} END", (m["shift"],))
    for t in WEEK_ONLY:
        conn.execute(f"UPDATE {t} SET week = week + %s", (m["shift"],))
    for t, col in WEEK_ARRAYS:
        conn.execute(f"UPDATE {t} SET {col} = (SELECT array_agg(w + %s ORDER BY w) FROM unnest({col}) w) "
                     f"WHERE {col} IS NOT NULL", (m["shift"],))
    for c in log["carts"]:
        if not c["fresh"]:
            continue
        weeks, v = conn.execute("SELECT weeks, plan_fingerprint FROM carts WHERE id=%s", (c["id"],)).fetchone()
        new = db.plan_fingerprint(conn, weeks, db._fingerprint_version(v), normalise=False)
        conn.execute("UPDATE carts SET plan_fingerprint=%s WHERE id=%s", (new, c["id"]))
        c["new_fingerprint"] = new
    _check(conn, 1 - m["from_dow"])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m mealprep.monday_weeks")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--revert", action="store_true", help="Monday weeks back to Sunday weeks (implies --apply)")
    ap.add_argument("--log", help="write the change log (JSON) here")
    a = ap.parse_args(argv)
    with psycopg.connect(Settings().dsn) as conn:
        for t in WEEK_DAY + WEEK_ONLY + tuple(t for t, _ in WEEK_ARRAYS):
            conn.execute(f"LOCK TABLE {t} IN EXCLUSIVE MODE")
        log = plan(conn, revert=a.revert)
        if a.apply or a.revert:
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
