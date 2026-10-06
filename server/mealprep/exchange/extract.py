"""Find schema.org Recipe nodes in parsed JSON or in a saved web page's <script type="application/ld+json"> blocks.
Nothing in the page is fetched (images, links, scripts): only the embedded JSON-LD is read."""
from html.parser import HTMLParser
from typing import Any

from .safe import Unreadable, loads_limited

MAX_DEPTH = 64
MAX_NODES = 5000
_PREFIXES = ("schema:", "http://schema.org/", "https://schema.org/")


def _types(node: dict) -> set[str]:
    t = node.get("@type")
    ts = t if isinstance(t, list) else [t]
    out = set()
    for x in ts:
        if isinstance(x, str):
            for p in _PREFIXES:
                if x.startswith(p):
                    x = x[len(p):]
            out.add(x)
    return out


def is_recipe(node) -> bool:
    return isinstance(node, dict) and "Recipe" in _types(node)


def find_recipes(data: Any) -> list[dict]:
    """Recipe nodes in a parsed JSON value: a single node, a top-level array, @graph (any level), mainEntity /
    mainEntityOfPage wrappers, ItemList.itemListElement (ListItem.item). Too deep or too many nodes → Unreadable."""
    found: list[dict] = []
    seen = 0

    def walk(v, depth):
        nonlocal seen
        seen += 1
        if seen > MAX_NODES:
            raise Unreadable("This file has too many parts to read.")
        if depth > MAX_DEPTH:
            raise Unreadable("This file is nested too deeply to read.")
        if isinstance(v, list):
            for x in v:
                walk(x, depth + 1)
        elif isinstance(v, dict):
            if is_recipe(v):
                found.append(v)
                return
            for k in ("@graph", "mainEntity", "mainEntityOfPage", "itemListElement", "item"):
                if isinstance(v.get(k), (list, dict)):
                    walk(v[k], depth + 1)

    walk(data, 0)
    return found


class _Scripts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.blocks: list[str] = []
        self._buf: list[str] | None = None

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            t = (dict(attrs).get("type") or "").split(";")[0].strip().lower()
            self._buf = [] if t == "application/ld+json" else None

    def handle_endtag(self, tag):
        if tag == "script" and self._buf is not None:
            self.blocks.append("".join(self._buf))
            self._buf = None

    def handle_data(self, data):
        if self._buf is not None:
            self._buf.append(data)


def from_html(text: str) -> list[dict]:
    """Recipe nodes from a saved page's JSON-LD script blocks (bad blocks skipped)."""
    p = _Scripts()
    try:
        p.feed(text)
        p.close()
    except Exception:   # malformed markup: use what was read so far
        pass
    out: list[dict] = []
    for block in p.blocks:
        try:
            out.extend(find_recipes(loads_limited(block)))
        except Unreadable:
            continue
    return out
