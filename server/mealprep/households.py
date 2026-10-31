"""Households, members, roles and invites (server tasks 1–2).

A member is one person in a household: an `account` (13+, signs in with Google; user_id is null until they first
sign in) or a `child` profile (no sign-in). Roles come from the roles table; the rules kept here:
- Child is a child profile's only role, and only a child profile can hold it (also a trigger in schema.sql).
- Every household keeps at least one Owner and one Approver/Purchaser: a change that would remove the last one is
  refused.
- Sign-in is invite-only: a Google account gets in through a membership, an unlinked account member with its
  (verified) email address, or an invite.
These functions take explicit household ids (the identity tables aren't household-filtered) and run on an unscoped
or scoped connection alike.
"""
from datetime import datetime, timedelta, timezone

from psycopg.types.json import Jsonb

from . import auth
from .rbac import APPROVER, CHILD, OWNER

INVITE_DAYS = 14
NOT_INVITED = "This Google account isn't in a household yet. Ask your household head for an invite."


class RuleError(Exception):
    """A change the household rules don't allow; the message is safe to show (HTTP 409/422)."""


class NotFound(Exception):
    pass


class InviteError(Exception):
    pass


class NotInvited(Exception):
    pass


def audit(conn, household_id, action: str, target: str | None = None, member_id: int | None = None,
          user_id: int | None = None, detail: dict | None = None) -> None:
    conn.execute("INSERT INTO audit_log(household_id, member_id, user_id, action, target, detail) "
                 "VALUES(%s,%s,%s,%s,%s,%s)", (household_id, member_id, user_id, action, target,
                                               Jsonb(detail) if detail is not None else None))


# --- reading ---

def role_ids(conn) -> dict[str, tuple[int, str]]:
    """{key: (id, for_kind)}"""
    return {k: (i, kind) for i, k, kind in conn.execute("SELECT id, key, for_kind FROM roles")}


def permissions(conn, member_id: int) -> frozenset[str]:
    return frozenset(p for (p,) in conn.execute(
        "SELECT DISTINCT rp.permission FROM member_roles mr JOIN role_permissions rp ON rp.role_id = mr.role_id "
        "WHERE mr.member_id=%s", (member_id,)))


def all_permissions(conn) -> frozenset[str]:
    return frozenset(p for (p,) in conn.execute("SELECT DISTINCT permission FROM role_permissions"))


def member_roles(conn, member_id: int) -> list[str]:
    return [k for (k,) in conn.execute("SELECT r.key FROM member_roles mr JOIN roles r ON r.id = mr.role_id "
                                       "WHERE mr.member_id=%s ORDER BY r.id", (member_id,))]


def get_household(conn, household_id: int) -> dict | None:
    row = conn.execute("SELECT id, name, store_id, plan, status, ai_daily_budget FROM households WHERE id=%s",
                       (household_id,)).fetchone()
    if row is None:
        return None
    return dict(zip(("id", "name", "store_id", "plan", "status", "ai_daily_budget"), row))


def membership(conn, user_id: int, household_id: int) -> int | None:
    """The user's member id in an active household, or None."""
    row = conn.execute("SELECT m.id FROM members m JOIN households h ON h.id = m.household_id "
                       "WHERE m.user_id=%s AND m.household_id=%s AND h.status = 'active'",
                       (user_id, household_id)).fetchone()
    return row[0] if row else None


def user_households(conn, user_id: int) -> list[dict]:
    """The user's active households (oldest membership first) for the switcher."""
    rows = conn.execute("SELECT h.id, h.name, m.id FROM members m JOIN households h ON h.id = m.household_id "
                        "WHERE m.user_id=%s AND h.status = 'active' ORDER BY m.joined_at, m.id", (user_id,))
    return [{"id": h, "name": n, "member_id": m} for h, n, m in rows]


def legacy_household(conn) -> int:
    """The household the shared token acts for: the one that names a legacy member, else the first."""
    row = conn.execute("SELECT id FROM households WHERE legacy_member_id IS NOT NULL ORDER BY id LIMIT 1").fetchone()
    return row[0] if row else conn.execute("SELECT min(id) FROM households").fetchone()[0]


