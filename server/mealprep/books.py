"""Book metadata search for the app's "Which book?" field (0.4.3): Open Library first, Google Books when Open
Library has fewer than MIN_GOOD good results, fails or times out. Server side only (the phone talks to nobody
else). Upstream failures never reach the app: whatever was found is returned, [] when both fail."""
import logging, re, threading, time, unicodedata
from collections import OrderedDict, deque

import httpx

log = logging.getLogger(__name__)

OPEN_LIBRARY = "https://openlibrary.org/search.json"
GOOGLE_BOOKS = "https://www.googleapis.com/books/v1/volumes"
USER_AGENT = "MealPrep/0.4 (+https://github.com/titulartarantula/meal-prep)"
OL_FIELDS = "title,subtitle,author_name,first_publish_year,isbn,publisher"
MIN_QUERY, MAX_QUERY = 2, 100   # shorter → [] without asking anyone; longer is cut (the API itself refuses > 200)
MIN_GOOD = 3                    # fewer good Open Library results than this → ask Google Books too
TIMEOUT = 3.0                   # seconds, each upstream


def _norm(s: str | None) -> str:
    """Lowercase, accents and punctuation dropped, single spaces: "Crème  Brûlée!" → "creme brulee"."""
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower().replace("&", " and ")
    return " ".join(re.sub(r"[^\w\s]", " ", s).split())


def _title_key(title: str) -> str:
    t = _norm(title)
    return re.sub(r"^(the|a|an) ", "", t)


def _surname(author: str | None) -> str:
    words = _norm(author).split()
    return words[-1] if words else ""


def same_book(a: dict, b: dict) -> bool:
    """Same title (ignoring case, accents, punctuation, a leading article) and an author's surname in common (or no
    authors on one side): Open Library lists co-authored editions under either author first."""
    if _title_key(a["title"]) != _title_key(b["title"]):
        return False
    sa, sb = {_surname(x) for x in a["authors"]} - {""}, {_surname(x) for x in b["authors"]} - {""}
    return not sa or not sb or bool(sa & sb)


def tier(b: dict, q: str) -> int:
    """0 exact title, 1 title starts with the query, 2 every query word in the title, 3 every word in title +
    authors, 4 anything else (upstream relevance only). Title = title or "title subtitle"."""
    qn = _title_key(q)
    titles = [_title_key(b["title"])]
    if b.get("subtitle"):
        titles.append(_title_key(f"{b['title']} {b['subtitle']}"))
    if qn in titles:
        return 0
    if any(t.startswith(qn) for t in titles):
        return 1
    words = set(qn.split())
    if words <= set(titles[-1].split()):
        return 2
    if words <= set(titles[-1].split()) | set(_norm(" ".join(b["authors"])).split()):
        return 3
    return 4


def _isbn(values) -> str | None:
    """Prefer a 13-digit ISBN from the English-language groups (978-0, 978-1: an Open Library work lists every
    edition's), then any 13-digit one, then an ISBN-10; None when there is no well-formed one."""
    clean = [re.sub(r"[^0-9Xx]", "", v or "").upper() for v in values or []]
    isbn13 = [v for v in clean if len(v) == 13 and v.isdigit()]
    return next((v for v in isbn13 if v[:4] in ("9780", "9781")), None) or next(iter(isbn13), None) or \
        next((v for v in clean if len(v) == 10 and v[:9].isdigit()), None)


def _text(v, limit=200) -> str | None:
    v = " ".join(str(v).split()) if v is not None else ""
    return v[:limit] or None


def _book(title, subtitle, authors, year, isbn, publisher, source) -> dict | None:
    title = _text(title)
    if not title:
        return None
    return {"title": title, "subtitle": _text(subtitle), "authors": [a for a in (_text(x, 100) for x in authors or []) if a][:5],
            "year": year, "isbn": isbn, "publisher": _text(publisher, 100), "source": source}


def parse_open_library(data: dict) -> list[dict]:
    out = []
    for d in (data or {}).get("docs") or []:
        year = d.get("first_publish_year")
        b = _book(d.get("title"), d.get("subtitle"), d.get("author_name"), year if isinstance(year, int) else None,
                  _isbn(d.get("isbn")), (d.get("publisher") or [None])[0], "openlibrary")
        if b:
            out.append(b)
    return out


def parse_google(data: dict) -> list[dict]:
    out = []
    for item in (data or {}).get("items") or []:
        v = item.get("volumeInfo") or {}
        m = re.match(r"(\d{4})", v.get("publishedDate") or "")
        ids = sorted(v.get("industryIdentifiers") or [], key=lambda i: i.get("type") != "ISBN_13")
        b = _book(v.get("title"), v.get("subtitle"), v.get("authors"), int(m.group(1)) if m else None,
                  _isbn([i.get("identifier") for i in ids if (i.get("type") or "").startswith("ISBN")]),
                  v.get("publisher"), "google")
        if b:
            out.append(b)
    return out


