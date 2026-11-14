"""Import a recipe from any web page (2026-11-08): fetch the page safely, find the recipe, say where it came from.

Fetching (`fetch_page`): http(s) only, the default ports, no credentials in the link; every hop's host must resolve
to public addresses only (no LAN, loopback, link-local, carrier-grade NAT, multicast or reserved space) and the
connection itself goes through `GuardBackend`, which re-checks the addresses it actually connects to (so a DNS answer
that changes between the check and the connection can't reach the LAN). Redirects are followed by hand (at most
MAX_REDIRECTS, each one checked), with a browser-like User-Agent, a connect/read timeout, an overall deadline and a
size cap (the import cap). Only HTML is read. A page a bot check turns away (403 / "Just a moment…") is fetched
once more by `browser_fetch` (curl_cffi with a browser's TLS fingerprint, the same checks, the connection pinned to a
checked address). Every failure is a WebImportError with a `reason` code and a sentence
for the household; the code is logged.

Finding the recipe (`read_recipe`), in order:
  1. schema.org Recipe JSON-LD (single node, arrays, @graph, mainEntity, ItemList; HowToSection steps), with @id
     references in the graph resolved (author, publisher);
  2. microdata (itemscope/itemtype … Recipe, itemprop) or RDFa Lite (typeof/property), read into the same shape;
  3. the AI, on the page's visible text (the document reader's prompt), when the page has neither.
The node then goes through the JSON-LD importer (exchange.jsonld.from_jsonld), and the ingredient lines through the
AI tidy-up like any imported recipe (deterministic lines if the AI doesn't answer).

Where it came from (`attribution`): the site name (og:site_name → the recipe's / graph's publisher name →
application-name → a cleaned registrable domain), the author (the recipe's author, if it names one; never the site
itself), the canonical link (rel=canonical → og:url, only on the same site, else the fetched link; tracking
parameters and the #fragment dropped) and a photo (the recipe's image, else og:image; https only, hotlinked like NYT's).
"""
from dataclasses import dataclass, field
from html.parser import HTMLParser
import ipaddress
import logging
import re
import socket
import time
import uuid
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit

import httpcore
import httpx
from httpcore._backends.sync import SyncBackend

from ..exchange import jsonld
from ..exchange.extract import find_recipes, is_recipe
from ..exchange.safe import Unreadable, loads_limited
from ..models import Recipe
from .nyt import UA, pick_image

log = logging.getLogger(__name__)

MAX_REDIRECTS = 5
TIMEOUT = httpx.Timeout(20.0, connect=10.0)
DEADLINE = 45.0               # seconds for the whole fetch (all hops and the body)
AI_TEXT = 48_000              # characters of a page's visible text the AI reads (about 4 parts)
MIN_TEXT = 200                # visible characters a page needs before the AI is asked
PORTS = {"http": 80, "https": 443}
_HTML_TYPES = ("text/html", "application/xhtml+xml")
_LOCAL_SUFFIXES = (".local", ".localhost", ".internal", ".lan", ".home", ".home.arpa", ".localdomain", ".intranet",
                   ".corp", ".private")
# query parameters that only track the click: dropped from the saved link (so the same page is the same recipe)
_TRACKING = re.compile(r"^(?:utm_.*|fbclid|gclid|dclid|msclkid|mc_cid|mc_eid|igshid|smid|ref|ref_src|s_cid|cmpid|"
                       r"_ga|_gl|yclid|wt_mc|ito|epik|si)$", re.I)
# two-label public suffixes common for recipe sites (no full Public Suffix List: a wrong guess only changes the name
# shown when a site names itself nowhere)
_SUFFIX2 = {"co.uk", "org.uk", "ac.uk", "me.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "org.nz",
            "net.nz", "co.jp", "ne.jp", "or.jp", "com.br", "com.mx", "co.za", "com.sg", "com.hk", "co.in", "com.tr",
            "com.cn", "com.ar", "co.kr", "com.tw", "co.il", "com.my", "com.ph", "com.pk", "com.ng", "co.id", "qc.ca",
            "on.ca", "bc.ca", "ab.ca", "gc.ca"}

