#!/bin/sh
# Copy the captured course data into the Android app's assets so it ships in the
# APK. Idempotent: existing audio is skipped (cp -n), so re-runs are fast.
set -eu
cd "$(dirname "$0")/.."
A=android/app/src/main/assets
mkdir -p "$A/audio"
cp -f resources/memrise/690189-a2-beyond-beginner/seed.json "$A/seed.json"
cp -n resources/memrise/690189-a2-beyond-beginner/audio/*.mp3 "$A/audio/" 2>/dev/null || true
echo "assets ready: seed.json + $(ls "$A/audio" | wc -l) mp3 ($(du -sh "$A" | cut -f1))"
