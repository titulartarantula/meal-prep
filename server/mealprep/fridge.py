"""Fridge cleaner (0.14.0): the household's "In the fridge" list (perishables on hand) and what recipes use of it.

- An item's `key` is the shopping list's name for it (`staples.name_key`: cleaned, singular, no unit family), set
  once when the name is added or changed: a name already in the household's vocabulary (library recipe lines +
  staples) is used as is; otherwise one AI call maps it onto a vocabulary name, else the rule (`rule_key`). Never
  on a suggest or a list, and an AI failure still saves the item.
- Items never clear by themselves (not after a cart, a dinner or a photo): only a person removes them. Every amount
  carries `amount_as_of` (set when the amount is set or confirmed), so a stale count shows instead of being trusted.
"""
import hashlib
import json
import logging
from collections import Counter
from dataclasses import dataclass, field
from datetime import date

import psycopg
from psycopg.types.json import Jsonb

from . import db, shopping
from .ingredients import BASE, clean_ingredient
from .models import FridgeItem, Ingredient, Recipe
from .planner import _plural, _qty_text
from .staples import name_key

log = logging.getLogger(__name__)

VOCAB_CAP = 600          # vocabulary names kept (most used first)
SOON_DAYS = 3            # a use-by within this many days counts as "use soon"
STALE_DAYS = 7           # "last checked N days ago" after this many days


class FridgeNotFound(Exception):
    pass


class FridgeExists(Exception):
    """Same key + unit as an existing item; `item` is that item (the app offers to edit it)."""
    def __init__(self, item: FridgeItem):
        super().__init__(f"Already in the fridge: {amount_text(item.qty, item.unit, item.name)}")
        self.item = item


def rule_key(name: str) -> str:
    """The deterministic key: "Large Eggs" → "egg", "Green onions" → "green onion"."""
    return name_key(" ".join(name.split()))


def amount_text(qty: float | None, unit: str | None, name: str) -> str:
    """"6 eggs", "2 cups milk", "½ cup cream", "250 ml cream", "some cilantro" (no amount)."""
    if qty is None:
        return f"some {name}"
    q = _qty_text(round(qty) if unit in ("g", "ml") and qty >= 10 else qty, unit)
    return f"{q} {_plural(unit, qty)} {name}" if unit else f"{q} {name}"


# --- vocabulary + the one-time name mapping -----------------------------------------------------------------------

def vocabulary(conn) -> list[str]:
    """The household's item names (keys) from library recipe lines and staples, most used first (then A–Z)."""
    counts: Counter = Counter()
    for (data,) in conn.execute("SELECT data FROM recipes"):
        for raw in data.get("ingredients") or []:
            try:
                ing = Ingredient(**raw)
            except Exception:
                continue
            if ing.expanded:
                continue
            k = name_key(clean_ingredient(ing).name)
            if k:
                counts[k] += 1
    for (name,) in conn.execute("SELECT name FROM staples"):
        k = name_key(name)
        if k:
            counts[k] += 1
    return [k for k, _ in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))][:VOCAB_CAP]


def suggest_names(conn, q: str, limit: int = 12) -> list[str]:
    """Vocabulary names containing q (any case): those starting with it first, each group most used first."""
    q = " ".join(q.lower().split())
    if not q:
        return []
    hits = [v for v in vocabulary(conn) if q in v]
    return (sorted(hits, key=lambda v: not v.startswith(q)))[:limit]


MAP_PROMPT = """Someone is listing foods in their fridge. Match each fridge name to the household's vocabulary: the names their recipes and shopping list use for things to buy.
For each fridge name, give the vocabulary name for the SAME food as you would buy it ("spring onions" → "scallion", "coriander leaves" → "cilantro"), or null when no vocabulary name is the same food (a different food, or a different form: "dried cranberries" is not "cranberry", "chicken breast" is not "chicken thigh").
Return ONLY JSON: {{"matches": [{{"name": <the fridge name exactly as given>, "match": <a vocabulary name exactly as listed, or null>}}]}}
Fridge names: {names}
Vocabulary: {vocab}"""


