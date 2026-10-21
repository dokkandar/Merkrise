#!/bin/sh
# Build merkrise-core as an Android .so via cargo-ndk, into the app's jniLibs.
# M1 builds only the x86_64 (emulator) ABI; add arm64-v8a later for real devices.
set -eu

export ANDROID_HOME="${ANDROID_HOME:-/home/HSI/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
# Ensure cargo/rustup are found even when invoked from Gradle's environment.
export PATH="$HOME/.cargo/bin:$PATH"

cd "$(dirname "$0")/.."
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p merkrise-core --release
