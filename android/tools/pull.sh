#!/usr/bin/env bash
# Copy a file from the build host's mirror BUILD_DIR/android/ (see remote.sh).
#   android/tools/pull.sh app/build/outputs/apk/debug/app-debug.apk /tmp/
set -euo pipefail
. "$(dirname "$0")/env.sh"
scp -q -i "${BUILD_KEY:?}" -o BatchMode=yes "${BUILD_HOST:?}:${BUILD_DIR:?}/android/$1" "$2"