def _answers(d, names: list[str]) -> list:
    """The AI's match per name, in order (None where it gave none)."""
    if isinstance(d, dict) and "matches" not in d and len(names) == 1:
        return [d.get("match")]
    rows = d.get("matches") if isinstance(d, dict) else d
    if not isinstance(rows, list):
        return [None] * len(names)
    by_name = {str(r.get("name", "")).strip().lower(): r.get("match") for r in rows if isinstance(r, dict)}
    if len(by_name) == len(names) and not any(n.strip().lower() in by_name for n in names):   # names not echoed
        return [r.get("match") if isinstance(r, dict) else None for r in rows]
    return [by_name.get(n.strip().lower()) for n in names]


def map_names(provider, names: list[str], vocab: list[str], from_list: bool = False) -> list[tuple[str, str]]:
    """(key, key_source) per name with at most ONE AI call for all names not already in the vocabulary.
    Sources: 'list' (a vocabulary name), 'ai' (the AI's answer, accepted only if it is a vocabulary name), 'rule'."""
    vset = set(vocab)
    out: list[tuple[str, str] | None] = []
    ask = []
    for n in names:
        k = rule_key(n)
        if k in vset:
            out.append((k, "list"))
        else:
            out.append(None)
            ask.append(n)
    answers: dict[str, str] = {}
    if ask and vocab and provider is not None and not from_list:
        try:
            d = provider.complete_json(MAP_PROMPT.format(names=json.dumps(ask, ensure_ascii=False),
                                                         vocab=json.dumps(vocab, ensure_ascii=False)))
            for n, m in zip(ask, _answers(d, ask)):
                if isinstance(m, str):
                    mk = m.strip().lower()
                    mk = mk if mk in vset else name_key(mk)
                    if mk in vset:
                        answers[n] = mk
        except Exception as e:   # AIError, timeouts, anything: the rule still saves the item
            log.warning("fridge name mapping failed (%s): %s", type(e).__name__, str(e)[:200])
    return [o or ((answers[n], "ai") if n in answers else (rule_key(n), "rule")) for o, n in zip(out, names)]


def map_name(provider, name: str, vocab: list[str], from_list: bool = False) -> tuple[str, str]:
    return map_names(provider, [name], vocab, from_list=from_list)[0]


# --- items ------------------------------------------------------------------------------------------------------

_COLS = "id, name, key, key_source, qty::float, unit, qty_estimated, amount_as_of, use_soon, use_by"


def _item(row, today: date) -> FridgeItem:
    iid, name, key, src, qty, unit, est, as_of, use_soon, use_by = row
    return FridgeItem(id=iid, name=name, key=key, key_source=src, qty=qty, unit=unit, qty_estimated=est,
                      amount_as_of=as_of.isoformat(), days_since_checked=max(0, (today - as_of).days),
                      use_soon=use_soon, use_by=use_by.isoformat() if use_by else None,
                      soon=use_soon or (use_by is not None and (use_by - today).days <= SOON_DAYS))


def list_items(conn, today: date) -> list[FridgeItem]:
    """Soon first (use soon, or use-by within 3 days), then name A–Z."""
    items = [_item(r, today) for r in conn.execute(f"SELECT {_COLS} FROM fridge_items")]
    return sorted(items, key=lambda i: (not i.soon, i.name.lower(), i.id))


def get_item(conn, iid: int, today: date) -> FridgeItem:
    row = conn.execute(f"SELECT {_COLS} FROM fridge_items WHERE id=%s", (iid,)).fetchone()
    if row is None:
        raise FridgeNotFound(f"fridge item {iid}")
    return _item(row, today)


def _same(conn, key: str, unit: str | None, today: date, except_id: int | None = None) -> FridgeItem | None:
    row = conn.execute(f"SELECT {_COLS} FROM fridge_items WHERE key=%s AND coalesce(unit, '')=%s AND id IS DISTINCT FROM %s",
                       (key, unit or "", except_id)).fetchone()
    return _item(row, today) if row else None


def create_item(conn, provider, name: str, qty: float | None = None, unit: str | None = None, *, today: date,
                use_soon: bool = False, use_by: date | None = None, from_list: bool = False,
                estimated: bool = False) -> FridgeItem:
    """Add an item (the name mapped once). Same key + unit as an item already there → FridgeExists."""
    key, src = map_name(provider, name, vocabulary(conn), from_list=from_list)
    row = conn.execute("INSERT INTO fridge_items(name, key, key_source, qty, unit, qty_estimated, amount_as_of, use_soon, "
                       "use_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING RETURNING id",
                       (name, key, src, qty, unit, estimated, today, use_soon, use_by)).fetchone()
    if row is None:
        raise FridgeExists(_same(conn, key, unit, today))
    return get_item(conn, row[0], today)


