from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

from .models import PlanEntry, Rating, Recipe

SCHEMA = (Path(__file__).parent / "schema.sql").read_text()


def connect(dsn: str) -> psycopg.Connection:
    conn = psycopg.connect(dsn, autocommit=True)
    conn.execute(SCHEMA)
    return conn


def save_recipe(conn, r: Recipe, ai_provider: str | None = None) -> int:
    if r.source_url:
        row = conn.execute("SELECT id FROM recipes WHERE source_url=%s", (r.source_url,)).fetchone()
        if row:
            return row[0]
    return conn.execute(
        "INSERT INTO recipes(source, source_url, title, servings, data, ai_provider) VALUES(%s,%s,%s,%s,%s,%s) RETURNING id",
        (r.source, r.source_url, r.title, r.servings, Jsonb(r.model_dump(mode="json", exclude={"id"})), ai_provider),
    ).fetchone()[0]


def get_recipe(conn, rid: int, for_update: bool = False) -> Recipe | None:
    row = conn.execute("SELECT id, data FROM recipes WHERE id=%s" + (" FOR UPDATE" if for_update else ""), (rid,)).fetchone()
    return Recipe(**row[1], id=row[0]) if row else None



def update_recipe(conn, rid: int, r: Recipe) -> None:
    conn.execute("UPDATE recipes SET title=%s, servings=%s, data=%s WHERE id=%s",
                 (r.title, r.servings, Jsonb(r.model_dump(mode="json", exclude={"id"})), rid))


def list_recipes(conn) -> list[Recipe]:
    return [Recipe(**d, id=i) for i, d in conn.execute("SELECT id, data FROM recipes ORDER BY id DESC")]


def get_pick(conn, key: str) -> str | None:
    row = get_pick_full(conn, key)
    return row[0] if row else None


def get_pick_full(conn, key: str) -> tuple[str, str] | None:
    """(product_code, chosen_by) or None."""
    return conn.execute("SELECT product_code, chosen_by FROM picks WHERE key=%s", (key,)).fetchone()


def last_purchase(conn, key: str, code: str) -> tuple[float | None, int] | None:
    """(need_qty, quantity) of the most recent added cart line for this list key + product."""
    return conn.execute("SELECT need_qty, quantity FROM cart_lines WHERE item_key=%s AND product_code=%s "
                        "AND status='added' ORDER BY id DESC LIMIT 1", (key, code)).fetchone()


def set_pick(conn, key: str, code: str, chosen_by: str = "ai") -> None:
    with conn.transaction():
        conn.execute(
            "INSERT INTO picks(key, product_code, chosen_by) VALUES(%s,%s,%s) ON CONFLICT(key) DO UPDATE "
            "SET product_code=excluded.product_code, chosen_by=excluded.chosen_by, updated_at=now()",
            (key, code, chosen_by))
        conn.execute("INSERT INTO pick_history(key, product_code, chosen_by) VALUES(%s,%s,%s)", (key, code, chosen_by))


def week_start(d: date) -> date:
    return d - timedelta(days=(d.weekday() + 1) % 7)   # Python: Mon=0 … Sun=6


def add_to_week(conn, week: date, recipe_id: int) -> int:
    return conn.execute("INSERT INTO plan(week, recipe_id) VALUES(%s,%s) RETURNING id",
                        (week_start(week), recipe_id)).fetchone()[0]


def _rating(family, company, note, rated_at) -> Rating | None:
    if family is None:
        return None
    return Rating(family=family, company=company, note=note, rated_at=rated_at.isoformat())


def get_week(conn, week: date) -> list[PlanEntry]:
    rows = conn.execute("SELECT p.id, p.week, p.recipe_id, p.day, p.multiplier, rc.title, "
                        "r.family, r.company, r.note, r.rated_at "
                        "FROM plan p JOIN recipes rc ON rc.id = p.recipe_id LEFT JOIN ratings r ON r.plan_id = p.id "
                        "WHERE p.week=%s ORDER BY COALESCE(p.day, 99), p.id", (week_start(week),))
    return [PlanEntry(id=i, week=w.isoformat(), recipe_id=r, day=d, multiplier=m, title=t, rating=_rating(*rt))
            for i, w, r, d, m, t, *rt in rows]