# reason codes (logged; the API turns them into status codes)
BAD_LINK, PRIVATE, UNREACHABLE, TIMEOUT_, TOO_BIG, NOT_HTML = "bad_link", "private", "unreachable", "timeout", "too_big", "not_html"
NOT_FOUND, PAYWALL, BLOCKED, BUSY, HTTP_ERROR, NO_RECIPE, AI_FAILED = ("not_found", "paywall", "blocked", "busy",
                                                                       "http_error", "no_recipe", "ai_failed")
USER_ERRORS = {BAD_LINK, PRIVATE, NO_RECIPE, NOT_HTML}   # 422: the link itself; everything else is the site (502)


class WebImportError(Exception):
    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason


# --- links ---------------------------------------------------------------------------------------------------------

_LINK = re.compile(r"https?://[^\s<>\"'`]+", re.I)


def extract_url(text: str | None) -> str | None:
    """The first http(s) link in a shared text ("Look at this! https://…"), trailing punctuation dropped."""
    m = _LINK.search(text or "")
    if not m:
        return None
    url = m.group(0)
    while url and url[-1] in ".,;:!?)]}»”’":
        if url[-1] == ")" and url.count("(") >= url.count(")"):
            break
        url = url[:-1]
    return url or None


def normalise_url(url: str) -> str:
    """The link as saved and compared: scheme and host lower case, the default port and #fragment dropped, tracking
    parameters (utm_*, fbclid …) left out, an empty path as "/"."""
    u = urlsplit(url.strip())
    scheme = u.scheme.lower()
    host = (u.hostname or "").lower().rstrip(".")
    if ":" in host:
        host = f"[{host}]"
    port = f":{u.port}" if u.port and u.port != PORTS.get(scheme) else ""
    query = urlencode([(k, v) for k, v in parse_qsl(u.query, keep_blank_values=True) if not _TRACKING.match(k)])
    return urlunsplit((scheme, host + port, u.path or "/", query, ""))


def url_variants(url: str) -> list[str]:
    """Links that are the same page for de-duplication: http/https, with/without www., with/without a trailing slash."""
    n = urlsplit(normalise_url(url))
    host = n.netloc
    hosts = {host, host[4:] if host.startswith("www.") else "www." + host}
    paths = {n.path, n.path.rstrip("/") or "/", n.path if n.path.endswith("/") else n.path + "/"}
    return sorted({urlunsplit((s, h, p, n.query, "")) for s in ("https", "http") for h in hosts for p in paths})


def registrable_domain(host: str) -> str:
    host = host.lower().rstrip(".")
    labels = host.split(".")
    if len(labels) >= 3 and ".".join(labels[-2:]) in _SUFFIX2:
        return ".".join(labels[-3:])
    return ".".join(labels[-2:]) if len(labels) >= 2 else host


def same_site(a: str, b: str) -> bool:
    ha, hb = urlsplit(a).hostname, urlsplit(b).hostname
    return bool(ha and hb) and registrable_domain(ha) == registrable_domain(hb)


# --- safety --------------------------------------------------------------------------------------------------------

def is_public(ip: str) -> bool:
    """A globally routable unicast address (IPv4-mapped IPv6 judged as its IPv4)."""
    try:
        a = ipaddress.ip_address(ip.split("%", 1)[0])
    except ValueError:
        return False
    if isinstance(a, ipaddress.IPv6Address) and a.ipv4_mapped:
        a = a.ipv4_mapped
    return a.is_global and not a.is_multicast and not a.is_reserved and not a.is_unspecified


def _resolve(host: str, port: int) -> list[str]:
    return sorted({ai[4][0] for ai in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)})


def check_url(url: str, resolve=_resolve) -> str:
    """The link if it may be fetched; else WebImportError(bad_link | private). Every address the host resolves to
    must be public (one LAN address in the answer is enough to refuse)."""
    try:
        u = urlsplit(url)
        port = u.port
    except ValueError:
        raise WebImportError(BAD_LINK, "That link isn't a web address.")
    scheme = u.scheme.lower()
    if scheme not in PORTS or not u.hostname:
        raise WebImportError(BAD_LINK, "Only http and https links can be imported.")
    if u.username is not None or u.password is not None or "@" in u.netloc:
        raise WebImportError(BAD_LINK, "Links with a user name or password can't be imported.")
    if port is not None and port != PORTS[scheme]:
        raise WebImportError(BAD_LINK, "Only links on the standard web ports can be imported.")
    host = u.hostname.lower().rstrip(".")
    single_label = "." not in host and ":" not in host   # "nas", "router", a decimal IPv4 like 2130706433
    if host == "localhost" or host.endswith(_LOCAL_SUFFIXES) or single_label:
        raise WebImportError(PRIVATE, "That link points into a private network, not the public web.")
    try:
        ipaddress.ip_address(host)
        literal = True
    except ValueError:
        literal = False
    try:
        addrs = [host] if literal else resolve(host, PORTS[scheme])
    except (socket.gaierror, UnicodeError, OSError) as e:
        raise WebImportError(UNREACHABLE, f"Couldn't find the site {host}. Check the link.") from e
    if not addrs or not all(is_public(a) for a in addrs):
        raise WebImportError(PRIVATE, "That link points into a private network, not the public web.")
    return url