_UNSET = object()


def update_item(conn, provider, iid: int, *, today: date, name=_UNSET, qty=_UNSET, unit=_UNSET, use_soon=_UNSET,
                use_by=_UNSET, confirm: bool = False, from_list: bool = False) -> FridgeItem:
    """Change only the fields passed. A new name is mapped again (one AI call at most); setting the amount (qty or
    unit) dates it today and makes it typed, not estimated; `confirm` ("still right") only dates it today."""
    cur = get_item(conn, iid, today)
    sets, vals, key = [], [], cur.key
    if name is not _UNSET and name != cur.name:
        key, src = map_name(provider, name, vocabulary(conn), from_list=from_list)
        sets += ["name=%s", "key=%s", "key_source=%s"]; vals += [name, key, src]
    if qty is not _UNSET or unit is not _UNSET:
        if qty is not _UNSET:
            sets.append("qty=%s"); vals.append(qty)
        if unit is not _UNSET:
            sets.append("unit=%s"); vals.append(unit)
        sets += ["qty_estimated=false", "amount_as_of=%s"]; vals.append(today)
    elif confirm:
        sets.append("amount_as_of=%s"); vals.append(today)
    for col, v in (("use_soon", use_soon), ("use_by", use_by)):
        if v is not _UNSET:
            sets.append(f"{col}=%s"); vals.append(v)
    if sets:
        try:
            with conn.transaction():
                n = conn.execute(f"UPDATE fridge_items SET {', '.join(sets)}, updated_at=now() WHERE id=%s",
                                 (*vals, iid)).rowcount
        except psycopg.errors.UniqueViolation:
            other = _same(conn, key, unit if unit is not _UNSET else cur.unit, today, except_id=iid)
            raise FridgeExists(other or cur)
        if n == 0:
            raise FridgeNotFound(f"fridge item {iid}")
    return get_item(conn, iid, today)


def delete_item(conn, iid: int) -> None:
    """Idempotent, like a staple."""
    conn.execute("DELETE FROM fridge_items WHERE id=%s", (iid,))


# --- what a recipe uses from the fridge (pure; the list, suggestions and the nudge all use this) -------------------
#
# Amounts are compared only within a unit family: volume with volume, mass with mass, a count unit with the same count
# unit ("clove" with "clove", a plain count with a plain count). Never volume with mass (no density guesses here):
# anything else is a use without a number ("check you have enough").

@dataclass(frozen=True)
class Line:
    """A recipe line as the list sees it: cleaned, scaled, keyed like `fridge_items.key`."""
    key: str
    name: str
    qty: float | None
    unit: str | None
    likely_on_hand: bool = False


@dataclass(frozen=True)
class Use:
    item_id: int
    name: str                                  # the fridge item's name
    need: tuple[float, str | None] | None      # what the recipe needs, in the item's unit (None: not convertible)
    convertible: bool
    used: tuple[float, str | None] | None      # min(need, what is there), in the item's unit


def amount_in_base(qty: float | None, unit: str | None) -> tuple[str, float] | None:
    """(unit family, amount in its base unit: tsp / g / the count unit itself)."""
    if qty is None:
        return None
    if unit in BASE:
        dim, size = BASE[unit]
        return dim, qty * size
    return unit or "each", qty


def from_base(base: float, unit: str | None) -> float:
    return base / BASE[unit][1] if unit in BASE else base


def recipe_lines(recipe: Recipe, factor: float) -> list[Line]:
    """The recipe's bought lines, cleaned like the list's and scaled; attached-reference lines are skipped."""
    out = []
    for ing in recipe.ingredients:
        if ing.expanded:
            continue
        c = clean_ingredient(ing)
        out.append(Line(key=name_key(c.name), name=c.name, qty=None if c.qty is None else c.qty * factor, unit=c.unit,
                        likely_on_hand=c.likely_on_hand))
    return out


