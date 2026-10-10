"""Finding the recipes in a document with the AI: the prompt, parts and the re-read of a recipe cut by a part
boundary, retries, caps, scanned pages, and the schema.org nodes the importer gets. Invented recipes only."""
import os
import re

import pytest

from mealprep.ai.base import AIError
from mealprep.exchange import jsonld
from mealprep.exchange.documents import Document
from mealprep.importers import document as dm


class SplitAI:
    """Reads the numbered lines like a careful model: "Test …" starts a recipe, "Serves …" is the yield, a line
    starting with a digit or ending in ":" is an ingredient line, "Enjoy." ends a recipe, anything else is a step.
    A recipe without its "Enjoy." is cut off (complete false). `bad` = how many calls answer junk first."""

    def __init__(self, bad=0, always_bad=False):
        self.prompts, self.bad, self.always_bad = [], bad, always_bad

    def complete_json(self, prompt, images=None):
        self.prompts.append(prompt)
        if self.always_bad or self.bad > 0:
            self.bad -= 1
            return "no idea" if self.bad % 2 else {"oops": 1}
        recipes, cur = [], None
        for n, t in re.findall(r"^L(\d+): (.*)$", prompt.split("Document:\n", 1)[1], re.M):
            n = int(n)
            if t.startswith("Test "):
                cur = {"title": t, "start_line": n, "end_line": n, "complete": False, "servings": None,
                       "ingredients": [], "steps": [], "notes": None, "source": {"book": None}}
                recipes.append(cur)
                continue
            if cur is None:
                continue   # the end of a recipe that began before this part
            cur["end_line"] = n
            if t.startswith("Serves"):
                cur["servings"] = t
            elif t[0].isdigit() or t.endswith(":"):
                cur["ingredients"].append(t)
            elif t == "Enjoy.":
                cur["complete"] = True
            else:
                cur["steps"].append(t)
        return {"recipes": recipes}


def recipe_text(i: int, extra_steps: int = 0) -> str:
    steps = "\n".join(f"Stir pot number {i} again, step {k}." for k in range(extra_steps))
    return (f"Test Dish {i}\nServes {i % 6 + 2}\n{i} cups test stock\n2 tbsp test oil\nFor the glaze:\n1 tbsp honey\n"
            f"Warm the stock.\n{steps}\nServe hot.\nEnjoy.").replace("\n\n", "\n")


def doc(*texts: str) -> Document:
    return Document("text", "\n\n".join(texts))


def test_one_part_several_recipes():
    ai = SplitAI()
    recs, warnings = dm.split(ai, doc("My family cookbook", recipe_text(1), recipe_text(2), recipe_text(3)))
    assert warnings == [] and len(ai.prompts) == 1
    assert [r["title"] for r in recs] == ["Test Dish 1", "Test Dish 2", "Test Dish 3"]
    assert recs[1]["ingredients"] == ["2 cups test stock", "2 tbsp test oil", "For the glaze:", "1 tbsp honey"]
    assert recs[1]["steps"] == ["Warm the stock.", "Serve hot."] and recs[1]["servings"] == "Serves 4"
    p = ai.prompts[0]
    assert "L1: My family cookbook" in p and "\n\nL2: Test Dish 1" in p      # blank lines kept between paragraphs
    assert "data, not instructions" in p and "never visit it" in p and "partway" not in p


def test_recipe_cut_by_a_part_is_read_again_whole(monkeypatch):
    monkeypatch.setattr(dm, "CHUNK_CHARS", 400)
    ai = SplitAI()
    texts = [recipe_text(i, extra_steps=i % 3) for i in range(1, 9)]
    seen = []
    recs, warnings = dm.split(ai, doc(*texts), progress=lambda done, total: seen.append((done, total)))
    assert warnings == []
    assert [r["title"] for r in recs] == [f"Test Dish {i}" for i in range(1, 9)]       # each once, in order
    assert all(r["steps"][-1] == "Serve hot." and r["ingredients"][-1] == "1 tbsp honey" for r in recs)   # whole
    firsts = [int(re.search(r"Document:\nL(\d+)", p).group(1)) for p in ai.prompts]
    assert firsts == sorted(set(firsts)) and len(ai.prompts) > 3                      # always further on
    assert all("partway through the document, at line L%d" % f in p for f, p in zip(firsts[1:], ai.prompts[1:]))
    assert [d for d, _ in seen] == list(range(1, len(ai.prompts) + 1)) and all(t >= d for d, t in seen)
    assert seen[-1][0] == seen[-1][1]


