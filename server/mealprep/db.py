from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import uuid
from contextlib import contextmanager
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

from .models import COLUMN_FIELDS, PlanEntry, Rating, Recipe
from .sources import KINDS, NYT_SITE, is_nyt_url

SCHEMA = (Path(__file__).parent / "schema.sql").read_text()


APP_ROLE = "mealprep_app"


def connect(dsn: str) -> psycopg.Connection:
    """A connection as the login role with the schema applied (migrations, startup, tests). Unscoped: it sees every
    household's rows; scope() it before using it for one household."""
    conn = psycopg.connect(dsn, autocommit=True)
    conn.execute(SCHEMA)
    return conn


def scope(conn, household_id: int) -> None:
    """From here on the connection acts for one household: it runs as mealprep_app, so row-level security shows it
    only that household's rows, and inserts take the household from the column default."""
    conn.execute(f"SET ROLE {APP_ROLE}")
    conn.execute("SELECT set_config('mealprep.household_id', %s, false)", (str(int(household_id)),))


def unscope(conn) -> None:
    conn.execute("RESET ROLE")
    conn.execute("SELECT set_config('mealprep.household_id', '', false)")


@contextmanager
def unscoped(conn):
    """Act as the login role for a moment (DDL, migrations), then for the same household again."""
    scoped, hid = conn.execute("SELECT current_user = %s, current_setting('mealprep.household_id', true)",
                               (APP_ROLE,)).fetchone()
    if scoped:
        conn.execute("RESET ROLE")
    try:
        yield conn
    finally:
        if scoped:
            scope(conn, int(hid))


def migrate(conn) -> None:
    """Apply the schema (idempotent; every startup does it) as the login role."""
    with unscoped(conn):
        conn.execute(SCHEMA)


def scoped_connect(dsn: str, household_id: int) -> psycopg.Connection:
    """A new connection for one household (background jobs)."""
    conn = psycopg.connect(dsn, autocommit=True)
    scope(conn, household_id)
    return conn


def first_household(conn) -> int:
    return conn.execute("SELECT min(id) FROM households").fetchone()[0]


def _data(r: Recipe) -> Jsonb:
    return Jsonb(r.model_dump(mode="json", exclude={"id", *COLUMN_FIELDS}))


def save_recipe(conn, r: Recipe, ai_provider: str | None = None) -> int:
    """Insert the recipe (an NYT link already in the library returns that recipe's id, unchanged). r.uid is kept when
    set (an import; an existing uid raises UniqueViolation), else the column default makes one."""
    if r.source_url:
        row = conn.execute("SELECT id FROM recipes WHERE source_url=%s", (r.source_url,)).fetchone()
        if row:
            return row[0]
    kind = r.source_kind or r.default_kind()
    stitle = r.source_title or (NYT_SITE if kind == "web" and is_nyt_url(r.source_url) else None)
    title, ref, author, isbn = _source(kind, stitle, r.source_ref, r.source_author, r.source_isbn)
    return conn.execute(
        "INSERT INTO recipes(source, source_url, title, servings, data, ai_provider, source_kind, source_title, source_ref, "
        "source_author, source_isbn, uid) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,COALESCE(%s::uuid, gen_random_uuid())) "
        "RETURNING id",
        (r.source, r.source_url, r.title, r.servings, _data(r), ai_provider, kind, title, ref, author, isbn, r.uid),
    ).fetchone()[0]


def _source(kind, title, ref, author, isbn) -> tuple:
    """An author/ISBN belongs to a known book title only (an "other" source, e.g. "Mum's recipes", is a name and a
    note: no author/ISBN); a web page (NYT Cooking included) has its site and author only."""
    if kind == "other":
        return title, ref, None, None
    if kind == "web":   # the site and the recipe's author; no page or ISBN
        return title, None, author, None
    return (title, ref, author, isbn) if title else (None, ref, None, None)


_RECIPE_COLS = "id, data, source_kind, source_title, source_ref, source_author, source_isbn, uid::text"


def _recipe(row) -> Recipe:
    i, d, kind, title, ref, author, isbn, uid = row
    d = {k: v for k, v in d.items() if k not in COLUMN_FIELDS}
    return Recipe(**d, id=i, source_kind=kind, source_title=title, source_ref=ref, source_author=author, source_isbn=isbn,
                  uid=uid)


def get_recipe(conn, rid: int, for_update: bool = False) -> Recipe | None:
    row = conn.execute(f"SELECT {_RECIPE_COLS} FROM recipes WHERE id=%s" + (" FOR UPDATE" if for_update else ""),
                       (rid,)).fetchone()
    return _recipe(row) if row else None


def get_recipe_by_uid(conn, uid: str) -> Recipe | None:
    try:
        uid = str(uuid.UUID(str(uid)))
    except ValueError:
        return None
    row = conn.execute(f"SELECT {_RECIPE_COLS} FROM recipes WHERE uid=%s", (uid,)).fetchone()
    return _recipe(row) if row else None


def update_recipe(conn, rid: int, r: Recipe) -> None:
    """Title, servings, ingredients and steps (the source columns are changed only by update_source)."""
    conn.execute("UPDATE recipes SET title=%s, servings=%s, data=%s WHERE id=%s", (r.title, r.servings, _data(r), rid))


def update_source(conn, rid: int, kind: str, title: str | None, ref: str | None,
                  author: str | None = None, isbn: str | None = None) -> None:
    title, ref, author, isbn = _source(kind, title, ref, author, isbn)
    conn.execute("UPDATE recipes SET source_kind=%s, source_title=%s, source_ref=%s, source_author=%s, source_isbn=%s "
                 "WHERE id=%s", (kind, title, ref, author, isbn, rid))