def legacy_member(conn, household_id: int) -> int | None:
    """The member the shared token acts as: households.legacy_member_id, else the household's first Owner; None when
    the household has no members yet (then the token keeps every permission, as before households)."""
    row = conn.execute(
        "SELECT COALESCE(h.legacy_member_id, (SELECT m.id FROM members m JOIN member_roles mr ON mr.member_id = m.id "
        "JOIN roles r ON r.id = mr.role_id WHERE m.household_id = h.id AND r.key = %s ORDER BY m.id LIMIT 1)) "
        "FROM households h WHERE h.id=%s", (OWNER, household_id)).fetchone()
    return row[0] if row else None


def list_members(conn, household_id: int) -> list[dict]:
    rows = conn.execute(
        "SELECT m.id, m.display_name, m.kind, m.birth_year, m.user_id IS NOT NULL, m.email IS NOT NULL, m.joined_at, "
        "COALESCE(array_agg(r.key ORDER BY r.id) FILTER (WHERE r.key IS NOT NULL), '{}') "
        "FROM members m LEFT JOIN member_roles mr ON mr.member_id = m.id LEFT JOIN roles r ON r.id = mr.role_id "
        "WHERE m.household_id=%s GROUP BY m.id ORDER BY m.joined_at, m.id", (household_id,))
    return [{"id": i, "name": n, "kind": k, "birth_year": b, "signed_in": linked, "email_set": has_email,
             "joined_at": j.isoformat(), "roles": list(roles)} for i, n, k, b, linked, has_email, j, roles in rows]


def _member(conn, household_id: int, member_id: int, lock: bool = False):
    row = conn.execute("SELECT id, kind, user_id FROM members WHERE id=%s AND household_id=%s"
                       + (" FOR UPDATE" if lock else ""), (member_id, household_id)).fetchone()
    if row is None:
        raise NotFound(f"member {member_id}")
    return row


# --- the rules ---

def _counts(conn, household_id: int) -> tuple[int, int]:
    row = conn.execute(
        "SELECT count(DISTINCT m.id) FILTER (WHERE r.key = %s), count(DISTINCT m.id) FILTER (WHERE r.key = %s) "
        "FROM members m JOIN member_roles mr ON mr.member_id = m.id JOIN roles r ON r.id = mr.role_id "
        "WHERE m.household_id = %s", (OWNER, APPROVER, household_id)).fetchone()
    return row[0], row[1]


def _keep_minimums(conn, household_id: int, before: tuple[int, int]) -> None:
    owners, approvers = _counts(conn, household_id)
    if before[0] > 0 and owners == 0:
        raise RuleError("A household needs at least one Owner.")
    if before[1] > 0 and approvers == 0:
        raise RuleError("A household needs at least one Approver / Purchaser.")


def _check_roles(conn, kind: str, keys: list[str]) -> list[int]:
    roles = role_ids(conn)
    unknown = [k for k in keys if k not in roles]
    if unknown:
        raise RuleError(f"Unknown role: {', '.join(unknown)}.")
    if kind == "child":
        if set(keys) != {CHILD}:
            raise RuleError("A child profile has the Child role and no other.")
    elif CHILD in keys:
        raise RuleError("The Child role is only for child profiles.")
    return [roles[k][0] for k in dict.fromkeys(keys)]


def _lock_household(conn, household_id: int) -> None:
    # serialises role changes in one household, so two owners can't each remove the other
    if conn.execute("SELECT 1 FROM households WHERE id=%s FOR UPDATE", (household_id,)).fetchone() is None:
        raise NotFound(f"household {household_id}")


