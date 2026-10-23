import html as htmllib, json, logging, re
import httpx

log = logging.getLogger(__name__)

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
BASE = "https://cooking.nytimes.com"
_URL = re.compile(r"https?://(?:www\.)?cooking\.nytimes\.com/recipes/[\w-]+")
_ID_SLUG = re.compile(r"/recipes/(\d+)(?:-([\w-]*))?$")
# Filler words a share slug may carry that the canonical one drops ("…-pasta-with-fish-ragu" vs "…-pasta-fish-ragu").
_FILLER = {"with", "and", "the", "a", "an", "of", "in", "on", "for", "or", "to"}


class NotARecipe(Exception):
    pass


class NytFetchError(Exception):
    """The recipe page couldn't be fetched (and, after a 404, no canonical link with the same id was found)."""


def extract_nyt_url(text: str) -> str | None:
    m = _URL.search(text or "")
    return m.group(0).replace("://www.", "://").replace("http://", "https://") if m else None


def recipe_id(url: str) -> str | None:
    """The numeric NYT Cooking recipe id in a recipe link, or None."""
    m = _ID_SLUG.search(url or "")
    return m.group(1) if m else None


def _get(url: str, params: dict | None = None) -> httpx.Response:
    return httpx.get(url, params=params, headers={"User-Agent": UA, "Accept-Language": "en-CA"},
                     follow_redirects=True, timeout=30)


def resolve_canonical(url: str) -> str | None:
    """NYT Cooking 404s unless the slug is exactly the canonical one (the 404 page names no canonical link, and an
    id-only link 404s too). Search for the slug's words and take the result link with the same numeric id — never
    another recipe. None when the link has no slug words or no result has that id (searching by id finds nothing)."""
    m = _ID_SLUG.search(url or "")
    if not m:
        return None
    rid, words = m.group(1), [w for w in (m.group(2) or "").split("-") if w]
    if not words:
        log.warning("nyt resolve %s: id-only link, nothing to search for", url)
        return None
    queries = [" ".join(words)]
    short = [w for w in words if w.lower() not in _FILLER]
    if short and short != words:
        queries.append(" ".join(short))
    link = re.compile(r"/recipes/" + rid + r"-[\w-]+")
    for q in queries:
        try:
            r = _get(f"{BASE}/search", {"q": q})
        except httpx.HTTPError as e:
            log.warning("nyt resolve %s: search %r failed: %s: %s", url, q, type(e).__name__, e)
            return None
        if r.status_code != 200:
            log.warning("nyt resolve %s: search %r → HTTP %s", url, q, r.status_code)
            continue
        found = link.search(r.text)
        if found:
            return BASE + found.group(0)
        log.warning("nyt resolve %s: search %r has no result with id %s", url, q, rid)
    return None


def fetch_nyt_recipe(url: str) -> tuple[str, str]:
    """(page html, the link it came from). On a 404 the canonical link is looked up (`resolve_canonical`) and
    fetched instead. Every failure is logged with the status and the link tried; raises NytFetchError."""
    try:
        r = _get(url)
    except httpx.HTTPError as e:
        log.warning("nyt fetch %s failed: %s: %s", url, type(e).__name__, e)
        raise NytFetchError(f"couldn't reach NYT Cooking ({type(e).__name__})") from e
    if r.status_code == 200:
        return r.text, url
    log.warning("nyt fetch %s → HTTP %s", url, r.status_code)
    if r.status_code != 404:
        raise NytFetchError(f"NYT Cooking answered HTTP {r.status_code} for {url}")
    canonical = resolve_canonical(url)
    if not canonical or canonical == url:
        log.warning("nyt fetch %s: 404 and no canonical link found", url)
        if not (_ID_SLUG.search(url) or [None, None, None])[2]:
            raise NytFetchError(f"NYT Cooking has no page at {url}: the link has only the recipe number and NYT needs "
                                "the full link with the recipe's name; share it from the recipe page again")
        raise NytFetchError(f"NYT Cooking has no page at {url} and no recipe with id {recipe_id(url)} was found "
                            "by searching its name; share the link from the recipe page again")
    try:
        r = _get(canonical)
    except httpx.HTTPError as e:
        log.warning("nyt fetch %s (canonical for %s) failed: %s: %s", canonical, url, type(e).__name__, e)
        raise NytFetchError(f"couldn't reach NYT Cooking ({type(e).__name__})") from e
    if r.status_code != 200:
        log.warning("nyt fetch %s (canonical for %s) → HTTP %s", canonical, url, r.status_code)
        raise NytFetchError(f"NYT Cooking answered HTTP {r.status_code} for {canonical}")
    # warning, not info: the unit has no logging config, so only warnings reach the journal
    log.warning("nyt fetch %s → 404; fetched the canonical %s instead", url, canonical)
    return r.text, canonical


def _steps(instr) -> list[str]:
    out = []
    for s in instr or []:
        if isinstance(s, str):
            out.append(s)
        elif not isinstance(s, dict):
            continue
        elif s.get("@type") == "HowToSection":
            out.extend(_steps(s.get("itemListElement")))
        elif s.get("text"):
            out.append(s["text"])
    return [htmllib.unescape(x).strip() for x in out if x.strip()]


def _servings(y) -> int | None:
    """People served, or None when the yield is a count of things ("18 cookies", "Makes 48 pieces") —
    scaling those to the household would be wrong, so they're made as written."""
    for v in (y if isinstance(y, list) else [y]):
        t = str(v or "").strip().lower()
        if re.fullmatch(r"\d+", t):
            return int(t)
        m = re.search(r"serves\s+(\d+)|(\d+)(?:\s*(?:to|-|–)\s*\d+)?\s*(?:servings?|people|portions?)\b", t)
        if m:
            return int(m.group(1) or m.group(2))
    return None


def _is_recipe(n) -> bool:
    t = n.get("@type") if isinstance(n, dict) else None
    return t == "Recipe" or (isinstance(t, list) and "Recipe" in t)


def parse_nyt_html(html: str, url: str):
    for block in re.findall(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>', html, re.S):
        try:
            data = json.loads(block)
        except json.JSONDecodeError:
            continue
        nodes = data if isinstance(data, list) else data.get("@graph", [data]) if isinstance(data, dict) else []
        for n in nodes:
            if _is_recipe(n):
                ings = [htmllib.unescape(i).strip() for i in n.get("recipeIngredient", [])]
                return htmllib.unescape(n.get("name", "")), _servings(n.get("recipeYield")), ings, _steps(n.get("recipeInstructions"))
    raise NotARecipe(url)
