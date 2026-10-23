"""Fridge cleaner (0.14.0): the household's "In the fridge" list (perishables on hand) and what recipes use of it.

- An item's `key` is the shopping list's name for it (`staples.name_key`: cleaned, singular, no unit family), set
  once when the name is added or changed: a name already in the household's vocabulary (library recipe lines +
  staples) is used as is; otherwise one AI call maps it onto a vocabulary name, else the rule (`rule_key`). Never
  on a suggest or a list, and an AI failure still saves the item.
- Items never clear by themselves (not after a cart, a dinner or a photo): only a person removes them. Every amount
  carries `amount_as_of` (set when the amount is set or confirmed), so a stale count shows instead of being trusted.
"""
import json
import logging
from collections import Counter
from dataclasses import dataclass
from datetime import date

import psycopg

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
    """"6 eggs", "2 cups milk", "½ cup cream", "some cilantro" (no amount)."""
    if qty is None:
        return f"some {name}"
    q = _qty_text(qty, unit)
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
