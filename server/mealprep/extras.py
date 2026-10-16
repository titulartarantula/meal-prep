"""Items added by search (0.13.0): anything bought outside the recipes and staples (paper towels, a snack), found
with the same product chooser as a list line (`drafts.search_choices`) and kept for a week.

An added item is a product + quantity for one week. `POST /drafts` adds the items of its weeks as lines after the
recipe and staple lines (`items_for`); the build uses the chosen product, or matches the search term when it is out
of stock (`drafts._match_extra`). Adding, changing or deleting an item also changes the newest ready cart of its
week, so a cart under review never misses one; a line's edits come back here (`drafts._mirror_extra`).

The chosen product becomes the household's pick for the search's item key ("paper towel|each"), so the next search
for it shows that product under Bought before once a cart with it is sent."""
from datetime import date

from psycopg.types.json import Jsonb

from . import db, drafts
from .ingredients import item_key
from .models import ListItem
from .staples import buy_name

ADDED = "Added"            # the "recipe" name an added item's line carries


class ExtraNotFound(Exception):
    pass


def extra_key(term: str) -> str:
    """The item key of a search: "Paper Towels" → "paper towel|each"."""
    return item_key(buy_name(term), "each")


def _product(conn, code: str):
    p = drafts._current_product(conn, code)
    if p is None:
        raise drafts.UnknownProduct(code)
    return p


def _out(conn, row) -> dict:
    eid, week, term, key, code, qty, created = row
    p = drafts._current_product(conn, code)
    return {"id": eid, "week": week.isoformat(), "term": term, "key": key, "product": p.model_dump() if p else None,
            "quantity": qty, "created_at": created.isoformat()}


_COLS = "id, week, term, key, product_code, quantity, created_at"


def list_extras(conn, weeks) -> list[dict]:
    wks = sorted({db.week_start(w) for w in weeks})
    rows = conn.execute(f"SELECT {_COLS} FROM extras WHERE week = ANY(%s) ORDER BY week, id", (wks,)).fetchall()
    return [_out(conn, r) for r in rows]


def get_extra(conn, eid: int) -> dict:
    row = conn.execute(f"SELECT {_COLS} FROM extras WHERE id=%s", (eid,)).fetchone()
    if row is None:
        raise ExtraNotFound(f"added item {eid}")
    return _out(conn, row)


def items_for(conn, weeks) -> list[ListItem]:
    """The weeks' added items as list items for a draft (no amount: the quantity is the chosen one)."""
    wks = sorted({db.week_start(w) for w in weeks})
    rows = conn.execute("SELECT id, term, key, product_code, quantity FROM extras WHERE week = ANY(%s) ORDER BY week, id",
                        (wks,)).fetchall()
    return [ListItem(key=key, name=term, recipes=[ADDED], extra_id=eid, product_code=code, quantity=qty)
            for eid, term, key, code, qty in rows]


def _open_draft(conn, week: date) -> int | None:
    """The newest cart of the week, if it is ready (still being reviewed): it gets the change too."""
    row = conn.execute("SELECT id, status FROM carts WHERE %s = ANY(weeks) ORDER BY id DESC LIMIT 1",
                       (db.week_start(week),)).fetchone()
    return row[0] if row and row[1] == "ready" else None


def _add_line(conn, draft_id: int, eid: int, term: str, key: str, product, quantity: int) -> int:
    it = ListItem(key=key, name=term, recipes=[ADDED], extra_id=eid, product_code=product.code, quantity=quantity)
    pos = conn.execute("SELECT COALESCE(max(position) + 1, 0) FROM cart_lines WHERE cart_id=%s", (draft_id,)).fetchone()[0]
    lid = conn.execute(
        "INSERT INTO cart_lines(cart_id, position, item, item_key, item_name, need_qty, need_unit, product, product_code, "
        "quantity, source, status, extra_id) VALUES(%s,%s,%s,%s,%s,NULL,NULL,%s,%s,%s,'user','matched',%s) RETURNING id",
        (draft_id, pos, Jsonb(it.model_dump(mode="json")), key, term, Jsonb(product.model_dump()), product.code,
         quantity, eid)).fetchone()[0]
    conn.execute("UPDATE carts SET progress_total = progress_total + 1, progress_done = progress_done + 1 WHERE id=%s",
                 (draft_id,))
    return lid


