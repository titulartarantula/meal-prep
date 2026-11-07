"""Permissions and the route → permission map (server task 2).

Roles are data (tables roles / role_permissions, seeded in schema.sql); the code only knows permissions. Every route
of the API is listed here: a household route with the permission it needs (MEMBER = any member of the household),
PUBLIC routes need no sign-in, SIGNED_IN routes need a session but act on no household. The household dependency in
api.py looks the request's route up here and refuses anything not listed (a new route must be added; the route sweep
in tests/test_rbac_routes.py fails until it is).
"""

PERMISSIONS = frozenset({
    "recipe.read", "plan.read", "rate.self", "rate.for_children", "request.create", "plan.edit", "recipe.edit",
    "fridge.edit", "request.approve", "cart.build", "cart.send", "members.manage", "roles.assign",
    "household.settings", "recipe.company"})
MEMBER = "member"           # any member of the household, whatever their roles

OWNER, APPROVER, CHILD = "owner", "approver", "child"

PUBLIC = frozenset({
    ("GET", "/health"), ("GET", "/auth/login"), ("GET", "/auth/callback"), ("POST", "/auth/google"),
    ("POST", "/auth/app-token")})
SIGNED_IN = frozenset({
    ("GET", "/auth/me"), ("POST", "/auth/logout"), ("PUT", "/auth/household"), ("POST", "/invites/accept")})

ROUTES: dict[tuple[str, str], str] = {
    # recipes (import counts as editing: it adds to the library)
    ("GET", "/recipes"): "recipe.read",
    ("GET", "/recipes/{recipe_id}"): "recipe.read",
    ("GET", "/recipes/sources"): "recipe.read",
    ("GET", "/recipes/export"): "recipe.read",
    ("GET", "/recipes/{recipe_id}/export"): "recipe.read",
    ("POST", "/recipes/share"): "recipe.edit",          # + plan.edit when a week is sent
    ("POST", "/recipes/photo"): "recipe.edit",          # + plan.edit when a week is sent
    ("PATCH", "/recipes/{recipe_id}"): "recipe.edit",
    ("POST", "/recipes/{recipe_id}/pages"): "recipe.edit",
    ("POST", "/recipes/import"): "recipe.edit",
    ("GET", "/imports/{job_id}"): "recipe.edit",
    ("GET", "/books/search"): "recipe.edit",
    # the week plan
    ("GET", "/weeks"): "plan.read",
    ("GET", "/weeks/{week}"): "plan.read",
    ("POST", "/weeks/{week}/entries"): "plan.edit",
    ("GET", "/weeks/{week}/nights"): "plan.read",             # night names + the nights' store items
    ("PUT", "/weeks/{week}/nights/{day}"): "plan.edit",       # name / rename / clear a night
    ("DELETE", "/weeks/{week}/nights/{day}"): "plan.edit",
    ("PATCH", "/plan/{entry_id}"): "plan.edit",
    ("DELETE", "/plan/{entry_id}"): "plan.edit",
    ("GET", "/ratings/pending"): "plan.read",
    # per-person ratings: each member rates for themselves; member_id = a child profile also needs rate.for_children
    ("PUT", "/plan/{entry_id}/rating"): "rate.self",
    ("DELETE", "/plan/{entry_id}/rating"): "rate.self",
    ("PUT", "/recipes/{recipe_id}/rating"): "rate.self",
    ("DELETE", "/recipes/{recipe_id}/rating"): "rate.self",
    ("PUT", "/recipes/{recipe_id}/company"): "recipe.company",   # the household's Good for company verdict (Owners)
    # Sunday prep + cook cards: retired 2026-10-31, these answer 410 (kept so an older app gets a clear answer)
    ("POST", "/prep-plans"): "plan.edit",
    ("GET", "/prep-plans/{plan_id}"): "plan.read",
    ("GET", "/weeks/{week}/prep-plan"): "plan.read",
    ("PATCH", "/prep-plans/{plan_id}/tasks/{task_id}"): "plan.edit",
    ("GET", "/plan/{entry_id}/card"): "plan.read",
    # shopping list, staples, cart
    ("POST", "/list"): "plan.read",                      # computes the list; writes nothing
    ("GET", "/staples"): "plan.read",
    ("POST", "/staples"): "cart.build",
    ("PATCH", "/staples/{staple_id}"): "cart.build",
    ("DELETE", "/staples/{staple_id}"): "cart.build",
    ("GET", "/cart/default-week"): "cart.build",
    ("POST", "/cart"): "cart.send",                      # builds and sends in one go (smoke scripts)
    ("POST", "/drafts"): "cart.build",
    ("GET", "/drafts/{draft_id}"): "cart.build",
    ("GET", "/weeks/{week}/draft"): "cart.build",
    ("PATCH", "/drafts/{draft_id}/lines/{line_id}"): "cart.build",
    ("PUT", "/drafts/{draft_id}/lines/{line_id}/pack"): "cart.build",
    ("POST", "/drafts/{draft_id}/lines/{line_id}/search"): "cart.build",
    ("GET", "/drafts/{draft_id}/lines/{line_id}/choices"): "cart.build",
    ("POST", "/drafts/{draft_id}/lines"): "cart.build",
    ("POST", "/drafts/{draft_id}/send"): "cart.send",
    ("GET", "/products/choices"): "cart.build",
    ("GET", "/extras"): "cart.build",
    ("POST", "/extras"): "cart.build",
    ("PATCH", "/extras/{extra_id}"): "cart.build",
    ("DELETE", "/extras/{extra_id}"): "cart.build",
    ("PUT", "/picks/{key:path}"): "cart.build",
    # fridge
    ("GET", "/fridge"): "plan.read",
    ("GET", "/fridge/names"): "plan.read",
    ("POST", "/fridge/suggest"): "plan.read",            # read-only suggestions
    ("GET", "/fridge/used"): "plan.read",
    ("POST", "/fridge"): "fridge.edit",
    ("PATCH", "/fridge/{item_id}"): "fridge.edit",
    ("DELETE", "/fridge/{item_id}"): "fridge.edit",
    ("POST", "/fridge/photos"): "fridge.edit",
    ("GET", "/fridge/reads"): "fridge.edit",
    ("GET", "/fridge/reads/{read_id}"): "fridge.edit",
    ("POST", "/fridge/reads/{read_id}/apply"): "fridge.edit",
    ("POST", "/fridge/reads/{read_id}/dismiss"): "fridge.edit",
    ("POST", "/fridge/used/{entry_id}"): "fridge.edit",
    # the household itself
    ("GET", "/household"): MEMBER,
    ("PATCH", "/household"): "household.settings",
    ("GET", "/household/ai-usage"): "household.settings",
    ("GET", "/household/members"): MEMBER,
    ("POST", "/household/members"): "members.manage",
    ("PATCH", "/household/members/{member_id}"): "members.manage",
    ("DELETE", "/household/members/{member_id}"): "members.manage",
    ("PUT", "/household/members/{member_id}/roles"): "roles.assign",
    ("GET", "/household/invites"): "members.manage",
    ("POST", "/household/invites"): "members.manage",
    ("DELETE", "/household/invites/{invite_id}"): "members.manage",
}

assert all(p == MEMBER or p in PERMISSIONS for p in ROUTES.values())
