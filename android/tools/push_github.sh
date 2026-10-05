#!/usr/bin/env bash
# Push main to GITHUB_REPO via SERVER_HOST (this host has no GitHub credentials; see tools/local.env).
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/../.."
: "${SERVER_HOST:?}" "${SERVER_KEY:?}" "${GITHUB_REPO:?}"
git bundle create /tmp/mp.bundle main
scp -i "$SERVER_KEY" /tmp/mp.bundle "$SERVER_HOST:/tmp/"
ssh -i "$SERVER_KEY" "$SERVER_HOST" "set -e; d=\$(mktemp -d); git clone -q https://github.com/$GITHUB_REPO.git \$d/mp && cd \$d/mp && git pull -q --ff-only /tmp/mp.bundle main && git push -q origin main; rm -rf \$d /tmp/mp.bundle"
rm /tmp/mp.bundle
echo "pushed main to $GITHUB_REPO"