class GuardBackend(SyncBackend):
    """httpcore's socket backend, but it resolves the host itself and connects only to public addresses."""

    def __init__(self, resolve=_resolve):
        self._resolve = resolve

    def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        try:
            addrs = self._resolve(host, port)
        except OSError as e:
            raise httpcore.ConnectError(str(e)) from e
        if not addrs or not all(is_public(a) for a in addrs):
            raise httpcore.ConnectError(f"refused a non-public address for {host}")
        last = None
        for a in addrs:
            try:
                return super().connect_tcp(a, port, timeout=timeout, local_address=local_address,
                                           socket_options=socket_options)
            except httpcore.ConnectError as e:
                last = e
        raise last or httpcore.ConnectError(f"couldn't connect to {host}")

    def connect_unix_socket(self, *a, **kw):   # never
        raise httpcore.ConnectError("unix sockets are not allowed")


def guarded_client(resolve=_resolve) -> httpx.Client:
    """An httpx client whose connections go through GuardBackend; no proxies from the environment, no redirects."""
    t = httpx.HTTPTransport(trust_env=False)
    t._pool = httpcore.ConnectionPool(ssl_context=httpx.create_ssl_context(), network_backend=GuardBackend(resolve),
                                      max_connections=4)
    return httpx.Client(transport=t, trust_env=False, follow_redirects=False, timeout=TIMEOUT,
                        headers={"User-Agent": UA, "Accept-Language": "en-CA,en;q=0.8",
                                 "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"})


# --- fetching ------------------------------------------------------------------------------------------------------

@dataclass
class Page:
    url: str          # where it was fetched from, after redirects
    html: str


def _charset(content_type: str, head: bytes) -> str | None:
    m = re.search(r"charset=[\"']?([\w.:-]+)", content_type or "", re.I) or \
        re.search(rb"<meta[^>]+charset=[\"']?([\w.:-]+)", head[:4096], re.I)
    if not m:
        return None
    cs = m.group(1)
    return cs.decode("ascii", "ignore") if isinstance(cs, bytes) else cs


def _decode(data: bytes, content_type: str) -> str:
    cs = _charset(content_type, data)
    if cs:
        try:
            return data.decode(cs, errors="replace")
        except LookupError:
            pass
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")


def _status_error(status: int, headers, url: str, body: bytes) -> WebImportError:
    site = urlsplit(url).hostname or "the site"
    challenge = headers.get("cf-mitigated") == "challenge" or b"Just a moment" in body[:20000] or \
        b"captcha" in body[:20000].lower()
    if status in (401, 402, 407):
        return WebImportError(PAYWALL, f"{site} wants a subscription or a sign-in for that page, so its recipe can't "
                                       "be read. Save the page from your browser and import the file instead.")
    if status == 403 or challenge:
        return WebImportError(BLOCKED, f"{site} refused to show that page to the Meal Prep server (it blocks automated "
                                       "readers). Save the page from your browser and import the file instead.")
    if status in (404, 410):
        return WebImportError(NOT_FOUND, f"{site} has no page at that link (HTTP {status}). Check the link.")
    if status in (429, 503):
        return WebImportError(BUSY, f"{site} is busy or limiting visitors (HTTP {status}). Try again in a few minutes.")
    return WebImportError(HTTP_ERROR, f"{site} answered HTTP {status} for that page.")


_DEFAULT = object()