def set_roles(conn, household_id: int, member_id: int, keys: list[str], by_member: int | None = None) -> list[str]:
    with conn.transaction():
        _lock_household(conn, household_id)
        _, kind, _ = _member(conn, household_id, member_id, lock=True)
        ids = _check_roles(conn, kind, keys)
        before, old = _counts(conn, household_id), member_roles(conn, member_id)
        conn.execute("DELETE FROM member_roles WHERE member_id=%s", (member_id,))
        for rid in ids:
            conn.execute("INSERT INTO member_roles(member_id, role_id) VALUES(%s,%s)", (member_id, rid))
        _keep_minimums(conn, household_id, before)
        new = member_roles(conn, member_id)
        audit(conn, household_id, "roles.set", f"member:{member_id}", by_member, detail={"from": old, "to": new})
    return new


def add_member(conn, household_id: int, name: str, kind: str, birth_year: int | None = None,
               email: str | None = None, roles: list[str] | None = None, by_member: int | None = None) -> int:
    """A child profile (Child role) or an account member nobody has signed in as yet (claimed later by its email
    or an invite with member_id). Accounts default to Contributor."""
    name = " ".join((name or "").split())
    if not name or len(name) > 60:
        raise RuleError("A member needs a name (up to 60 characters).")
    if kind == "child" and email:
        raise RuleError("A child profile doesn't sign in, so it has no email address.")
    roles = roles if roles is not None else ([CHILD] if kind == "child" else ["contributor"])
    with conn.transaction():
        _lock_household(conn, household_id)
        ids = _check_roles(conn, kind, roles)
        mid = conn.execute("INSERT INTO members(household_id, display_name, kind, birth_year, email) "
                           "VALUES(%s,%s,%s,%s,%s) RETURNING id",
                           (household_id, name, kind, birth_year, (email or "").strip().lower() or None)).fetchone()[0]
        for rid in ids:
            conn.execute("INSERT INTO member_roles(member_id, role_id) VALUES(%s,%s)", (mid, rid))
        audit(conn, household_id, "member.add", f"member:{mid}", by_member, detail={"kind": kind, "roles": roles})
    return mid


def update_member(conn, household_id: int, member_id: int, by_member: int | None = None, **fields) -> None:
    sets, vals = [], []
    for f in ("display_name", "birth_year", "email"):
        if f in fields:
            v = fields[f]
            if f == "display_name":
                v = " ".join((v or "").split())
                if not v or len(v) > 60:
                    raise RuleError("A member needs a name (up to 60 characters).")
            if f == "email":
                v = (v or "").strip().lower() or None
            sets.append(f"{f}=%s")
            vals.append(v)
    with conn.transaction():
        _, kind, user_id = _member(conn, household_id, member_id, lock=True)
        if "email" in fields and (kind == "child" or user_id is not None):
            raise RuleError("Only an account nobody has signed in as yet can have its email address changed.")
        if sets:
            conn.execute(f"UPDATE members SET {', '.join(sets)} WHERE id=%s", (*vals, member_id))
            audit(conn, household_id, "member.update", f"member:{member_id}", by_member,
                  detail={"fields": sorted(k for k in fields if k in ("display_name", "birth_year", "email"))})


def remove_member(conn, household_id: int, member_id: int, by_member: int | None = None) -> None:
    with conn.transaction():
        _lock_household(conn, household_id)
        _member(conn, household_id, member_id, lock=True)
        before = _counts(conn, household_id)
        conn.execute("UPDATE households SET legacy_member_id = NULL WHERE legacy_member_id=%s", (member_id,))
        conn.execute("DELETE FROM sessions WHERE household_id=%s AND user_id = "
                     "(SELECT user_id FROM members WHERE id=%s)", (household_id, member_id))
        conn.execute("DELETE FROM members WHERE id=%s", (member_id,))
        _keep_minimums(conn, household_id, before)
        audit(conn, household_id, "member.remove", f"member:{member_id}", by_member)


def update_household(conn, household_id: int, by_member: int | None = None, **fields) -> None:
    sets, vals = [], []
    if "name" in fields:
        name = " ".join((fields["name"] or "").split())
        if not name or len(name) > 80:
            raise RuleError("A household needs a name (up to 80 characters).")
        sets.append("name=%s"); vals.append(name)
    if "store_id" in fields:
        if not str(fields["store_id"] or "").isdigit():
            raise RuleError("store_id is a PC Express store number.")
        sets.append("store_id=%s"); vals.append(str(fields["store_id"]))
    if sets:
        conn.execute(f"UPDATE households SET {', '.join(sets)} WHERE id=%s", (*vals, household_id))
        audit(conn, household_id, "household.update", None, by_member, detail={"fields": sorted(fields)})


