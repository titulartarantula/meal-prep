# Deploying the mealprep server

Runs on a Linux host with Docker (for Postgres) and Python 3.12, as a systemd **user** unit.
Layout on the server (`~/meal-prep/`):

- `docker-compose.yml` — `mealprep-db` (postgres:18, `127.0.0.1:5433`, data in `./pgdata`), its own compose project.
- `.env.db` (600) — Postgres creds for the container (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`).
- `.env` (600) — `MEALPREP_TOKEN` (the app's bearer token), `MEALPREP_PROVIDER`, `MEALPREP_DSN`, `MEALPREP_TEST_DSN`,
  `MEALPREP_PCX_APIKEY` (the public web API key the loblaws.ca site sends as `x-apikey`), plus any provider settings
  from `mealprep/config.py` (`MEALPREP_CLAUDE_BIN`, `MEALPREP_QWEN_URL`, API keys, …).
- `server/` — rsynced from this repo's `server/`; venv in `server/.venv` (built with `uv`).
- `~/.config/systemd/user/mealprep.service` — copy of `deploy/mealprep.service` (needs `loginctl enable-linger`).

## Update

From the development machine, with `android/tools/local.env` filled in (`SERVER_HOST`, `SERVER_KEY`):

```bash
server/deploy/deploy.sh      # rsync server/, reinstall into the venv, restart the unit
```

## Tests (on the server, against the `mealprep_test` DB)

```bash
cd ~/meal-prep/server && set -a && . ../.env && set +a && .venv/bin/pytest -q
RUN_LIVE=1 .venv/bin/pytest -q tests/test_pcx_live.py   # creates a throwaway anonymous PC Express cart
```

DB tests skip (not fail) when `MEALPREP_TEST_DSN` is unset.

## First-time setup

```bash
cd ~/meal-prep && docker compose up -d && docker exec mealprep-db psql -U mealprep -c 'create database mealprep_test'
cd server && ~/.local/bin/uv venv --python /usr/bin/python3.12 .venv && ~/.local/bin/uv pip install --python .venv/bin/python -e ".[dev]"
loginctl enable-linger "$USER"
cp deploy/mealprep.service ~/.config/systemd/user/ && systemctl --user daemon-reload && systemctl --user enable --now mealprep
```

## Smoke scripts

Draft flow (the app's path — build in app → review → send):

```bash
cd ~/meal-prep/server && set -a && . ../.env && set +a && .venv/bin/python deploy/smoke_draft.py 2026-10-11
```

Lists the week with every line ticked → `POST /drafts` (202) → polls `GET /drafts/{id}` to `ready` (timed) → swaps one
line to its first alternative (records a **user pick** for that item) → removes one line → `POST /drafts/{id}/send`
twice (same cart id) → reads the anonymous PC Express cart back and checks codes/quantities. Creates one real anonymous
cart and marks the week carted.

Prep plan (empty test week only; deletes what it adds unless `--keep`):

```bash
cd ~/meal-prep/server && set -a && . ../.env && set +a && .venv/bin/python deploy/smoke_prep.py 2026-11-01 3:2 1:4
```

Adds recipe_id:day entries → `POST /prep-plans` → polls to ready (timed) → prints the plan and cards → ticks a task on
and off → deletes the entries, the prep plan and its task events.

## API (all but /health need `Authorization: Bearer $MEALPREP_TOKEN`)

| Endpoint | Purpose |
|---|---|
| `POST /list` `{weeks, people?, staples?: [ids]}` | the merged shopping list for the weeks (plan entries placed or not); `staples` = the ticked weekly staples to merge in (see below) |
| `GET /staples` | weekly staples in the household's order: `id, name, qty, unit, weekly, position, last_bought, created_at` |
| `POST /staples` `{name, qty?, unit?, weekly?}` | add one at the end → 201; 409 if the item is already a staple, 422 for an unknown unit |
| `PATCH /staples/{id}` `{name?, qty?, unit?, weekly?, last_bought?, position?}` | partial update (null clears qty/unit/last_bought); `position` moves it there and renumbers |
| `DELETE /staples/{id}` | remove → 204 (idempotent) |
| `POST /drafts` `{weeks, items}` | start a draft → 202 `{id, status:"building"}`; matching runs in the background (`MEALPREP_MATCH_WORKERS`, default 6) |
| `GET /drafts/{id}` | status `building/ready/failed/sent`, progress, lines (product, quantity, source, ≤5 alternatives, removed, `packs_min`, `needs_check`, `why` — see below), `estimated_total`, `pcx_cart_id` |
| `GET /weeks/{date}/draft` | newest draft (any status) covering that week — same shape as `GET /drafts/{id}`; 404 if none (lets either phone find this week's cart) |
| `PATCH /drafts/{id}/lines/{line}` `{product_code?, quantity?, removed?}` | edit while `ready` (409 otherwise); a product swap is remembered as the household's pick |
| `POST /drafts/{id}/lines/{line}/search` `{term}` | free-text candidates for a swap (409 unless `ready`) |
| `POST /drafts/{id}/send` | create the PC Express cart (once; idempotent) → `{pcx_cart_id}`; only now is the week carted |
| `POST /cart` `{items, weeks}` | legacy/scripts: draft + build + send synchronously |
| `PUT /plan/{entry}/rating` `{family 1–5, company? yes/maybe/no, note?}` | rate one time cooked → 204; re-rating overwrites (every change logged in `rating_history`); 404 unknown entry, 422 bad values |
| `DELETE /plan/{entry}/rating` | remove the rating → 204 (idempotent) |
| `GET /ratings/pending?today=YYYY-MM-DD` | unrated entries placed on a night before today, last 14 days, newest first (morning-after prompt) |
| `POST /recipes/share` `{text, week?}` | import the NYT Cooking link in `text` into the library (or find it there: `existing: true`, no re-import) → `{recipe, entry, existing}`; `recipe` carries `ratings` + `planned_weeks`. With `week` it is also added to that week (not on a night) and `entry` is the plan entry; without one it is library only and `entry` is null. 422 no NYT link, 502 fetch/read failed (nothing saved) |
| `POST /recipes/photo` multipart `files` (1–10 pages, in order) + optional `title`, `week` | read a cookbook recipe from photos (sync, ~20 s a page) → same shape as share; without `week` library only |
| `GET /recipes?sort=newest\|favourites` | library with `ratings` summary (times cooked/rated, avg family, last, company verdict, ≤5 notes) and `planned_weeks` (ISO Sundays from this week on that have it) |
| `GET /recipes/{id}` | one recipe + `ratings` summary + `planned_weeks` + `history` (every plan entry with its rating) |
| `POST /weeks/{date}/entries` `{recipe_id}` | add a library recipe to a week (not on a night yet) → the entry; no duplicate check (the app checks first) |
| `GET /weeks/{date}` | that week's plan entries, each with `title` and `rating` or null |
| `POST /prep-plans` `{weeks}` | generate the Sunday prep plan + cook cards → 202 `{id, status:"building"}` (422 if nothing is planned); runs in the background (~4 min for 2 recipes with Claude CLI) |
| `GET /prep-plans/{id}` | status `building/ready/failed`, progress, `sections` (knife → sauces → proteins → pack, each task with `id`, `text`, `serves` [recipe + night], `est_minutes`, `shelf_life` ok/day_of/freeze_then_thaw, `thaw`, `contents`, `flags`, `done`), `total_minutes`, `warnings`, `entries`, `checklist` (done/total, est vs actual minutes), `stale` |
| `GET /weeks/{date}/prep-plan` | newest prep plan for that week (any status) + `last_ready_id` |
| `PATCH /prep-plans/{id}/tasks/{task}` `{done}` | tick/untick a checklist task (409 unless `ready`); every change logged in `prep_task_events` |
| `GET /plan/{entry}/card` | day-of cook card from the newest ready plan: `kit`, `day_of`, `thaw`, `steps` (`minutes`, `timer_minutes`), `total_minutes`, `rating_notes` ("last time: less salt"), `stale` if the entry moved night/multiplier since |
| `POST /recipes/{id}/pages` multipart `files` (1–10) + optional `for_line` | read a cross-referenced sub-recipe page ("Batter for 24 crêpes, page 191") and add its ingredients/steps to the recipe (sync, ~25–30 s); 422 if the line is ambiguous, 409 if already attached |

A restart marks in-flight (`building`) drafts and prep plans `failed`; start a new one.

### Purchase planner (`mealprep/planner.py`)

Each draft line's quantity covers the list need. The need (qty + unit) and the product's `package_size` ("2 l",
"12x355.0 ml", "12 ea", "per kg") go into ml, g or a count; a small density / piece-weight table converts cups of
flour or onions to grams. The AI sees each candidate's `min_packs`. When the planner can size the need against the
pack, AI and remembered picks get exactly that floor, never more (one bottle of vanilla stays one bottle, whatever
was bought last time). When it can't (priced by weight, unknown sizes, units it can't compare: teaspoons of salt
against a 1 kg bag) the line gets 1 and `needs_check`, except a pack sold by the piece (onions each), which keeps
the AI's number (1–12) or a remembered one scaled by the need in base units; a remembered quantity above
2 × max(1, floor) is ignored as stale. User edits are kept; a product swap without a quantity moves the planner's
number to the new product's floor and raises a user's own number to it. Each line reports `packs_min` (null when
unmatched), `needs_check` and `why` ("Need ⅚ cup → 1 × 1 L", "… (short)" below the floor, "… (you chose 3)" above
it, "… (check: priced by weight)").
Products carry `sold_by` (PC Express pricing type) in search snapshots.

### Weekly staples (`mealprep/staples.py`)

Items bought most weeks (milk, eggs …). A staple's `qty` without a `unit` counts packs ("1" = one carton); with a
unit it is an amount. The app ticks `weekly` staples at the top of its shopping list and sends the ticked ids to
`POST /list`, which merges them like recipe lines (same clean-up, merge keys, planner and remembered picks), so
`POST /drafts {weeks, items}` is unchanged. An amount adds to the recipes' amount of the same item (never scaled
by people); a pack count joins a line for the same item in any unit as a floor: the line keeps the recipe amount,
gets `staple_packs` and is bought in at least that many packs ("Need 1 cup; staple: at least 1 pack → 1 × 2 L"),
or stands alone ("Need 1 pack (weekly staple) → 1 × 2 L"). Lines with a staple are `staple: true`, needed, and list
"Staples" among their `recipes`; draft lines carry `staple` and `staple_packs` too. `last_bought` is the local date
of the newest *sent* cart with an added line for the item (any unit; drafts never sent and removed lines don't
count), or the manual `last_bought` hint when that is later. Three starter staples are seeded once.
