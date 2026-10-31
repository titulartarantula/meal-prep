# Meal Prep — web

The laptop version of Meal Prep: sign in with Google, plan the week, keep the recipe library, make the shopping
list, review the cart and send it to Loblaws. React + Vite + TypeScript, TanStack Query; the "Stone" design
(warm greige, one olive accent, Inter bundled with the build — no CDN).

It talks to the FastAPI server under `/api` (Caddy strips the prefix and proxies to the server; same origin, so no
CORS). The session is the server's HttpOnly cookie; writes send the CSRF token from `/auth/me` as `X-CSRF-Token`.

## Run it

```sh
npm install
npm run dev:mock     # http://localhost:5173 with an in-memory API (invented data; nothing leaves the machine)
npm run dev          # proxies /api to MEALPREP_API (default http://192.168.1.101:8790)
npm run build        # static files in dist/ for Caddy
```

## Tests

```sh
npm test             # unit tests (weeks, formatting, cart maths) + WCAG AA contrast of every theme token pair
npm run e2e          # Playwright against the mock API: sign-in, week (move/drag/rate/add), recipes + import,
                     # list → build → review/swap/add → send → handoff; axe WCAG 2.1 AA scan on every screen
SHOTS=1 npm run e2e  # also writes 1440×900 screenshots to e2e/screens/
```

## API types

Routes with pydantic models are typed from the server's OpenAPI schema; routes that return plain dicts (drafts,
choices, imports, `/auth/me`) are typed by hand in `src/api/types.ts`.

```sh
<server venv>/bin/python scripts/dump_openapi.py   # writes openapi.json (no database needed)
npm run gen:api                                    # regenerates src/api/schema.d.ts
```

## Sending the cart to Loblaws from a laptop

A web page can't write loblaws.ca's storage, so the handoff uses a bookmarklet (installed once from the sent cart's
page): on a signed-out loblaws.ca tab it asks for the cart ID (copied from Meal Prep), stores it where loblaws.ca keeps
its anonymous cart, and reloads; signing in with PC id then merges the cart into the account. Start signed out (a
private window): merging into an already signed-in session doesn't work.

## Deploy

```sh
web/deploy/deploy.sh   # build, rsync dist/ + Caddyfile + compose.yaml to ~/meal-prep/web/, docker compose up
```

On the docker server the `mealprep-web` container (caddy, read-only, LAN port 8793) is the whole site's origin: the
Cloudflare tunnel (cloudflared on the host; `mealprep.drwhom.ca` is a proxied CNAME to the tunnel) sends every request
there. It serves `dist/` with the CSP and cache headers in `deploy/Caddyfile` and passes `/api/*` to the FastAPI
service on :8790 with the prefix stripped (ufw allows that from its compose network, 172.24.0.0/16); FastAPI's `/docs`
and `openapi.json` answer 404 there. The server must not trust `X-Forwarded-For`: the shared token is judged by the
caller's address, so it is refused through the public site.