# --- invites ---

def create_invite(conn, household_id: int, by_member: int | None, email: str | None = None,
                  roles: list[str] | None = None, member_id: int | None = None, days: int = INVITE_DAYS,
                  may_assign: bool = True) -> tuple[int, str, datetime]:
    """A one-use invite: (id, token, expires_at). The token is shown once (it goes in the invite link) and stored
    hashed. email = only that Google account can use it; member_id = it claims that unlinked member (keeping their
    history; a child profile becomes an account). Without roles.assign (may_assign) an invite gives Contributor only."""
    roles = list(dict.fromkeys(roles or ["contributor"]))
    if not may_assign and roles != ["contributor"]:
        raise RuleError("Only someone who can assign roles can invite with other roles than Contributor.")
    _check_roles(conn, "account", roles)
    if not 1 <= days <= 60:
        raise RuleError("An invite lasts 1–60 days.")
    if member_id is not None:
        _, _, user_id = _member(conn, household_id, member_id)
        if user_id is not None:
            raise RuleError("That member already signs in.")
    token = auth.new_token()
    expires = datetime.now(timezone.utc) + timedelta(days=days)
    iid = conn.execute("INSERT INTO invites(household_id, email, roles, member_id, token_hash, created_by, expires_at) "
                       "VALUES(%s,%s,%s,%s,%s,%s,%s) RETURNING id",
                       (household_id, (email or "").strip().lower() or None, roles, member_id, auth.hash_token(token),
                        by_member, expires)).fetchone()[0]
    audit(conn, household_id, "invite.create", f"invite:{iid}", by_member,
          detail={"roles": roles, "email_bound": bool(email), "member_id": member_id})
    return iid, token, expires


def list_invites(conn, household_id: int) -> list[dict]:
    rows = conn.execute("SELECT id, email IS NOT NULL, roles, member_id, created_at, expires_at, used_at, revoked_at "
                        "FROM invites WHERE household_id=%s ORDER BY id DESC", (household_id,))
    now = datetime.now(timezone.utc)
    return [{"id": i, "email_bound": e, "roles": r, "member_id": m, "created_at": c.isoformat(),
             "expires_at": x.isoformat(),
             "status": "used" if u else "revoked" if rv else "expired" if x < now else "open"}
            for i, e, r, m, c, x, u, rv in rows]


def revoke_invite(conn, household_id: int, invite_id: int, by_member: int | None = None) -> None:
    n = conn.execute("UPDATE invites SET revoked_at = COALESCE(revoked_at, now()) WHERE id=%s AND household_id=%s",
                     (invite_id, household_id)).rowcount
    if not n:
        raise NotFound(f"invite {invite_id}")
    audit(conn, household_id, "invite.revoke", f"invite:{invite_id}", by_member)


def _open_invite(conn, invite_hash: str):
    row = conn.execute("SELECT id, household_id, email, roles, member_id, expires_at > now(), used_at, revoked_at "
                       "FROM invites WHERE token_hash=%s FOR UPDATE", (invite_hash,)).fetchone()
    if row is None or row[6] is not None or row[7] is not None or not row[5]:
        raise InviteError("This invite link isn't valid any more. Ask for a new one.")
    return row