def fetch_page(url: str, max_bytes: int, client: httpx.Client | None = None, resolve=_resolve,
               fallback=_DEFAULT) -> Page:
    """The page's HTML after redirects (each hop checked by check_url). Raises WebImportError.

    A site behind a bot check (Cloudflare's "Just a moment…", a bare 403) often turns away httpx by its TLS
    fingerprint alone, though the page is public. Then the page is fetched once more with `fallback` (default
    `browser_fetch`: a browser's TLS/HTTP2 fingerprint, the same address checks, pinned to the checked address). Tests
    that pass their own client get no fallback unless they pass one."""
    if fallback is _DEFAULT:
        fallback = browser_fetch if client is None else None
    start = time.monotonic()
    try:
        return _httpx_fetch(url, max_bytes, client, resolve, start)
    except WebImportError as e:
        if e.reason != BLOCKED or fallback is None:
            raise
        log.warning("web fetch %s blocked (%s); trying a browser-like fetch", url[:200], e)
        try:
            return fallback(url, max_bytes, resolve=resolve, start=start)
        except WebImportError as e2:
            log.warning("browser-like fetch of %s failed: %s: %s", url[:200], e2.reason, e2)
            raise e2 if e2.reason != BLOCKED else e
        except Exception as e2:   # the fallback itself broke: the first answer stands
            log.warning("browser-like fetch of %s failed: %s: %s", url[:200], type(e2).__name__, str(e2)[:200])
            raise e


def _pin(url: str, resolve) -> tuple[str, int, str | None]:
    """(host, port, address to connect to) for a link check_url accepted; the address is re-resolved and must be
    public (None = the host is an IP literal, already checked)."""
    u = urlsplit(url)
    host = (u.hostname or "").lower().rstrip(".")
    port = u.port or PORTS[u.scheme.lower()]
    try:
        ipaddress.ip_address(host)
        return host, port, None
    except ValueError:
        pass
    try:
        addrs = resolve(host, port)
    except (socket.gaierror, UnicodeError, OSError) as e:
        raise WebImportError(UNREACHABLE, f"Couldn't find the site {host}. Check the link.") from e
    if not addrs or not all(is_public(a) for a in addrs):
        raise WebImportError(PRIVATE, "That link points into a private network, not the public web.")
    return host, port, addrs[0]


def browser_fetch(url: str, max_bytes: int, resolve=_resolve, start: float | None = None) -> Page:
    """fetch_page's second try (curl_cffi impersonating Chrome): the same rules — every hop checked by check_url,
    redirects by hand, the connection pinned (CURLOPT_RESOLVE) to an address that was just checked to be public and
    confirmed after, http/https only, no proxies from the environment, the size cap and the overall deadline."""
    from curl_cffi import CurlOpt, requests as creq
    from curl_cffi.requests.exceptions import RequestException, Timeout
    start = start or time.monotonic()
    for _ in range(MAX_REDIRECTS + 1):
        check_url(url, resolve)
        host, port, pin = _pin(url, resolve)
        left = DEADLINE - (time.monotonic() - start)
        if left <= 1:
            raise WebImportError(TIMEOUT_, "That site took too long to answer. Try again later.")
        opts = {CurlOpt.PROTOCOLS_STR: b"http,https", CurlOpt.REDIR_PROTOCOLS_STR: b"http,https"}
        if pin:
            opts[CurlOpt.RESOLVE] = [f"{host}:{port}:{'[' + pin + ']' if ':' in pin else pin}"]
        try:
            with creq.Session(impersonate="chrome", trust_env=False, curl_options=opts) as s:
                r = s.get(url, stream=True, allow_redirects=False, timeout=(10, min(20.0, left)),
                          headers={"Accept-Language": "en-CA,en;q=0.8"})
                try:
                    ip = getattr(r, "primary_ip", None)
                    if ip and not is_public(ip):
                        raise WebImportError(PRIVATE, "That link points into a private network, not the public web.")
                    loc = r.headers.get("location")
                    if r.status_code in (301, 302, 303, 307, 308) and loc:
                        nxt = urljoin(url, loc)
                        log.warning("browser-like fetch %s → %s %s", url, r.status_code, nxt)
                        url = nxt
                        continue
                    body = bytearray()
                    for chunk in r.iter_content():
                        body += chunk
                        if len(body) > max_bytes:
                            raise WebImportError(TOO_BIG, "That page is too big to read.")
                        if time.monotonic() - start > DEADLINE:
                            raise WebImportError(TIMEOUT_, "That site took too long to answer. Try again later.")
                        if r.status_code >= 400 and len(body) > 65536:
                            break
                finally:
                    r.close()
        except Timeout as e:
            raise WebImportError(TIMEOUT_, "That site took too long to answer. Try again later.") from e
        except RequestException as e:
            raise WebImportError(UNREACHABLE, f"Couldn't reach {host} ({type(e).__name__}).") from e
        if r.status_code >= 400:
            raise _status_error(r.status_code, r.headers, url, bytes(body))
        if r.status_code != 200:
            raise WebImportError(HTTP_ERROR, f"The site answered HTTP {r.status_code} for that page.")
        ctype = (r.headers.get("content-type") or "").split(";")[0].strip().lower()
        if ctype and ctype not in _HTML_TYPES:
            raise WebImportError(NOT_HTML, "That link isn't a web page (it's "
                                           f"{ctype or 'something else'}). Share the recipe's page.")
        return Page(url=url, html=_decode(bytes(body), r.headers.get("content-type") or ""))
    raise WebImportError(HTTP_ERROR, "That link redirects too many times.")


