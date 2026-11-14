"""Weeks start on Monday (2026-11-14): an Android app that still works out Sunday weeks (OkHttp, no
`X-Week-Start: monday`) gets 426 and a sentence to update, except for /health and sign-in; other clients are unaffected."""
from mealprep import api as api_mod
from test_api import H, client

OLD_APP = {**H, "User-Agent": "okhttp/4.12.0"}
NEW_APP = {**OLD_APP, "X-Week-Start": "monday"}


def test_an_old_android_app_is_told_to_update(conn):
    c = client(conn)
    for method, url in [("GET", "/weeks/2026-10-12"), ("GET", "/recipes"), ("GET", "/weeks"),
                        ("PATCH", "/plan/1"), ("GET", "/ratings/pending")]:
        r = c.request(method, url, headers=OLD_APP, json={"day": 1} if method == "PATCH" else None)
        assert r.status_code == 426, (method, url)
        assert r.json() == {"detail": api_mod.OLD_WEEK_APP}
    assert "Monday" in api_mod.OLD_WEEK_APP
    # any capitalisation of OkHttp; a header with another value is still an old app
    assert c.get("/recipes", headers={**H, "User-Agent": "OkHttp/3.14.9"}).status_code == 426
    assert c.get("/recipes", headers={**OLD_APP, "X-Week-Start": "sunday"}).status_code == 426
    assert c.get("/recipes", headers={**OLD_APP, "X-Week-Start": ""}).status_code == 426
    # before auth: an old app without a token still gets the update message, not 401
    assert c.get("/recipes", headers={"User-Agent": "okhttp/4.12.0"}).status_code == 426
    assert conn.execute("SELECT count(*) FROM plan").fetchone()[0] == 0


def test_a_new_android_app_works_as_before(conn):
    c = client(conn)
    assert c.get("/weeks/2026-10-12", headers=NEW_APP).json() == []
    assert c.get("/recipes", headers={**NEW_APP, "X-Week-Start": "Monday"}).status_code == 200
    assert c.get("/recipes", headers={"User-Agent": "okhttp/4.12.0", "X-Week-Start": "monday"}).status_code == 401


def test_health_and_sign_in_stay_open_to_an_old_app(conn):
    c = client(conn)
    assert c.get("/health", headers=OLD_APP).json() == {"ok": True, "provider": "fake"}
    me = c.get("/auth/me", headers={"User-Agent": "okhttp/4.12.0"})
    assert me.status_code != 426 and me.status_code == c.get("/auth/me").status_code


def test_other_clients_are_unaffected(conn):
    c = client(conn)
    for ua in ("testclient", "Mozilla/5.0 (Linux; Android 14) Chrome/131.0", "python-httpx/0.27.0", "curl/8.5.0"):
        assert c.get("/weeks/2026-10-12", headers={**H, "User-Agent": ua}).status_code == 200, ua
    assert c.get("/weeks/2026-10-12", headers=H).status_code == 200      # no User-Agent override
