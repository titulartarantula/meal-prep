"""Sunday prep plans + day-of cook cards — a background job like cart drafts (building → ready | failed).

One LLM call writes the prep plan; prep_rules corrects it deterministically; then one cook card per placed
entry is written concurrently in a bounded pool. All DB writes stay on the job thread's connection."""
import json, logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta

from psycopg.types.json import Jsonb
from pydantic import ValidationError

from . import db
from .ai import AIError
from .models import CookCard, Ingredient, MealIn, PrepTask, RawCookCard, RawPrepPlan
from .prep_rules import DAYS, SECTIONS, TITLES, apply_rules
from .ingredients import clean_ingredient
from .shopping import scale_factor

log = logging.getLogger(__name__)


def _qty(q: float) -> str:
    return ("%.2f" % q).rstrip("0").rstrip(".")


def _ingredient_line(ing: Ingredient, factor: float) -> str:
    ing = clean_ingredient(ing)
    parts = ([_qty(ing.qty * factor)] if ing.qty is not None else []) + ([ing.unit] if ing.unit else []) + [ing.name]
    s = " ".join(parts)
    s += f", {ing.prep}" if ing.prep else ""
    return s + (f" (for the {ing.sub_recipe})" if ing.sub_recipe else "")


def _label(week: date, day: int | None, multi: bool) -> str:
    if day is None:
        return "no night yet"
    d = week + timedelta(days=day)
    return f"{DAYS[day]} {d.day} {d:%b}" if multi else DAYS[day]


def gather_meals(conn, weeks: list[date], today: date, people: int = 4) -> list[MealIn]:
    """The weeks' plan entries, scaled, with nights relative to the first week's Sunday (prep day)."""
    wks = sorted({db.week_start(w) for w in weeks})
    if not wks:
        return []
    prep, multi = wks[0], len(wks) > 1
    entries = [e for w in wks for e in db.get_week(conn, w)]
    notes = db.rating_summaries(conn, today, list({e.recipe_id for e in entries})) if entries else {}
    out = []
    for e in entries:
        r = db.get_recipe(conn, e.recipe_id)
        if r is None:
            continue
        wk = date.fromisoformat(e.week)
        night = wk + timedelta(days=e.day) if e.day is not None else None
        f = scale_factor(r.servings, e.multiplier, people)
        recent = notes.get(r.id, {}).get("notes", [])[:3]
        out.append(MealIn(
            entry_id=e.id, recipe_id=r.id, title=r.title, week=e.week, day=e.day, multiplier=e.multiplier,
            date=night.isoformat() if night else None, offset=(night - prep).days if night else None,
            label=_label(wk, e.day, multi), factor=round(f, 3),
            ingredients=[_ingredient_line(i, f) for i in r.ingredients if not i.expanded], steps=list(r.steps),
            notes=[("last time: " if k == 0 else f"{n['date'] or 'earlier'}: ") + n["note"]
                   for k, n in enumerate(recent)]))
    return out



class NothingPlanned(Exception):
    pass


class PrepNotFound(Exception):
    pass


class PrepStateError(Exception):
    pass


class PrepFailed(Exception):
    """A readable failure message for the plan's `error`."""


PLAN_PROMPT = """Write the Sunday PREP PLAN for a family of 4 (2 adults, 2 kids). The cook preps on Sunday and cooks each dinner on its night.
SUNDAY IS PREP ONLY: nothing is heated or cooked on Sunday — no roasting, baking, boiling (rice and pasta included), simmering, frying, sautéing, toasting or melting. Hot steps belong on the night and must NOT appear in this plan.
The meals (JSON below) give each entry_id, the recipe, its night ("no night yet" = not placed: prep only what clearly keeps), days_after_prep_sunday, the ingredients already scaled to the amounts to use, the method, and notes from past ratings.

Return four sections, in this order:
- "knife": washing, peeling and cutting, batched ACROSS recipes with quantities summed and split by night, e.g. "Dice 3 onions: 2 Tue chili, 1 Thu soup".
- "sauces": sauces, dressings, marinades and spice blends mixed and jarred (cold).
- "proteins": trim, portion, marinate where safe.
- "pack": exactly ONE kit per night that has a meal: text like "TUE – chili kit", "contents": the prepped and measured items going in it (each with its amount).
Every task: {"text": str, "serves": [entry_id, ...], "est_minutes": int, "shelf_life": "ok" | "day_of" | "freeze_then_thaw", "thaw": str or null, "contents": [str] (pack only, else [])}.
Shelf life — "ok" keeps in the fridge until its night; otherwise:
- "day_of" (leave it for the night): cutting avocado, apple, pear, banana or potato; chopping delicate herbs (basil, cilantro, mint, dill, parsley leaves); an acidic marinade (citrus, vinegar, wine, yogurt) on fish for any night after Sunday.
- "freeze_then_thaw": raw fish/seafood or ground meat for a night more than 2 days after Sunday (Wednesday onward), raw chicken for Thursday onward, and chicken in an acidic marinade more than 2 days out (freeze it in the marinade). Put the instruction in "thaw", e.g. "Freeze Sunday; move to the fridge Tuesday night".
Only include prep that saves real time on the night; an empty section is []. Write for the cook: short, imperative, with amounts.
"warnings": anything the cook should know (a fragile meal late in the week, a recipe with no night yet, something that can't be prepped ahead).
Return ONLY JSON: {"knife": [...], "sauces": [...], "proteins": [...], "pack": [...], "warnings": [str]}

Meals:
{meals}"""

