"""PC Express anonymous API client. No login, no checkout — search, product detail, create cart, add entries, read
cart only."""
from html.parser import HTMLParser
import html
import os
import re
from datetime import date
import httpx
from .models import Product

BASE = "https://api.pcexpress.ca/pcx-bff/api/v1"
HEADERS = {
    # plus "x-apikey": the public web key shipped in loblaws.ca's JS bundle, read from MEALPREP_PCX_APIKEY
    "x-application-type": "Web",
    "x-loblaw-tenant-id": "ONLINE_GROCERIES", "Site-Banner": "loblaw", "Accept-Language": "en",
    "Content-Type": "application/json", "Origin": "https://www.loblaws.ca", "Referer": "https://www.loblaws.ca/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36",
}


class Pcx:
    def __init__(self, store_id: str = "1092", client: httpx.Client | None = None, api_key: str | None = None):
        self.store = store_id
        key = os.environ.get("MEALPREP_PCX_APIKEY", "") if api_key is None else api_key
        self.http = client or httpx.Client(headers={**HEADERS, "x-apikey": key}, timeout=30)

    def _body(self, term: str, page: int, size: int) -> dict:
        return {"pagination": {"from": page, "size": size}, "banner": "loblaw", "cartId": "", "lang": "en",
                "date": date.today().strftime("%d%m%Y"), "storeId": self.store, "pcId": False,
                "pickupType": "STORE", "offerType": "ALL", "term": term,
                "userData": {"domainUserId": "", "sessionId": ""}}

    def search_page(self, term: str, page: int = 0, size: int = 20) -> tuple[list[Product], int]:
        """One page of results in Loblaws' order, and the total. `page` is a page number (the API's
        `pagination.from` counts pages, not results); page 0 also carries the sponsored results."""
        r = self.http.post(f"{BASE}/products/search", json=self._body(term, page, size))
        r.raise_for_status()
        d = r.json()
        total = (d.get("pagination") or {}).get("totalResults") or 0
        return [to_product(x) for x in d.get("results") or [] if x.get("code")], int(total)

    def search(self, term: str, size: int = 8) -> list[Product]:
        return self.search_page(term, 0, size)[0]

    def product(self, code: str) -> Product | None:
        """The product by its code (price, stock and offers for this store), or None when Loblaws doesn't know it."""
        r = self.http.get(f"{BASE}/products/{code}", params={
            "lang": "en", "date": date.today().strftime("%d%m%Y"), "pickupType": "STORE", "storeId": self.store,
            "banner": "loblaw"})
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return to_product(r.json())

    def create_cart(self) -> str:
        r = self.http.post(f"{BASE}/carts", json={"bannerId": "loblaw", "language": "en", "storeId": self.store})
        r.raise_for_status()
        return r.json()["id"]

    def add(self, cart_id: str, entries: dict[str, int]) -> None:
        body = {"entries": {code: {"quantity": q, "fulfillmentMethod": "pickup", "sellerId": self.store}
                            for code, q in entries.items()}}
        r = self.http.post(f"{BASE}/carts/{cart_id}", json=body)
        r.raise_for_status()

    def get_cart(self, cart_id: str) -> dict:
        r = self.http.get(f"{BASE}/carts/{cart_id}")
        r.raise_for_status()
        d = r.json()
        return d.get("cart", d)


# --- mapping ---------------------------------------------------------------------------------------------------

