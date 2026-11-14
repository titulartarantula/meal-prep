"""Nights of a week beyond their recipes (2026-11-06): an optional name ("Pizza night", "Leftovers") and the store items
added for that night (`extras` with a `day`). "Build from the week view without needing a recipe": on This week the
household names a night and adds products for it from the same chooser as "Add an item"; the products go into that
week's cart like any added item.

A night with only a name or only store items counts as planned for display. It never asks for a rating: ratings stay
tied to recipes on the plan."""
from datetime import date

from . import db, extras

MAX_TITLE = 60


class NightError(ValueError):
    pass


def _day(day: int) -> int:
    if not 0 <= day <= 6:
        raise NightError("day must be 0 (Mon) … 6 (Sun)")
    return day


def clean_title(title: str | None) -> str | None:
    """Whitespace collapsed; empty = no name (None). Longer than MAX_TITLE → NightError."""
    t = " ".join((title or "").split())
    if len(t) > MAX_TITLE:
        raise NightError(f"a night's name is at most {MAX_TITLE} characters")
    return t or None


def list_nights(conn, week: date) -> list[dict]:
    """The week's nights that have a name or store items, Monday first: {day, title, items} (items = the night's
    added items, as GET /extras gives them)."""
    wk = db.week_start(week)
    titles = dict(conn.execute("SELECT day, title FROM nights WHERE week=%s", (wk,)).fetchall())
    items: dict[int, list] = {}
    for e in extras.list_extras(conn, [wk]):
        if e["day"] is not None:
            items.setdefault(e["day"], []).append(e)
    return [{"day": d, "title": titles.get(d), "items": items.get(d, [])}
            for d in sorted(set(titles) | set(items))]


def set_title(conn, week: date, day: int, title: str | None) -> dict:
    """Name a night, rename it, or clear it (an empty name). Returns {week, day, title}."""
    wk, d, t = db.week_start(week), _day(day), clean_title(title)
    if t is None:
        conn.execute("DELETE FROM nights WHERE week=%s AND day=%s", (wk, d))
    else:
        conn.execute("INSERT INTO nights(week, day, title) VALUES(%s,%s,%s) ON CONFLICT (household_id, week, day) "
                     "DO UPDATE SET title=EXCLUDED.title, updated_at=now()", (wk, d, t))
    return {"week": wk.isoformat(), "day": d, "title": t}


def clear_title(conn, week: date, day: int) -> None:
    conn.execute("DELETE FROM nights WHERE week=%s AND day=%s", (db.week_start(week), _day(day)))
