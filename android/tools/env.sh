# Sourced by the tools here: loads the untracked tools/local.env (copy local.env.example and fill it in).
LOCAL_ENV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/local.env"
if [ ! -f "$LOCAL_ENV" ]; then
  echo "$(basename "$0"): missing $LOCAL_ENV (copy local.env.example and fill it in)" >&2
  exit 2
fi
# shellcheck disable=SC1090
. "$LOCAL_ENV"
