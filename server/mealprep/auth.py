"""Sign-in (server task 1): Google OpenID Connect and the app's own sessions.

- Web: GET /auth/login → Google (authorization code + PKCE, scopes openid email profile) → GET /auth/callback, which
  verifies the ID token and sets an HttpOnly Secure SameSite=Lax session cookie. Writes made with the cookie must
  carry X-CSRF-Token (GET /auth/me returns it).
- Android: the same round trip in a Custom Tab. GET /auth/login?client=android&app=<application id>&app_challenge=<S256
  of a verifier the app keeps> → Google → GET /auth/callback, which sets no cookie but sends the browser to
  <application id>://auth?code=<one-time code> (the app's return URL comes from APP_IDS, never from the request). The
  app trades code + verifier at POST /auth/app-token for a session token, sent as a bearer token. The code is single
  use, lives 2 minutes, is stored hashed and is useless without the verifier (another app that grabbed the redirect
  can't redeem it).
- Android (older path, kept): a Google ID token from Credential Manager → POST /auth/google → a session token.
- Tokens (sessions, OAuth state, invites) are random, shown once and stored only as sha256 hashes.
"""
import base64, hashlib, hmac, ipaddress, secrets, threading, time
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import httpx
from joserfc import jwt
from joserfc.jwk import KeySet

COOKIE = "__Host-mealprep_session"      # __Host-: Secure, Path=/, no Domain (can't be set by a sibling subdomain)
CSRF_HEADER = "X-CSRF-Token"
LOGIN_MINUTES = 10
APP_CODE_SECONDS = 120
ANDROID = "android"
# The Android builds that may sign in (release, debug): their application ids are their return URL schemes.
APP_IDS = frozenset({"dev.mealprep.app", "dev.mealprep.app.debug"})
DEFAULT_APP = "dev.mealprep.app"


class AuthError(Exception):
    """The sign-in can't go on; the message is safe to show."""


def new_token(n: int = 32) -> str:
    return secrets.token_urlsafe(n)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def csrf_token(session_token: str) -> str:
    """Derived from the session token, so nothing extra is stored; only someone who has the cookie can compute it."""
    return hmac.new(session_token.encode(), b"mealprep-csrf", hashlib.sha256).hexdigest()


def pkce_challenge(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


def safe_next(path: str | None) -> str:
    """Where to go after signing in: a path on this site only (no //host, no scheme)."""
    if not path or not path.startswith("/") or path.startswith("//") or "\\" in path or "\n" in path:
        return "/"
    return path[:500]


def valid_challenge(c: str | None) -> bool:
    """An S256 PKCE challenge: 43 base64url characters (RFC 7636)."""
    return bool(c) and len(c) == 43 and all(ch.isascii() and (ch.isalnum() or ch in "-_") for ch in c)


def app_return(app_id: str, **params) -> str:
    """The app's return URL (custom scheme = its application id). app_id must be one of APP_IDS."""
    assert app_id in APP_IDS
    return f"{app_id}://auth?" + urlencode({k: v for k, v in params.items() if v is not None})


def networks(spec: str) -> list:
    return [ipaddress.ip_network(n.strip(), strict=False) for n in spec.split(",") if n.strip()]


def address_in(host: str | None, nets: list) -> bool:
    try:
        ip = ipaddress.ip_address(host or "")
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip in n for n in nets if n.version == ip.version)