CARD_PROMPT = """Write the day-of COOK CARD for one dinner tonight. Sunday prep is done: the kit below is in the fridge, so don't repeat that prep.
Steps for tonight, in order — aim for 20–30 minutes of work where the recipe allows. Include everything hot (rice, pasta, roasting, baking, simmering…) and the "do tonight" items. Use the scaled amounts. If a past rating note suggests a change (e.g. "less salt"), apply it in the steps.
Each step: {"text": str, "minutes": int or null (how long the step takes), "timer_minutes": int or null (only for a timed wait: oven, simmer, rest)}.
Return ONLY JSON: {"steps": [...], "total_minutes": int (start to plate)}

Meal: {meal}
Kit (prepped Sunday): {kit}
Do tonight (not prepped Sunday): {day_of}
Thawing: {thaw}"""


def _meal_json(m: MealIn) -> dict:
    return {"entry_id": m.entry_id, "recipe": m.title, "night": m.label, "days_after_prep_sunday": m.offset,
            "scale": f"×{m.factor:g} of the recipe as written (family of 4)", "ingredients": m.ingredients,
            "steps": m.steps, "past_rating_notes": m.notes}


def plan_prompt(meals: list[MealIn]) -> str:
    return PLAN_PROMPT.replace("{meals}", json.dumps([_meal_json(m) for m in meals], ensure_ascii=False, indent=1))


def card_prompt(m: MealIn, kit: list[str], day_of: list[str], thaw: list[str]) -> str:
    j = lambda x: json.dumps(x, ensure_ascii=False)
    return (CARD_PROMPT.replace("{meal}", j(_meal_json(m))).replace("{kit}", j(kit))
            .replace("{day_of}", j(day_of)).replace("{thaw}", j(thaw)))


def _ask(provider, prompt: str, model, what: str):
    try:
        out = provider.complete_json(prompt)
    except AIError as e:
        raise PrepFailed(f"{what}: AI call failed: {e}") from e
    try:
        return model.model_validate(out)
    except ValidationError as e:
        first = e.errors()[0]
        raise PrepFailed(f"{what}: AI returned an unexpected shape ({e.error_count()} problem(s); first: "
                         f"{'.'.join(map(str, first['loc'])) or 'top level'}: {first['msg']})") from e


def create_prep_plan(conn, weeks, today: date, ai_provider: str | None = None, people: int = 4) -> int:
    """Record a building plan with a snapshot of its input. No AI calls."""
    wks = sorted({db.week_start(w) for w in weeks})
    if not wks:
        raise NothingPlanned("pick at least one week")
    meals = gather_meals(conn, wks, today, people)
    if not meals:
        raise NothingPlanned(f"no recipes planned for {', '.join(w.isoformat() for w in wks)}")
    placed = sum(m.day is not None for m in meals)
    return conn.execute("INSERT INTO prep_plans(weeks, ai_provider, input, progress_total) VALUES(%s,%s,%s,%s) "
                        "RETURNING id", (wks, ai_provider, Jsonb([m.model_dump() for m in meals]), 1 + placed)
                        ).fetchone()[0]


def build_prep_plan(conn, plan_id: int, provider, workers: int = 4) -> None:
    """Write the plan + cards, then mark ready; any failure → failed with a readable error (never stuck)."""
    try:
        _build(conn, plan_id, provider, workers)
    except Exception as e:
        log.exception("prep plan %s failed", plan_id)
        msg = str(e) if isinstance(e, PrepFailed) else f"build failed: {type(e).__name__}: {str(e)[:300]}"
        conn.execute("UPDATE prep_plans SET status='failed', error=%s, finished_at=now() "
                     "WHERE id=%s AND status='building'", (msg, plan_id))


def _kit(tasks: list[PrepTask], entry_id: int) -> list[str]:
    return [c for t in tasks if t.section == "pack" and entry_id in {s.entry_id for s in t.serves}
            for c in (t.contents or [t.text])]