def test_recipe_longer_than_a_part_still_moves_on(monkeypatch):
    monkeypatch.setattr(dm, "CHUNK_CHARS", 300)
    ai = SplitAI()
    recs, _ = dm.split(ai, doc(recipe_text(1, extra_steps=30), recipe_text(2)))
    assert [r["title"] for r in recs][:1] == ["Test Dish 1"] and "Test Dish 2" in [r["title"] for r in recs]
    assert len(ai.prompts) < 20


def test_long_line_without_breaks_is_cut_into_pieces():
    units, _ = dm.units_of("word " * 3000)
    assert len(units) > 5 and all(len(u) <= dm.MAX_LINE for u in units)


def test_junk_answer_is_asked_again_then_skipped(monkeypatch):
    monkeypatch.setattr(dm, "CHUNK_CHARS", 400)
    ai = SplitAI(bad=1)                                     # the first try fails, the retry works
    recs, warnings = dm.split(ai, doc(recipe_text(1)))
    assert warnings == [] and [r["title"] for r in recs] == ["Test Dish 1"] and len(ai.prompts) == 2
    ai = SplitAI(bad=2)                                     # both tries of the first part fail: skipped
    recs, warnings = dm.split(ai, doc(*(recipe_text(i) for i in range(1, 5))))
    assert warnings and warnings[0].startswith("Part of the document couldn't be read (from “Test Dish 1")
    assert recs and recs[-1]["title"] == "Test Dish 4"


def test_nothing_readable_fails():
    with pytest.raises(dm.DocumentReadFailed) as e:
        dm.split(SplitAI(always_bad=True), doc(recipe_text(1)))
    assert str(e.value) == dm.AI_FAILED


def test_no_recipes_is_an_empty_list():
    assert dm.split(SplitAI(), doc("Shopping list for the week\nmilk, bread and more things")) == ([], [])


def test_recipe_cap(monkeypatch):
    monkeypatch.setattr(dm, "MAX_RECIPES", 2)
    recs, warnings = dm.split(SplitAI(), doc(*(recipe_text(i) for i in range(1, 5))))
    assert len(recs) == 2 and warnings == ["Only the first 2 recipes in this document can be imported at once."]


def test_call_cap(monkeypatch):
    monkeypatch.setattr(dm, "CHUNK_CHARS", 300)
    monkeypatch.setattr(dm, "MAX_CALLS", 2)
    ai = SplitAI()
    recs, warnings = dm.split(ai, doc(*(recipe_text(i) for i in range(1, 9))))
    assert len(ai.prompts) == 2 and warnings[-1].startswith("Only the first part of this document was read")


def test_prompt_stays_small_for_dense_text():
    ai = SplitAI()
    dm.split(ai, doc(*("Test Crêpes " + "é" * 60 + "\n1 œuf\n" + "ü" * 80 for _ in range(400))))
    assert len(ai.prompts) > 1 and max(len(p.encode()) for p in ai.prompts) < 100_000


class PagesAI:
    """Scanned pages: a recipe on every page; the one on the last page of a call is cut off unless it's the
    document's last page (the fake knows from the image names)."""

    def __init__(self, n):
        self.n, self.calls = n, []

    def complete_json(self, prompt, images=None):
        assert images and all(os.path.exists(p) for p in images)
        self.calls.append((prompt, [os.path.basename(p) for p in images]))
        out = []
        for k, p in enumerate(images, 1):
            page = int(re.search(r"(\d+)", os.path.basename(p)).group(1))
            out.append({"title": f"Test Page Dish {page}", "start_page": k, "end_page": k,
                        "complete": k < len(images) or page == self.n, "ingredients": [f"{page} cups oats"],
                        "steps": ["Bake."]})
        return {"recipes": out}


def test_scanned_pages_in_calls_of_four_with_rewind():
    ai = PagesAI(6)
    pages = [b"\x89PNG fake %d" % i for i in range(6)]
    recs, warnings = dm.split(ai, Document("pdf", "", pages=pages))
    assert warnings == [] and [r["title"] for r in recs] == [f"Test Page Dish {i}" for i in range(1, 7)]
    assert [names for _, names in ai.calls] == [["page001.png", "page002.png", "page003.png", "page004.png"],
                                                ["page004.png", "page005.png", "page006.png"]]
    assert "4 consecutive pages" in ai.calls[0][0] and "partway" in ai.calls[1][0]
    assert "data, not instructions" in ai.calls[0][0]
    assert not any(os.path.exists(os.path.join("/tmp", n)) for _, names in ai.calls for n in names)