class _Text(HTMLParser):
    """Product description HTML → plain text: paragraphs and line breaks as new lines, list items as "• " lines."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag in ("p", "div", "ul", "ol", "br", "tr", "h1", "h2", "h3", "h4"):
            self.parts.append("\n")
        elif tag == "li":
            self.parts.append("\n• ")

    def handle_endtag(self, tag):
        if tag in ("p", "div", "ul", "ol", "h1", "h2", "h3", "h4"):
            self.parts.append("\n")

    def handle_data(self, data):
        self.parts.append(data)


MAX_DESCRIPTION = 4000


def text_from_html(s: str | None) -> str | None:
    """Plain text of a description (tags dropped, entities decoded, blank runs folded); None when empty."""
    if not s or not s.strip():
        return None
    p = _Text()
    try:
        p.feed(s)
        p.close()
        raw = "".join(p.parts)
    except Exception:   # never fail a search over a description
        raw = re.sub(r"<[^>]+>", " ", html.unescape(s))
    lines = [" ".join(line.split()) for line in raw.splitlines()]
    out = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    out = "\n".join(line for line in out.split("\n") if line != "•")
    return out[:MAX_DESCRIPTION] or None


def _date(v) -> str | None:
    """ISO date of an expiry; the API's "never" (9999-…) and junk are None."""
    if not isinstance(v, str) or len(v) < 10 or v.startswith("9999"):
        return None
    try:
        return date.fromisoformat(v[:10]).isoformat()
    except ValueError:
        return None


def _num(v) -> float | None:
    try:
        return None if v is None else float(v)
    except (TypeError, ValueError):
        return None


def _int(v) -> int | None:
    f = _num(v)
    return None if f is None else int(f)


def _qty_unit(q, unit) -> str | None:
    if not unit:
        return None
    q = _num(q) or 1
    return f"{q:g} {unit}"


def to_product(x: dict) -> Product:
    """A search result or a product detail (whose price, stock and badges sit in offers[0]) → Product."""
    offer = (x.get("offers") or [None])[0] or {}
    prices = x.get("prices") or offer or {}
    price = prices.get("price") or {}
    was = prices.get("wasPrice") or {}
    cmp_ = (prices.get("comparisonPrices") or [None])[0] or {}
    badges = x.get("badges") or offer.get("badges") or {}
    deal = badges.get("dealBadge") or {}
    loyal = badges.get("loyaltyBadge") or {}
    pu = x.get("pricingUnits") or {}
    imgs = [a for a in x.get("imageAssets") or [] if isinstance(a, dict)]
    value = _num(price.get("value"))
    on_sale = price.get("type") == "SPECIAL"
    weighted = bool(pu.get("weighted")) or pu.get("type") == "SOLD_BY_WEIGHT"
    pq = _num(price.get("quantity")) if price.get("unit") in ("g", "kg") else None
    return Product(
        code=x["code"], name=x.get("name") or "", brand=x.get("brand"), package_size=x.get("packageSize"),
        price=value, stock=x.get("stockStatus") or offer.get("stockStatus"), sold_by=pu.get("type"),
        image=next((a.get("smallUrl") or a.get("mediumUrl") or a.get("thumbnailUrl") for a in imgs
                    if a.get("smallUrl") or a.get("mediumUrl") or a.get("thumbnailUrl")), None),
        images=[u for a in imgs if (u := a.get("largeUrl") or a.get("mediumUrl") or a.get("smallUrl"))],
        description=text_from_html(x.get("description")),
        unit_price=_num(cmp_.get("value")), unit_price_unit=_qty_unit(cmp_.get("quantity"), cmp_.get("unit")),
        regular_price=_num(was.get("value")) if on_sale and was else (None if on_sale else value),
        was_price=_num(was.get("value")) if was else None,
        sale_ends=_date(price.get("expiryDate")) if on_sale else None,
        deal_text=deal.get("text") or None, deal_ends=_date(deal.get("expiryDate")) if deal else None,
        optimum_text=loyal.get("text") or None, optimum_points=_int(loyal.get("points")),
        optimum_ends=_date(loyal.get("expiryDate")) if loyal else None,
        sponsored=bool(x.get("sponsored")),
        min_qty=_int(pu.get("minOrderQuantity")), max_qty=_int(pu.get("maxOrderQuantity")),
        interval=_int(pu.get("interval")), weighted=weighted, price_quantity=pq if weighted else None,
        link=x.get("link"))