def _build(conn, plan_id, provider, workers):
    row = conn.execute("SELECT input FROM prep_plans WHERE id=%s AND status='building'", (plan_id,)).fetchone()
    if row is None:
        return
    meals = [MealIn(**m) for m in row[0]]
    res = apply_rules(_ask(provider, plan_prompt(meals), RawPrepPlan, "prep plan"), meals)
    total = sum(t.est_minutes for t in res.tasks if t.shelf_life != "day_of")
    with conn.transaction():
        for pos, t in enumerate(res.tasks):
            conn.execute("INSERT INTO prep_tasks(prep_plan_id, task_id, section, position, text, serves, est_minutes, "
                         "shelf_life, thaw, contents, flags) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                         (plan_id, t.id, t.section, pos, t.text, Jsonb([s.model_dump() for s in t.serves]),
                          t.est_minutes, t.shelf_life, t.thaw, Jsonb(t.contents), Jsonb(t.flags)))
        conn.execute("UPDATE prep_plans SET warnings=%s, total_minutes=%s, progress_done=1 WHERE id=%s",
                     (Jsonb(res.warnings), total, plan_id))
    placed = [m for m in meals if m.day is not None]
    errors = []
    with ThreadPoolExecutor(max_workers=max(1, workers), thread_name_prefix=f"prep{plan_id}") as pool:
        futs = {}
        for m in placed:
            kit, day_of, thaw = _kit(res.tasks, m.entry_id), res.day_of.get(m.entry_id, []), res.thaw.get(m.entry_id, [])
            fut = pool.submit(_ask, provider, card_prompt(m, kit, day_of, thaw), RawCookCard,
                              f"cook card for {m.label} {m.title}")
            futs[fut] = (m, kit, day_of, thaw)
        for fut in as_completed(futs):
            m, kit, day_of, thaw = futs[fut]
            try:
                raw = fut.result()
            except PrepFailed as e:
                errors.append(str(e))
                continue
            except Exception as e:
                errors.append(f"cook card for {m.label} {m.title}: {type(e).__name__}: {str(e)[:200]}")
                continue
            card = CookCard(entry_id=m.entry_id, recipe_id=m.recipe_id, title=m.title, night=m.label, date=m.date,
                            kit=kit, day_of=day_of, thaw=thaw, steps=raw.steps,
                            total_minutes=raw.total_minutes or sum(s.minutes or 0 for s in raw.steps),
                            rating_notes=m.notes)
            with conn.transaction():
                # the entry may have been deleted meanwhile: skip its card rather than fail the plan
                conn.execute("INSERT INTO cook_cards(prep_plan_id, plan_id, recipe_id, week, day, multiplier, card) "
                             "SELECT %s,%s,%s,%s,%s,%s,%s WHERE EXISTS (SELECT 1 FROM plan WHERE id=%s)",
                             (plan_id, m.entry_id, m.recipe_id, m.week, m.day, m.multiplier,
                              Jsonb(card.model_dump(exclude={"prep_plan_id", "generated_at", "stale"})), m.entry_id))
                conn.execute("UPDATE prep_plans SET progress_done = progress_done + 1 WHERE id=%s", (plan_id,))
    if errors:
        raise PrepFailed("; ".join(sorted(errors)))
    with conn.transaction():
        # regenerating replaces cards: drop older plans' cards for these entries (the plans stay, for history)
        conn.execute("DELETE FROM cook_cards WHERE prep_plan_id < %s AND plan_id = ANY(%s)",
                     (plan_id, [m.entry_id for m in placed]))
        conn.execute("UPDATE prep_plans SET status='ready', finished_at=now() WHERE id=%s AND status='building'",
                     (plan_id,))


def fail_interrupted(conn) -> None:
    """At startup: plans that were generating when the process stopped will never finish."""
    conn.execute("UPDATE prep_plans SET status='failed', finished_at=now(), "
                 "error='interrupted by a server restart; generate the prep plan again' WHERE status='building'")


_TASK_SQL = ("SELECT task_id, section, text, serves, est_minutes, shelf_life, thaw, contents, flags, done, done_at "
             "FROM prep_tasks WHERE prep_plan_id=%s")


def _task(row) -> dict:
    tid, section, text, serves, est, shelf, thaw, contents, flags, done, done_at = row
    return PrepTask(id=tid, section=section, text=text, serves=serves, est_minutes=est, shelf_life=shelf, thaw=thaw,
                    contents=contents, flags=flags, done=done,
                    done_at=done_at.isoformat() if done_at else None).model_dump()


