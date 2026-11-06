"""Sign-in: Google OIDC (a fake Google with real RS256 keys), sessions, CSRF, invites, the shared token's networks."""
import base64, hashlib, time
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient
from joserfc import jwt
from joserfc.jwk import RSAKey

from mealprep import auth, db, households
from mealprep.api import create_app
from mealprep.config import Settings

CLIENT_ID = "web-client.apps.googleusercontent.com"
H = {"Authorization": "Bearer secret"}


class Resp:
    def __init__(self, status, body):
        self.status_code, self.body = status, body

    def json(self):
        return self.body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)


class FakeGoogle:
    """Google's token endpoint and JWKS: codes issued by `authorize()` (after the browser's trip) become ID tokens
    signed with a real RSA key; the token endpoint checks the PKCE verifier against the challenge it was sent."""

    def __init__(self):
        self.key = RSAKey.generate_key(2048, parameters={"kid": "k1"})
        self.codes, self.jwks_calls = {}, 0
        self.published = [self.key]

    def id_token(self, key=None, alg="RS256", **claims):
        now = int(time.time())
        c = {"iss": "https://accounts.google.com", "aud": CLIENT_ID, "sub": "sub-1", "email": "a@example.com",
             "email_verified": True, "name": "Ada Example", "iat": now, "exp": now + 3600, **claims}
        key = key or self.key
        return jwt.encode({"alg": alg, "kid": key.kid}, {k: v for k, v in c.items() if v is not None}, key)

    def authorize(self, location: str, **claims) -> str:
        """The user picks an account at Google: returns the code Google would send to the callback."""
        q = {k: v[0] for k, v in parse_qs(urlparse(location).query).items()}
        code = f"code-{len(self.codes)}"
        self.codes[code] = (q["code_challenge"], self.id_token(nonce=q["nonce"], **claims))
        return code

    # httpx.Client surface used by auth.Google
    def get(self, url):
        assert url == auth.Google.JWKS
        self.jwks_calls += 1
        return Resp(200, {"keys": [k.as_dict(private=False) for k in self.published]})

    def post(self, url, data):
        assert url == auth.Google.TOKEN and data["grant_type"] == "authorization_code"
        challenge, token = self.codes.pop(data["code"], (None, None))
        if token is None or auth.pkce_challenge(data["code_verifier"]) != challenge:
            return Resp(400, {"error": "invalid_grant"})
        return Resp(200, {"id_token": token, "access_token": "unused"})


@pytest.fixture
def g():
    return FakeGoogle()


def make(conn, g=None, **kw):
    s = Settings(dsn="unused", token="secret", provider="fake", public_url="https://mealprep.example")
    google = auth.Google(CLIENT_ID, "shh", "https://mealprep.example/api/auth/callback", http=g) if g else None
    return TestClient(create_app(s, provider=object(), pcx=object(), conn=conn, google=google), **kw)


def household(conn, name="Testers", owner_email=None, roles=("owner", "approver", "planner", "contributor")):
    hid = conn.execute("INSERT INTO households(name) VALUES(%s) RETURNING id", (name,)).fetchone()[0]
    mid = households.add_member(conn, hid, "Owner One", "account", email=owner_email, roles=list(roles))
    return hid, mid


def browser_login(c, g, path="/auth/login", **claims):
    r = c.get(path, follow_redirects=False)
    assert r.status_code == 302
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    code = g.authorize(r.headers["location"], **claims)
    return c.get("/auth/callback", params={"code": code, "state": state}, follow_redirects=False)


def cookie_of(r):
    return r.cookies.get(auth.COOKIE) or next(v.split("=", 1)[1].split(";")[0]
                                               for k, v in r.headers.multi_items() if k == "set-cookie")


# --- Google ID token checks -------------------------------------------------------------------------------------