def _httpx_fetch(url: str, max_bytes: int, client: httpx.Client | None, resolve, start: float) -> Page:
    own = client is None
    client = client or guarded_client(resolve)
    try:
        for _ in range(MAX_REDIRECTS + 1):
            check_url(url, resolve)
            try:
                with client.stream("GET", url) as r:
                    if r.is_redirect and r.headers.get("location"):
                        nxt = urljoin(url, r.headers["location"])
                        log.warning("web fetch %s → %s %s", url, r.status_code, nxt)
                        url = nxt
                        continue
                    body = bytearray()
                    for chunk in r.iter_bytes():
                        body += chunk
                        if len(body) > max_bytes:
                            raise WebImportError(TOO_BIG, "That page is too big to read.")
                        if time.monotonic() - start > DEADLINE:
                            raise WebImportError(TIMEOUT_, "That site took too long to answer. Try again later.")
                        if r.status_code >= 400 and len(body) > 65536:
                            break
                    if r.status_code >= 400:
                        raise _status_error(r.status_code, r.headers, url, bytes(body))
                    if r.status_code != 200:
                        raise WebImportError(HTTP_ERROR, f"The site answered HTTP {r.status_code} for that page.")
                    ctype = r.headers.get("content-type", "").split(";")[0].strip().lower()
                    if ctype and ctype not in _HTML_TYPES:
                        raise WebImportError(NOT_HTML, "That link isn't a web page (it's "
                                                       f"{ctype or 'something else'}). Share the recipe's page.")
                    return Page(url=url, html=_decode(bytes(body), r.headers.get("content-type", "")))
            except httpx.TimeoutException as e:
                raise WebImportError(TIMEOUT_, "That site took too long to answer. Try again later.") from e
            except httpx.HTTPError as e:
                if "non-public" in str(e):
                    raise WebImportError(PRIVATE, "That link points into a private network, not the public web.") from e
                raise WebImportError(UNREACHABLE, f"Couldn't reach {urlsplit(url).hostname} "
                                                  f"({type(e).__name__}).") from e
        raise WebImportError(HTTP_ERROR, "That link redirects too many times.")
    finally:
        if own:
            client.close()


# --- reading the page ------------------------------------------------------------------------------------------------

_VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param"}
_SKIP = {"script", "style", "noscript", "svg", "template", "iframe", "head", "nav", "footer", "header", "aside",
         "form", "button", "select", "dialog"}
_BLOCK = {"p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "section", "article",
          "br", "blockquote", "pre", "dd", "dt", "figcaption", "main"}
_URL_PROPS = {"a": "href", "link": "href", "img": "src", "source": "src", "area": "href", "audio": "src",
              "video": "src", "object": "data", "embed": "src"}
MAX_ITEMS = 2000


def _prop_names(v: str) -> list[str]:
    out = []
    for p in (v or "").split():
        for pre in ("schema:", "http://schema.org/", "https://schema.org/"):
            if p.startswith(pre):
                p = p[len(pre):]
        if p and ":" not in p and "/" not in p:
            out.append(p)
    return out


