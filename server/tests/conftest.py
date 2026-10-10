import os
import pytest

TEST_DSN = os.environ.get("MEALPREP_TEST_DSN")
TABLES = "document_reads, import_jobs, imported_ratings, staples, prep_task_events, cook_cards, prep_tasks, prep_plans, rating_history, ratings, cart_lines, cart_weeks, carts, pick_history, picks, price_observations, products, plan, recipes"


@pytest.fixture
def conn():
    if not TEST_DSN:
        pytest.skip("MEALPREP_TEST_DSN not set")
    from mealprep import db
    c = db.connect(TEST_DSN)
    c.execute(f"TRUNCATE {TABLES} RESTART IDENTITY CASCADE")
    yield c
    c.close()
