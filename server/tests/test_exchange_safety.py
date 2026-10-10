"""Hostile import files (Review Focus 5): caps, nesting, junk bytes → our exceptions only, bounded time."""
import io
import json
import time

import pytest
from hypothesis import given, settings, strategies as st

from mealprep.exchange import extract, importer, jsonld, safe


class Endless(io.RawIOBase):
    """A stream that never ends; counts what was read."""
    def __init__(self):
        self.n = 0

    def read(self, size=-1):
        size = 65536 if size is None or size < 0 else size
        self.n += size
        return b"x" * size


def test_read_capped_stops_at_the_cap():
    f = Endless()
    with pytest.raises(safe.ImportTooBig):
        safe.read_capped(f, 1024 * 1024)
    assert f.n <= 1024 * 1024 + safe.CHUNK
    assert safe.read_capped(io.BytesIO(b"abc"), 3) == b"abc"


def test_deep_json_is_unreadable_quickly():
    t = time.monotonic()
    for text in ("[" * 10000 + "]" * 10000, '{"a":' * 10000 + "1" + "}" * 10000):
        with pytest.raises(safe.Unreadable):
            safe.loads_limited(text)
    assert time.monotonic() - t < 2
    assert safe.loads_limited(json.dumps(["[" * 100 + '\\"' + "{" * 100])) == ["[" * 100 + '\\"' + "{" * 100]


def test_decode():
    assert safe.decode(b"\xef\xbb\xbf{}") == "{}"
    assert safe.decode("café".encode("cp1252")) == "café"
    assert safe.decode(b"\x81\x8d") is not None


@pytest.mark.parametrize("data, msg", [
    (b"", importer.NOT_A_RECIPE_FILE), (b"   \n", importer.NOT_A_RECIPE_FILE),
    (b"PK\x03\x04rest-of-a-zip", importer.NOT_A_RECIPE_FILE), (b"\x1f\x8b\x08gz", importer.NOT_A_RECIPE_FILE),
    (b"[]", importer.NO_RECIPES), (b"{}", importer.NO_RECIPES), (b'{"@type": "Thing"}', importer.NO_RECIPES),
    (b"<html><script>var x</script></html>", importer.NO_RECIPES), (b"{broken", "This file isn't valid JSON."),
    (b'[{"name": "Test", ', "This file isn't valid JSON."), (b"<html><body>no recipe</body></html>", importer.NO_RECIPES),
    (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1rest", "This is an old Word file"),
    (b"[" * 5000, "This file is nested too deeply to read.")])
def test_read_file_errors(data, msg):
    with pytest.raises(safe.Unreadable, match=msg.replace(".", r"\.").replace("(", r"\(").replace(")", r"\)")):
        importer.read_file(data)


@pytest.mark.parametrize("data, kind", [
    (b"hello", "text"), (b"[From the recipe box]\nTest Pea Soup\n1 cup peas", "text"), (b"%PDF-1.4\n", "pdf")])
def test_documents_need_reading(data, kind):
    """Text and documents aren't errors any more: without a cached read they need the AI (a read job)."""
    with pytest.raises(importer.NeedsReading) as e:
        importer.read_file(data)
    assert e.value.kind == kind


def test_too_many_recipes_capped_with_warning():
    nodes = [{"@type": "Recipe", "name": f"R{i}", "recipeIngredient": ["1 egg"]} for i in range(2100)]
    fmt, got, warnings = importer.read_file(json.dumps({"@graph": nodes}).encode())
    assert fmt == "jsonld" and len(got) == 2000 and "2100" in warnings[0]


def test_huge_string_field_is_capped_fast():
    big = {"@type": "Recipe", "name": "N" * (15 * 1024 * 1024), "description": "d" * 1_000_000,
           "recipeIngredient": ["1 egg " + "e" * 3_000_000], "recipeInstructions": "s" * 3_000_000}
    t = time.monotonic()
    fmt, [node], _ = importer.read_file(json.dumps(big).encode())
    inc = jsonld.from_jsonld(node)
    assert time.monotonic() - t < 5
    assert len(inc.recipe.title) == 200 and len(inc.recipe.description) == 2000
    assert len(inc.recipe.ingredients[0].raw) == 500 and len(inc.recipe.steps[0]) == 5000


def test_javascript_url_never_kept():
    inc = jsonld.from_jsonld({"@type": "Recipe", "name": "X", "url": "javascript:alert(1)", "recipeIngredient": ["1 egg"],
                              "isBasedOn": "javascript:alert(2)", "image": {"url": "javascript:alert(3)"}})
    assert inc.recipe.source_url is None and inc.recipe.image is None and jsonld.DROPPED_LINK in inc.warnings


@settings(max_examples=300, deadline=None, derandomize=True)
@given(st.binary(max_size=400))
def test_random_bytes_only_raise_our_errors(data):
    try:
        fmt, nodes, _ = importer.read_file(data)
    except (safe.Unreadable, importer.NeedsReading):
        return
    for n in nodes:
        try:
            jsonld.from_jsonld(n)
        except jsonld.NotUsable:
            pass


json_values = st.recursive(st.none() | st.booleans() | st.integers() | st.floats(allow_nan=False) | st.text(max_size=20),
                           lambda kids: st.lists(kids, max_size=4) | st.dictionaries(
                               st.sampled_from(["@type", "name", "recipeIngredient", "recipeInstructions", "recipeYield",
                                                "url", "image", "isBasedOn", "identifier", "@graph", "itemListElement",
                                                "mealprep:recipe", "mealprep:ratings", "text", "author", "format"]),
                               kids, max_size=6), max_leaves=30)


@settings(max_examples=300, deadline=None, derandomize=True)
@given(json_values, st.sampled_from(["Recipe", ["Recipe"], "schema:Recipe"]))
def test_random_recipe_shapes_never_crash(v, t):
    node = {**v, "@type": t} if isinstance(v, dict) else {"@type": t, "recipeInstructions": v, "recipeIngredient": v,
                                                          "name": "X"}
    for n in extract.find_recipes([node]):
        try:
            jsonld.from_jsonld(n)
        except jsonld.NotUsable:
            pass
