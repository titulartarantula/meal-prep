"""The households migration on a copy of a pre-households database (the 0.15.0 schema with rows in every table):
one household is created, every household-owned row is backfilled to it, row-level security shows them to that
household only, the shared caches stay global, and re-running changes nothing."""
import os
from datetime import date

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict, make_conninfo
from psycopg.types.json import Jsonb

from mealprep import db
from mealprep.models import Recipe

TEST_DSN = os.environ.get("MEALPREP_TEST_DSN")
OLD = (os.path.dirname(__file__) + "/fixtures/schema_before_households.sql")
SCRATCH = "mealprep_migtest"
OWNED = ["recipes", "plan", "picks", "pick_history", "carts", "cart_weeks", "cart_lines", "ratings", "rating_history",
         "prep_plans", "prep_tasks", "prep_task_events", "cook_cards", "staples", "imported_ratings", "import_jobs",
         "extras", "fridge_items", "fridge_reads", "fridge_nudges"]
GLOBAL = ["products", "price_observations", "pack_contents", "document_reads", "seeds"]


class Dsn(str):
    def __repr__(self):       # never show the connection string (it has the password) in a failure report
        return "<scratch database>"


@pytest.fixture
def old_db():
    if not TEST_DSN:
        pytest.skip("MEALPREP_TEST_DSN not set")
    admin = psycopg.connect(TEST_DSN, autocommit=True)
    admin.execute(f"DROP DATABASE IF EXISTS {SCRATCH} WITH (FORCE)")
    admin.execute(f"CREATE DATABASE {SCRATCH}")
    dsn = Dsn(make_conninfo(**{**conninfo_to_dict(TEST_DSN), "dbname": SCRATCH}))
    try:
        with psycopg.connect(dsn, autocommit=True) as c:
            c.execute(open(OLD).read())
            fill(c)
        yield dsn
    finally:
        admin.execute(f"DROP DATABASE IF EXISTS {SCRATCH} WITH (FORCE)")
        admin.close()


def fill(c):
    """Rows in every table, as 0.15.0 wrote them."""
    data = {"title": "Soup", "source": "nyt", "ingredients": [{"raw": "2 eggs", "name": "egg", "qty": 2}], "steps": ["x"]}
    r1 = c.execute("INSERT INTO recipes(source, source_url, title, servings, data) VALUES('nyt', "
                   "'https://cooking.nytimes.com/recipes/1-soup', 'Soup', 4, %s) RETURNING id", (Jsonb(data),)).fetchone()[0]
    r2 = c.execute("INSERT INTO recipes(source, title, servings, data, source_kind, source_title) VALUES('photo', "
                   "'Stew', 4, %s, 'book', 'Invented Book') RETURNING id", (Jsonb({**data, "title": "Stew"}),)).fetchone()[0]
    p1 = c.execute("INSERT INTO plan(week, recipe_id, day) VALUES('2026-10-12', %s, 1) RETURNING id", (r1,)).fetchone()[0]
    p2 = c.execute("INSERT INTO plan(week, recipe_id, day) VALUES('2026-10-12', %s, 2) RETURNING id", (r2,)).fetchone()[0]
    c.execute("INSERT INTO ratings(plan_id, family, company) VALUES(%s, 5, 'yes')", (p1,))
    c.execute("INSERT INTO rating_history(plan_id, recipe_id, week, day, action, family) "
              "VALUES(%s,%s,'2026-10-12',1,'set',5)", (p1, r1))
    c.execute("INSERT INTO products(code, name) VALUES('P1', 'Eggs'), ('P2', 'Milk')")
    c.execute("INSERT INTO price_observations(code, store_id, price) VALUES('P1', '1092', 4.99)")
    c.execute("INSERT INTO pack_contents(code, pieces, word, source) VALUES('P1', 12, 'carton', 'user')")
    c.execute("INSERT INTO picks(key, product_code, chosen_by, pinned) VALUES('egg|each', 'P1', 'user', true)")
    c.execute("INSERT INTO pick_history(key, product_code, chosen_by) VALUES('egg|each', 'P1', 'user')")
    cart = c.execute("INSERT INTO carts(pcx_cart_id, store_id, status, weeks, sent_at) VALUES('pc-1', '1092', 'sent', "
                     "'{2026-10-12}', now()) RETURNING id").fetchone()[0]
    c.execute("INSERT INTO cart_weeks VALUES(%s, '2026-10-12')", (cart,))
    c.execute("INSERT INTO cart_lines(cart_id, item_key, product_code, quantity, status, source) "
              "VALUES(%s, 'egg|each', 'P1', 1, 'added', 'memory')", (cart,))
    pp = c.execute("INSERT INTO prep_plans(weeks, status, input) VALUES('{2026-10-12}', 'ready', '{}') RETURNING id"
                   ).fetchone()[0]
    c.execute("INSERT INTO prep_tasks(prep_plan_id, task_id, section, position, text, shelf_life) "
              "VALUES(%s, 't1', 'knife', 0, 'Chop', 'ok')", (pp,))
    c.execute("INSERT INTO prep_task_events(prep_plan_id, task_id, done) VALUES(%s, 't1', true)", (pp,))
    c.execute("INSERT INTO cook_cards(prep_plan_id, plan_id, card) VALUES(%s, %s, '{}')", (pp, p1))
    c.execute("INSERT INTO imported_ratings(recipe_id, family, origin, fingerprint) VALUES(%s, 4, 'mealprep', 'f1')",
              (r2,))
    c.execute("INSERT INTO import_jobs(file_sha, choices_sha, status, report) VALUES('s', 'c', 'done', '{}')")
    c.execute("INSERT INTO document_reads(file_sha, format, recipes) VALUES('s', 'pdf', '[]')")
    c.execute("INSERT INTO extras(week, term, key, product_code, quantity) VALUES('2026-10-12', 'milk', 'milk', 'P2', 1)")
    c.execute("INSERT INTO fridge_items(name, key, key_source, qty) VALUES('Eggs', 'egg', 'rule', 6)")
    c.execute("INSERT INTO fridge_reads(photos_sha, photo_count, status) VALUES('ph', 2, 'applied')")
    c.execute("INSERT INTO fridge_nudges(plan_id, action) VALUES(%s, 'dismissed')", (p2,))