def merge(q: str, *lists: list[dict], limit: int) -> list[dict]:
    """Dedupe (the first one seen wins and borrows a missing year/ISBN/publisher/subtitle from the later ones), then
    rank: tier first, then upstream order (Open Library before Google)."""
    order: list[dict] = []
    for books in lists:
        for b in books:
            first = next((o for o in order if same_book(o, b)), None)
            if first is not None:
                for f in ("year", "isbn", "publisher", "subtitle"):
                    if first.get(f) is None and b.get(f) is not None:
                        first[f] = b[f]
                if b.get("year") and first.get("year") and b["year"] < first["year"]:
                    first["year"] = b["year"]   # the earliest edition's year
                continue
            order.append(dict(b))
    ranked = sorted(enumerate(order), key=lambda ib: (tier(ib[1], q), ib[0]))
    return [b for _, b in ranked][:limit]


class RateGuard:
    """At most `n` calls in any `per` seconds (sliding window); over that, upstream is skipped, not waited for."""

    def __init__(self, n: int, per: float, clock=time.monotonic):
        self.n, self.per, self.clock, self.calls, self.lock = n, per, clock, deque(), threading.Lock()

    def allow(self) -> bool:
        with self.lock:
            now = self.clock()
            while self.calls and now - self.calls[0] >= self.per:
                self.calls.popleft()
            if len(self.calls) >= self.n:
                return False
            self.calls.append(now)
            return True


class BookSearch:
    def __init__(self, google_key: str = "", client: httpx.Client | None = None, clock=time.monotonic,
                 cache_size: int = 500, ttl: float = 86400, error_ttl: float = 300):
        self.google_key = google_key or ""
        self.http = client or httpx.Client(timeout=TIMEOUT, headers={"User-Agent": USER_AGENT})
        self.clock, self.cache_size, self.ttl, self.error_ttl = clock, cache_size, ttl, error_ttl
        self.cache: OrderedDict[tuple, tuple[float, list[dict]]] = OrderedDict()
        self.lock = threading.Lock()
        # Open Library asks for about one request a second; the Google key allows ~1000 a day.
        self.ol_guard = RateGuard(60, 60, clock)
        self.google_guard = RateGuard(30, 60, clock)
        self.google_daily = RateGuard(900, 86400, clock)

    def _get(self, url: str, params: dict, headers: dict | None = None) -> dict:
        r = self.http.get(url, params=params, headers=headers, timeout=TIMEOUT)
        r.raise_for_status()
        return r.json()

    def _open_library(self, q: str, n: int) -> list[dict]:
        return parse_open_library(self._get(OPEN_LIBRARY, {"q": q, "limit": n, "fields": OL_FIELDS}))

    def _google(self, q: str, n: int) -> list[dict]:
        # the key goes in a header, not the URL, so it never shows up in a logged URL or an httpx error message
        return parse_google(self._get(GOOGLE_BOOKS, {"q": q, "maxResults": n, "printType": "books"},
                                      {"X-Goog-Api-Key": self.google_key}))

    def search(self, q: str, limit: int = 8) -> list[dict]:
        q = " ".join((q or "").split())[:MAX_QUERY]
        if len(q) < MIN_QUERY or limit < 1:
            return []
        key = (_norm(q), limit)
        with self.lock:
            hit = self.cache.get(key)
            if hit and hit[0] > self.clock():
                self.cache.move_to_end(key)
                return [dict(b) for b in hit[1]]
        n = min(max(limit * 2, 10), 20)   # room for duplicates
        failed = False
        ol: list[dict] = []
        if self.ol_guard.allow():
            try:
                ol = self._open_library(q, n)
            except Exception as e:   # timeout, HTTP error, bad JSON: try Google
                failed = True
                log.warning("book search: Open Library failed (%s)", type(e).__name__)
        else:
            failed = True
        google: list[dict] = []
        if sum(tier(b, q) < 4 for b in merge(q, ol, limit=n)) < MIN_GOOD and self.google_key:
            if self.google_guard.allow() and self.google_daily.allow():
                try:
                    google = self._google(q, min(n, 20))
                except Exception as e:
                    failed = True
                    log.warning("book search: Google Books failed (%s)", type(e).__name__)
            else:
                failed = True
        out = merge(q, ol, google, limit=limit)
        with self.lock:   # a failure is remembered briefly only, so a later keystroke tries again
            self.cache[key] = (self.clock() + (self.error_ttl if failed else self.ttl), out)
            self.cache.move_to_end(key)
            while len(self.cache) > self.cache_size:
                self.cache.popitem(last=False)
        return [dict(b) for b in out]
