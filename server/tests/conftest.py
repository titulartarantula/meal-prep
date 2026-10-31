import os
import pytest

TEST_DSN = os.environ.get("MEALPREP_TEST_DSN")
TABLES = "recipe_ratings, pack_contents, fridge_nudges, fridge_reads, fridge_items, extras, document_reads, import_jobs, imported_ratings, staples, prep_task_events, cook_cards, prep_tasks, prep_plans, rating_history, ratings, cart_lines, cart_weeks, carts, pick_history, picks, price_observations, products, plan, recipes"
# Identity rows a test may add (the first household stays: everything from before households belongs to it).
IDENTITY = ("DELETE FROM sessions; DELETE FROM login_states; DELETE FROM invites; DELETE FROM audit_log; "
            "DELETE FROM ai_usage; UPDATE households SET legacy_member_id = NULL; DELETE FROM member_roles; "
            "DELETE FROM members; DELETE FROM households WHERE id <> (SELECT min(id) FROM households); "
            "DELETE FROM users; UPDATE households SET created_by = NULL, name = 'Household', store_id = '1092', plan = 'free', "
            "status = 'active', ai_daily_budget = NULL")


@pytest.fixture(autouse=True)
def _home_network_client(monkeypatch):
    """The shared bearer token is only accepted from the home network: test clients come from 127.0.0.1 unless a
    test says otherwise (TestClient's default host is "testclient", not an address)."""
    from fastapi.testclient import TestClient
    init = TestClient.__init__

    def __init__(self, *a, **kw):
        kw.setdefault("client", ("127.0.0.1", 50000))
        init(self, *a, **kw)
    monkeypatch.setattr(TestClient, "__init__", __init__)


@pytest.fixture
def conn():
    if not TEST_DSN:
        pytest.skip("MEALPREP_TEST_DSN not set")
    from mealprep import db
    c = db.connect(TEST_DSN)
    c.execute(f"TRUNCATE {TABLES} RESTART IDENTITY CASCADE")
    c.execute(IDENTITY)
    db.scope(c, db.first_household(c))   # the existing tests act for the first household, as the app's requests do
    yield c
    c.close()
