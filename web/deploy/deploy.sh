#!/usr/bin/env bash
# Build the web app and publish it to SERVER_HOST:~/meal-prep/web/ (compose project "mealprep-web").
# SERVER_HOST / SERVER_KEY come from android/tools/local.env, as for server/deploy/deploy.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/../../android/tools/env.sh"
: "${SERVER_HOST:?}" "${SERVER_KEY:?}"
(cd "$here/.." && npm run -s build)
ssh -i "$SERVER_KEY" "$SERVER_HOST" 'mkdir -p ~/meal-prep/web/dist'
rsync -a -e "ssh -i $SERVER_KEY" "$here/Caddyfile" "$here/compose.yaml" "$SERVER_HOST:meal-prep/web/"
rsync -a --delete -e "ssh -i $SERVER_KEY" --exclude '*.map' "$here/../dist/" "$SERVER_HOST:meal-prep/web/dist/"
ssh -i "$SERVER_KEY" "$SERVER_HOST" 'cd ~/meal-prep/web && docker compose up -d --remove-orphans >/dev/null && docker compose ps --format "{{.Name}} {{.Status}}"'
echo "web deployed to $SERVER_HOST"