def uses(lines: list[Line], items: list[FridgeItem], have: dict[int, float | None] | None = None) -> list[Use]:
    """One Use per fridge item the lines need (in item order). `have` (item id → amount in the item's unit) overrides
    the stored amounts, e.g. what is left after other recipes. Items with nothing left (0) are left out."""
    out = []
    for it in items:
        q = it.qty if have is None or it.id not in have else have[it.id]
        if q is not None and q <= 1e-9:
            continue
        mine = [ln for ln in lines if ln.key == it.key]
        if not mine:
            continue
        hb = amount_in_base(q, it.unit)
        need = None
        if hb is not None:
            for ln in mine:
                nb = amount_in_base(ln.qty, ln.unit)
                if nb is not None and nb[0] == hb[0]:
                    need = (need or 0.0) + nb[1]
        if need is None:
            out.append(Use(it.id, it.name, None, False, None))
            continue
        out.append(Use(it.id, it.name, (from_base(need, it.unit), it.unit), True,
                       (from_base(min(need, hb[1]), it.unit), it.unit)))
    return out


# --- suggestions: a greedy set of dinners that uses the fridge ------------------------------------------------------
#
# Each round picks the candidate with the best gain = Σ weight × the share of an item's remaining amount it uses
# (an item without a convertible amount counts 0.5 × weight, once across the set) − EXTRA_COST × the extra
# ingredients it adds (lines not matching a fridge item, not likely on hand, not already needed by a planned recipe or
# an earlier pick), then takes what it used off the remaining fridge. It stops early when no candidate uses anything,
# so a pick never comes from extras alone. Ties: average family rating (unrated last), Good for company, cooked
# longest ago (never cooked first), recipe id. No AI: fast and the same input gives the same answer.

WEIGHT_SOON = 2.0        # use soon / use-by within 3 days
WEIGHT_TYPED = 1.5       # an amount someone typed (not a photo's estimate) is surer
WEIGHT_CHECK = 0.5       # an item used without a convertible amount, counted once
EXTRA_COST = 0.35        # each new distinct extra ingredient
STILL_NEED_SHOWN = 8


@dataclass
class Candidate:
    recipe_id: int
    title: str
    lines: list[Line]
    avg_family: float | None = None
    company: str | None = None
    last_cooked: date | None = None
    ratings: dict = field(default_factory=dict)


def weight(it: FridgeItem) -> float:
    return (WEIGHT_SOON if it.soon else 1.0) * (1.0 if it.qty_estimated else WEIGHT_TYPED)


def _stale_text(it: FridgeItem) -> str:
    return f"{it.name}: last checked {it.days_since_checked} days ago"