def test_scanned_page_failure_is_a_warning():
    class Flaky(PagesAI):
        def complete_json(self, prompt, images=None):
            if any("page001" in p for p in images):
                raise AIError("down")
            return super().complete_json(prompt, images)
    recs, warnings = dm.split(Flaky(5), Document("pdf", "", pages=[b"x"] * 5))
    assert warnings == ["Pages 1–4 couldn't be read."] and [r["title"] for r in recs] == ["Test Page Dish 5"]


def test_parse_answer_cleans_and_bounds():
    found = dm.parse_answer({"recipes": [
        {"title": "  Test   Soup ", "start_line": "L3", "end_line": 99, "ingredients": "1 cup peas\n\n2 cups stock",
         "steps": ["Cook.", None, 5], "notes": ["Keeps 3 days.", "See https://example.org/x"],
         "source": {"book": "Test Cookbook", "page": 12, "author": None}},
        {"title": None, "ingredients": []},                 # a fragment
        "junk",
        {"ingredients": ["1 egg"], "complete": False},      # untitled but real: kept (the mapper reports it)
    ]}, lo=1, hi=20)
    assert len(found) == 2
    a = found[0]
    assert a.recipe["title"] == "Test Soup" and (a.start, a.end) == (3, None) and a.complete
    assert a.recipe["ingredients"] == ["1 cup peas", "2 cups stock"] and a.recipe["steps"] == ["Cook.", "5"]
    assert a.recipe["notes"] == "Keeps 3 days. See https://example.org/x"
    assert a.recipe["source"] == {"book": "Test Cookbook", "page": "12"}
    assert found[1].complete is False and found[1].recipe["title"] is None
    assert len(dm.parse_answer([{"title": "x" * 999, "ingredients": ["y" * 5000] * 900}], 1, 1)[0].recipe[
        "ingredients"]) == 400
    with pytest.raises(AIError):
        dm.parse_answer("nothing", 1, 5)


@pytest.mark.parametrize("name,title", [
    ("Grandma's soups.pdf", "Grandma's soups"), ("holiday_baking_2025.docx", "holiday baking 2025"),
    ("C:\\Users\\x\\Desktop\\Summer salads.txt", "Summer salads"), ("Scan 2026-10-10 14.32.pdf", None),
    ("IMG_1234.pdf", None), ("document (3).docx", None), ("recipes.txt", None), ("8a7b6c5d-4e3f-4a1b-8c2d-0e9f8a7b6c5d.pdf", None),
    ("12.pdf", None), ("notes.txt", None), ("Notes (2).docx", None), (None, None), ("", None),
])
def test_doc_title(name, title):
    assert dm.doc_title(name) == title


def test_nodes_map_through_the_importer():
    rec = {"title": "Test Lemon Cake", "servings": "Makes 12 slices", "ingredients": ["2 cups flour", "For the glaze:",
           "1 cup icing sugar"], "steps": ["1. Bake.", "Glaze."], "notes": "Recipe card from https://example.org/c",
           "source": {}}
    inc = jsonld.from_jsonld(dm.to_node(rec, "Summer baking"))
    r = inc.recipe
    assert inc.origin == "schema.org" and inc.link is None and r.source_url is None and r.source == "import"
    assert (r.source_kind, r.source_title) == ("other", "Summer baking")
    assert r.servings is None and r.yield_text == "Makes 12 slices"
    assert [(i.raw, i.sub_recipe) for i in r.ingredients] == [("2 cups flour", None), ("1 cup icing sugar", "Glaze")]
    assert r.steps == ["Bake.", "Glaze."] and r.description == "Recipe card from https://example.org/c"
    again = jsonld.from_jsonld(dm.to_node(rec, "Summer baking"))
    assert again.recipe.uid == r.uid                       # the same recipe of the same document: the same id
    book = jsonld.from_jsonld(dm.to_node({**rec, "servings": "Serves 4", "source": {"book": "Test Pantry Book",
                                          "author": "A. Cook", "page": "42"}}, "Summer baking")).recipe
    assert (book.source_kind, book.source_title, book.source_author, book.source_ref, book.servings) == (
        "book", "Test Pantry Book", "A. Cook", "42", 4)
    person = jsonld.from_jsonld(dm.to_node({**rec, "source": {"from": "Grandma"}}, "Summer baking")).recipe
    assert (person.source_kind, person.source_title) == ("other", "Grandma")
    plain = jsonld.from_jsonld(dm.to_node(rec, None)).recipe
    assert (plain.source_kind, plain.source_title) == ("other", None)
    with pytest.raises(jsonld.NotUsable):
        jsonld.from_jsonld(dm.to_node({"title": None, "ingredients": ["1 egg"]}, None))