def add_extra(conn, week: date, term: str, code: str, quantity: int) -> dict:
    """Add a product for the week (also to its cart under review). The product must have been seen in a search."""
    term = " ".join(term.split())
    if not term:
        raise ValueError("term can't be empty")
    week = db.week_start(week)
    with conn.transaction():
        p = _product(conn, code)
        q = drafts.order_quantity(p, quantity or 1)
        key = extra_key(term)
        eid = conn.execute("INSERT INTO extras(week, term, key, product_code, quantity) VALUES(%s,%s,%s,%s,%s) "
                           "RETURNING id", (week, term, key, p.code, q)).fetchone()[0]
        db.set_pick(conn, key, p.code, chosen_by="user")
        did = _open_draft(conn, week)
        if did is not None:
            conn.execute("SELECT 1 FROM carts WHERE id=%s FOR UPDATE", (did,))
            _add_line(conn, did, eid, term, key, p, q)
    return get_extra(conn, eid)


def add_to_draft(conn, draft_id: int, term: str, code: str, quantity: int) -> dict:
    """"Add an item" on a cart under review: the item for the cart's first week and its line. Returns the line."""
    term = " ".join(term.split())
    if not term:
        raise ValueError("term can't be empty")
    with conn.transaction():
        drafts._lock_ready(conn, draft_id)
        week = conn.execute("SELECT weeks[1] FROM carts WHERE id=%s", (draft_id,)).fetchone()[0]
        if week is None:
            raise drafts.DraftStateError(f"draft {draft_id} has no week")
        p = _product(conn, code)
        q = drafts.order_quantity(p, quantity or 1)
        key = extra_key(term)
        eid = conn.execute("INSERT INTO extras(week, term, key, product_code, quantity) VALUES(%s,%s,%s,%s,%s) "
                           "RETURNING id", (week, term, key, p.code, q)).fetchone()[0]
        db.set_pick(conn, key, p.code, chosen_by="user")
        lid = _add_line(conn, draft_id, eid, term, key, p, q)
    return drafts._get_line(conn, draft_id, lid)


def update_extra(conn, eid: int, quantity: int | None = None, product_code: str | None = None) -> dict:
    """Change an item's quantity (> 0) or product; the open cart's line follows."""
    with conn.transaction():
        row = conn.execute("SELECT week, key, product_code, quantity FROM extras WHERE id=%s FOR UPDATE", (eid,)).fetchone()
        if row is None:
            raise ExtraNotFound(f"added item {eid}")
        week, key, code, qty = row
        p = _product(conn, product_code or code)
        if product_code and product_code != code:
            db.set_pick(conn, key, p.code, chosen_by="user")
        q = drafts.order_quantity(p, quantity if quantity else qty)
        conn.execute("UPDATE extras SET product_code=%s, quantity=%s WHERE id=%s", (p.code, q, eid))
        did = _open_draft(conn, week)
        if did is not None:
            conn.execute("UPDATE cart_lines SET product=%s, product_code=%s, quantity=%s, source='user', "
                         "status='matched', note=NULL WHERE cart_id=%s AND extra_id=%s AND NOT removed",
                         (Jsonb(p.model_dump()), p.code, q, did, eid))
    return get_extra(conn, eid)


def delete_extra(conn, eid: int) -> None:
    """Idempotent; the open cart's line is removed too."""
    with conn.transaction():
        row = conn.execute("DELETE FROM extras WHERE id=%s RETURNING week", (eid,)).fetchone()
        if row is None:
            return
        did = _open_draft(conn, row[0])
        if did is not None:
            conn.execute("UPDATE cart_lines SET removed=true WHERE cart_id=%s AND extra_id=%s", (did, eid))