def source_filter(source: str | None) -> tuple[str, dict]:
    """SQL condition for GET /recipes?source=: "web" (every website), "book" (every book), "other" (every other
    source), "book:<title>" / "other:<name>" / "web:<site>" (case-insensitive, spacing ignored), "book:" (book not
    known yet), "other:" (other source without a name), "web:" (a page without a site name); any other value is a
    book title. "nyt" (apps before 2026-11-08) = "web:NYT Cooking"."""
    if not source:
        return "", {}
    s = source.strip()
    if s == "nyt":
        s = f"web:{NYT_SITE}"
    if s in KINDS:
        return " WHERE source_kind = %(kind)s", {"kind": s}
    low = s.lower()
    kind = "other" if low.startswith("other:") else "web" if low.startswith("web:") else "book"
    title = s[len(kind) + 1:] if s.lower().startswith(kind + ":") else s
    title = " ".join(title.split())
    if not title:
        return f" WHERE source_kind = '{kind}' AND source_title IS NULL", {}
    return f" WHERE source_kind = '{kind}' AND lower(source_title) = lower(%(title)s)", {"title": title}


def list_recipes(conn, source: str | None = None) -> list[Recipe]:
    where, args = source_filter(source)
    return [_recipe(row) for row in conn.execute(f"SELECT {_RECIPE_COLS} FROM recipes{where} ORDER BY id DESC", args)]


def recipe_sources(conn) -> list[dict]:
    """Distinct sources with counts: each book and each named other source ("Mum's recipes") A–Z, each website A–Z
    (NYT Cooking among them; titles grouped case-insensitively, the newest recipe's spelling shown), Websites (no site
    name), Unknown book, Other (no name). Every entry's `kind` is web, book or other; `key` is the GET /recipes?source=
    value that lists them."""
    rows = conn.execute(
        "SELECT source_kind, (array_agg(source_title ORDER BY id DESC))[1], count(*), "
        "(array_agg(source_author ORDER BY id DESC) FILTER (WHERE source_author IS NOT NULL))[1], "
        "(array_agg(source_isbn ORDER BY id DESC) FILTER (WHERE source_isbn IS NOT NULL))[1] FROM recipes "
        "GROUP BY source_kind, lower(source_title)").fetchall()
    # a book's author/ISBN = the newest recipe's that has one
    books = [{"key": f"book:{t}", "kind": "book", "title": t, "label": t, "count": n, "author": a, "isbn": i}
             for k, t, n, a, i in rows if k == "book" and t is not None]
    named = [{"key": f"other:{t}", "kind": "other", "title": t, "label": t, "count": n}
             for k, t, n, _, _ in rows if k == "other" and t is not None]
    # web sites after the books and named sources, A–Z (an old app shows them nowhere: it lists books and others)
    sites = sorted(({"key": f"web:{t}", "kind": "web", "title": t, "label": t, "count": n}
                    for k, t, n, _, _ in rows if k == "web" and t is not None), key=lambda b: b["title"].lower())
    unnamed_web = [{"key": "web:", "kind": "web", "title": None, "label": "Websites", "count": n}
                   for k, t, n, _, _ in rows if k == "web" and t is None]
    unknown = [{"key": "book:", "kind": "book", "title": None, "label": "Unknown book", "count": n}
               for k, t, n, _, _ in rows if k == "book" and t is None]
    other = [{"key": "other:", "kind": "other", "title": None, "label": "Other", "count": n}
             for k, t, n, _, _ in rows if k == "other" and t is None]
    return sorted(books + named, key=lambda b: (b["title"].lower(), b["kind"])) + sites + unnamed_web + unknown + other


def get_pick(conn, key: str) -> str | None:
    row = get_pick_full(conn, key)
    return row[0] if row else None


def get_pick_full(conn, key: str) -> tuple[str, str] | None:
    """(product_code, chosen_by) or None."""
    return conn.execute("SELECT product_code, chosen_by FROM picks WHERE key=%s", (key,)).fetchone()


def all_picks(conn) -> list[tuple[str, str, str]]:
    """(key, product_code, chosen_by) for every remembered pick, user picks first, then newest first."""
    return conn.execute("SELECT key, product_code, chosen_by FROM picks "
                        "ORDER BY (chosen_by = 'user') DESC, updated_at DESC").fetchall()


def copy_pick(conn, old_key: str, new_key: str) -> None:
    """Carry a remembered pick over to a list key renamed by better name clean-up (not a new choice: no history)."""
    conn.execute("INSERT INTO picks(key, product_code, chosen_by, updated_at, pinned) SELECT %s, product_code, "
                 "chosen_by, updated_at, pinned FROM picks WHERE key=%s ON CONFLICT(household_id, key) DO NOTHING", (new_key, old_key))


def pinned_code(conn, key: str) -> str | None:
    """The product "always bought" for this list key, or None."""
    row = conn.execute("SELECT product_code FROM picks WHERE key=%s AND pinned", (key,)).fetchone()
    return row[0] if row else None


def set_pinned(conn, key: str, pinned: bool) -> None:
    conn.execute("UPDATE picks SET pinned=%s WHERE key=%s", (pinned, key))


def purchases(conn, codes) -> dict[str, tuple]:
    """{code: (sent_at, price_at_add, quantity)} of each product's newest line in a sent cart (any list key)."""
    if not codes:
        return {}
    rows = conn.execute(
        "SELECT DISTINCT ON (l.product_code) l.product_code, c.sent_at, l.price_at_add::float, l.quantity "
        "FROM cart_lines l JOIN carts c ON c.id = l.cart_id WHERE c.status='sent' AND l.status='added' "
        "AND NOT l.removed AND l.product_code = ANY(%s) ORDER BY l.product_code, c.sent_at DESC NULLS LAST, l.id DESC",
        (list(codes),)).fetchall()
    return {r[0]: r[1:] for r in rows}