def test_verify_accepts_a_good_token_and_rejects_bad_ones(g):
    google = auth.Google(CLIENT_ID, "shh", "https://x/cb", http=g)
    assert google.verify(g.id_token(nonce="n1"), nonce="n1")["sub"] == "sub-1"
    assert google.verify(g.id_token(iss="accounts.google.com"))["email"] == "a@example.com"
    bad = [g.id_token(aud="someone-else"), g.id_token(iss="https://evil.example"),
           g.id_token(exp=int(time.time()) - 3600), g.id_token(sub=None),
           g.id_token(key=RSAKey.generate_key(2048, parameters={"kid": "k1"})),   # right kid, wrong key
           g.id_token() + "x", "not.a.jwt"]
    for t in bad:
        with pytest.raises(auth.AuthError):
            google.verify(t)
    with pytest.raises(auth.AuthError):
        google.verify(g.id_token(nonce="n1"), nonce="n2")
    with pytest.raises(auth.AuthError):          # no nonce in the token but one expected
        google.verify(g.id_token(), nonce="n1")


def test_verify_rejects_unsigned_and_hmac_tokens(g):
    google = auth.Google(CLIENT_ID, "shh", "https://x/cb", http=g)
    claims = {"iss": "https://accounts.google.com", "aud": CLIENT_ID, "sub": "s", "exp": int(time.time()) + 60}
    head = base64.urlsafe_b64encode(b'{"alg":"none","kid":"k1"}').rstrip(b"=").decode()
    body = base64.urlsafe_b64encode(str(claims).replace("'", '"').encode()).rstrip(b"=").decode()
    with pytest.raises(auth.AuthError):
        google.verify(f"{head}.{body}.")
    from joserfc.jwk import OctKey
    with pytest.raises(auth.AuthError):
        google.verify(jwt.encode({"alg": "HS256", "kid": "k1"}, claims, OctKey.import_key("x" * 32)))


def test_unknown_key_id_refetches_googles_keys_once(g):
    now = [1000.0]
    google = auth.Google(CLIENT_ID, "shh", "https://x/cb", http=g, now=lambda: now[0])
    google._key_set()
    rotated = RSAKey.generate_key(2048, parameters={"kid": "k2"})
    g.published = [g.key, rotated]
    now[0] += 120
    tok = jwt.encode({"alg": "RS256", "kid": "k2"}, {"iss": "https://accounts.google.com", "aud": CLIENT_ID,
                                                     "sub": "s", "exp": int(time.time()) + 60}, rotated)
    assert google.verify(tok)["sub"] == "s" and g.jwks_calls == 2


def test_pkce_and_safe_next():
    v = "a" * 64
    assert auth.pkce_challenge(v) == base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).rstrip(b"=").decode()
    for bad in ("https://evil.example", "//evil.example/x", "evil", "/\\evil", None, ""):
        assert auth.safe_next(bad) == "/"
    assert auth.safe_next("/week?x=1") == "/week?x=1"


def test_home_network_addresses():
    nets = auth.networks("127.0.0.1/32,192.168.1.0/24,10.8.0.0/24")
    for ok in ("127.0.0.1", "192.168.1.50", "10.8.0.7", "::ffff:192.168.1.5"):
        assert auth.address_in(ok, nets), ok
    for no in ("192.168.2.1", "203.0.113.5", "172.18.0.2", "::1", "testclient", "", None, "10.8.1.1"):
        assert not auth.address_in(no, nets), no


# --- the shared token ---------------------------------------------------------------------------------------------

def test_shared_token_only_from_the_home_network(conn):
    assert make(conn).get("/recipes", headers=H).status_code == 200                       # 127.0.0.1
    assert make(conn, client=("192.168.1.23", 1)).get("/recipes", headers=H).status_code == 200
    assert make(conn, client=("10.8.0.4", 1)).get("/recipes", headers=H).status_code == 200
    r = make(conn, client=("203.0.113.5", 1)).get("/recipes", headers=H)
    assert r.status_code == 401 and "home network" in r.json()["detail"]
    assert make(conn, client=("172.18.0.3", 1)).get("/recipes", headers=H).status_code == 401   # e.g. via Caddy
    s = Settings(dsn="unused", token="secret", provider="fake", legacy_networks="203.0.113.0/24")
    c = TestClient(create_app(s, provider=object(), pcx=object(), conn=conn), client=("203.0.113.5", 1))
    assert c.get("/recipes", headers=H).status_code == 200