class _Page(HTMLParser):
    """One pass: <meta>, rel=canonical, <title>, JSON-LD blocks, microdata / RDFa Lite items, the visible text."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.meta: dict[str, str] = {}
        self.canonical: str | None = None
        self.title = ""
        self.blocks: list[str] = []
        self.items: list[dict] = []          # top-level microdata items
        self.text: list[str] = []
        self._stack: list[dict] = []        # open elements: {tag, item, props, buf}
        self._skip = 0
        self._ld: list[str] | None = None
        self._in_title = False
        self._n = 0

    # the current microdata item (innermost open element that started one)
    def _item(self):
        for el in reversed(self._stack):
            if el.get("item") is not None:
                return el["item"]
        return None

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "meta":
            key = (a.get("property") or a.get("name") or a.get("itemprop") or "").strip().lower()
            if key and "content" in a and key not in self.meta:
                self.meta[key] = a["content"]
        elif tag == "link" and "canonical" in a.get("rel", "").lower().split() and a.get("href") and not self.canonical:
            self.canonical = a["href"].strip()
        elif tag == "title" and not self.title:
            self._in_title = True
        elif tag == "script":
            t = a.get("type", "").split(";")[0].strip().lower()
            self._ld = [] if t == "application/ld+json" else None
        if tag in _SKIP:
            self._skip += 1
        if tag in _BLOCK and not self._skip:
            self.text.append("\n")
        props = _prop_names(a.get("itemprop") or a.get("property", "")) if ("itemprop" in a or "property" in a) else []
        new_item = None
        if ("itemscope" in a or "typeof" in a) and self._n < MAX_ITEMS:
            self._n += 1
            types = _prop_names(a.get("itemtype", "") or a.get("typeof", "")) or \
                [t.rstrip("/").rsplit("/", 1)[-1] for t in a.get("itemtype", "").split() if t]
            new_item = {"@type": types[0] if len(types) == 1 else types, "_props": {}}
        el = {"tag": tag, "item": new_item, "props": props, "buf": [] if props and new_item is None else None}
        parent = self._item()
        if props and parent is not None:
            if new_item is not None:
                for p in props:
                    parent["_props"].setdefault(p, []).append(new_item)
            else:
                val = a.get("content")
                if val is None and tag in _URL_PROPS and a.get(_URL_PROPS[tag]):
                    val = a[_URL_PROPS[tag]]
                if val is None and tag == "time" and a.get("datetime"):
                    val = a["datetime"]
                if val is not None:
                    for p in props:
                        parent["_props"].setdefault(p, []).append(val)
                    el["buf"] = None
        elif new_item is not None and parent is None:
            self.items.append(new_item)
        elif new_item is not None and parent is not None:
            self.items.append(new_item)   # an item inside another without itemprop: still findable
        if tag in _VOID:
            self._close(el)
        else:
            self._stack.append(el)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in _VOID:
            self.handle_endtag(tag)

    def _close(self, el):
        if el["buf"] is not None and el["props"]:
            parent = None
            idx = self._stack.index(el) if el in self._stack else len(self._stack)
            for e in reversed(self._stack[:idx]):
                if e.get("item") is not None:
                    parent = e["item"]
                    break
            if parent is not None:
                val = " ".join("".join(el["buf"]).split())
                for p in el["props"]:
                    parent["_props"].setdefault(p, []).append(val)
        if el["tag"] in _SKIP and self._skip:
            self._skip -= 1

    def handle_endtag(self, tag):
        if tag == "script" and self._ld is not None:
            self.blocks.append("".join(self._ld))
            self._ld = None
        if tag == "title":
            self._in_title = False
        if tag in _BLOCK and not self._skip:
            self.text.append("\n")
        for i in range(len(self._stack) - 1, -1, -1):
            if self._stack[i]["tag"] == tag:
                for el in reversed(self._stack[i:]):
                    self._close(el)
                del self._stack[i:]
                break

    def handle_data(self, data):
        if self._ld is not None:
            self._ld.append(data)
            return
        if self._in_title:
            self.title += data
        for el in self._stack:
            if el["buf"] is not None:
                el["buf"].append(data)
        if not self._skip:
            self.text.append(data)


def _plain(item: dict, depth: int = 0) -> dict:
    """A microdata item as a JSON-LD-shaped dict: one value as itself, several as a list (ingredients/steps always a
    list); nested items likewise."""
    out: dict = {"@type": item["@type"]}
    if depth > 16:
        return out
    for k, vals in item["_props"].items():
        vals = [_plain(v, depth + 1) if isinstance(v, dict) else v for v in vals]
        vals = [v for v in vals if v not in ("", None)]
        if not vals:
            continue
        out[k] = vals if len(vals) > 1 or k in ("recipeIngredient", "ingredients", "recipeInstructions") else vals[0]
    return out


@dataclass
class Parsed:
    meta: dict[str, str]
    canonical: str | None
    title: str
    nodes: list[dict]                    # every JSON-LD object with an @id or @type (for publisher/author look-ups)
    recipes: list[dict]                  # Recipe nodes from JSON-LD
    micro: list[dict]                    # Recipe items from microdata / RDFa
    text: str
    paywalled: bool = False
    by_id: dict[str, dict] = field(default_factory=dict)


def _walk_nodes(v, out: list, depth=0):
    if depth > 32 or len(out) > 5000:
        return
    if isinstance(v, list):
        for x in v:
            _walk_nodes(x, out, depth + 1)
    elif isinstance(v, dict):
        if "@type" in v or "@id" in v:
            out.append(v)
        for x in v.values():
            if isinstance(x, (list, dict)):
                _walk_nodes(x, out, depth + 1)


def parse_page(html: str) -> Parsed:
    p = _Page()
    try:
        p.feed(html)
        p.close()
    except Exception:   # malformed markup: what was read so far
        pass
    nodes, recipes = [], []
    for block in p.blocks:
        try:
            data = loads_limited(block.strip())
            recipes.extend(find_recipes(data))
        except Unreadable:
            continue
        _walk_nodes(data, nodes)
    by_id = {n["@id"]: n for n in nodes if isinstance(n.get("@id"), str) and len(n) > 1}
    micro = [_plain(i) for i in p.items if is_recipe(i)]
    text = re.sub(r"[ \t\r\f\v]+", " ", "".join(p.text))
    text = re.sub(r"\n\s*\n+", "\n\n", "\n".join(line.strip() for line in text.split("\n"))).strip()
    paywalled = any(str(n.get("isAccessibleForFree", "")).lower() in ("false", "no") for n in nodes)
    return Parsed(meta=p.meta, canonical=p.canonical, title=" ".join(p.title.split()), nodes=nodes, recipes=recipes,
                  micro=micro, text=text, paywalled=paywalled, by_id=by_id)


def _deref(v, by_id: dict, depth=0):
    """Replace {"@id": …} references with the graph's node (one level per call, lists too)."""
    if depth > 4:
        return v
    if isinstance(v, list):
        return [_deref(x, by_id, depth + 1) for x in v]
    if isinstance(v, dict) and set(v) <= {"@id", "@type"} and isinstance(v.get("@id"), str) and v["@id"] in by_id:
        return by_id[v["@id"]]
    return v