_UNSET = object()


def update_entry(conn, entry_id: int, day=_UNSET, multiplier=_UNSET) -> None:
    """Update only the fields passed; day=None explicitly un-places the recipe from its night."""
    sets, vals = [], []
    if day is not _UNSET:
        sets.append("day=%s"); vals.append(day)
    if multiplier is not _UNSET:
        sets.append("multiplier=%s"); vals.append(multiplier)
    if sets:
        conn.execute(f"UPDATE plan SET {', '.join(sets)} WHERE id=%s", (*vals, entry_id))


def remove_entry(conn, entry_id: int) -> None:
    conn.execute("DELETE FROM plan WHERE id=%s", (entry_id,))


def record_products(conn, store_id: str, products) -> None:
    """Upsert search results into products and log one price observation each (price/stock history)."""
    if not products:
        return
    with conn.transaction():
        for p in products:
            conn.execute(
                "INSERT INTO products(code, name, brand, package_size) VALUES(%s,%s,%s,%s) ON CONFLICT(code) DO UPDATE "
                "SET name=excluded.name, brand=excluded.brand, package_size=excluded.package_size, last_seen=now()",
                (p.code, p.name, p.brand, p.package_size))
            conn.execute("INSERT INTO price_observations(code, store_id, price, stock) VALUES(%s,%s,%s,%s)",
                         (p.code, store_id, p.price, p.stock))


def week_summaries(conn, start: date, count: int) -> list[dict]:
    """One row per week from start's Sunday: number of plan entries and whether any cart covers it."""
    first = week_start(start)
    weeks = [first + timedelta(weeks=i) for i in range(count)]
    entries = dict(conn.execute("SELECT week, count(*) FROM plan WHERE week = ANY(%s) GROUP BY week", (weeks,)).fetchall())
    carted = {w for (w,) in conn.execute("SELECT DISTINCT cw.week FROM cart_weeks cw JOIN carts c ON c.id = cw.cart_id "
                                         "WHERE c.status = 'sent' AND cw.week = ANY(%s)", (weeks,))}
    return [{"week": w.isoformat(), "entries": entries.get(w, 0), "carted": w in carted} for w in weeks]


def default_cart_week(conn, today: date) -> date | None:
    """Earliest week >= this week's Sunday that has plan entries and no cart yet."""
    row = conn.execute("SELECT min(p.week) FROM plan p WHERE p.week >= %s "
                       "AND NOT EXISTS (SELECT 1 FROM cart_weeks cw JOIN carts c ON c.id = cw.cart_id "
                       "WHERE c.status = 'sent' AND cw.week = p.week)", (week_start(today),)).fetchone()
    return row[0] if row else None


# --- ratings: one per plan entry (time cooked); rating_history is the append-only log for reports ---

def set_rating(conn, entry_id: int, family: int, company: str | None = None, note: str | None = None,
               at: datetime | None = None) -> bool:
    """Upsert the entry's rating and log it. False if the plan entry doesn't exist."""
    with conn.transaction():
        row = conn.execute("SELECT recipe_id, week, day FROM plan WHERE id=%s FOR UPDATE", (entry_id,)).fetchone()
        if row is None:
            return False
        conn.execute(
            "INSERT INTO ratings(plan_id, family, company, note, rated_at) VALUES(%s,%s,%s,%s,COALESCE(%s::timestamptz, now())) "
            "ON CONFLICT(plan_id) DO UPDATE SET family=excluded.family, company=excluded.company, note=excluded.note, "
            "rated_at=excluded.rated_at", (entry_id, family, company, note, at))
        conn.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, family, company, note, at) "
                     "VALUES(%s,%s,%s,%s,'set',%s,%s,%s,COALESCE(%s::timestamptz, now()))", (entry_id, *row, family, company, note, at))
    return True


