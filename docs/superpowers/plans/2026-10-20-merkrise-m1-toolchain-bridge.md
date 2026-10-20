# Merkrise M1 — Toolchain + Rust↔Kotlin Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Rust `merkrise-core` crate exposes functions over **uniffi**, and the
Android "Merkrise" app calls one of them across the JNI bridge and shows its
result on the Pixel_9 emulator — proving the whole architecture end-to-end before
any real feature.

**Architecture:** Cargo workspace with a `merkrise-core` cdylib+rlib crate built
for Android ABIs via `cargo-ndk`; uniffi generates Kotlin bindings; a Jetpack
Compose app loads the `.so`, calls the generated binding, and renders the string.

**Tech Stack:** Rust 1.95, uniffi 0.28.x (proc-macro mode), cargo-ndk, Android
NDK r27, Android Gradle Plugin + Kotlin + Jetpack Compose, JDK 21 (Android Studio
JBR), Pixel_9 emulator (x86_64).

**Spec:** `docs/superpowers/specs/2026-10-20-merkrise-v1-design.md` (see §4
architecture, §10 toolchain/build, §13 layout).

## Global Constraints

- Build only on ext4 `/home/HSI/workspace/merkrise`; **never** on
  `/run/media/HSI/Data` (NTFS corrupts fresh binaries). `target/` stays on ext4.
- App id = `com.hsi.merkrise`; Rust crate = `merkrise-core`; Gradle app label =
  "Merkrise".
- Emulator ABI = **x86_64** (Pixel_9); build the `.so` for `x86_64` (add
  `arm64-v8a` later for real devices — not in M1).
- `JAVA_HOME` = `/opt/android-studio/jbr` (JDK 21) for Gradle.
- `ANDROID_HOME` = `/home/HSI/Android/Sdk`; `ANDROID_NDK_HOME` = the r27 NDK
  installed in Task 2.
- compileSdk/targetSdk = 35; minSdk = 26.
- `.gitignore` already excludes `target/`, `*.so`, `capture/`; keep build output
  and the NDK out of git.

## Review Focus

- **Wrong/old NDK** → modern-Rust linker errors (`libgcc`/`libunwind`). Task 2's
  verification builds the `.so` with r27 and fails loudly if the linker breaks.
- **ABI mismatch** → app installs but crashes with `UnsatisfiedLinkError` because
  the `.so` ABI ≠ emulator ABI. Task 4 verifies the `x86_64` `.so` is packaged
  and the app loads it on the x86_64 emulator (logcat shows no link error).
- **uniffi version skew** between the crate and the Kotlin bindings → runtime
  `uniffi` checksum panic. Task 3 pins one uniffi version and generates bindings
  from that exact build.
- **`.so` not packaged into the APK** (wrong `jniLibs` path) → `UnsatisfiedLinkError`.
  Task 4 verifies `jniLibs/x86_64/libmerkrise_core.so` exists in the built APK.
- **Gradle using the wrong JDK** → build fails or uses a stale JVM. Task 4 pins
  `JAVA_HOME`/`org.gradle.java.home` to the JBR and the build succeeds.

---

### Task 1: Rust workspace + `merkrise-core` with a uniffi-exported function (host-testable)

**Files:**
- Create: `Cargo.toml` (workspace), `core/Cargo.toml`, `core/src/lib.rs`
- Test: `core/src/lib.rs` (`#[cfg(test)]` module)

**Interfaces:**
- Produces: `pub fn core_version() -> String` (returns a fixed banner, e.g.
  `"Merkrise core 0.1.0"`), exported with `#[uniffi::export]`; crate sets up
  `uniffi::setup_scaffolding!()`.

- [ ] **Step 1: Write the failing test** in `core/src/lib.rs`:
```rust
#[test]
fn version_banner_is_stable() {
    assert_eq!(super::core_version(), "Merkrise core 0.1.0");
}
```
- [ ] **Step 2: Run `cargo test -p merkrise-core`** — Expected: FAIL (`core_version` not found).
- [ ] **Step 3: Implement** the workspace `Cargo.toml` (`members = ["core"]`), and
  `core/Cargo.toml` with `[lib] crate-type = ["cdylib", "lib"]`, `uniffi = "0.28"`
  dependency + `[build-dependencies] uniffi = { version = "0.28", features = ["build"] }`
  and `features = ["cli"]` as needed. In `core/src/lib.rs` add
  `uniffi::setup_scaffolding!();` and `#[uniffi::export] pub fn core_version() -> String { "Merkrise core 0.1.0".into() }`.