def counts(c, tables):
    return {t: c.execute(f"SELECT count(*) FROM {t}").fetchone()[0] for t in tables}


def test_migration_backfills_every_household_row_and_is_idempotent(old_db):
    with psycopg.connect(old_db, autocommit=True) as c:
        before = counts(c, OWNED + GLOBAL)
    assert all(before[t] > 0 for t in OWNED + GLOBAL), before
    with db.connect(old_db) as c:          # the service's startup migration
        db.migrate(c)                      # … and the next start
        assert c.execute("SELECT id, name, store_id, plan, status FROM households").fetchall() == \
            [(1, "Household", "1092", "free", "active")]
        assert counts(c, OWNED + GLOBAL) == {**before, "seeds": before["seeds"] + 2}   # + role_permissions-1, -2
        for t in OWNED:
            col = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_name=%s "
                            "AND column_name='household_id'", (t,)).fetchone()
            assert col == ("NO",), t
            assert c.execute(f"SELECT array_agg(DISTINCT household_id) FROM {t}").fetchone()[0] == [1], t
            assert c.execute("SELECT relrowsecurity FROM pg_class WHERE relname=%s", (t,)).fetchone()[0], t
        for t in GLOBAL:
            assert c.execute("SELECT 1 FROM information_schema.columns WHERE table_name=%s "
                             "AND column_name='household_id'", (t,)).fetchone() is None, t
        assert c.execute("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='picks_pkey'"
                         ).fetchone()[0] == "PRIMARY KEY (household_id, key)"
        # per-person ratings: the old ratings stay, as Family (no member); one per night and person now
        assert c.execute("SELECT family, company, member_id, entered_by FROM ratings").fetchall() == [(5, "yes", None, None)]
        assert c.execute("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ratings_pkey'"
                         ).fetchone()[0] == "PRIMARY KEY (id)"
        assert {k for (k,) in c.execute("SELECT key FROM roles")} == {"owner", "approver", "planner", "contributor",
                                                                      "child"}
        # the household sees everything it had …
        db.scope(c, 1)
        assert counts(c, OWNED) == {t: before[t] for t in OWNED}
        assert [e.rating.family if e.rating else None for e in db.get_week(c, date(2026, 10, 12))] == [5, None]
        assert db.get_pick(c, "egg|each") == "P1"
        # … and a new household sees none of it, but the shared caches
        db.unscope(c)
        other = c.execute("INSERT INTO households(name) VALUES('Other') RETURNING id").fetchone()[0]
        db.scope(c, other)
        assert set(counts(c, OWNED).values()) == {0}
        assert counts(c, ["products", "pack_contents", "document_reads"]) == \
            {t: before[t] for t in ["products", "pack_contents", "document_reads"]}
        db.save_recipe(c, Recipe(title="Soup", source="nyt", ingredients=[], steps=[],
                                 source_url="https://cooking.nytimes.com/recipes/1-soup"))   # per-household unique
        db.unscope(c)
        assert c.execute("SELECT count(*) FROM recipes").fetchone()[0] == before["recipes"] + 1
        db.migrate(c)
        assert c.execute("SELECT count(*) FROM households").fetchone()[0] == 2     # no second "first" household


def test_staples_seed_on_a_fresh_database_goes_to_the_first_household(old_db):
    admin = psycopg.connect(TEST_DSN, autocommit=True)
    admin.execute(f"DROP DATABASE IF EXISTS {SCRATCH} WITH (FORCE)")
    admin.execute(f"CREATE DATABASE {SCRATCH}")
    admin.close()
    with db.connect(old_db) as c:                       # a brand-new database: schema from nothing
        db.migrate(c)
        assert c.execute("SELECT household_id, name FROM staples ORDER BY position").fetchall() == \
            [(1, "2% milk"), (1, "eggs"), (1, "lemonade")]