def test_no_or_wrong_credentials_are_401(conn):
    c = make(conn)
    assert c.get("/recipes").status_code == 401
    assert c.get("/recipes", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert c.get("/recipes", headers={"Cookie": f"{auth.COOKIE}=nope"}).status_code == 401
    assert c.get("/auth/me").status_code == 401
    assert c.get("/health").status_code == 200


def test_shared_token_acts_as_the_legacy_member(conn):
    hid = db.first_household(conn)
    c = make(conn)
    assert set(c.get("/auth/me", headers=H).json()["permissions"]) == set(households.all_permissions(conn))
    owner = households.add_member(conn, hid, "Owner", "account", roles=["owner", "approver", "planner", "contributor"])
    kid = households.add_member(conn, hid, "Reader", "account", roles=["contributor"])
    me = c.get("/auth/me", headers=H).json()
    assert me["via"] == "legacy" and me["member_id"] == owner          # no legacy member set: the first Owner
    conn.execute("UPDATE households SET legacy_member_id=%s WHERE id=%s", (kid, hid))
    assert c.get("/recipes", headers=H).status_code == 200
    assert c.get("/cart/default-week", headers=H).status_code == 403   # a Contributor can't build carts
    assert c.post("/auth/logout", headers=H).status_code == 204
    assert c.put("/auth/household", headers=H, json={"household_id": hid}).status_code == 403


# --- Google not configured ----------------------------------------------------------------------------------------

def test_sign_in_routes_are_503_without_a_google_client(conn, monkeypatch):
    for v in ("MEALPREP_GOOGLE_CLIENT_ID", "MEALPREP_GOOGLE_CLIENT_SECRET"):   # a server's .env may set them
        monkeypatch.delenv(v, raising=False)
    c = make(conn)
    assert c.get("/auth/login", follow_redirects=False).status_code == 503
    assert c.get("/auth/callback", params={"code": "x", "state": "y"}).status_code == 503
    assert c.post("/auth/google", json={"id_token": "x" * 30}).status_code == 503
    assert c.get("/recipes", headers=H).status_code == 200


# --- browser sign-in ----------------------------------------------------------------------------------------------

def test_login_redirects_to_google_with_pkce_and_minimal_scopes(conn, g):
    r = make(conn, g).get("/auth/login", params={"next": "/week"}, follow_redirects=False)
    assert r.status_code == 302
    u = urlparse(r.headers["location"])
    q = {k: v[0] for k, v in parse_qs(u.query).items()}
    assert f"{u.scheme}://{u.netloc}{u.path}" == auth.Google.AUTH
    assert q["scope"] == "openid email profile" and q["response_type"] == "code"
    assert q["code_challenge_method"] == "S256" and len(q["code_challenge"]) == 43
    assert q["client_id"] == CLIENT_ID and q["redirect_uri"] == "https://mealprep.example/api/auth/callback"
    assert q["state"] and q["nonce"]
    stored = conn.execute("SELECT state_hash, next FROM login_states").fetchall()
    assert stored == [(auth.hash_token(q["state"]), "/week")]          # the state is stored hashed


def test_member_signs_in_with_a_cookie_session(conn, g):
    hid, mid = household(conn, owner_email="A@Example.com")             # claimable by its verified email
    c = make(conn, g)
    r = browser_login(c, g, "/auth/login?next=/week")
    assert r.status_code == 303 and r.headers["location"] == "/week"
    sc = r.headers["set-cookie"]
    assert sc.startswith(auth.COOKIE + "=") and "HttpOnly" in sc and "Secure" in sc and "samesite=lax" in sc.lower()
    assert "Path=/" in sc
    token = cookie_of(r)
    assert conn.execute("SELECT count(*) FROM sessions WHERE id_hash=%s", (auth.hash_token(token),)).fetchone()[0] == 1
    assert conn.execute("SELECT count(*) FROM sessions WHERE id_hash=%s", (token,)).fetchone()[0] == 0
    assert conn.execute("SELECT user_id IS NOT NULL, email FROM members WHERE id=%s", (mid,)).fetchone() == (True, None)
    me = c.get("/auth/me", headers={"Cookie": f"{auth.COOKIE}={token}"}).json()
    assert me["via"] == "cookie" and me["household"]["id"] == hid and me["member_id"] == mid
    assert me["user"]["email"] == "a@example.com" and me["csrf_token"] == auth.csrf_token(token)
    assert "cart.send" in me["permissions"]
    # reads with the cookie alone; writes need the CSRF header
    ck = {"Cookie": f"{auth.COOKIE}={token}"}
    assert c.get("/staples", headers=ck).status_code == 200
    assert c.post("/staples", headers=ck, json={"name": "milk"}).status_code == 403
    assert c.post("/staples", json={"name": "milk"}, headers={**ck, auth.CSRF_HEADER: "wrong"}).status_code == 403
    r = c.post("/staples", json={"name": "milk"}, headers={**ck, auth.CSRF_HEADER: me["csrf_token"]})
    assert r.status_code == 201
    # the staple belongs to this household, not the first one
    with db.unscoped(conn):
        assert conn.execute("SELECT household_id FROM staples").fetchall() == [(hid,)]
    # logout needs CSRF too, then the session is gone
    assert c.post("/auth/logout", headers=ck).status_code == 403
    r = c.post("/auth/logout", headers={**ck, auth.CSRF_HEADER: me["csrf_token"]})
    assert r.status_code == 204 and auth.COOKIE in r.headers["set-cookie"]
    assert c.get("/staples", headers=ck).status_code == 401


def test_state_is_single_use_and_a_wrong_verifier_fails(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    r = c.get("/auth/login", follow_redirects=False)
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    code = g.authorize(r.headers["location"])
    assert c.get("/auth/callback", params={"code": code, "state": state}, follow_redirects=False).status_code == 303
    code2 = g.authorize(r.headers["location"])
    r2 = c.get("/auth/callback", params={"code": code2, "state": state})
    assert r2.status_code == 400 and "expired" in r2.text
    assert c.get("/auth/callback", params={"error": "access_denied"}).status_code == 400
    # a code made for another login's challenge doesn't pass PKCE
    a = c.get("/auth/login", follow_redirects=False)
    b = c.get("/auth/login", follow_redirects=False)
    code_b = g.authorize(b.headers["location"])
    state_a = parse_qs(urlparse(a.headers["location"]).query)["state"][0]
    assert c.get("/auth/callback", params={"code": code_b, "state": state_a}).status_code == 400


def test_expired_login_state_fails(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    r = c.get("/auth/login", follow_redirects=False)
    conn.execute("UPDATE login_states SET expires_at = now() - interval '1 second'")
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    assert c.get("/auth/callback", params={"code": g.authorize(r.headers["location"]), "state": state}).status_code == 400


def test_a_stranger_is_told_to_ask_for_an_invite_and_nothing_is_stored(conn, g):
    household(conn, owner_email="someone@example.com")
    c = make(conn, g)
    r = browser_login(c, g, email="stranger@example.com", sub="sub-x")
    assert r.status_code == 403 and "Ask your household head for an invite" in r.text
    assert "set-cookie" not in r.headers
    assert conn.execute("SELECT count(*) FROM users").fetchone()[0] == 0
    r = c.post("/auth/google", json={"id_token": g.id_token(email="stranger@example.com", sub="sub-x")})
    assert r.status_code == 403 and "invite" in r.json()["detail"]


def test_an_unverified_email_claims_nothing(conn, g):
    household(conn, owner_email="a@example.com")
    r = browser_login(make(conn, g), g, email_verified=False)
    assert r.status_code == 403


# --- app (bearer) sessions ------------------------------------------------------------------------------------------

def test_android_id_token_gets_a_bearer_session(conn, g):
    hid, mid = household(conn, owner_email="a@example.com")
    c = make(conn, g, client=("203.0.113.5", 1))                          # works from anywhere
    r = c.post("/auth/google", json={"id_token": g.id_token()})
    assert r.status_code == 200 and r.json()["household_id"] == hid
    bearer = {"Authorization": f"Bearer {r.json()['token']}"}
    assert c.get("/recipes", headers=bearer).status_code == 200
    assert c.post("/staples", headers=bearer, json={"name": "eggs"}).status_code == 201   # no CSRF for bearer
    me = c.get("/auth/me", headers=bearer).json()
    assert me["via"] == "bearer" and "csrf_token" not in me
    assert c.post("/auth/google", json={"id_token": g.id_token(aud="other")}).status_code == 401
    assert c.post("/auth/logout", headers=bearer).status_code == 204
    assert c.get("/recipes", headers=bearer).status_code == 401


def test_expired_sessions_are_refused(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    tok = c.post("/auth/google", json={"id_token": g.id_token()}).json()["token"]
    conn.execute("UPDATE sessions SET expires_at = now() - interval '1 minute'")
    assert c.get("/recipes", headers={"Authorization": f"Bearer {tok}"}).status_code == 401


def test_household_switcher_and_non_members_get_404(conn, g):
    h1, m1 = household(conn, "One", owner_email="a@example.com")
    h2, _ = household(conn, "Two")
    inv = households.create_invite(conn, h2, None, roles=["contributor"])[1]
    h3, _ = household(conn, "Three")
    c = make(conn, g)
    tok = c.post("/auth/google", json={"id_token": g.id_token()}).json()["token"]
    b = {"Authorization": f"Bearer {tok}"}
    assert c.post("/invites/accept", headers=b, json={"token": inv}).json() == {"household_id": h2}
    me = c.get("/auth/me", headers=b).json()
    assert [h["id"] for h in me["households"]] == [h1, h2] and me["household"]["id"] == h2
    assert me["roles"] == ["contributor"]
    assert c.get("/cart/default-week", headers=b).status_code == 403                 # Contributor in Two
    assert c.get("/cart/default-week", headers={**b, "X-Household": str(h1)}).status_code == 200
    assert c.get("/recipes", headers={**b, "X-Household": str(h3)}).status_code == 404
    assert c.get("/recipes", headers={**b, "X-Household": "abc"}).status_code == 404
    assert c.put("/auth/household", headers=b, json={"household_id": h3}).status_code == 404
    assert c.put("/auth/household", headers=b, json={"household_id": h1}).status_code == 200
    assert c.get("/cart/default-week", headers=b).status_code == 200
    conn.execute("UPDATE households SET status='suspended' WHERE id=%s", (h1,))
    assert c.get("/recipes", headers={**b, "X-Household": str(h1)}).status_code == 404


# --- invites ------------------------------------------------------------------------------------------------------

def test_invite_link_joins_with_its_roles_and_is_single_use(conn, g):
    hid, owner = household(conn)
    iid, token, _ = households.create_invite(conn, hid, owner, roles=["planner", "contributor"])
    assert conn.execute("SELECT count(*) FROM invites WHERE token_hash=%s", (auth.hash_token(token),)).fetchone()[0] == 1
    c = make(conn, g)
    r = browser_login(c, g, f"/auth/login?invite={token}", email="new@example.com", sub="sub-new", name="Newt")
    assert r.status_code == 303
    m = [x for x in households.list_members(conn, hid) if x["name"] == "Newt"]
    assert m and m[0]["roles"] == ["planner", "contributor"] and m[0]["signed_in"]
    assert households.list_invites(conn, hid)[0]["status"] == "used"
    r = browser_login(c, g, f"/auth/login?invite={token}", email="other@example.com", sub="sub-o")
    assert r.status_code == 400 and "valid any more" in r.text
    assert conn.execute("SELECT count(*) FROM users WHERE google_sub='sub-o'").fetchone()[0] == 0


def test_email_bound_expired_and_revoked_invites(conn, g):
    hid, owner = household(conn)
    _, bound, _ = households.create_invite(conn, hid, owner, email="Fi@Example.com")
    c = make(conn, g)
    r = c.post("/auth/google", json={"id_token": g.id_token(email="wrong@example.com", sub="s2"), "invite": bound})
    assert r.status_code == 400 and "another Google account" in r.json()["detail"]
    r = c.post("/auth/google", json={"id_token": g.id_token(email="fi@example.com", sub="s3"), "invite": bound})
    assert r.status_code == 200 and r.json()["household_id"] == hid
    iid, old, _ = households.create_invite(conn, hid, owner)
    conn.execute("UPDATE invites SET expires_at = now() - interval '1 second' WHERE id=%s", (iid,))
    assert c.post("/auth/google", json={"id_token": g.id_token(sub="s4"), "invite": old}).status_code == 400
    iid, rev, _ = households.create_invite(conn, hid, owner)
    households.revoke_invite(conn, hid, iid, owner)
    assert c.post("/auth/google", json={"id_token": g.id_token(sub="s5"), "invite": rev}).status_code == 400


def test_invite_claims_a_seeded_member_and_converts_a_child(conn, g):
    hid, owner = household(conn)
    teen = households.add_member(conn, hid, "Teen", "account", birth_year=2013)
    kid = households.add_member(conn, hid, "Kid", "child", birth_year=2016)
    _, t1, _ = households.create_invite(conn, hid, owner, member_id=teen)
    _, t2, _ = households.create_invite(conn, hid, owner, member_id=kid, roles=["contributor"])
    c = make(conn, g)
    assert c.post("/auth/google", json={"id_token": g.id_token(sub="s-teen", email="t@example.com"),
                                         "invite": t1}).status_code == 200
    assert c.post("/auth/google", json={"id_token": g.id_token(sub="s-kid", email="k@example.com"),
                                         "invite": t2}).status_code == 200
    ms = {m["id"]: m for m in households.list_members(conn, hid)}
    assert ms[teen]["signed_in"] and ms[teen]["roles"] == ["contributor"] and ms[teen]["birth_year"] == 2013
    assert ms[kid]["kind"] == "account" and ms[kid]["roles"] == ["contributor"] and ms[kid]["signed_in"]
    assert len(ms) == 3                                               # claimed, not added


def test_invite_endpoints_need_members_manage(conn, g):
    hid, owner = household(conn, owner_email="a@example.com")
    c = make(conn, g)
    b = {"Authorization": "Bearer " + c.post("/auth/google", json={"id_token": g.id_token()}).json()["token"]}
    r = c.post("/household/invites", headers=b, json={"roles": ["planner"], "email": "x@example.com"})
    assert r.status_code == 201
    body = r.json()
    assert body["url"].startswith("https://mealprep.example/api/auth/login?invite=") and body["token"] in body["url"]
    assert c.get("/household/invites", headers=b).json()[0]["status"] == "open"
    assert "token" not in c.get("/household/invites", headers=b).json()[0]
    assert c.delete(f"/household/invites/{body['id']}", headers=b).status_code == 204
    assert c.get("/household/invites", headers=b).json()[0]["status"] == "revoked"
    assert c.post("/household/invites", headers=b, json={"roles": ["child"]}).status_code == 409
    households.set_roles(conn, hid, households.add_member(conn, hid, "Second", "account",
                                                          roles=["owner", "approver"]), ["owner", "approver"])
    households.set_roles(conn, hid, owner, ["contributor"])
    assert c.post("/household/invites", headers=b, json={}).status_code == 403


# --- Android: the same round trip in a Custom Tab, a one-time code for the app -----------------------------------

APP = "dev.mealprep.app"
VERIFIER = "v" * 20 + "erifier-held-by-the-app-only-0123456789"     # 59 chars, like the app's


def app_login(c, g, verifier=VERIFIER, params=None, **claims):
    """The app opens /auth/login in a Custom Tab; Google signs the user in; returns the callback's response."""
    q = {"client": "android", "app_challenge": auth.pkce_challenge(verifier), **(params or {})}
    r = c.get("/auth/login", params=q, follow_redirects=False)
    assert r.status_code == 302, r.text
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    return c.get("/auth/callback", params={"code": g.authorize(r.headers["location"], **claims), "state": state},
                 follow_redirects=False)


def returned(r):
    """The app return URL's scheme and query."""
    assert r.status_code == 303 and "set-cookie" not in r.headers
    u = urlparse(r.headers["location"])
    assert u.netloc == "auth" and u.path == ""
    return u.scheme, {k: v[0] for k, v in parse_qs(u.query).items()}


def test_app_sign_in_hands_a_one_time_code_to_the_app_not_a_cookie(conn, g):
    hid, mid = household(conn, owner_email="a@example.com")
    c = make(conn, g, client=("203.0.113.5", 1))                          # works away from home
    r = app_login(c, g)
    scheme, q = returned(r)
    assert scheme == APP and set(q) == {"code"} and r.headers["cache-control"] == "no-store"
    assert conn.execute("SELECT count(*) FROM sessions").fetchone()[0] == 0           # no session until redeemed
    assert conn.execute("SELECT code_hash FROM app_codes").fetchall() == [(auth.hash_token(q["code"]),)]
    t = c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER})
    assert t.status_code == 200 and t.json()["household_id"] == hid and t.json()["expires_at"]
    assert "set-cookie" not in t.headers
    b = {"Authorization": f"Bearer {t.json()['token']}"}
    me = c.get("/auth/me", headers=b).json()
    assert me["via"] == "bearer" and me["member_id"] == mid and "cart.send" in me["permissions"]
    assert conn.execute("SELECT kind FROM sessions").fetchall() == [("app",)]
    # single use
    again = c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER})
    assert again.status_code == 400 and "expired" in again.json()["detail"]
    # an app session is no cookie session, and a web cookie is no bearer token
    assert c.get("/recipes", headers={"Cookie": f"{auth.COOKIE}={t.json()['token']}"}).status_code == 401
    web = cookie_of(browser_login(c, g))
    assert c.get("/recipes", headers={"Cookie": f"{auth.COOKIE}={web}"}).status_code == 200
    assert c.get("/recipes", headers={"Authorization": f"Bearer {web}"}).status_code == 401


