#!/usr/bin/env bash
# Upload an AAB to a Play track with the PLAY_ACCOUNT service account (Android Publisher API v3).
#   play_upload.sh <app.aab> <track> "<release notes>"
# PLAY_STATUS=draft is required while the app has never been rolled out (Play: "only draft releases on a draft app").
set -euo pipefail
. "$(dirname "$0")/env.sh"
AAB=$1; TRACK=$2; NOTES=$3
STATUS=${PLAY_STATUS:-completed}; LANG_CODE=${PLAY_LANG:-en-US}
PKG=$(grep '^mealprep.applicationId=' "$(dirname "$0")/../gradle.properties" | cut -d= -f2)
TOKEN=$(gcloud auth print-access-token --account "${PLAY_ACCOUNT:?set PLAY_ACCOUNT in tools/local.env}" \
  --scopes=https://www.googleapis.com/auth/androidpublisher)
API=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PKG
UP=https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PKG
H=(-H "Authorization: Bearer $TOKEN")
EDIT=$(curl -4 -sS --fail-with-body -X POST "${H[@]}" "$API/edits" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
VC=$(curl -4 -sS --fail-with-body -X POST "${H[@]}" -H "Content-Type: application/octet-stream" --data-binary @"$AAB" \
  "$UP/edits/$EDIT/bundles?uploadType=media" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])')
BODY=$(python3 -c 'import json,sys; t,v,s,l,n=sys.argv[1:]; print(json.dumps({"track":t,"releases":[{"versionCodes":[v],"status":s,"releaseNotes":[{"language":l,"text":n}]}]}))' \
  "$TRACK" "$VC" "$STATUS" "$LANG_CODE" "$NOTES")
curl -4 -sS --fail-with-body -X PUT "${H[@]}" -H "Content-Type: application/json" -d "$BODY" "$API/edits/$EDIT/tracks/$TRACK" >/dev/null
curl -4 -sS --fail-with-body -X POST "${H[@]}" "$API/edits/$EDIT:commit" >/dev/null
echo "uploaded $PKG versionCode $VC to $TRACK ($STATUS)"
