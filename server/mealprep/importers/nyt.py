import html as htmllib, json, re
import httpx

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
_URL = re.compile(r"https?://(?:www\.)?cooking\.nytimes\.com/recipes/[\w-]+")


class NotARecipe(Exception):
    pass


def extract_nyt_url(text: str) -> str | None:
    m = _URL.search(text or "")
    return m.group(0).replace("://www.", "://").replace("http://", "https://") if m else None


def fetch_nyt(url: str) -> str:
    r = httpx.get(url, headers={"User-Agent": UA, "Accept-Language": "en-CA"}, follow_redirects=True, timeout=30)
    r.raise_for_status()
    return r.text


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