- [ ] **Step 4: Run `cargo test -p merkrise-core`** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(core): merkrise-core crate with uniffi core_version()`.

### Task 2: Android build toolchain — targets, cargo-ndk, NDK r27; build the `.so`

**Files:**
- Create: `scripts/build-core-android.sh` (wraps the cargo-ndk invocation)
- Modify: none (installs tooling + NDK outside the repo)

**Interfaces:**
- Produces: `scripts/build-core-android.sh` that outputs
  `android/app/src/main/jniLibs/x86_64/libmerkrise_core.so`.

- [ ] **Step 1:** Add Rust targets:
  `rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi i686-linux-android`.
- [ ] **Step 2:** `cargo install cargo-ndk` — verify `cargo ndk --version`.
- [ ] **Step 3:** Install **NDK r27c** by direct download (no GUI/sdkmanager):
  download `https://dl.google.com/android/repository/android-ndk-r27c-linux.zip`
  into a fresh temp dir, verify it unzips, and move it to
  `$ANDROID_HOME/ndk/27.2.12479018/` (or keep its path and export
  `ANDROID_NDK_HOME`). Treat the download dir as untrusted (don't run anything
  from it).
- [ ] **Step 4:** Write `scripts/build-core-android.sh`:
```sh
#!/bin/sh
set -eu
export ANDROID_HOME=/home/HSI/Android/Sdk
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:?set to the r27 NDK path}
cargo ndk -t x86_64 -o android/app/src/main/jniLibs build -p merkrise-core --release
```
- [ ] **Step 5:** Run it — Expected: exits 0 and
  `android/app/src/main/jniLibs/x86_64/libmerkrise_core.so` exists
  (`file` reports an x86-64 shared object). If the linker errors on `libgcc`,
  the NDK is wrong — stop and fix Step 3.
- [ ] **Step 6: Commit** `build: android core build script + r27 toolchain` (script only; NDK/targets are environment).

### Task 3: uniffi Kotlin bindings generation

**Files:**
- Create: `scripts/gen-bindings.sh`; generated output under
  `android/app/src/main/java/com/hsi/merkrise/core/` (git-tracked or regenerated — see step)

**Interfaces:**
- Consumes: Task 1's crate (the `.so` or library) + Task 2's build.
- Produces: `uniffi`-generated `merkrise_core.kt` exposing `coreVersion(): String`.

- [ ] **Step 1:** Add a `uniffi-bindgen` entry (a tiny `bin` in the core crate or
  the `uniffi::uniffi_bindgen_main` pattern) so bindings can be generated from the
  built library with the **same uniffi version** as Task 1.
- [ ] **Step 2:** Write `scripts/gen-bindings.sh` that runs the bindgen for the
  `kotlin` language against the built `x86_64` library, outputting into
  `android/app/src/main/java/`.
- [ ] **Step 3:** Run it — Expected: `merkrise_core.kt` is produced and contains a
  `fun coreVersion(): String` (name per uniffi's camelCase mapping).
- [ ] **Step 4: Commit** `build: uniffi kotlin binding generation`.

### Task 4: Android Compose app that calls the bridge, verified on the emulator

**Files:**
- Create: `android/` Gradle project — `settings.gradle.kts`, `build.gradle.kts`,
  `gradle.properties`, `app/build.gradle.kts`,
  `app/src/main/AndroidManifest.xml`,
  `app/src/main/java/com/hsi/merkrise/MainActivity.kt`
- Consumes: Task 2's `.so` (in `app/src/main/jniLibs/x86_64/`), Task 3's bindings.

**Interfaces:**
- Consumes: `coreVersion(): String` from the generated bindings; loads the native
  lib via `System.loadLibrary("merkrise_core")` (uniffi's generated loader).

- [ ] **Step 1:** Scaffold the Gradle app: `applicationId = "com.hsi.merkrise"`,
  `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, Kotlin + Compose enabled,
  `ndk { abiFilters += "x86_64" }`, and the JNA/uniffi runtime dependency uniffi
  requires for Kotlin (`net.java.dev.jna:jna` with the `@aar` classifier).
  `gradle.properties` sets `org.gradle.java.home=/opt/android-studio/jbr`.
- [ ] **Step 2:** `MainActivity.kt` — a Compose `setContent { Text(coreVersion()) }`
  (centered), importing the generated binding package.
- [ ] **Step 3:** Boot the emulator headless and wait for it:
  `"$ANDROID_HOME"/emulator/emulator -avd Pixel_9 -no-window -no-audio -gpu swiftshader_indirect &`
  then `adb wait-for-device` and poll `getprop sys.boot_completed`.
- [ ] **Step 4:** Build + install:
  `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:installDebug`.
- [ ] **Step 5: Verify the bridge works** — launch
  `adb shell am start -n com.hsi.merkrise/.MainActivity`, then confirm **no**
  `UnsatisfiedLinkError` or uniffi checksum panic in `adb logcat`, and capture a
  screenshot (`adb exec-out screencap -p > /tmp/merkrise-m1.png`) showing
  "Merkrise core 0.1.0". Also confirm `app/build/outputs/apk/debug/*.apk` contains
  `lib/x86_64/libmerkrise_core.so` (`unzip -l`).
- [ ] **Step 6: Commit** `feat(app): compose app renders core_version over uniffi bridge`.

---

## Done when
`adb` launches Merkrise on the Pixel_9 emulator and the screen shows
**"Merkrise core 0.1.0"** — the string originates in Rust and crosses the uniffi
bridge. The architecture is proven; Milestone 2 (the core engine) and Milestone 3
(study UI) get their own plans.
