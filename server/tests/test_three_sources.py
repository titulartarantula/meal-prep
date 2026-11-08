"""The one-off three-sources migration (2026-11-08): NYT and linked Other recipes become websites. Invented data."""
import pytest
from psycopg.types.json import Jsonb

from mealprep import db, three_sources
from test_sources import book, nyt


def raw(conn, rid):
    return conn.execute("SELECT source_kind, source_title, source_ref, source_author, source_isbn FROM recipes "
                        "WHERE id=%s", (rid,)).fetchone()


def library(conn):
    ids = {"nyt": db.save_recipe(conn, nyt("Test Noodles", 1)),
           "book": db.save_recipe(conn, book("Test Tart", "Invented Bakes", "p. 40")),
           "card": db.save_recipe(conn, book("Test Card", "Index card", kind="other")),
           "linked": db.save_recipe(conn, book("Test Stew", "Sam's binder", "tab 2", kind="other")),
           "pub": db.save_recipe(conn, book("Test Pie", kind="other")),
           "nytother": db.save_recipe(conn, book("Test Soup", kind="other"))}
    # rows as the database held them before 2026-11-08: kind nyt without a site; others with links
    conn.execute("UPDATE recipes SET source_kind='nyt', source_title=NULL WHERE id=%s", (ids["nyt"],))
    conn.execute("UPDATE recipes SET source_url='https://www.kitchen.example.co.uk/stew?utm_source=x' WHERE id=%s",
                 (ids["linked"],))
    conn.execute("UPDATE recipes SET source_url='https://pies.example.com/p', data = data || %s WHERE id=%s",
                 (Jsonb({"schema_extra": {"publisher": {"name": "Pie Place"}, "author": [{"name": "Ada Pepper"}]}}),
                  ids["pub"]))
    conn.execute("UPDATE recipes SET source_url='https://cooking.nytimes.com/recipes/2-soup' WHERE id=%s",
                 (ids["nytother"],))
    return ids


def test_plan_apply_revert(conn):
    ids = library(conn)
    before = {k: raw(conn, i) for k, i in ids.items()}
    keep = three_sources.snapshot(conn)
    log = three_sources.plan(conn)
    assert log["counts"] == {"nyt_to_web": 1, "other_to_web": 3, "unchanged": 2}
    assert raw(conn, ids["nyt"]) == before["nyt"]               # a plan changes nothing
    three_sources.apply(conn, log)
    assert three_sources.snapshot(conn) == keep                  # links, data, titles untouched
    assert raw(conn, ids["nyt"]) == ("web", "NYT Cooking", None, None, None)
    assert raw(conn, ids["linked"]) == ("web", "example.co.uk", None, None, None)      # the domain, no note
    assert raw(conn, ids["pub"]) == ("web", "Pie Place", None, "Ada Pepper", None)     # saved publisher + author
    assert raw(conn, ids["nytother"]) == ("web", "NYT Cooking", None, None, None)
    assert raw(conn, ids["book"]) == before["book"] and raw(conn, ids["card"]) == before["card"]
    assert {s["kind"] for s in db.recipe_sources(conn)} == {"web", "book", "other"}
    again = three_sources.plan(conn)                             # a second run changes nothing more
    assert again["counts"] == {"nyt_to_web": 0, "other_to_web": 0, "unchanged": 6}
    # someone edits one recipe after the run: revert leaves that one alone
    db.update_source(conn, ids["pub"], "web", "Pie Place", None, "Basil Thyme")
    out = three_sources.revert(conn, log)
    assert out["skipped_changed_since"] == [ids["pub"]] and len(out["reverted"]) == 3
    for k in ("nyt", "linked", "nytother", "book", "card"):
        assert raw(conn, ids[k]) == before[k]
    assert three_sources.snapshot(conn) == keep


def test_apply_stops_when_a_row_changed_since_the_plan(conn):
    ids = library(conn)
    log = three_sources.plan(conn)
    db.update_source(conn, ids["linked"], "other", "Renamed binder", None)   # the household edits it meanwhile
    with pytest.raises(RuntimeError, match="changed since the plan"):
        three_sources.apply(conn, log)