def select(cands: list[Candidate], items: list[FridgeItem], nights: int, planned: list[list[Line]] = ()) -> dict:
    """The greedy pick (see above) over `cands`; `planned` = the lines of recipes already on the week (scaled), whose
    uses come off the fridge first and whose extras are already needed."""
    items = [it for it in items if it.qty is None or it.qty > 1e-9]
    by_id = {it.id: it for it in items}
    left: dict[int, float | None] = {it.id: it.qty for it in items}
    spent: set[int] = set()                         # items without a convertible amount, already counted
    keys = {it.key for it in items}
    needed: set[str] = set()

    def extras(lines: list[Line]) -> list[Line]:
        out, seen = [], set()
        for ln in lines:
            if ln.key and ln.key not in keys and not ln.likely_on_hand and ln.key not in seen:
                seen.add(ln.key)
                out.append(ln)
        return out

    def take(us: list[Use]) -> None:
        for u in us:
            if u.convertible:
                left[u.item_id] = max(0.0, left[u.item_id] - u.used[0])
            else:
                spent.add(u.item_id)

    for lines in planned:
        take(uses(lines, items, have=left))
        needed |= {ln.key for ln in extras(lines)}

    def gain(c: Candidate):
        us = uses(c.lines, items, have=left)
        g = 0.0
        for u in us:
            w = weight(by_id[u.item_id])
            if u.convertible:
                if left[u.item_id] and u.used[0] > 0:
                    g += w * u.used[0] / left[u.item_id]
            elif u.item_id not in spent:
                g += WEIGHT_CHECK * w
        new = [ln for ln in extras(c.lines) if ln.key not in needed]
        return g, g - EXTRA_COST * len(new), us

    def order(c: Candidate, total: float):
        return (-round(total, 9), c.avg_family is None, -(c.avg_family or 0), c.company != "yes",
                c.last_cooked or date.min, c.recipe_id)

    pool = sorted({c.recipe_id: c for c in cands}.values(), key=lambda c: c.recipe_id)
    usable = sum(1 for c in pool if gain(c)[0] > 0)
    picks = []
    while pool and len(picks) < nights:
        scored = [(c, *gain(c)) for c in pool]
        scored = [x for x in scored if x[1] > 0]
        if not scored:
            break
        c, _, total, us = min(scored, key=lambda x: order(x[0], x[2]))
        pool.remove(c)
        mine = extras(c.lines)
        picks.append({
            "recipe_id": c.recipe_id, "title": c.title, "score": round(total, 3), "ratings": c.ratings,
            "uses": [{"item_id": u.item_id, "name": u.name,
                      "amount": amount_text(u.used[0], u.used[1], u.name) if u.convertible else u.name,
                      "qty": u.used[0] if u.convertible else None, "unit": u.used[1] if u.convertible else None,
                      "convertible": u.convertible, "stale": by_id[u.item_id].days_since_checked > STALE_DAYS}
                     for u in us],
            "still_need": [ln.name for ln in mine[:STILL_NEED_SHOWN]],
            "still_need_more": max(0, len(mine) - STILL_NEED_SHOWN),
        })
        take(us)
        needed |= {ln.key for ln in mine}
    used_ids = [u["item_id"] for p in picks for u in p["uses"]]
    stale = [_stale_text(by_id[i]) for i in dict.fromkeys(used_ids) if by_id[i].days_since_checked > STALE_DAYS]
    fridge_left = []
    for it in items:
        q = left[it.id]
        if q is None:
            if it.id not in spent:
                fridge_left.append({"item_id": it.id, "name": it.name, "amount": it.name})
        elif q > 1e-9:
            fridge_left.append({"item_id": it.id, "name": it.name, "amount": amount_text(q, it.unit, it.name)})
    return {"picks": picks, "fridge_left": fridge_left, "stale": stale, "thin_library": usable < nights}


def _last_cooked(conn, today: date) -> dict[int, date]:
    rows = conn.execute("SELECT recipe_id, max(d) FROM (SELECT recipe_id, week + day::int AS d FROM plan "
                        "WHERE day IS NOT NULL AND week + day::int < %(t)s UNION ALL SELECT recipe_id, cooked_on FROM "
                        "imported_ratings WHERE cooked_on IS NOT NULL) x GROUP BY recipe_id", {"t": today})
    return dict(rows.fetchall())


def suggest(conn, today: date, nights: int, people: int = 4, week: date | None = None, exclude=()) -> dict:
    """POST /fridge/suggest: library recipes (with ingredient lines; not excluded, not already on `week`) picked by
    `select`, scaled to `people`; the week's planned recipes use the fridge first."""
    items = list_items(conn, today)
    planned_entries = db.get_week(conn, week) if week else []
    planned_ids = {e.recipe_id for e in planned_entries}
    recipes = {r.id: r for r in db.list_recipes(conn)}
    planned = [recipe_lines(recipes[e.recipe_id], shopping.scale_factor(recipes[e.recipe_id].servings, e.multiplier,
                                                                        people))
               for e in planned_entries if e.recipe_id in recipes]
    summaries, last = db.rating_summaries(conn, today), _last_cooked(conn, today)
    skip = planned_ids | set(exclude)
    cands = []
    for r in recipes.values():
        if r.id in skip:
            continue
        lines = recipe_lines(r, shopping.scale_factor(r.servings, 1, people))
        if not lines:
            continue
        s = summaries.get(r.id, {})
        cands.append(Candidate(recipe_id=r.id, title=r.title, lines=lines, avg_family=s.get("avg_family"),
                               company=s.get("company"), last_cooked=last.get(r.id), ratings=s))
    return select(cands, items, nights, planned)


# --- fridge photos: a background read proposes changes; nothing saves until the review is applied -----------------
#
# Proposal kinds: `new` (not on the list: add it), `amount` (the photo's estimate differs from the stored amount by
# more than 15 %, or by a whole count: write it, marked estimated), `seen` (no estimate, it matches, or its unit doesn't
# convert: only "as of today"), `not_seen` (on the list, in no photo: "remove?"). `not_seen` always starts unticked: it
# is the only way a photo leads to a removal. Low confidence starts unticked, and so does an estimate on an amount
# someone typed in the last 2 days.

