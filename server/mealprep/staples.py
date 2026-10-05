"""Weekly staples: items the household buys most weeks (milk, eggs …), kept in their own list.

The app shows them at the top of the shopping list (ticked when `weekly`) and asks `POST /list` to merge the
ticked ones in (`staples: [ids]`), so they go through the same clean-up, planner and remembered picks as recipe
lines and the draft contract (`POST /drafts {weeks, items}`) stays as it was. See `shopping.build_list`:
- a staple with a unit is an amount and adds to the recipes' amount of the same item;
- a staple without a unit counts packs ("1" = one carton) and is a floor: a recipe line for the same item keeps
  its amount and is bought in at least that many packs (`ListItem.staple_packs`, `planner`).

`last_bought` comes from sent carts only: the newest sent cart with an added line for the item (any unit), or the
manual hint in the column when that is later."""
from datetime import date, datetime

from .ingredients import COUNT_UNITS, UNITS, item_key, split_prep
from .models import Staple

STAPLES = "Staples"          # the "recipe" name staple lines carry in ListItem.recipes


class StapleNotFound(Exception):
    pass


class StapleExists(Exception):
    pass


def normalise_unit(unit: str | None) -> str | None:
    """"Litres" → "l", "Cans" → "can"; blank → None (count packs). Unknown units raise ValueError."""
    u = (unit or "").strip().lower().rstrip(".")
    if not u:
        return None
    if u in {"fl oz", "fl. oz", "fluid ounce", "fluid ounces"}:
        return "fl oz"
    out = UNITS.get(u) or COUNT_UNITS.get(u)
    if out is None or u in {"t", "c"}:   # one-letter recipe shorthands are too easy to mistype here
        raise ValueError(f"unknown unit {unit!r}: use g, kg, ml, l, cup, can, bottle …, or leave it empty to count packs")
    return out


def buy_name(name: str) -> str:
    """What you buy, as the list names it: "Eggs, large" → "eggs" (prep/size words moved out)."""
    return split_prep(name)[0] or name.strip().lower()


def name_key(name: str) -> str:
    """Item identity across unit families: "egg" for "eggs", "2% milk" for "2% Milk"."""
    return item_key(buy_name(name), "")[:-1]


_COLS = "id, name, qty::float, unit, weekly, position, last_bought, created_at"


def _staple(row, sent: dict[str, date]) -> Staple:
    sid, name, qty, unit, weekly, pos, manual, created = row
    bought = max((d for d in (sent.get(name_key(name)), manual) if d), default=None)
    return Staple(id=sid, name=name, qty=qty, unit=unit, weekly=weekly, position=pos,
                  last_bought=bought.isoformat() if bought else None, created_at=created.isoformat())


def _last_sent(conn) -> dict[str, date]:
    """{name key: local date of the newest sent cart with an added line for it}. Drafts never sent don't count."""
    out: dict[str, date] = {}
    rows = conn.execute("SELECT l.item_key, max(c.sent_at) FROM cart_lines l JOIN carts c ON c.id = l.cart_id "
                        "WHERE c.status = 'sent' AND l.status = 'added' AND NOT l.removed AND c.sent_at IS NOT NULL "
                        "GROUP BY l.item_key")
    for key, sent_at in rows:
        if not key:
            continue
        k = name_key(key.rpartition("|")[0] or key)
        d = sent_at.astimezone().date() if isinstance(sent_at, datetime) else sent_at
        if d > out.get(k, date.min):
            out[k] = d
    return out


def list_staples(conn, ids: list[int] | None = None) -> list[Staple]:
    """All staples (or just `ids`) in the household's order."""
    where = "" if ids is None else " WHERE id = ANY(%(ids)s)"
    rows = conn.execute(f"SELECT {_COLS} FROM staples{where} ORDER BY position, id", {"ids": ids}).fetchall()
    sent = _last_sent(conn) if rows else {}
    return [_staple(r, sent) for r in rows]


def get_staple(conn, sid: int) -> Staple:
    row = conn.execute(f"SELECT {_COLS} FROM staples WHERE id=%s", (sid,)).fetchone()
    if row is None:
        raise StapleNotFound(f"staple {sid}")
    return _staple(row, _last_sent(conn))


def _check_unique(conn, name: str, except_id: int | None = None) -> None:
    k = name_key(name)
    for sid, other in conn.execute("SELECT id, name FROM staples"):
        if sid != except_id and name_key(other) == k:
            raise StapleExists(f"{other!r} is already a staple")


def create_staple(conn, name: str, qty: float | None = None, unit: str | None = None, weekly: bool = True) -> Staple:
    """Add a staple at the end of the list."""
    with conn.transaction():
        conn.execute("LOCK TABLE staples IN SHARE ROW EXCLUSIVE MODE")   # two phones adding the same item
        _check_unique(conn, name)
        sid = conn.execute("INSERT INTO staples(name, qty, unit, weekly, position) "
                           "VALUES(%s,%s,%s,%s,(SELECT COALESCE(max(position) + 1, 0) FROM staples)) RETURNING id",
                           (name, qty, unit, weekly)).fetchone()[0]
    return get_staple(conn, sid)


_UNSET = object()


def update_staple(conn, sid: int, name=_UNSET, qty=_UNSET, unit=_UNSET, weekly=_UNSET, last_bought=_UNSET,
                  position=_UNSET) -> Staple:
    """Change only the fields passed (qty/unit/last_bought None clears them); `position` moves the staple there
    (0 = first; past the end = last) and renumbers the list 0…n-1."""
    with conn.transaction():
        conn.execute("LOCK TABLE staples IN SHARE ROW EXCLUSIVE MODE")
        if conn.execute("SELECT 1 FROM staples WHERE id=%s", (sid,)).fetchone() is None:
            raise StapleNotFound(f"staple {sid}")
        sets, vals = [], []
        if name is not _UNSET:
            _check_unique(conn, name, except_id=sid)
            sets.append("name=%s"); vals.append(name)
        for col, v in (("qty", qty), ("unit", unit), ("weekly", weekly), ("last_bought", last_bought)):
            if v is not _UNSET:
                sets.append(f"{col}=%s"); vals.append(v)
        if sets:
            conn.execute(f"UPDATE staples SET {', '.join(sets)} WHERE id=%s", (*vals, sid))
        if position is not _UNSET and position is not None:
            order = [i for (i,) in conn.execute("SELECT id FROM staples ORDER BY position, id") if i != sid]
            order.insert(max(0, min(position, len(order))), sid)
            for pos, i in enumerate(order):
                conn.execute("UPDATE staples SET position=%s WHERE id=%s AND position IS DISTINCT FROM %s", (pos, i, pos))
    return get_staple(conn, sid)


def delete_staple(conn, sid: int) -> None:
    """Idempotent, like deleting a plan entry."""
    conn.execute("DELETE FROM staples WHERE id=%s", (sid,))