def test_app_code_needs_the_verifier_and_any_wrong_try_uses_it_up(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    _, q = returned(app_login(c, g))
    r = c.post("/auth/app-token", json={"code": q["code"], "verifier": "w" * 59})
    assert r.status_code == 400 and "didn't come from this app" in r.json()["detail"]
    assert c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER}).status_code == 400
    assert c.post("/auth/app-token", json={"code": "x" * 40, "verifier": VERIFIER}).status_code == 400
    assert c.post("/auth/app-token", json={"code": "short", "verifier": VERIFIER}).status_code == 422
    assert conn.execute("SELECT count(*) FROM sessions").fetchone()[0] == 0


def test_app_code_expires(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    _, q = returned(app_login(c, g))
    assert conn.execute("SELECT expires_at - created_at <= interval '2 minutes' FROM app_codes").fetchone()[0]
    conn.execute("UPDATE app_codes SET expires_at = now() - interval '1 second'")
    assert c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER}).status_code == 400


def test_app_login_state_is_single_use_and_a_web_state_never_reaches_the_app(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    r = c.get("/auth/login", params={"client": "android", "app_challenge": auth.pkce_challenge(VERIFIER)},
              follow_redirects=False)
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    assert returned(c.get("/auth/callback", params={"code": g.authorize(r.headers["location"]), "state": state},
                          follow_redirects=False))[1]["code"]
    r2 = c.get("/auth/callback", params={"code": g.authorize(r.headers["location"]), "state": state})
    assert r2.status_code == 400 and "expired" in r2.text and "set-cookie" not in r2.headers
    # a wrong state: the browser page, no code, no cookie
    r3 = c.get("/auth/callback", params={"code": "c", "state": "not-a-state"}, follow_redirects=False)
    assert r3.status_code == 400 and "location" not in r3.headers
    # a web sign-in ends with a cookie and makes no app code
    conn.execute("DELETE FROM app_codes")
    w = browser_login(c, g)
    assert w.status_code == 303 and w.headers["location"] == "/" and auth.COOKIE in w.headers["set-cookie"]
    assert conn.execute("SELECT count(*) FROM app_codes").fetchone()[0] == 0


def test_app_login_parameters_are_checked_and_the_return_url_is_never_the_callers(conn, g):
    household(conn, owner_email="a@example.com")
    c = make(conn, g)
    ch = auth.pkce_challenge(VERIFIER)
    bad = [{"client": "ios", "app_challenge": ch}, {"client": "android"}, {"client": "android", "app_challenge": "x"},
           {"client": "android", "app_challenge": ch[:-1] + "="}, {"client": "android", "app_challenge": ch + "a"},
           {"client": "android", "app_challenge": ch, "app": "com.evil.app"},
           {"client": "android", "app_challenge": ch, "app": "https"},
           {"app_challenge": ch}, {"app": APP}]
    for p in bad:
        assert c.get("/auth/login", params=p, follow_redirects=False).status_code == 422, p
    assert conn.execute("SELECT count(*) FROM login_states").fetchone()[0] == 0
    # next / redirect_uri can't move an app sign-in anywhere else; the debug build returns to its own scheme
    r = app_login(c, g, params={"app": APP + ".debug", "next": "https://evil.example/x",
                                "redirect_uri": "https://evil.example/cb"})
    scheme, q = returned(r)
    assert scheme == APP + ".debug" and "evil" not in r.headers["location"]
    assert c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER}).status_code == 200
    # what Google is sent stays the registered https callback
    loc = c.get("/auth/login", params={"client": "android", "app_challenge": ch}, follow_redirects=False).headers["location"]
    assert parse_qs(urlparse(loc).query)["redirect_uri"] == ["https://mealprep.example/api/auth/callback"]
    # the challenge given to Google is the server's own PKCE pair, not the app's
    assert parse_qs(urlparse(loc).query)["code_challenge"] != [ch]