def bought_codes(conn, key: str, limit: int = 5) -> list[str]:
    """Products sent to a cart for this list key, most recent first."""
    return [r[0] for r in conn.execute(
        "SELECT l.product_code FROM cart_lines l JOIN carts c ON c.id = l.cart_id WHERE l.item_key=%s "
        "AND c.status='sent' AND l.status='added' AND NOT l.removed AND l.product_code IS NOT NULL "
        "GROUP BY l.product_code ORDER BY max(c.sent_at) DESC NULLS LAST, max(l.id) DESC LIMIT %s", (key, limit))]


def last_purchase(conn, key: str, code: str) -> tuple[float | None, int, str | None] | None:
    """(need_qty, quantity, need_unit) of the most recent added cart line for this list key + product."""
    return conn.execute("SELECT need_qty, quantity, need_unit FROM cart_lines WHERE item_key=%s AND product_code=%s "
                        "AND status='added' ORDER BY id DESC LIMIT 1", (key, code)).fetchone()


def set_pick(conn, key: str, code: str, chosen_by: str = "ai") -> None:
    with conn.transaction():
        # A pin belongs to its product: another product for the key unpins.
        conn.execute(
            "INSERT INTO picks(key, product_code, chosen_by) VALUES(%s,%s,%s) ON CONFLICT(household_id, key) DO UPDATE "
            "SET product_code=excluded.product_code, chosen_by=excluded.chosen_by, updated_at=now(), "
            "pinned = picks.pinned AND picks.product_code = excluded.product_code",
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


def get_week(conn, week: date, viewer: int | None = None) -> list[PlanEntry]:
    """The week's entries with everyone's ratings; viewer = the member asking (None = the shared token / Family)."""
    rows = conn.execute("SELECT p.id, p.week, p.recipe_id, p.day, p.multiplier, rc.title "
                        "FROM plan p JOIN recipes rc ON rc.id = p.recipe_id "
                        "WHERE p.week=%s ORDER BY COALESCE(p.day, 99), p.id", (week_start(week),)).fetchall()
    people = _people(conn)
    rated = _plan_ratings(conn, [r[0] for r in rows], people)
    verdicts = company_verdicts(conn, list({r[2] for r in rows}))
    return [PlanEntry(id=i, week=w.isoformat(), recipe_id=r, day=d, multiplier=m, title=t,
                      **_cooked(rated.get(i, []), viewer, people, verdicts.get(r, {}).get("company")))
            for i, w, r, d, m, t in rows]


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
            detail = p.model_dump(exclude={"price", "stock", "pieces", "pieces_word", "pieces_source"})
            conn.execute(
                "INSERT INTO products(code, name, brand, package_size, detail) VALUES(%s,%s,%s,%s,%s) "
                "ON CONFLICT(code) DO UPDATE SET name=excluded.name, brand=excluded.brand, "
                "package_size=excluded.package_size, detail=excluded.detail, last_seen=now()",
                (p.code, p.name, p.brand, p.package_size, Jsonb(detail)))
            conn.execute("INSERT INTO price_observations(code, store_id, price, stock) VALUES(%s,%s,%s,%s)",
                         (p.code, store_id, p.price, p.stock))


def _hash(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def shopping_key(servings, ingredients) -> str:
    """What a recipe makes the shopping list buy: its servings (the scale) and each bought line's item, amount, unit and
    whether it's likely on hand (ticked or not). The wording of a line, its prep notes, the title and steps aren't in it.
    `ingredients`: Ingredient models or their stored dicts."""
    rows = [i if isinstance(i, dict) else i.model_dump() for i in ingredients]
    return _hash([servings, [[i.get("name"), None if i.get("qty") is None else float(i["qty"]), i.get("unit"),
                              bool(i.get("likely_on_hand"))]
                             for i in rows if not i.get("expanded")]])[:16]


def plan_fingerprint(conn, weeks, version: int = 2) -> str:
    """Stable hash of the shopping-relevant plan of these weeks: the multiset of (week, recipe, scale) over every entry,
    placed on a night or not (as the shopping list uses them). Moving a dinner to another night doesn't change it;
    adding or removing a recipe or changing its scale does. Version 2 ("2:" + 32 hex, since 2026-10-11) also covers
    what each recipe buys (shopping_key), so editing a planned recipe's ingredients or servings changes it; version 1
    (32 hex, carts from 0.8.1 on) is still computed for the carts that recorded it."""
    wks = sorted({week_start(w) for w in weeks})
    rows = conn.execute("SELECT p.week, p.recipe_id, p.multiplier, r.servings, r.data -> 'ingredients' FROM plan p "
                        "JOIN recipes r ON r.id = p.recipe_id WHERE p.week = ANY(%s)", (wks,)).fetchall()
    entries = sorted(f"{w.isoformat()}|{r}|{float(m):.4f}" + (f"|{shopping_key(s, ings or [])}" if version >= 2 else "")
                     for w, r, m, s, ings in rows)
    text = ",".join(w.isoformat() for w in wks) + ";" + ";".join(entries)
    digest = hashlib.sha256(text.encode()).hexdigest()[:32]
    return digest if version == 1 else f"{version}:{digest}"


def _fingerprint_version(fp: str) -> int:
    head, sep, _ = fp.partition(":")
    return int(head) if sep and head.isdigit() else 1


def cart_is_stale(conn, weeks, fingerprint: str | None, _cache: dict | None = None) -> bool:
    """A cart is stale when its weeks' plan changed since it was made (compared with the fingerprint version it
    recorded). No fingerprint (older carts) = not stale."""
    if fingerprint is None or not weeks:
        return False
    v = _fingerprint_version(fingerprint)
    key = (tuple(sorted(weeks)), v)
    if _cache is None:
        return plan_fingerprint(conn, key[0], v) != fingerprint
    if key not in _cache:
        _cache[key] = plan_fingerprint(conn, key[0], v)
    return _cache[key] != fingerprint


def sent_cart_stale(conn, weeks: list[date]) -> dict[date, bool]:
    """{week: stale} for each of these weeks that has a sent cart, judged by the newest sent cart covering it."""
    rows = conn.execute("SELECT DISTINCT ON (cw.week) cw.week, c.weeks, c.plan_fingerprint FROM cart_weeks cw "
                        "JOIN carts c ON c.id = cw.cart_id WHERE c.status = 'sent' AND cw.week = ANY(%s) "
                        "ORDER BY cw.week, c.id DESC", (weeks,)).fetchall()
    cache: dict = {}
    return {w: cart_is_stale(conn, cweeks or [w], fp, cache) for w, cweeks, fp in rows}


def week_summaries(conn, start: date, count: int) -> list[dict]:
    """One row per week from start's Sunday: number of plan entries; extras = items added by search (for the week or
    a night: something to shop for without a recipe); named_nights = nights with a name; carted = its newest sent cart
    still matches the plan; cart_stale = a cart was sent but the week's recipes changed since."""
    first = week_start(start)
    weeks = [first + timedelta(weeks=i) for i in range(count)]
    entries = dict(conn.execute("SELECT week, count(*) FROM plan WHERE week = ANY(%s) GROUP BY week", (weeks,)).fetchall())
    added = dict(conn.execute("SELECT week, count(*) FROM extras WHERE week = ANY(%s) GROUP BY week", (weeks,)).fetchall())
    named = dict(conn.execute("SELECT week, count(*) FROM nights WHERE week = ANY(%s) GROUP BY week", (weeks,)).fetchall())
    sent = sent_cart_stale(conn, weeks)
    return [{"week": w.isoformat(), "entries": entries.get(w, 0), "extras": added.get(w, 0),
             "named_nights": named.get(w, 0), "carted": w in sent and not sent[w],
             "cart_stale": sent.get(w, False)} for w in weeks]


def default_cart_week(conn, today: date) -> date | None:
    """Earliest week >= this week's Sunday that has plan entries or added items and no up-to-date sent cart (a stale
    one counts as none)."""
    planned = [w for (w,) in conn.execute("SELECT week FROM plan WHERE week >= %s UNION SELECT week FROM extras "
                                          "WHERE week >= %s ORDER BY week", (week_start(today), week_start(today)))]
    sent = sent_cart_stale(conn, planned)
    return next((w for w in planned if w not in sent or sent[w]), None)


# --- ratings: one per time cooked and person (plan entry, or the recipe on a day); rating_history is the append-only
# log for reports. The shared token (the phones) rates as the household's legacy member; member_id NULL =
# "Family" is only left for a household without members (and any old row not reattributed). Good for company is not
# part of a person's rating: it is one household verdict per recipe (recipe_company, set by an Owner). ---

FAMILY, FORMER = "Family", "Former member"


def _people(conn) -> dict[int, tuple[str, str, int]]:
    """The scoped household's members: id → (name, kind, household order)."""
    rows = conn.execute("SELECT id, display_name, kind FROM members WHERE household_id = "
                        "NULLIF(current_setting('mealprep.household_id', true), '')::int ORDER BY joined_at, id")
    return {i: (n, k, o) for o, (i, n, k) in enumerate(rows)}


def _who(people: dict, member_id: int | None) -> tuple[str, str]:
    if member_id is None:
        return FAMILY, "family"
    p = people.get(member_id)
    return (p[0], p[1]) if p else (FORMER, "former")


def _order(people: dict, member_id: int | None) -> tuple:
    """Members in household order, then Family, then former members."""
    if member_id is None:
        return (1, 0)
    p = people.get(member_id)
    return (0, p[2]) if p else (2, member_id)


def _avg(scores) -> float | None:
    scores = list(scores)
    if not scores:
        return None
    return float((Decimal(sum(scores)) / Decimal(len(scores))).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def company_verdicts(conn, recipe_ids: list[int] | None = None) -> dict[int, dict]:
    """{recipe_id: {company, company_set_by, company_set_by_name, company_set_at, company_source}} for recipes that
    have a Good for company verdict (no entry = not decided)."""
    people = _people(conn)
    rows = conn.execute("SELECT recipe_id, verdict, set_by, set_at, source FROM recipe_company"
                        + ("" if recipe_ids is None else " WHERE recipe_id = ANY(%s)"),
                        () if recipe_ids is None else (recipe_ids,))
    return {rid: {"company": v, "company_set_by": by,
                  "company_set_by_name": _who(people, by)[0] if by is not None else None,
                  "company_set_at": at.isoformat(), "company_source": src} for rid, v, by, at, src in rows}


def set_company(conn, recipe_id: int, company: str | None, by: int | None, source: str = "member") -> dict | None:
    """Set (or with None clear) the recipe's Good for company verdict ("yes" | "no"). Returns {before, after}; None if
    the recipe doesn't exist."""
    if company not in ("yes", "no", None):
        raise ValueError(f"company verdict {company!r}")
    with conn.transaction():
        if conn.execute("SELECT 1 FROM recipes WHERE id=%s FOR UPDATE", (recipe_id,)).fetchone() is None:
            return None
        old = conn.execute("SELECT verdict FROM recipe_company WHERE recipe_id=%s", (recipe_id,)).fetchone()
        if company is None:
            conn.execute("DELETE FROM recipe_company WHERE recipe_id=%s", (recipe_id,))
        else:
            conn.execute("INSERT INTO recipe_company(recipe_id, verdict, set_by, source) VALUES(%s,%s,%s,%s) "
                         "ON CONFLICT(recipe_id) DO UPDATE SET verdict=excluded.verdict, set_by=excluded.set_by, "
                         "set_at=now(), source=excluded.source", (recipe_id, company, by, source))
    return {"before": old[0] if old else None, "after": company}


def adopt_company(conn, recipe_id: int, company: str | None, source: str = "import") -> bool:
    """Take a verdict brought in (an import) only when the recipe has none yet. True if it was set."""
    if company not in ("yes", "no"):   # "maybe" (older files) is ignored: the verdict is yes / no / not decided
        return False
    return conn.execute("INSERT INTO recipe_company(recipe_id, verdict, set_by, source) VALUES(%s,%s,NULL,%s) "
                        "ON CONFLICT(recipe_id) DO NOTHING", (recipe_id, company, source)).rowcount == 1


def _person_rating(people: dict, member_id, by, family, company, note, rated_at) -> dict:
    """One person's rating; `company` (an old per-person answer) is not part of it any more (see recipe_company)."""
    name, kind = _who(people, member_id)
    other = by is not None and by != member_id
    return {"member_id": member_id, "name": name, "kind": kind, "family": family, "note": note,
            "rated_at": rated_at.isoformat(), "entered_by": by if other else None,
            "entered_by_name": _who(people, by)[0] if other else None}


def _plan_ratings(conn, plan_ids: list[int], people: dict) -> dict[int, list[dict]]:
    """{plan_id: [person rating, …]} newest first."""
    if not plan_ids:
        return {}
    out: dict[int, list[dict]] = {}
    for pid, *r in conn.execute("SELECT plan_id, member_id, entered_by, family, company, note, rated_at FROM ratings "
                                "WHERE plan_id = ANY(%s) ORDER BY rated_at DESC, id DESC", (plan_ids,)):
        out.setdefault(pid, []).append(_person_rating(people, *r))
    return out


def _cooked(rows: list[dict], viewer: int | None, people: dict, company: str | None = None) -> dict:
    """CookedRatings fields for one time cooked from its person ratings (newest first). `rating` (older clients: the
    phones, which act as the legacy member) = `my_rating` = the viewer's own (viewer None = the Family rating) — someone else's
    rating doesn't count as the viewer's; their `company` is the recipe's household verdict, so the phone's rating
    screen shows and edits that."""
    def plain(x):
        return {"family": x["family"], "company": company, "note": x["note"], "rated_at": x["rated_at"]} if x else None
    mine = next((x for x in rows if x["member_id"] == viewer), None)
    return {"rating": plain(mine), "my_rating": plain(mine),
            "ratings": sorted(rows, key=lambda x: _order(people, x["member_id"])),
            "rating_avg": _avg(x["family"] for x in rows), "rating_count": len(rows)}


def _same(column: str) -> str:
    return f"{column} IS NOT DISTINCT FROM %(m)s"


def set_rating(conn, entry_id: int, family: int, company: str | None = None, note: str | None = None,
               at: datetime | None = None, rater: int | None = None, by: int | None = None) -> bool:
    """Upsert the rater's rating of the entry (rater None = Family) and log it. False if the plan entry doesn't
    exist."""
    with conn.transaction():
        row = conn.execute("SELECT recipe_id, week, day FROM plan WHERE id=%s FOR UPDATE", (entry_id,)).fetchone()
        if row is None:
            return False
        conn.execute(
            "INSERT INTO ratings(plan_id, member_id, entered_by, family, company, note, rated_at) "
            "VALUES(%s,%s,%s,%s,%s,%s,COALESCE(%s::timestamptz, now())) "
            "ON CONFLICT(plan_id, member_id) DO UPDATE SET family=excluded.family, company=excluded.company, "
            "note=excluded.note, rated_at=excluded.rated_at, entered_by=excluded.entered_by",
            (entry_id, rater, by, family, company, note, at))
        conn.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, family, company, note, at, "
                     "member_id, entered_by) VALUES(%s,%s,%s,%s,'set',%s,%s,%s,COALESCE(%s::timestamptz, now()),%s,%s)",
                     (entry_id, *row, family, company, note, at, rater, by))
    return True


def delete_rating(conn, entry_id: int, rater: int | None = None, by: int | None = None) -> bool:
    """Remove the rater's rating of the entry (logged in history if there was one). False if the plan entry doesn't
    exist."""
    with conn.transaction():
        row = conn.execute("SELECT recipe_id, week, day FROM plan WHERE id=%s FOR UPDATE", (entry_id,)).fetchone()
        if row is None:
            return False
        if conn.execute("DELETE FROM ratings WHERE plan_id=%(p)s AND " + _same("member_id"),
                        {"p": entry_id, "m": rater}).rowcount:
            conn.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, member_id, entered_by) "
                         "VALUES(%s,%s,%s,%s,'delete',%s,%s)", (entry_id, *row, rater, by))
    return True


