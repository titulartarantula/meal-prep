#!/usr/bin/env bash
# Capture GET-only fixtures from the live server into app/src/test/resources/fixtures/.
# NEVER add POST/PATCH/DELETE here. The GETs run on SERVER_HOST, so the token never leaves it.
# Captures are real household data: scrub personal details and copied recipe text before committing.
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
mkdir -p "$OUT"
ssh -i "${SERVER_KEY:?}" "${SERVER_HOST:?}" 'bash -s' > /tmp/mp-fixtures.tar <<'EOF'
set -euo pipefail
set -a; . ~/meal-prep/.env; set +a
d=$(mktemp -d); cd "$d"
g() { curl -sf -H "Authorization: Bearer $MEALPREP_TOKEN" "http://127.0.0.1:8790$1" -o "$2"; }
curl -sf http://127.0.0.1:8790/health -o health.json
g "/weeks?from=2026-10-04&count=8" weeks.json
g /weeks/2026-10-11 week_2026-10-11.json
g /recipes recipes.json
g /recipes/1 recipe_1.json
g /recipes/3 recipe_3.json
g /drafts/3 draft_3_sent.json
g /cart/default-week default_week.json
tar cf - ./*.json; rm -rf "$d"
EOF
tar xf /tmp/mp-fixtures.tar -C "$OUT" && rm /tmp/mp-fixtures.tar
ls -l "$OUT"
