"""What one pack holds when its size text doesn't say (table `pack_contents`).

Loblaws lists Green Onion and Leeks as "1 ea", and each "ea" is a bunch of several. The cart's quantity check
(`qtycheck`) learns how many pieces a pack holds when it corrects a line, and the household can set it on a cart
line; either way it's saved per product code and every later cart plans with it (`planner` reads `Product.pieces`).
A household's number ('user') is never overwritten by a check. Without a record the planner falls back to its own
guesses (`planner.BUNCH`)."""
import re

from .models import Product

MAX_PIECES = 100
DEFAULT_WORD = "pack"


def clean_word(word) -> str:
    """"Bunch", "bag " → "bunch", "bag"; anything odd becomes "pack"."""
    w = re.sub(r"\s+", " ", str(word or "")).strip().lower()
    return w if re.fullmatch(r"[a-z][a-z ]{0,19}", w) else DEFAULT_WORD


def known(conn) -> dict[str, tuple[int, str, str]]:
    """{code: (pieces, word, source)} for every learned pack (a small table: read once per cart build)."""
    return {r[0]: (r[1], r[2], r[3]) for r in conn.execute("SELECT code, pieces, word, source FROM pack_contents")}


def lookup(conn, code: str | None) -> tuple[int, str, str] | None:
    if not code:
        return None
    r = conn.execute("SELECT pieces, word, source FROM pack_contents WHERE code=%s", (code,)).fetchone()
    return (r[0], r[1], r[2]) if r else None


def attach(product, rec):
    """The product (a Product or a stored line's dict) with what a pack holds set from `rec` (or cleared)."""
    if product is None:
        return None
    vals = {"pieces": rec[0], "pieces_word": rec[1], "pieces_source": rec[2]} if rec else \
        {"pieces": None, "pieces_word": None, "pieces_source": None}
    return product.model_copy(update=vals) if isinstance(product, Product) else {**product, **vals}


def attach_all(products, table: dict) -> list:
    return [attach(p, table.get(p.code)) for p in products]


def save(conn, code: str, pieces: int, word, source: str, reason: str | None = None) -> bool:
    """Record what one pack of `code` holds. A check never replaces the household's own number. True if saved."""
    pieces = int(pieces)
    if not code or not 1 <= pieces <= MAX_PIECES or source not in {"check", "user"}:
        raise ValueError("pieces must be 1–100")
    cur = conn.execute(
        "INSERT INTO pack_contents(code, pieces, word, source, reason) VALUES(%s,%s,%s,%s,%s) "
        "ON CONFLICT (code) DO UPDATE SET pieces=EXCLUDED.pieces, word=EXCLUDED.word, source=EXCLUDED.source, "
        "reason=EXCLUDED.reason, updated_at=now() WHERE pack_contents.source <> 'user' OR EXCLUDED.source = 'user'",
        (code, pieces, clean_word(word), source, (reason or None) and reason[:200]))
    return cur.rowcount > 0


def forget(conn, code: str) -> None:
    conn.execute("DELETE FROM pack_contents WHERE code=%s", (code,))


class Attaching:
    """A PC Express client whose products come with what a pack holds (`attach`), for cart builds and the chooser."""
    def __init__(self, pcx, table: dict):
        self._pcx, self._table = pcx, table

    def search(self, *a, **kw):
        return attach_all(self._pcx.search(*a, **kw), self._table)

    def search_page(self, *a, **kw):
        found, total = self._pcx.search_page(*a, **kw)
        return attach_all(found, self._table), total

    def product(self, code):
        p = self._pcx.product(code)
        return attach(p, self._table.get(p.code)) if p is not None else None

    def __getattr__(self, name):
        return getattr(self._pcx, name)