# --- ratings from the recipe (not a planned night): one per recipe, day and person, in recipe_ratings ---

def _night_entry(conn, recipe_id: int, day: date, rater: int | None) -> int | None:
    """The recipe's plan entry placed on that night (one the rater hasn't rated first), or None."""
    row = conn.execute("SELECT p.id FROM plan p WHERE p.recipe_id=%(r)s AND p.day IS NOT NULL "
                       "AND p.week + p.day::int = %(d)s ORDER BY EXISTS (SELECT 1 FROM ratings x WHERE "
                       "x.plan_id = p.id AND " + _same("x.member_id") + "), p.id LIMIT 1",
                       {"r": recipe_id, "d": day, "m": rater}).fetchone()
    return row[0] if row else None


def rate_recipe(conn, recipe_id: int, family: int, company: str | None, note: str | None, today: date,
                cooked_on: date | None = None, rater: int | None = None, by: int | None = None) -> dict | None:
    """Rate a time the recipe was cooked, from the recipe itself, as `rater` (None = Family). Without cooked_on, the
    newest dinner of this recipe still waiting for the rater's rating (GET /ratings/pending) takes it, else today. A
    night on the plan for the recipe on that day takes the rating (the same as PUT /plan/{id}/rating), else it's the
    rater's rating of the recipe for that day (made or overwritten). Returns {cooked_on, entry_id, recipe_rating_id};
    None if the recipe doesn't exist."""
    if conn.execute("SELECT 1 FROM recipes WHERE id=%s", (recipe_id,)).fetchone() is None:
        return None
    if cooked_on is None:
        waiting = next((p for p in pending_ratings(conn, today, rater=rater) if p["recipe_id"] == recipe_id), None)
        if waiting:
            set_rating(conn, waiting["entry_id"], family, company, note, rater=rater, by=by)
            return {"cooked_on": waiting["date"], "entry_id": waiting["entry_id"], "recipe_rating_id": None}
        cooked_on = today
    eid = _night_entry(conn, recipe_id, cooked_on, rater)
    if eid is not None:
        set_rating(conn, eid, family, company, note, rater=rater, by=by)
        return {"cooked_on": cooked_on.isoformat(), "entry_id": eid, "recipe_rating_id": None}
    with conn.transaction():
        rrid = conn.execute(
            "INSERT INTO recipe_ratings(recipe_id, cooked_on, member_id, entered_by, family, company, note) "
            "VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(household_id, recipe_id, cooked_on, member_id) DO UPDATE SET "
            "family=excluded.family, company=excluded.company, note=excluded.note, entered_by=excluded.entered_by, "
            "rated_at=now() RETURNING id",
            (recipe_id, cooked_on, rater, by, family, company, note)).fetchone()[0]
        conn.execute("INSERT INTO rating_history(plan_id, recipe_rating_id, recipe_id, week, day, action, family, "
                     "company, note, member_id, entered_by) VALUES(NULL,%s,%s,%s,%s,'set',%s,%s,%s,%s,%s)",
                     (rrid, recipe_id, week_start(cooked_on), (cooked_on.weekday() + 1) % 7, family, company, note,
                      rater, by))
    return {"cooked_on": cooked_on.isoformat(), "entry_id": None, "recipe_rating_id": rrid}


