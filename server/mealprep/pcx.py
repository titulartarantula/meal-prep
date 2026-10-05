"""PC Express anonymous API client. No login, no checkout — search, create cart, add entries, read cart only."""
import os
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

    def search(self, term: str, size: int = 8) -> list[Product]:
        body = {"pagination": {"from": 0, "size": size}, "banner": "loblaw", "cartId": "", "lang": "en",
                "date": date.today().strftime("%d%m%Y"), "storeId": self.store, "pcId": False,
                "pickupType": "STORE", "offerType": "ALL", "term": term,
                "userData": {"domainUserId": "", "sessionId": ""}}
        r = self.http.post(f"{BASE}/products/search", json=body)
        r.raise_for_status()
        return [Product(code=x["code"], name=x.get("name") or "", brand=x.get("brand"),
                        package_size=x.get("packageSize"),
                        price=((x.get("prices") or {}).get("price") or {}).get("value"),
                        stock=x.get("stockStatus"))
                for x in r.json().get("results") or []]

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