# --- attribution ---------------------------------------------------------------------------------------------------

@dataclass
class Attribution:
    site: str
    author: str | None
    url: str
    image: str | None


def _site_from_domain(url: str) -> str:
    return registrable_domain(urlsplit(url).hostname or "")


def _publisher_name(node: dict | None, parsed: Parsed) -> str | None:
    if node and node.get("publisher"):
        n = jsonld._names(_deref(node["publisher"], parsed.by_id))
        if n:
            return n
    for n in parsed.nodes:   # the graph's WebSite / Organization
        types = jsonld._type_set(n.get("@type"))
        if types & {"WebSite", "Organization", "NewsMediaOrganization"} and isinstance(n.get("name"), str):
            return jsonld.clean_text(n["name"], 100)
    return None


def attribution(parsed: Parsed, node: dict | None, fetched: str) -> Attribution:
    canon = None
    for cand in (parsed.canonical, parsed.meta.get("og:url")):
        if cand:
            full = jsonld.safe_url(urljoin(fetched, cand.strip()))
            if full and same_site(full, fetched):
                canon = full
                break
    url = normalise_url(canon or fetched)
    site = jsonld.clean_text(parsed.meta.get("og:site_name"), 100) or _publisher_name(node, parsed) or \
        jsonld.clean_text(parsed.meta.get("application-name"), 100) or _site_from_domain(url)
    author = None
    if node and node.get("author"):
        author = jsonld._names(_deref(node["author"], parsed.by_id))
    if author and site and author.casefold() == site.casefold():
        author = None   # "by Serious Eats" adds nothing to "From Serious Eats"
    image = pick_image(_deref(node.get("image"), parsed.by_id) if node else None)
    if image is None and parsed.meta.get("og:image"):
        image = pick_image(urljoin(fetched, parsed.meta["og:image"].strip()))
    return Attribution(site=site[:200], author=author, url=url, image=image)