def unrate_recipe(conn, recipe_id: int, cooked_on: date, rater: int | None = None, by: int | None = None) -> bool:
    """Remove the rater's rating of the time the recipe was cooked on that day: of the planned night, else of the
    recipe that day. False if the recipe doesn't exist."""
    if conn.execute("SELECT 1 FROM recipes WHERE id=%s", (recipe_id,)).fetchone() is None:
        return False
    row = conn.execute("SELECT p.id FROM plan p JOIN ratings r ON r.plan_id = p.id WHERE p.recipe_id=%(r)s "
                       "AND p.day IS NOT NULL AND p.week + p.day::int = %(d)s AND " + _same("r.member_id")
                       + " ORDER BY p.id LIMIT 1", {"r": recipe_id, "d": cooked_on, "m": rater}).fetchone()
    if row:
        return delete_rating(conn, row[0], rater, by)
    with conn.transaction():
        gone = conn.execute("DELETE FROM recipe_ratings WHERE recipe_id=%(r)s AND cooked_on=%(d)s AND "
                            + _same("member_id") + " RETURNING id",
                            {"r": recipe_id, "d": cooked_on, "m": rater}).fetchone()
        if gone:
            conn.execute("INSERT INTO rating_history(plan_id, recipe_rating_id, recipe_id, week, day, action, "
                         "member_id, entered_by) VALUES(NULL,%s,%s,%s,%s,'delete',%s,%s)",
                         (gone[0], recipe_id, week_start(cooked_on), (cooked_on.weekday() + 1) % 7, rater, by))
    return True