def test_app_sign_in_errors_go_back_to_the_app(conn, g):
    household(conn, owner_email="someone@example.com")
    c = make(conn, g)
    scheme, q = returned(app_login(c, g, email="stranger@example.com", sub="sub-x"))
    assert scheme == APP and q["error"] == "not_invited" and "Ask your household head for an invite" in q["message"]
    assert "code" not in q and conn.execute("SELECT count(*) FROM users").fetchone()[0] == 0
    # cancelled at Google
    r = c.get("/auth/login", params={"client": "android", "app_challenge": auth.pkce_challenge(VERIFIER)},
              follow_redirects=False)
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    _, q = returned(c.get("/auth/callback", params={"error": "access_denied", "state": state}, follow_redirects=False))
    assert q == {"error": "cancelled"}
    # a used invite
    hid, owner = household(conn, "Other")
    _, token, _ = households.create_invite(conn, hid, owner)
    conn.execute("UPDATE invites SET revoked_at = now()")
    _, q = returned(app_login(c, g, params={"invite": token}, email="new@example.com", sub="sub-n"))
    assert q["error"] == "failed" and q["message"]


def test_app_sign_in_with_an_invite_joins_the_household(conn, g):
    hid, owner = household(conn)
    _, token, _ = households.create_invite(conn, hid, owner, roles=["contributor"])
    c = make(conn, g)
    _, q = returned(app_login(c, g, params={"invite": token}, email="new@example.com", sub="sub-new", name="Newt"))
    t = c.post("/auth/app-token", json={"code": q["code"], "verifier": VERIFIER}).json()
    me = c.get("/auth/me", headers={"Authorization": f"Bearer {t['token']}"}).json()
    assert me["household"]["id"] == hid and me["roles"] == ["contributor"] and "cart.build" not in me["permissions"]


def test_app_token_is_503_without_a_google_client(conn, monkeypatch):
    for v in ("MEALPREP_GOOGLE_CLIENT_ID", "MEALPREP_GOOGLE_CLIENT_SECRET"):
        monkeypatch.delenv(v, raising=False)
    assert make(conn).post("/auth/app-token", json={"code": "x" * 40, "verifier": VERIFIER}).status_code == 503