READ_KEEP_DAYS = 14       # a ready read nobody reviewed is dismissed when a new read starts after this long
AMOUNT_CHANGE = 0.15
TYPED_RECENTLY_DAYS = 2


class ReadNotFound(Exception):
    pass


class ReadState(Exception):
    """The read isn't ready to apply (applied already, dismissed, failed, still reading); `read` = its state."""
    def __init__(self, text: str, read: dict):
        super().__init__(text)
        self.read = read


def photos_sha(photos: list[bytes]) -> str:
    h = hashlib.sha256()
    for p in photos:
        h.update(hashlib.sha256(p).digest())
    return h.hexdigest()


def _current(it: FridgeItem) -> dict:
    return {"qty": it.qty, "unit": it.unit, "amount_as_of": it.amount_as_of, "qty_estimated": it.qty_estimated}


def _differs(it: FridgeItem, est: dict) -> bool | None:
    """True/False whether the estimate is another amount; None when the units don't convert."""
    if it.qty is None:
        return True
    a, b = amount_in_base(it.qty, it.unit), amount_in_base(est["qty"], est["unit"])
    if a[0] != b[0]:
        return None
    diff = abs(a[1] - b[1])
    return diff > AMOUNT_CHANGE * a[1] or (a[0] not in ("vol", "mass") and diff >= 1 - 1e-9)


def _about(it: FridgeItem, est, confidence, where, today: date, pid: str) -> dict:
    kind = "seen" if est is None or not _differs(it, est) else "amount"
    typed_recently = (kind == "amount" and it.qty is not None and not it.qty_estimated
                      and (today - date.fromisoformat(it.amount_as_of)).days <= TYPED_RECENTLY_DAYS)
    return {"pid": pid, "kind": kind, "item_id": it.id, "name": it.name, "key": it.key, "key_source": it.key_source,
            "estimate": est, "current": _current(it), "confidence": confidence, "where": where,
            "default_on": confidence != "low" and not typed_recently}


def proposals(items: list[FridgeItem], read: dict, mapped: list[tuple[str, str]], today: date) -> list[dict]:
    """The review's lines for a read ({seen, new} from importers.fridge_photo) against the list. `mapped` = (key,
    source) for each new name (map_names). A new name that maps onto an item on the list is about that item."""
    by_id = {it.id: it for it in items}
    out, about = [], set()

    def pid() -> str:
        return f"p{len(out) + 1}"

    for s in read["seen"]:
        it = by_id.get(s["id"])
        if it is not None and it.id not in about:
            about.add(it.id)
            out.append(_about(it, s["estimate"], s["confidence"], s["where"], today, pid()))
    new_keys = set()
    for n, (key, src) in zip(read["new"], mapped):
        same = [it for it in items if it.key == key]
        if same:
            est = n["estimate"]
            it = next((i for i in same if est and (i.unit or "") == (est["unit"] or "")), same[0])
            if it.id not in about:
                about.add(it.id)
                out.append(_about(it, est, n["confidence"], n["where"], today, pid()))
            continue
        if key in new_keys:
            continue
        new_keys.add(key)
        out.append({"pid": pid(), "kind": "new", "item_id": None, "name": n["name"], "key": key, "key_source": src,
                    "estimate": n["estimate"], "current": None, "confidence": n["confidence"], "where": n["where"],
                    "default_on": n["confidence"] != "low"})
    for it in items:
        if it.id not in about:
            out.append({"pid": pid(), "kind": "not_seen", "item_id": it.id, "name": it.name, "key": it.key,
                        "key_source": it.key_source, "estimate": None, "current": _current(it), "confidence": None,
                        "where": None, "default_on": False})
    return out


_READ_COLS = "id, status, photo_count, proposals, result, error, created_at, finished_at, applied_at"


def _read_out(row) -> dict:
    rid, status, n, props, result, error, created, finished, applied = row
    counts = {k: sum(1 for p in props if p["kind"] == k) for k in ("new", "amount", "seen", "not_seen")}
    return {"id": rid, "status": status, "photo_count": n,
            "progress": f"Reading {n} photo{'' if n == 1 else 's'}…" if status == "reading" else None,
            "proposals": props, "counts": counts, "result": result, "error": error,
            "created_at": created.isoformat(), "finished_at": finished.isoformat() if finished else None,
            "applied_at": applied.isoformat() if applied else None}