def delete_rating(conn, entry_id: int) -> bool:
    """Remove the entry's rating (logged in history if there was one). False if the plan entry doesn't exist."""
    with conn.transaction():
        row = conn.execute("SELECT recipe_id, week, day FROM plan WHERE id=%s FOR UPDATE", (entry_id,)).fetchone()
        if row is None:
            return False
        if conn.execute("DELETE FROM ratings WHERE plan_id=%s", (entry_id,)).rowcount:
            conn.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action) VALUES(%s,%s,%s,%s,'delete')",
                         (entry_id, *row))
    return True


def pending_ratings(conn, today: date, days: int = 14) -> list[dict]:
    """Entries placed on a night before today (within `days`) that have no rating yet — most recent first."""
    rows = conn.execute(
        "SELECT p.id, p.week, p.day, p.week + p.day::int AS d, p.recipe_id, r.title, p.multiplier "
        "FROM plan p JOIN recipes r ON r.id = p.recipe_id "
        "WHERE p.day IS NOT NULL AND p.week + p.day::int < %(t)s AND p.week + p.day::int >= %(t)s - %(n)s::int "
        "AND NOT EXISTS (SELECT 1 FROM ratings x WHERE x.plan_id = p.id) ORDER BY d DESC, p.id",
        {"t": today, "n": days})
    return [{"entry_id": i, "week": w.isoformat(), "day": dy, "date": d.isoformat(), "recipe_id": rid, "title": t,
             "multiplier": m} for i, w, dy, d, rid, t, m in rows]


def _empty_summary() -> dict:
    return {"times_cooked": 0, "times_rated": 0, "avg_family": None, "last_family": None,
            "last_rated_at": None, "company": None, "notes": []}


def rating_summaries(conn, today: date, recipe_ids: list[int] | None = None) -> dict[int, dict]:
    """Per-recipe rating summary (all recipes, or just recipe_ids)."""
    where = "" if recipe_ids is None else " WHERE id = ANY(%(ids)s)"
    out = {rid: _empty_summary() for (rid,) in conn.execute("SELECT id FROM recipes" + where, {"ids": recipe_ids})}
    rows = conn.execute(
        "SELECT p.recipe_id, p.week + p.day::int, r.family, r.company, r.note, r.rated_at "
        "FROM plan p LEFT JOIN ratings r ON r.plan_id = p.id"
        + ("" if recipe_ids is None else " WHERE p.recipe_id = ANY(%(ids)s)")
        + " ORDER BY r.rated_at DESC NULLS LAST, p.id DESC", {"ids": recipe_ids})
    totals: dict[int, int] = {}
    for rid, cooked_on, family, company, note, rated_at in rows:   # rated rows newest first
        s = out.get(rid)
        if s is None:
            continue
        if family is None:
            if cooked_on is not None and cooked_on < today:
                s["times_cooked"] += 1
            continue
        s["times_cooked"] += 1
        s["times_rated"] += 1
        totals[rid] = totals.get(rid, 0) + family
        if s["last_family"] is None:
            s["last_family"], s["last_rated_at"] = family, rated_at.isoformat()
        if s["company"] is None and company is not None:
            s["company"] = company
        if note and len(s["notes"]) < 5:
            s["notes"].append({"note": note, "date": cooked_on.isoformat() if cooked_on else None,
                               "rated_at": rated_at.isoformat()})
    for rid, total in totals.items():
        avg = Decimal(total) / Decimal(out[rid]["times_rated"])
        out[rid]["avg_family"] = float(avg.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))
    return out


def recipe_history(conn, recipe_id: int) -> list[dict]:
    """Every plan entry for a recipe (newest week first) with its rating."""
    rows = conn.execute(
        "SELECT p.id, p.week, p.day, p.week + p.day::int, p.multiplier, r.family, r.company, r.note, r.rated_at "
        "FROM plan p LEFT JOIN ratings r ON r.plan_id = p.id WHERE p.recipe_id=%s "
        "ORDER BY p.week DESC, COALESCE(p.day, -1) DESC, p.id DESC", (recipe_id,))
    return [{"entry_id": i, "week": w.isoformat(), "day": dy, "date": d.isoformat() if d else None, "multiplier": m,
             "rating": (rt.model_dump() if (rt := _rating(*r)) else None)} for i, w, dy, d, m, *r in rows]
