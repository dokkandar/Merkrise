#!/bin/sh
# Generate the Kotlin uniffi bindings from the built x86_64 library.
# Uses --library mode (reads metadata from the .so; no UDL needed), with the
# same uniffi version as the crate.
set -eu

cd "$(dirname "$0")/.."
SO=android/app/src/main/jniLibs/x86_64/libmerkrise_core.so
OUT=android/app/src/main/java

[ -f "$SO" ] || { echo "missing $SO — run scripts/build-core-android.sh first" >&2; exit 1; }

cargo run -q -p merkrise-core --bin uniffi-bindgen -- \
    generate --library "$SO" --language kotlin \
    --config core/uniffi.toml --out-dir "$OUT"