class Google:
    """Google as the OpenID provider (endpoints from https://accounts.google.com/.well-known/openid-configuration)."""
    AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    TOKEN = "https://oauth2.googleapis.com/token"
    JWKS = "https://www.googleapis.com/oauth2/v3/certs"
    ISSUERS = ("https://accounts.google.com", "accounts.google.com")
    LEEWAY = 60

    def __init__(self, client_id: str, client_secret: str, redirect_uri: str, http: httpx.Client | None = None,
                 now=time.time):
        self.client_id, self.client_secret, self.redirect_uri = client_id, client_secret, redirect_uri
        self.http = http or httpx.Client(timeout=10)
        self.now = now
        self._keys, self._keys_at, self._lock = None, 0.0, threading.Lock()

    def authorize_url(self, state: str, challenge: str, nonce: str) -> str:
        return self.AUTH + "?" + urlencode({
            "client_id": self.client_id, "redirect_uri": self.redirect_uri, "response_type": "code",
            "scope": "openid email profile", "state": state, "nonce": nonce, "code_challenge": challenge,
            "code_challenge_method": "S256", "prompt": "select_account"})

    def exchange(self, code: str, verifier: str) -> str:
        """The authorization code → the ID token (no access token is kept: no Google API is called)."""
        try:
            r = self.http.post(self.TOKEN, data={
                "code": code, "client_id": self.client_id, "client_secret": self.client_secret,
                "redirect_uri": self.redirect_uri, "grant_type": "authorization_code", "code_verifier": verifier})
        except httpx.HTTPError as e:
            raise AuthError(f"Couldn't reach Google ({type(e).__name__}).")
        if r.status_code != 200 or "id_token" not in r.json():
            raise AuthError("Google didn't accept the sign-in; try again.")
        return r.json()["id_token"]

    def _key_set(self, refresh: bool = False) -> KeySet:
        with self._lock:
            # refetch at most once a minute (a key rotation shows up as an unknown kid)
            if self._keys is None or (refresh and self.now() - self._keys_at > 60) or self.now() - self._keys_at > 3600:
                r = self.http.get(self.JWKS)
                r.raise_for_status()
                self._keys, self._keys_at = KeySet.import_key_set(r.json()), self.now()
            return self._keys

    def verify(self, id_token: str, nonce: str | None = None) -> dict:
        """Check an ID token's signature (Google's keys, RS256 only) and claims: issuer, audience = our client id,
        not expired, the nonce when there is one. Returns the claims."""
        try:
            try:
                token = jwt.decode(id_token, self._key_set(), algorithms=["RS256"])
            except Exception:
                token = jwt.decode(id_token, self._key_set(refresh=True), algorithms=["RS256"])
        except httpx.HTTPError as e:
            raise AuthError(f"Couldn't reach Google ({type(e).__name__}).")
        except Exception:
            raise AuthError("That Google sign-in isn't valid.")
        c = token.claims
        aud = c.get("aud")
        auds = aud if isinstance(aud, list) else [aud]
        now = self.now()
        if c.get("iss") not in self.ISSUERS or self.client_id not in auds or not c.get("sub"):
            raise AuthError("That Google sign-in isn't for this app.")
        if not isinstance(c.get("exp"), (int, float)) or c["exp"] < now - self.LEEWAY:
            raise AuthError("That Google sign-in has expired; sign in again.")
        if isinstance(c.get("iat"), (int, float)) and c["iat"] > now + self.LEEWAY:
            raise AuthError("That Google sign-in isn't valid.")
        if nonce is not None and not hmac.compare_digest(str(c.get("nonce", "")), nonce):
            raise AuthError("That Google sign-in isn't valid.")
        return c


# --- login state (the OAuth round trip) ---

def start_login(conn, next_path: str | None, invite: str | None, app_id: str | None = None,
                app_challenge: str | None = None) -> tuple[str, str, str]:
    """(state, code_verifier, nonce); stored hashed by state for the callback (single use, 10 minutes). app_id +
    app_challenge mark an Android sign-in (the caller has checked both)."""
    state, verifier, nonce = new_token(24), new_token(64), new_token(16)
    conn.execute("DELETE FROM login_states WHERE expires_at < now()")
    conn.execute("INSERT INTO login_states(state_hash, code_verifier, nonce, next, invite_hash, app_id, app_challenge, "
                 "expires_at) VALUES(%s,%s,%s,%s,%s,%s,%s, now() + %s * interval '1 minute')",
                 (hash_token(state), verifier, nonce, safe_next(next_path), hash_token(invite) if invite else None,
                  app_id, app_challenge, LOGIN_MINUTES))
    return state, verifier, nonce