# --- the recipe ------------------------------------------------------------------------------------------------------

@dataclass
class WebRecipe:
    recipe: Recipe
    how: str                      # "jsonld" | "microdata" | "ai"
    warnings: list[str]


def _best(nodes: list[dict], title: str) -> dict:
    """The page's recipe when it has several (a round-up): the one named like the page, else the first."""
    words = set(re.findall(r"\w+", title.casefold()))
    def score(n):
        name = set(re.findall(r"\w+", str(n.get("name") or n.get("title") or "").casefold()))
        return len(name & words) / (len(name) or 1)
    return max(nodes, key=score) if len(nodes) > 1 and words else nodes[0]


def _ai_node(ai, parsed: Parsed) -> dict | None:
    """The AI reads the page's visible text (the document reader's prompt); the recipe named like the page."""
    from ..exchange.documents import Document
    from . import document as docsplit
    text = parsed.text[:AI_TEXT]
    if ai is None or sum(1 for ch in text if not ch.isspace()) < MIN_TEXT:
        return None
    try:
        recipes, _ = docsplit.split(ai, Document("text", text))
    except docsplit.DocumentReadFailed as e:
        raise WebImportError(AI_FAILED, "The AI couldn't read that page. Try again in a few minutes.") from e
    recipes = [r for r in recipes if r.get("title") and (r.get("ingredients") or r.get("steps"))]
    if not recipes:
        return None
    best = _best(recipes, parsed.meta.get("og:title") or parsed.title)
    node = docsplit.to_node({**best, "source": None}, None)
    node.pop("isBasedOn", None)
    return node


def _no_recipe(parsed: Parsed, site: str) -> WebImportError:
    if parsed.paywalled:
        return WebImportError(PAYWALL, f"That page on {site} is for subscribers, so its recipe can't be read. Save "
                                       "the page from your browser while signed in and import the file instead.")
    return WebImportError(NO_RECIPE, f"No recipe found on that page of {site}.")


def read_recipe(html: str, fetched: str, ai=None, tidy=None) -> WebRecipe:
    """The page's recipe with its attribution (JSON-LD → microdata → AI). `tidy(ai, incoming)` = the importer's AI
    clean-up of the ingredient lines (exchange.importer.tidy). Raises WebImportError(no_recipe | paywall | ai_failed)."""
    parsed = parse_page(html)
    page_title = parsed.meta.get("og:title") or parsed.title
    usable, how = None, None
    for nodes, kind in ((parsed.recipes, "jsonld"), (parsed.micro, "microdata")):
        good = []
        for n in nodes:
            try:
                good.append((n, jsonld.from_jsonld(n)))
            except jsonld.NotUsable:
                continue
        if good:
            node = _best([g[0] for g in good], page_title)
            usable, how = (node, next(i for n, i in good if n is node)), kind
            break
    if usable is None:
        node = _ai_node(ai, parsed)
        if node is not None:
            try:
                usable, how = (node, jsonld.from_jsonld(node)), "ai"
            except jsonld.NotUsable:
                usable = None
    src_node = usable[0] if usable and how != "ai" else None
    att = attribution(parsed, src_node, fetched)
    if usable is None:
        raise _no_recipe(parsed, att.site)
    inc = usable[1]
    warnings = list(inc.warnings)
    r = inc.recipe.model_copy(update={
        "source": "web", "source_kind": "web", "source_title": att.site, "source_ref": None,
        "source_author": att.author, "source_isbn": None, "source_url": att.url,
        "image": att.image or (inc.recipe.image if inc.recipe.image and inc.recipe.image.startswith("https://") else None),
        "uid": str(uuid.uuid5(uuid.NAMESPACE_URL, att.url))})
    if tidy is not None and ai is not None:   # names, units, prep, pantry by the AI (deterministic if it fails)
        r, _, warn = tidy(ai, inc.model_copy(update={"recipe": r}))
        if warn:
            warnings.append(warn)
    return WebRecipe(recipe=r, how=how, warnings=warnings)