def _checklist(tasks: list[dict]) -> dict:
    """Sunday work only (day_of tasks happen on the night). actual_minutes = first tick → last tick, once all done."""
    sunday = [t for t in tasks if t["shelf_life"] != "day_of"]
    ticks = sorted(t["done_at"] for t in sunday if t["done"] and t["done_at"])
    done = sum(t["done"] for t in sunday)
    actual = None
    if sunday and done == len(sunday) and ticks:
        actual = round((datetime.fromisoformat(ticks[-1]) - datetime.fromisoformat(ticks[0])).total_seconds() / 60)
    return {"done": done, "total": len(sunday), "est_minutes": sum(t["est_minutes"] for t in sunday),
            "first_done_at": ticks[0] if ticks else None, "last_done_at": ticks[-1] if ticks else None,
            "actual_minutes": actual}


def _stale(conn, weeks, meals: list[MealIn]) -> bool:
    now = {(e.id, e.recipe_id, e.day, e.multiplier) for w in weeks for e in db.get_week(conn, w)}
    return now != {(m.entry_id, m.recipe_id, m.day, m.multiplier) for m in meals}


def get_prep_plan(conn, plan_id: int) -> dict | None:
    row = conn.execute("SELECT status, error, weeks, input, warnings, total_minutes, progress_done, progress_total, "
                       "created_at, finished_at FROM prep_plans WHERE id=%s", (plan_id,)).fetchone()
    if row is None:
        return None
    status, error, weeks, inp, warnings, total, done, ptotal, created, finished = row
    meals = [MealIn(**m) for m in inp]
    tasks = [_task(r) for r in conn.execute(_TASK_SQL + " ORDER BY position", (plan_id,))]
    carded = {pid for (pid,) in conn.execute("SELECT plan_id FROM cook_cards WHERE prep_plan_id=%s", (plan_id,))}
    return {"id": plan_id, "status": status, "error": error, "weeks": [w.isoformat() for w in weeks],
            "progress": {"done": done, "total": ptotal}, "created_at": created.isoformat(),
            "finished_at": finished.isoformat() if finished else None,
            "generation_seconds": round((finished - created).total_seconds(), 1) if finished else None,
            "total_minutes": total, "warnings": warnings,
            "sections": [{"key": s, "title": TITLES[s], "tasks": [t for t in tasks if t["section"] == s]}
                         for s in SECTIONS],
            "entries": [{"entry_id": m.entry_id, "recipe_id": m.recipe_id, "title": m.title, "night": m.label,
                         "date": m.date, "has_card": m.entry_id in carded} for m in meals],
            "checklist": _checklist(tasks), "stale": _stale(conn, weeks, meals)}


def latest_for_week(conn, week: date) -> dict | None:
    """Newest plan covering the week (any status) + last_ready_id, so a failed regeneration can fall back."""
    rows = conn.execute("SELECT id, status FROM prep_plans WHERE %s = ANY(weeks) ORDER BY id DESC",
                        (db.week_start(week),)).fetchall()
    if not rows:
        return None
    out = get_prep_plan(conn, rows[0][0])
    out["last_ready_id"] = next((i for i, s in rows if s == "ready"), None)
    return out


def set_task_done(conn, plan_id: int, task_id: str, done: bool) -> dict:
    with conn.transaction():
        row = conn.execute("SELECT status FROM prep_plans WHERE id=%s FOR UPDATE", (plan_id,)).fetchone()
        if row is None:
            raise PrepNotFound(f"prep plan {plan_id}")
        if row[0] != "ready":
            raise PrepStateError(f"prep plan {plan_id} is {row[0]}, not ready")
        n = conn.execute("UPDATE prep_tasks SET done=%s, done_at = CASE WHEN %s THEN COALESCE(done_at, now()) END "
                         "WHERE prep_plan_id=%s AND task_id=%s", (done, done, plan_id, task_id)).rowcount
        if not n:
            raise PrepNotFound(f"task {task_id} in prep plan {plan_id}")
        conn.execute("INSERT INTO prep_task_events(prep_plan_id, task_id, done) VALUES(%s,%s,%s)",
                     (plan_id, task_id, done))
        return _task(conn.execute(_TASK_SQL + " AND task_id=%s", (plan_id, task_id)).fetchone())


def get_card(conn, entry_id: int) -> dict | None:
    """The entry's card from the newest ready plan; stale if the entry moved night or multiplier since."""
    row = conn.execute("SELECT cc.card, cc.day, cc.multiplier, cc.prep_plan_id, pp.finished_at FROM cook_cards cc "
                       "JOIN prep_plans pp ON pp.id = cc.prep_plan_id WHERE cc.plan_id=%s AND pp.status='ready' "
                       "ORDER BY pp.id DESC LIMIT 1", (entry_id,)).fetchone()
    if row is None:
        return None
    card, day, mult, pid, finished = row
    cur = conn.execute("SELECT day, multiplier FROM plan WHERE id=%s", (entry_id,)).fetchone()
    return CookCard(**card, prep_plan_id=pid, generated_at=finished.isoformat() if finished else None,
                    stale=cur is None or tuple(cur) != (day, mult)).model_dump()