def get_read(conn, rid: int) -> dict:
    row = conn.execute(f"SELECT {_READ_COLS} FROM fridge_reads WHERE id=%s", (rid,)).fetchone()
    if row is None:
        raise ReadNotFound(f"fridge read {rid}")
    return _read_out(row)


def open_read(conn) -> dict | None:
    """The newest read still reading or ready to review (both phones show it), if any."""
    row = conn.execute(f"SELECT {_READ_COLS} FROM fridge_reads WHERE status IN ('reading','ready') AND created_at > "
                       f"now() - make_interval(days => %s) ORDER BY id DESC LIMIT 1", (READ_KEEP_DAYS,)).fetchone()
    return _read_out(row) if row else None


def start_read(conn, photos: list[bytes]) -> tuple[int, bool]:
    """(read id, existing): the same photos while that read is reading or ready → that read."""
    sha = photos_sha(photos)
    with conn.transaction():
        conn.execute("LOCK TABLE fridge_reads IN SHARE ROW EXCLUSIVE MODE")   # two uploads of the same photos
        conn.execute("UPDATE fridge_reads SET status='dismissed' WHERE status='ready' AND created_at <= "
                     "now() - make_interval(days => %s)", (READ_KEEP_DAYS,))
        row = conn.execute("SELECT id FROM fridge_reads WHERE photos_sha=%s AND status IN ('reading','ready') "
                           "ORDER BY id DESC LIMIT 1", (sha,)).fetchone()
        if row:
            return row[0], True
        rid = conn.execute("INSERT INTO fridge_reads(photos_sha, photo_count) VALUES(%s,%s) RETURNING id",
                           (sha, len(photos))).fetchone()[0]
    return rid, False


def run_read(conn, rid: int, provider, map_provider, paths: list[str], today: date) -> None:
    """The background read: one vision call, one mapping call for the new names, then the proposals."""
    from .importers import fridge_photo
    try:
        items = [it for it in list_items(conn, today)]
        vocab = vocabulary(conn)
        read = fridge_photo.read(provider, paths, items, vocab)
        mapped = map_names(map_provider, [n["name"] for n in read["new"]], vocab)
        props = proposals(items, read, mapped, today)
        conn.execute("UPDATE fridge_reads SET status='ready', proposals=%s, finished_at=now() WHERE id=%s AND "
                     "status='reading'", (Jsonb(props), rid))
        usage = getattr(provider, "last_usage", None)
        log.warning("fridge read %s: %d photos, %d proposals%s", rid, len(paths), len(props),
                    f", usage {usage}" if usage is not None else "")
    except Exception as e:
        log.warning("fridge read %s failed: %s: %s", rid, type(e).__name__, str(e)[:300])
        text = str(e) if isinstance(e, fridge_photo.ReadFailed) else fridge_photo.FAILED
        conn.execute("UPDATE fridge_reads SET status='failed', error=%s, finished_at=now() WHERE id=%s AND "
                     "status='reading'", (text, rid))


def fail_interrupted(conn) -> None:
    """At startup: reads cut off by a restart will never finish (their photos are gone)."""
    conn.execute("UPDATE fridge_reads SET status='failed', error='interrupted by a server restart', finished_at=now() "
                 "WHERE status='reading'")


def dismiss_read(conn, rid: int) -> dict:
    with conn.transaction():
        read = get_read(conn, rid)
        if read["status"] == "applied":
            raise ReadState("These changes were already saved.", read)
        conn.execute("UPDATE fridge_reads SET status='dismissed' WHERE id=%s AND status IN ('reading','ready')", (rid,))
    return get_read(conn, rid)


def _edited(line: dict) -> bool:
    return "qty" in line or "unit" in line


def _amount(p: dict, line: dict) -> tuple[float | None, str | None, bool]:
    """(qty, unit, estimated) to write: the reviewer's numbers if edited (typed), else the photo's estimate."""
    est = p["estimate"] or {"qty": None, "unit": None}
    if _edited(line):
        return line.get("qty", est["qty"]), line.get("unit", est["unit"]), False
    return est["qty"], est["unit"], est["qty"] is not None