def recipe_rated_history(conn, recipe_id: int, viewer: int | None = None) -> list[dict]:
    """The recipe's ratings made from the recipe (not a planned night), one entry per day, newest day first."""
    people = _people(conn)
    days: dict[date, list[dict]] = {}
    for d, *r in conn.execute("SELECT cooked_on, member_id, entered_by, family, company, note, rated_at "
                              "FROM recipe_ratings WHERE recipe_id=%s ORDER BY cooked_on DESC, rated_at DESC, id DESC",
                              (recipe_id,)):
        days.setdefault(d, []).append(_person_rating(people, *r))
    company = company_verdicts(conn, [recipe_id]).get(recipe_id, {}).get("company")
    return [{"date": d.isoformat(), **_cooked(rows, viewer, people, company)} for d, rows in days.items()]


def pending_ratings(conn, today: date, days: int = 14, rater: int | None = None) -> list[dict]:
    """Entries placed on a night before today (within `days`) that the rater hasn't rated yet — most recent first.
    rater None (the shared token): nights nobody has rated (the household question, as before profiles)."""
    who = "" if rater is None else " AND {}.member_id = %(m)s"
    rows = conn.execute(
        "SELECT p.id, p.week, p.day, p.week + p.day::int AS d, p.recipe_id, r.title, p.multiplier "
        "FROM plan p JOIN recipes r ON r.id = p.recipe_id "
        "WHERE p.day IS NOT NULL AND p.week + p.day::int < %(t)s AND p.week + p.day::int >= %(t)s - %(n)s::int "
        "AND NOT EXISTS (SELECT 1 FROM ratings x WHERE x.plan_id = p.id" + who.format("x") + ") "
        "AND NOT EXISTS (SELECT 1 FROM recipe_ratings y WHERE y.recipe_id = p.recipe_id "
        "AND y.cooked_on = p.week + p.day::int" + who.format("y") + ") ORDER BY d DESC, p.id",
        {"t": today, "n": days, "m": rater})
    return [{"entry_id": i, "week": w.isoformat(), "day": dy, "date": d.isoformat(), "recipe_id": rid, "title": t,
             "multiplier": m} for i, w, dy, d, rid, t, m in rows]


