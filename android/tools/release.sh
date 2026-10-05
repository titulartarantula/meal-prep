#!/usr/bin/env bash
# Bump versionCode, test + build a signed bundle on the build host (via remote.sh/pull.sh), upload to Play, commit the bump.
#   android/tools/release.sh "<release notes>" [track=internal]
# If anything fails after the bump and before the commit, gradle.properties is restored.
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES=$1; TRACK=${2:-internal}
# The Play build must match a commit: refuse to run with uncommitted or untracked changes under android/.
if [ -n "$(git status --porcelain -- .)" ]; then
  echo "release.sh: android/ has uncommitted changes — commit or stash them first:" >&2
  git status --short -- . >&2
  exit 1
fi
BACKUP=$(mktemp); cp gradle.properties "$BACKUP"
DONE=0
trap 'if [ "$DONE" != 1 ]; then cp "$BACKUP" gradle.properties; echo "release.sh: failed, gradle.properties restored" >&2; fi; rm -f "$BACKUP"' EXIT
VC=$(( $(grep '^mealprep.versionCode=' gradle.properties | cut -d= -f2) + 1 ))
sed -i "s/^mealprep.versionCode=.*/mealprep.versionCode=$VC/" gradle.properties
tools/remote.sh testDebugUnitTest bundleRelease
tools/pull.sh app/build/outputs/bundle/release/app-release.aab "/tmp/mealprep-$VC.aab"
tools/play_upload.sh "/tmp/mealprep-$VC.aab" "$TRACK" "$NOTES"
DONE=1
git add gradle.properties && git commit -m "release: versionCode $VC to $TRACK — $NOTES" -- gradle.properties
