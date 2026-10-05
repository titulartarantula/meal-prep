#!/usr/bin/env bash
# Push main to origin (GitHub) after a privacy scan of the tracked tree. The repo is public.
#   android/tools/push_github.sh
# The scan fails on any tracked text line matching PRIVACY_PATTERNS (an extended regex, case-insensitive, kept in the
# gitignored tools/local.env: names, emails, hosts, home paths) or holding a 40+ character hex string that isn't a
# git commit link. Fix the hits (or, for a false positive, narrow the pattern) and run it again.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/../.."
: "${PRIVACY_PATTERNS:?set PRIVACY_PATTERNS in tools/local.env}"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "push_github.sh: uncommitted changes to tracked files — commit them first" >&2; exit 1
fi
hits=$( { git grep -n -I -i -E "$PRIVACY_PATTERNS" main -- . || true
          git grep -n -I -E '[0-9a-fA-F]{40,}' main -- . | grep -v -E 'github\.com/[^ ]+/blob/[0-9a-f]{40}/' || true; } )
if [ -n "$hits" ]; then
  echo "push_github.sh: privacy scan found:" >&2; echo "$hits" >&2; exit 1
fi
git push origin main