def _empty_summary() -> dict:
    return {"times_cooked": 0, "times_rated": 0, "avg_family": None, "last_family": None,
            "last_rated_at": None, "company": None, "notes": [], "imported_ratings": 0, "rating_count": 0,
            "people": [], "good_for_company": False, "company_set_by": None, "company_set_by_name": None,
            "company_set_at": None}


def _cooking_rows(recipe_ids: list[int] | None, extra: str = "") -> str:
    """Every time cooked with its ratings, one row per rating (or one unrated row): recipe_id, cooking key, the
    day, multiplier, family, company, note, rated_at, imported, member_id, entered_by, tie-break."""
    ids = "" if recipe_ids is None else " AND {} = ANY(%(ids)s)"
    return ("SELECT p.recipe_id, 'p' || p.id AS k, p.week + p.day::int AS d, p.multiplier, r.family, r.company, "
            "r.note, r.rated_at, false AS imported, r.member_id, r.entered_by, p.id AS t "
            "FROM plan p LEFT JOIN ratings r ON r.plan_id = p.id WHERE " + (extra or "true") + ids.format("p.recipe_id")
            + " UNION ALL SELECT recipe_id, 'i' || id, cooked_on, multiplier, family, company, note, rated_at, true, "
            "NULL::int, NULL::int, -id FROM imported_ratings WHERE true" + ids.format("recipe_id")
            + " UNION ALL SELECT recipe_id, 'r' || cooked_on, cooked_on, 1::real, family, company, note, rated_at, "
            "false, member_id, entered_by, 0 FROM recipe_ratings WHERE true" + ids.format("recipe_id"))


def rating_summaries(conn, today: date, recipe_ids: list[int] | None = None) -> dict[int, dict]:
    """Per-recipe rating summary (all recipes, or just recipe_ids). A time cooked can have one rating per person:
    avg_family = the mean of every rating (each person's rating of each time cooked counts once; a Family rating —
    from before profiles or the shared token — counts as one rater; imported ones count one each, as before);
    times_rated = times cooked with at least one rating; people = per person: their latest rating and average;
    company = the recipe's household verdict (recipe_company), not the ratings' answers."""
    where = "" if recipe_ids is None else " WHERE id = ANY(%(ids)s)"
    out = {rid: _empty_summary() for (rid,) in conn.execute("SELECT id FROM recipes" + where, {"ids": recipe_ids})}
    people = _people(conn)
    rows = conn.execute("SELECT * FROM (" + _cooking_rows(recipe_ids) + ") x ORDER BY rated_at DESC NULLS LAST, t DESC",
                        {"ids": recipe_ids})
    cooked: dict[int, set] = {}
    rated: dict[int, set] = {}
    scores: dict[int, list[int]] = {}
    per: dict[int, dict] = {}       # recipe → member key → {latest, scores}
    for rid, key, cooked_on, _m, family, _company, note, rated_at, imported, member, _by, _t in rows:  # newest first
        s = out.get(rid)
        if s is None:
            continue
        if family is None:
            if imported or (cooked_on is not None and cooked_on < today):
                cooked.setdefault(rid, set()).add(key)
            continue
        cooked.setdefault(rid, set()).add(key)
        rated.setdefault(rid, set()).add(key)
        scores.setdefault(rid, []).append(family)
        s["imported_ratings"] += imported
        if s["last_family"] is None:
            s["last_family"], s["last_rated_at"] = family, rated_at.isoformat()
        name = None if imported else _who(people, member)[0]
        if note and len(s["notes"]) < 5:
            s["notes"].append({"note": note, "date": cooked_on.isoformat() if cooked_on else None,
                               "rated_at": rated_at.isoformat(), "member_id": member, "name": name})
        if not imported:
            p = per.setdefault(rid, {}).get(member)
            if p is None:
                n, kind = _who(people, member)
                per[rid][member] = p = {"member_id": member, "name": n, "kind": kind, "family": family,
                                        "note": note,
                                        "date": cooked_on.isoformat() if cooked_on else None,
                                        "rated_at": rated_at.isoformat(), "scores": []}
            p["scores"].append(family)
    verdicts = company_verdicts(conn, recipe_ids)
    for rid, s in out.items():
        s["times_cooked"], s["times_rated"] = len(cooked.get(rid, ())), len(rated.get(rid, ()))
        s["rating_count"], s["avg_family"] = len(scores.get(rid, ())), _avg(scores.get(rid, ()))
        v = verdicts.get(rid, {})
        s["company"] = v.get("company")
        s["company_set_by"], s["company_set_by_name"] = v.get("company_set_by"), v.get("company_set_by_name")
        s["company_set_at"] = v.get("company_set_at")
        s["good_for_company"] = s["company"] == "yes"
        s["people"] = [{**{k: v for k, v in p.items() if k != "scores"}, "avg": _avg(p["scores"]),
                        "count": len(p["scores"])}
                       for m, p in sorted(per.get(rid, {}).items(), key=lambda kv: _order(people, kv[0]))]
    return out


def planned_weeks(conn, today: date, recipe_ids: list[int] | None = None) -> dict[int, list[str]]:
    """{recipe_id: [ISO week, …]} for weeks from today's week on that have the recipe (placed or not), soonest first."""
    rows = conn.execute("SELECT recipe_id, array_agg(DISTINCT week ORDER BY week) FROM plan WHERE week >= %(w)s"
                        + ("" if recipe_ids is None else " AND recipe_id = ANY(%(ids)s)") + " GROUP BY recipe_id",
                        {"w": week_start(today), "ids": recipe_ids})
    return {rid: [w.isoformat() for w in weeks] for rid, weeks in rows}