def _use_invite(conn, invite_hash: str, user_id: int, email: str | None, name: str | None) -> int:
    """Join the invite's household (or claim its member); returns the household id."""
    iid, hid, bound, roles, member_id, *_ = _open_invite(conn, invite_hash)
    if bound and bound != (email or "").lower():
        raise InviteError("This invite is for another Google account.")
    existing = membership(conn, user_id, hid)
    if existing is not None:
        mid = existing
    elif member_id is not None:
        row = conn.execute("SELECT kind, user_id FROM members WHERE id=%s FOR UPDATE", (member_id,)).fetchone()
        if row is None or row[1] is not None:
            raise InviteError("This invite link isn't valid any more. Ask for a new one.")
        if row[0] == "child":   # turning 13: the profile becomes an account and keeps its history
            conn.execute("DELETE FROM member_roles WHERE member_id=%s", (member_id,))
            conn.execute("UPDATE members SET kind='account' WHERE id=%s", (member_id,))
        conn.execute("UPDATE members SET user_id=%s, email=NULL WHERE id=%s", (user_id, member_id))
        mid = member_id
    else:
        display = " ".join((name or (email or "").split("@")[0] or "New member").split())[:60]
        mid = conn.execute("INSERT INTO members(household_id, user_id, display_name, kind) VALUES(%s,%s,%s,'account') "
                           "RETURNING id", (hid, user_id, display)).fetchone()[0]
    for rid in _check_roles(conn, "account", roles):
        conn.execute("INSERT INTO member_roles(member_id, role_id) VALUES(%s,%s) ON CONFLICT DO NOTHING", (mid, rid))
    conn.execute("UPDATE invites SET used_by=%s, used_at=now() WHERE id=%s", (user_id, iid))
    audit(conn, hid, "invite.use", f"invite:{iid}", mid, user_id)
    return hid


def sign_in(conn, claims: dict, invite_hash: str | None = None) -> tuple[int, int]:
    """A verified Google sign-in → (user id, household id to open). Links unlinked account members whose email is
    the account's (verified) address and uses the invite. Raises NotInvited (nothing stored) when the account has no
    membership, no claimable member and no invite; InviteError for a bad invite."""
    sub, email = str(claims["sub"]), (claims.get("email") or "").strip().lower() or None
    verified = claims.get("email_verified") in (True, "true")
    with conn.transaction():
        row = conn.execute("SELECT id FROM users WHERE google_sub=%s FOR UPDATE", (sub,)).fetchone()
        uid = row[0] if row else None
        has = uid is not None and conn.execute("SELECT 1 FROM members WHERE user_id=%s", (uid,)).fetchone()
        claimable = [] if not (email and verified) else [r[0] for r in conn.execute(
            "SELECT id FROM members WHERE kind='account' AND user_id IS NULL AND lower(email)=%s FOR UPDATE", (email,))]
        if not has and not claimable and invite_hash is None:
            raise NotInvited(NOT_INVITED)
        if invite_hash is not None:
            _open_invite(conn, invite_hash)     # checked before anything is stored
        if uid is None:
            uid = conn.execute("INSERT INTO users(google_sub, email, email_verified, name, picture, last_login_at) "
                               "VALUES(%s,%s,%s,%s,%s,now()) RETURNING id",
                               (sub, email, verified, claims.get("name"), claims.get("picture"))).fetchone()[0]
        else:
            conn.execute("UPDATE users SET email=%s, email_verified=%s, name=%s, picture=%s, last_login_at=now() "
                         "WHERE id=%s", (email, verified, claims.get("name"), claims.get("picture"), uid))
        for mid in claimable:
            hid = conn.execute("SELECT household_id FROM members WHERE id=%s", (mid,)).fetchone()[0]
            if membership(conn, uid, hid) is None:
                conn.execute("UPDATE members SET user_id=%s, email=NULL WHERE id=%s", (uid, mid))
                audit(conn, hid, "member.claim", f"member:{mid}", mid, uid)
        open_hid = _use_invite(conn, invite_hash, uid, email if verified else None, claims.get("name")) \
            if invite_hash is not None else None
        households = user_households(conn, uid)
        if not households:
            raise NotInvited(NOT_INVITED)
    return uid, open_hid or households[0]["id"]


def accept_invite(conn, user_id: int, invite_hash: str) -> int:
    """A signed-in user joins another household with an invite; returns its id."""
    u = conn.execute("SELECT email, email_verified, name FROM users WHERE id=%s", (user_id,)).fetchone()
    with conn.transaction():
        return _use_invite(conn, invite_hash, user_id, u[0] if u[1] else None, u[2])
