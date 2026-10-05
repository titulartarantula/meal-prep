#!/usr/bin/env bash
# Rsync server/ to SERVER_HOST:~/meal-prep/server/, reinstall into its venv and restart the user unit.
# SERVER_HOST / SERVER_KEY come from android/tools/local.env (see local.env.example).
set -euo pipefail
. "$(dirname "$0")/../../android/tools/env.sh"
: "${SERVER_HOST:?}" "${SERVER_KEY:?}"
rsync -a -e "ssh -i $SERVER_KEY" --exclude .venv --exclude __pycache__ --exclude '*.egg-info' --exclude .pytest_cache \
  "$(dirname "$0")/../" "$SERVER_HOST:meal-prep/server/"
ssh -i "$SERVER_KEY" "$SERVER_HOST" \
  'cd ~/meal-prep/server && ~/.local/bin/uv pip install -q --python .venv/bin/python -e ".[dev]" && systemctl --user restart mealprep'
echo "deployed to $SERVER_HOST"
