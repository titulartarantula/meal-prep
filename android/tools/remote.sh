#!/usr/bin/env bash
# Build/test on a Windows build host (BUILD_HOST in tools/local.env), which has the Android SDK + JDK 21.
# Ships the android/ working tree (tracked + untracked-but-not-ignored files, so uncommitted work is
# included) as a tar to BUILD_DIR there, then runs tools/windows-build.ps1, which syncs the mirror
# BUILD_DIR/android and runs gradlew.bat with the given args.
# Exit status is Gradle's.
#   android/tools/remote.sh testDebugUnitTest
#   android/tools/remote.sh testDebugUnitTest --tests dev.mealprep.app.core.WeeksTest
#   android/tools/remote.sh testDebugUnitTest --tests "dev.mealprep.app.SmokeTest.robolectric renders compose"
# Arguments may contain spaces; they may not contain newlines, double quotes or cmd.exe metacharacters (& | < > ^ %).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
. "$HERE/tools/env.sh"
HOST="${BUILD_HOST:?set BUILD_HOST in tools/local.env}"
REMOTE_DIR="${BUILD_DIR:?set BUILD_DIR in tools/local.env}"
SSH_OPTS=(-i "${BUILD_KEY:?set BUILD_KEY in tools/local.env}" -o BatchMode=yes -o ConnectTimeout=15)

for a in "$@"; do
  case "$a" in *$'\n'*|*'"'*) echo "remote.sh: argument contains a newline or double quote: $a" >&2; exit 2;; esac
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
trap 'echo "remote.sh: failed (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR
# Tracked (minus deleted) + untracked-but-not-ignored files under android/, relative to android/.
(cd "$HERE" && git ls-files -z -co --exclude-standard -- . | while IFS= read -r -d '' f; do
   [ -f "$f" ] || continue   # tracked-but-deleted or non-regular: skip
   printf '%s\0' "$f"; done) > "$TMP/files"
tar -C "$HERE" --format=pax --null -T "$TMP/files" -cf "$TMP/upload.tar"
cp "$HERE/tools/windows-build.ps1" "$TMP/windows-build.ps1"
ssh "${SSH_OPTS[@]}" "$HOST" "New-Item -ItemType Directory -Force -Path '$REMOTE_DIR' | Out-Null"
scp -q "${SSH_OPTS[@]}" "$TMP/upload.tar" "$TMP/windows-build.ps1" "$HOST:$REMOTE_DIR/"

# Args travel as base64 of newline-joined UTF-8, so nothing in between (sshd, pwsh, powershell) re-parses them.
# With no args, -ArgsB64 is omitted entirely and gradlew runs its default task (help).
PS_ARGS=""
if [ $# -gt 0 ]; then PS_ARGS="-ArgsB64 $(printf '%s\n' "$@" | base64 -w0)"; fi
# The ssh exit status is Gradle's; don't let the ERR trap report a normal build failure as a script error.
trap - ERR
ssh "${SSH_OPTS[@]}" "$HOST" \
  "powershell -NoProfile -ExecutionPolicy Bypass -File $REMOTE_DIR/windows-build.ps1 $PS_ARGS"
