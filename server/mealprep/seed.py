"""Seed (or re-sync) the first household's name and members from a private file, never from the repo.

    python -m mealprep.seed household.json        (or MEALPREP_SEED_FILE=household.json python -m mealprep.seed)

household.json (keep it outside the repo, mode 600):
    {"household": "Our family",
     "members": [
       {"name": "A", "kind": "account", "roles": ["owner", "approver", "planner", "contributor"],
        "email": "a@example.com", "legacy_token": true},
       {"name": "B", "kind": "account", "roles": ["contributor"], "email_env": "MEALPREP_SEED_B_EMAIL",
        "birth_year": 2013},
       {"name": "C", "kind": "child", "birth_year": 2016}]}

Members are matched by name (case-insensitive) and created when missing; their roles are set to the file's (an
account defaults to Contributor, a child profile is Child). An email (or the variable named by email_env) lets that
Google account claim the member on its first sign-in; without one, the member waits for an invite (POST
/household/invites with member_id). legacy_token = the member the shared bearer token acts as. Members not in the
file are left alone. Runs in one transaction: a file that leaves no Owner or no Approver / Purchaser changes nothing.
"""
import json, os, sys

from . import db, households
from .config import Settings
from .rbac import CHILD


def seed(conn, spec: dict, household_id: int | None = None) -> list[dict]:
    hid = household_id or db.first_household(conn)
    out = []
    with conn.transaction():
        if spec.get("household"):
            households.update_household(conn, hid, name=spec["household"])
        roles = households.role_ids(conn)
        for m in spec.get("members", []):
            name, kind = " ".join(str(m["name"]).split()), m.get("kind", "account")
            if kind not in ("account", "child"):
                raise ValueError(f"{name}: kind is account or child")
            keys = m.get("roles") or ([CHILD] if kind == "child" else ["contributor"])
            households._check_roles(conn, kind, keys)
            email = m.get("email") or (os.environ.get(m["email_env"], "") if m.get("email_env") else "")
            email = email.strip().lower() or None
            if kind == "child" and email:
                raise ValueError(f"{name}: a child profile has no email address")
            row = conn.execute("SELECT id, kind, user_id FROM members WHERE household_id=%s AND lower(display_name)=lower(%s)",
                               (hid, name)).fetchone()
            if row is None:
                mid = conn.execute("INSERT INTO members(household_id, display_name, kind, birth_year, email) "
                                   "VALUES(%s,%s,%s,%s,%s) RETURNING id",
                                   (hid, name, kind, m.get("birth_year"), email)).fetchone()[0]
                linked = False
            else:
                mid, old_kind, user_id = row
                linked = user_id is not None
                if old_kind != kind:
                    raise ValueError(f"{name}: is a {old_kind} member (convert a child with an invite)")
                conn.execute("UPDATE members SET birth_year = COALESCE(%s, birth_year) WHERE id=%s",
                             (m.get("birth_year"), mid))
                if email and not linked:
                    conn.execute("UPDATE members SET email=%s WHERE id=%s", (email, mid))
            conn.execute("DELETE FROM member_roles WHERE member_id=%s", (mid,))
            for k in dict.fromkeys(keys):
                conn.execute("INSERT INTO member_roles(member_id, role_id) VALUES(%s,%s)", (mid, roles[k][0]))
            if m.get("legacy_token"):
                conn.execute("UPDATE households SET legacy_member_id=%s WHERE id=%s", (mid, hid))
            households.audit(conn, hid, "member.seed", f"member:{mid}", detail={"roles": keys})
            out.append({"id": mid, "name": name, "kind": kind, "roles": keys, "email_set": bool(email) or linked,
                        "signed_in": linked})
        owners, approvers = households._counts(conn, hid)
        if not owners or not approvers:
            raise ValueError("the household needs at least one Owner and one Approver / Purchaser")
    return out


def main(argv: list[str]) -> int:
    path = argv[1] if len(argv) > 1 else os.environ.get("MEALPREP_SEED_FILE")
    if not path:
        print(__doc__)
        return 2
    with open(path) as f:
        spec = json.load(f)
    with db.connect(Settings().dsn) as conn:
        try:
            done = seed(conn, spec)
        except (ValueError, households.RuleError) as e:
            print(f"Nothing changed: {e}", file=sys.stderr)
            return 1
    for m in done:   # no email addresses in the output
        print(f"{m['id']:>4}  {m['name']:<20} {m['kind']:<8} {','.join(m['roles']):<40} "
              f"{'signed in' if m['signed_in'] else 'claim by email' if m['email_set'] else 'needs an invite'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
