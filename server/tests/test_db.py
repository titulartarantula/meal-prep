from datetime import date
from mealprep import db
from mealprep.models import Recipe, Ingredient


def test_recipe_roundtrip(conn):
    r = Recipe(title="Chili", source="nyt", source_url="https://x", servings=6,
               ingredients=[Ingredient(raw="2 onions", name="onion", qty=2, unit=None)],
               steps=["Chop."])
    rid = db.save_recipe(conn, r, ai_provider="claude-cli")
    got = db.get_recipe(conn, rid)
    assert got.title == "Chili" and got.id == rid
    assert got.ingredients[0].name == "onion"
    assert conn.execute("SELECT ai_provider, title, servings FROM recipes WHERE id=%s", (rid,)).fetchone() == ("claude-cli", "Chili", 6)


def test_same_source_url_not_duplicated(conn):
    r = Recipe(title="A", source="nyt", source_url="https://x", servings=4, ingredients=[], steps=[])
    assert db.save_recipe(conn, r) == db.save_recipe(conn, r)


def test_list_recipes_newest_first(conn):
    a = db.save_recipe(conn, Recipe(title="A", source="photo", ingredients=[], steps=[]))
    b = db.save_recipe(conn, Recipe(title="B", source="photo", ingredients=[], steps=[]))
    assert [r.id for r in db.list_recipes(conn)] == [b, a]


def test_picks(conn):
    assert db.get_pick(conn, "chicken thigh|boneless") is None
    db.set_pick(conn, "chicken thigh|boneless", "21341017_EA")
    assert db.get_pick(conn, "chicken thigh|boneless") == "21341017_EA"


def test_set_pick_appends_history(conn):
    db.set_pick(conn, "k", "A")
    db.set_pick(conn, "k", "B", chosen_by="user")
    assert conn.execute("SELECT product_code, chosen_by FROM picks WHERE key='k'").fetchone() == ("B", "user")
    assert conn.execute("SELECT product_code, chosen_by FROM pick_history WHERE key='k' ORDER BY id").fetchall() == [("A", "ai"), ("B", "user")]


def test_week_start_is_sunday():
    assert db.week_start(date(2026, 10, 4)) == date(2026, 10, 4)    # Sunday
    assert db.week_start(date(2026, 10, 7)) == date(2026, 10, 4)    # Wednesday
    assert db.week_start(date(2026, 10, 10)) == date(2026, 10, 4)   # Saturday


def test_week_plan(conn):
    rid = db.save_recipe(conn, Recipe(title="A", source="photo", servings=4, ingredients=[], steps=[]))
    wk = date(2026, 10, 11)
    eid = db.add_to_week(conn, wk, rid)
    [e] = db.get_week(conn, wk)
    assert e.recipe_id == rid and e.day is None and e.multiplier == 1.0 and e.week == "2026-10-11"
    db.update_entry(conn, eid, day=2, multiplier=1.5)      # 0=Sun … 6=Sat
    [e] = db.get_week(conn, wk)
    assert e.day == 2 and e.multiplier == 1.5
    db.add_to_week(conn, wk, rid)                          # same recipe twice in a week is allowed (two nights)
    assert len(db.get_week(conn, wk)) == 2
    db.remove_entry(conn, eid)
    assert len(db.get_week(conn, wk)) == 1


def test_add_to_week_normalizes_to_sunday(conn):
    rid = db.save_recipe(conn, Recipe(title="A", source="photo", ingredients=[], steps=[]))
    db.add_to_week(conn, date(2026, 10, 14), rid)
    assert db.get_week(conn, date(2026, 10, 11))[0].week == "2026-10-11"


def test_record_products_upserts_and_observes_price(conn):
    from mealprep.models import Product
    p = Product(code="A", name="Thighs", brand="PC", package_size="1 ea", price=15.0, stock="OK")
    db.record_products(conn, "1092", [p])
    db.record_products(conn, "1092", [p.model_copy(update={"price": 13.5, "name": "Thighs 2"})])
    assert conn.execute("SELECT name, brand FROM products WHERE code='A'").fetchone() == ("Thighs 2", "PC")
    rows = conn.execute("SELECT store_id, price::float, stock FROM price_observations WHERE code='A' ORDER BY id").fetchall()
    assert rows == [("1092", 15.0, "OK"), ("1092", 13.5, "OK")]


LEGACY_CART_DDL = """
DROP TABLE IF EXISTS cart_lines, cart_weeks, carts CASCADE;
CREATE TABLE carts(id serial PRIMARY KEY, pcx_cart_id text UNIQUE, store_id text, ai_provider text,
  created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE cart_weeks(cart_id int REFERENCES carts ON DELETE CASCADE, week date, PRIMARY KEY(cart_id, week));
CREATE TABLE cart_lines(id serial PRIMARY KEY, cart_id int REFERENCES carts ON DELETE CASCADE, item_key text,
  item_name text, need_qty real, need_unit text, product_code text REFERENCES products, quantity int,
  price_at_add numeric(8,2), status text, source text CHECK (source IN ('memory','ai','none')));
"""


def test_schema_migrates_legacy_carts_idempotently(conn):
    """The live DB predates drafts: existing carts must become 'sent' drafts, data kept, rerunnable."""
    conn.execute(LEGACY_CART_DDL)
    conn.execute("INSERT INTO products(code, name) VALUES('A', 'Thighs')")
    cid = conn.execute("INSERT INTO carts(pcx_cart_id, store_id, ai_provider) VALUES('old-1','1092','claude-cli') "
                       "RETURNING id").fetchone()[0]
    conn.execute("INSERT INTO cart_weeks VALUES(%s, '2026-10-11')", (cid,))
    conn.execute("INSERT INTO cart_lines(cart_id, item_key, item_name, product_code, quantity, price_at_add, status, source) "
                 "VALUES(%s, 'chicken thigh|each', 'chicken thigh', 'A', 1, 15, 'added', 'ai')", (cid,))
    for _ in range(2):
        conn.execute(db.SCHEMA)
    assert conn.execute("SELECT status, pcx_cart_id, weeks FROM carts").fetchone() == ("sent", "old-1", [date(2026, 10, 11)])
    assert conn.execute("SELECT removed, alternatives, price_at_add::float FROM cart_lines").fetchone() == (False, [], 15.0)
    # new drafts default to building, and the 'user' source is now allowed
    new = conn.execute("INSERT INTO carts(store_id) VALUES('1092') RETURNING status").fetchone()[0]
    assert new == "building"
    conn.execute("INSERT INTO cart_lines(cart_id, item_key, source) VALUES(%s, 'k', 'user')", (cid,))
    assert db.week_summaries(conn, date(2026, 10, 11), 1)[0]["carted"] is True