def finish_login(conn, state: str) -> dict:
    """A login in progress (verifier, nonce, next, invite_hash, app_id, app_challenge); it is used up."""
    row = conn.execute("DELETE FROM login_states WHERE state_hash=%s RETURNING code_verifier, nonce, next, invite_hash, "
                       "app_id, app_challenge, expires_at > now()", (hash_token(state or ""),)).fetchone()
    if row is None or not row[6]:
        raise AuthError("This sign-in link has expired; start again.")
    return dict(zip(("verifier", "nonce", "next", "invite_hash", "app_id", "app_challenge"), row[:6]))


# --- app sign-in: the one-time code the callback hands to the app ---

def create_app_code(conn, user_id: int, household_id: int | None, app_challenge: str) -> str:
    code = new_token(32)
    conn.execute("DELETE FROM app_codes WHERE expires_at < now()")
    conn.execute("INSERT INTO app_codes(code_hash, user_id, household_id, app_challenge, expires_at) "
                 "VALUES(%s,%s,%s,%s, now() + %s * interval '1 second')",
                 (hash_token(code), user_id, household_id, app_challenge, APP_CODE_SECONDS))
    return code


def redeem_app_code(conn, code: str, verifier: str) -> tuple[int, int | None]:
    """(user id, household id) for a code + the verifier of its challenge. The code is used up by any attempt, right
    or wrong, so a guessed verifier gets one try."""
    row = conn.execute("DELETE FROM app_codes WHERE code_hash=%s RETURNING user_id, household_id, app_challenge, "
                       "expires_at > now()", (hash_token(code or ""),)).fetchone()
    if row is None or not row[3]:
        raise AuthError("This sign-in has expired; sign in again.")
    if not hmac.compare_digest(pkce_challenge(verifier or "").encode(), row[2].encode()):
        raise AuthError("This sign-in didn't come from this app; sign in again.")
    return row[0], row[1]


# --- sessions ---

def create_session(conn, user_id: int, household_id: int | None, kind: str, days: int,
                   user_agent: str | None = None) -> tuple[str, datetime]:
    token = new_token()
    expires = datetime.now(timezone.utc) + timedelta(days=days)
    conn.execute("INSERT INTO sessions(id_hash, user_id, household_id, kind, expires_at, user_agent) "
                 "VALUES(%s,%s,%s,%s,%s,%s)", (hash_token(token), user_id, household_id, kind, expires,
                                               (user_agent or "")[:300] or None))
    return token, expires


def get_session(conn, token: str, days: int) -> dict | None:
    """The live session for a token, or None. Use keeps it alive: last_seen and expiry move on at most once an hour."""
    if not token:
        return None
    h = hash_token(token)
    row = conn.execute("SELECT s.user_id, s.household_id, s.kind, s.last_seen < now() - interval '1 hour' "
                       "FROM sessions s WHERE s.id_hash=%s AND s.expires_at > now()", (h,)).fetchone()
    if row is None:
        return None
    if row[3]:
        conn.execute("UPDATE sessions SET last_seen=now(), expires_at=now() + %s * interval '1 day' WHERE id_hash=%s",
                     (days, h))
    return {"hash": h, "user_id": row[0], "household_id": row[1], "kind": row[2]}


def delete_session(conn, token_hash: str) -> None:
    conn.execute("DELETE FROM sessions WHERE id_hash=%s", (token_hash,))


def set_session_household(conn, token_hash: str, household_id: int) -> None:
    conn.execute("UPDATE sessions SET household_id=%s WHERE id_hash=%s", (household_id, token_hash))


def clean_expired(conn) -> None:
    conn.execute("DELETE FROM sessions WHERE expires_at < now()")
    conn.execute("DELETE FROM login_states WHERE expires_at < now()")
    conn.execute("DELETE FROM app_codes WHERE expires_at < now()")