def _apply_line(conn, p: dict, line: dict, mapped: dict, today: date, counts: dict) -> None:
    kind = p["kind"]
    if kind == "not_seen":
        if conn.execute("DELETE FROM fridge_items WHERE id=%s", (p["item_id"],)).rowcount:
            counts["removed"] += 1
        return
    soon = line.get("use_soon")
    if kind == "new":
        name = line.get("name") or p["name"]
        key, src = mapped.get(name, (p["key"], p["key_source"]))
        qty, unit, est = _amount(p, line)
        row = conn.execute("INSERT INTO fridge_items(name, key, key_source, qty, unit, qty_estimated, amount_as_of, "
                           "use_soon) VALUES(%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING RETURNING id",
                           (name, key, src, qty, unit, est, today, bool(soon))).fetchone()
        if row is None:   # added meanwhile (the other phone, by hand): the same item gets the amount
            conn.execute("UPDATE fridge_items SET qty=COALESCE(%s, qty), qty_estimated=%s, amount_as_of=%s, "
                         "use_soon=COALESCE(%s, use_soon), updated_at=now() WHERE key=%s AND coalesce(unit,'')=%s",
                         (qty, est and qty is not None, today, soon, key, unit or ""))
        counts["added"] += 1
        return
    if conn.execute("SELECT 1 FROM fridge_items WHERE id=%s FOR UPDATE", (p["item_id"],)).fetchone() is None:
        return            # removed meanwhile: nothing to change
    if kind == "amount" or _edited(line):
        qty, unit, est = _amount(p, line)
        if not _edited(line) and qty is None:
            qty, unit = p["current"]["qty"], p["current"]["unit"]
        try:
            with conn.transaction():
                conn.execute("UPDATE fridge_items SET qty=%s, unit=%s, qty_estimated=%s, amount_as_of=%s, updated_at=now() "
                             "WHERE id=%s", (qty, unit, est, today, p["item_id"]))
            counts["changed"] += 1
        except psycopg.errors.UniqueViolation:   # that unit is another row of the same food: only "as of today"
            conn.execute("UPDATE fridge_items SET amount_as_of=%s, updated_at=now() WHERE id=%s", (today, p["item_id"]))
            counts["seen"] += 1
    else:
        conn.execute("UPDATE fridge_items SET amount_as_of=%s, updated_at=now() WHERE id=%s", (today, p["item_id"]))
        counts["seen"] += 1
    if soon is not None:
        conn.execute("UPDATE fridge_items SET use_soon=%s WHERE id=%s", (soon, p["item_id"]))


def apply_read(conn, provider, rid: int, lines: list[dict], today: date) -> dict:
    """Save the reviewed lines in one transaction: accepted new → added (an item with the same key + unit added
    meanwhile gets the amount), amount → the estimate (marked estimated) or the reviewer's number, seen → as of today,
    not_seen → removed. Lines not accepted (or not sent) change nothing. A second apply → ReadState with the result."""
    read = get_read(conn, rid)
    props = {p["pid"]: p for p in read["proposals"]}
    renamed = [ln["name"] for ln in lines if ln.get("accept") and ln.get("name")
               and props.get(ln["pid"], {}).get("kind") == "new" and ln["name"] != props[ln["pid"]]["name"]]
    mapped = dict(zip(renamed, map_names(provider, renamed, vocabulary(conn)))) if renamed else {}
    counts = {"added": 0, "changed": 0, "seen": 0, "removed": 0}
    with conn.transaction():
        row = conn.execute("SELECT status FROM fridge_reads WHERE id=%s FOR UPDATE", (rid,)).fetchone()
        if row[0] != "ready":
            text = {"applied": "These changes were already saved (on the other phone?).",
                    "dismissed": "These changes were discarded.", "failed": "This read failed.",
                    "reading": "The photos are still being read."}[row[0]]
            raise ReadState(text, get_read(conn, rid))
        for ln in lines:
            p = props.get(ln.get("pid"))
            if p is not None and ln.get("accept"):
                _apply_line(conn, p, ln, mapped, today, counts)
        conn.execute("UPDATE fridge_reads SET status='applied', result=%s, applied_at=now() WHERE id=%s",
                     (Jsonb(counts), rid))
    return get_read(conn, rid)