def recipe_history(conn, recipe_id: int, viewer: int | None = None) -> list[dict]:
    """Every plan entry for a recipe (newest week first) with its ratings."""
    rows = conn.execute(
        "SELECT p.id, p.week, p.day, p.week + p.day::int, p.multiplier FROM plan p WHERE p.recipe_id=%s "
        "ORDER BY p.week DESC, COALESCE(p.day, -1) DESC, p.id DESC", (recipe_id,)).fetchall()
    people = _people(conn)
    rated = _plan_ratings(conn, [r[0] for r in rows], people)
    company = company_verdicts(conn, [recipe_id]).get(recipe_id, {}).get("company")
    return [{"entry_id": i, "week": w.isoformat(), "day": dy, "date": d.isoformat() if d else None, "multiplier": m,
             **_cooked(rated.get(i, []), viewer, people, company)} for i, w, dy, d, m in rows]


def rating_entries(conn, today: date, recipe_ids: list[int] | None = None) -> dict[int, list[dict]]:
    """{recipe_id: [entry, …]} — every time cooked, for the export's `mealprep:ratings.entries`: plan entries that are
    rated or were placed on a night before today (so times_cooked survives a trip to another library), the ratings
    made from the recipe (recipe_ratings) and the imported ones. Each entry: date (the night, or null), multiplier,
    family, company, note, rated_at. Newest first (rated_at, else the night). A time cooked that several people rated
    is one entry: family = their mean (rounded half up), notes "Name: note" joined. `company` is always null: Good for
    company is one household verdict per recipe (the file's summary), never an answer counted per time cooked."""
    rows = conn.execute(
        "SELECT * FROM (" + _cooking_rows(recipe_ids, "(r.plan_id IS NOT NULL OR (p.day IS NOT NULL AND "
                                                       "p.week + p.day::int < %(t)s))")
        + ") x ORDER BY COALESCE(rated_at, d::timestamptz) DESC NULLS LAST, t DESC", {"t": today, "ids": recipe_ids})
    people = _people(conn)
    cookings: dict[tuple, dict] = {}    # insertion order = newest first
    for rid, key, d, m, family, _company, note, rated_at, _imp, member, _by, _t in rows:
        e = cookings.setdefault((rid, key), {"rid": rid, "date": d, "multiplier": float(m), "rows": []})
        if family is not None:
            e["rows"].append((family, None, note, rated_at, member))
    out: dict[int, list[dict]] = {}
    for e in cookings.values():
        rs = e["rows"]
        if len(rs) > 1:
            family = int((Decimal(sum(r[0] for r in rs)) / len(rs)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
            note = " · ".join(f"{_who(people, r[4])[0]}: {r[2]}"
                             for r in sorted(rs, key=lambda r: _order(people, r[4])) if r[2]) or None
        else:
            family, note = (rs[0][0], rs[0][2]) if rs else (None, None)
        rated_at = max((r[3] for r in rs), default=None)
        out.setdefault(e["rid"], []).append({
            "date": e["date"].isoformat() if e["date"] else None, "multiplier": e["multiplier"], "family": family,
            "company": None, "note": note,
            "rated_at": rated_at.astimezone(timezone.utc).isoformat() if rated_at else None})
    return out


def created_at(conn, recipe_ids: list[int]) -> dict[int, datetime]:
    return dict(conn.execute("SELECT id, created_at FROM recipes WHERE id = ANY(%s)", (recipe_ids,)).fetchall())


def imported_history(conn, recipe_id: int) -> list[dict]:
    """The times cooked an import brought in for a recipe (newest first), shaped like GET /recipes/{id} history."""
    rows = conn.execute("SELECT cooked_on, multiplier, family, company, note, rated_at FROM imported_ratings "
                        "WHERE recipe_id=%s ORDER BY COALESCE(rated_at, cooked_on::timestamptz) DESC NULLS LAST, id",
                        (recipe_id,))
    return [{"date": d.isoformat() if d else None, "multiplier": float(m),
             "rating": (rt.model_dump() if (rt := _rating(f, c, n, at)) else None),
             "rated_at": at.isoformat() if at else None} for d, m, f, c, n, at in rows]


def insert_imported_ratings(conn, recipe_id: int, entries, origin: str = "mealprep") -> int:
    """Add imported times cooked (ImportedRating-like: date, multiplier, family, company, note, rated_at,
    fingerprint()); an entry already there (same fingerprint) is skipped. Returns how many were added."""
    added = 0
    for e in entries:
        added += conn.execute(
            "INSERT INTO imported_ratings(recipe_id, cooked_on, multiplier, family, company, note, rated_at, origin, "
            "fingerprint) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (recipe_id, fingerprint) DO NOTHING",
            (recipe_id, e.date, e.multiplier, e.family, e.company, e.note, e.rated_at, origin, e.fingerprint())).rowcount
    return added


def recipe_keys(conn) -> list[tuple]:
    """(id, uid, title, source_url, source_kind, source_title) of every recipe: the import's de-dup index."""
    return conn.execute("SELECT id, uid::text, title, source_url, source_kind, source_title FROM recipes "
                        "ORDER BY id").fetchall()


def replace_recipe(conn, rid: int, r: Recipe, with_source: bool) -> None:
    """An import's "Replace with the file's version": title, servings, lines, steps and the extra fields; the source
    (kind, title, page, author, ISBN, link) too when the file has one. Id, uid, plan entries and ratings stay."""
    conn.execute("UPDATE recipes SET title=%s, servings=%s, data=%s WHERE id=%s", (r.title, r.servings, _data(r), rid))
    if with_source:
        update_source(conn, rid, r.source_kind or r.default_kind(), r.source_title, r.source_ref, r.source_author,
                      r.source_isbn)
        if r.source_url:
            conn.execute("UPDATE recipes SET source_url=%s WHERE id=%s AND NOT EXISTS "
                         "(SELECT 1 FROM recipes WHERE source_url=%s AND id<>%s)", (r.source_url, rid, r.source_url, rid))
