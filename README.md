# Meal Prep

A small, self-hosted meal-planning system for one household: share recipe links from your phone, plan the
week's dinners night by night, turn the week into a grocery pickup cart, and get a Sunday prep checklist
plus a cook card for each night.

It has two parts:

- **Android app** (`android/`, Kotlin + Jetpack Compose, package `dev.mealprep.app`). Share an NYT Cooking
  link into the app, pick the week, and arrange recipes on nights in the "This week" view. The app talks only
  to the household's own server; it keeps an offline copy of the current week.
- **Server** (`server/`, Python 3.12 + FastAPI + Postgres). Imports recipes (schema.org JSON-LD from recipe
  pages, or cookbook photos read by an AI model), stores the weekly plan and ratings, builds a grocery cart
  for PC Express (Loblaws) by matching ingredients to products, and writes the Sunday prep plan and per-night
  cook cards with an LLM followed by a deterministic food-safety pass.

```
phone (app) ──HTTP + bearer token──▶ mealprep server (FastAPI, systemd user unit) ──▶ Postgres (Docker)
                                         │
                                         ├──▶ AI provider: Claude CLI / Anthropic API / OpenAI-compatible / Gemini
                                         └──▶ PC Express API (anonymous cart: search, create, add; no login, no checkout)
```

Not affiliated with The New York Times or Loblaws.

## Repository layout

| Path | What |
|---|---|
| `android/` | The app. `android/tools/` holds build/release helper scripts (see below). |
| `server/mealprep/` | API (`api.py`), DB access + schema (`db.py`, `schema.sql`), importers, AI providers (`ai/`), cart drafts and product matching (`drafts.py`, `matcher.py`, `pcx.py`), prep plans (`prepplan.py`, `prep_rules.py`). |
| `server/tests/` | pytest suite (DB tests need a Postgres test database). |
| `server/deploy/` | systemd unit, deploy script, smoke scripts, and the API reference (`README.md`). |
| `docker-compose.yml` | Postgres for the server. |
| `docs/play/`, `docs/play-assets/` | Google Play privacy policy, store listing text and graphics (`render.py` draws them). |

## Server

Requirements: Linux, Python 3.12, Docker (for Postgres), and one AI provider.

```bash
docker compose up -d                                   # Postgres on 127.0.0.1:5433; creds from .env.db
cd server && python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
```

Configuration is by environment variables (see `server/mealprep/config.py`): at least `MEALPREP_DSN`,
`MEALPREP_TOKEN` (the bearer token the app sends), `MEALPREP_PROVIDER` (`claude-cli`, `anthropic`, `openai`,
`qwen` for a local OpenAI-compatible server, or `gemini`) and that provider's settings, and
`MEALPREP_PCX_APIKEY` for PC Express (the public web API key the loblaws.ca site sends as `x-apikey`).
Run it with `server/deploy/mealprep.service` (uvicorn on port 8790); `server/deploy/README.md` covers
deployment and the full API.

Tests:

```bash
cd server && MEALPREP_TEST_DSN=postgresql://… .venv/bin/pytest -q     # DB tests skip if MEALPREP_TEST_DSN is unset
RUN_LIVE=1 .venv/bin/pytest -q tests/test_pcx_live.py                # creates a throwaway anonymous PC Express cart
```

## Android app

Requirements: JDK 21 and the Android SDK (compileSdk 36, minSdk 34).

```bash
cd android
./gradlew testDebugUnitTest        # unit + Robolectric UI tests
./gradlew assembleDebug
```

Release signing reads a properties file named by `MEALPREP_KEYSTORE_PROPS` (kept outside the repo).

The app's default server address and its cleartext-HTTP allowance (`app/src/main/res/xml/network_security_config.xml`)
are set for a server on a private home network or VPN; change both for your own network. Everything else is
HTTPS only.

### Helper scripts (`android/tools/`)

These drive a remote Windows build host and a Linux server over SSH. Copy `local.env.example` to `local.env`
(gitignored) and fill in your hosts, keys and paths first.

- `remote.sh <gradle args>`: ship the working tree to the build host and run Gradle there.
- `pull.sh <path> <dest>`: copy a build output back.
- `release.sh "<notes>" [track]`: bump versionCode, test, build a signed bundle, upload to Play, commit.
- `play_upload.sh`, `capture_fixtures.sh`, `push_github.sh`: Play upload, test-fixture capture, GitHub push via the server.

## License

License: not yet chosen. Until one is added, all rights are reserved.
