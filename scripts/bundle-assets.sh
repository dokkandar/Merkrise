#!/bin/sh
# Copy the captured course data into the Android app's assets so it ships in the
# APK. Idempotent: existing audio is skipped (cp -n), so re-runs are fast.
set -eu
cd "$(dirname "$0")/.."
A=android/app/src/main/assets
mkdir -p "$A/audio"
# seed.json is NOT copied from capture/seed.json: that is the un-chunked A2 course WITHOUT Lesson 01,
# and shipping it would drop Lesson 01 progress. The bundled seed is generated from the merged master.
PY=$(command -v python3 || command -v python)
[ -f capture/seed-merged.json ] || { echo "missing capture/seed-merged.json (the merged A2 + Lesson 01 master)" >&2; exit 1; }
"$PY" -I scripts/build-lessons.py capture/seed-merged.json "$A/seed.json"
cp -n capture/audio/*.mp3 "$A/audio/" 2>/dev/null || true
echo "assets ready: lessons seed.json + $(ls "$A/audio" | wc -l) mp3 ($(du -sh "$A" | cut -f1))"
