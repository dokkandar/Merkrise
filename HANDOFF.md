# Merkrise — Handoff

**For:** the next developer or agent taking over this project.
**Updated:** 2026-10-21. Supersedes the original pre-build design brief (that
earlier content now lives in the spec, `docs/superpowers/specs/`).

Merkrise is a **personal, offline Android app** for studying German vocabulary —
a self-hosted Memrise-style quiz built from the user's own captured
*A2 German: Beyond Beginner* Memrise course (id 690189) plus a custom lesson.
Rust core (NDK `.so`) + Kotlin UI bridged with **uniffi**. Personal use only, no
publishing.

---

## 1. Current status — what works today

A **functional study app**, verified running on the Pixel_9 emulator:

- **Home = chapter list:** all **51 A2 Memrise chapters** (real titles + word
  counts) plus **"Lesson 01 · Dating & Feelings" (64 words)**, and a
  "Study all — random 20" button. **1,681 words total**, all with audio.
- **Study a chapter or all** → a 20-question session mixing:
  - **Multiple choice** (German term → pick the English meaning; 4 options),
  - **Typing** (English → type the German; `ä ö ü ß` helper keys;
    umlaut-tolerant: *über* = *ueber* = *uber*),
  - **Audio** playback (native speaker) on each card.
- Instant ✓/✗ **feedback** (shows the correct answer) and a **score / results**
  screen. "Study again" / "Home".
- Fully **offline** (all data + audio bundled in the APK).

Runnable build: **`dist/merkrise-debug.apk`** (~121 MB).

### Not done yet (the honest list)
- **No persistence / no spaced repetition.** Study state is an in-memory `Vec`;
  every session is a *random* draw. There is no SQLite, no SM-2 scheduler, no
  review_state, and the user's captured Memrise progress is **not** imported yet.
  This is the #1 next milestone (design already in the spec, §5/§6).
- No settings screen, streaks, reversed-MC / audio-MC modes (the 5-stage design).
- Lesson import is **build-time** (a script), not the runtime folder/TSV importer
  described in the spec §16/§16.6.
- UI is plain Android Views (no Jetpack Compose yet).
- Deferred tech debt: 16 KB page-size alignment, `allowBackup=true`→false (real
  data now ships), strip the release `.so`.

---

## 2. How to build & run

**Prereqs** are already installed on this machine (see §6). From the repo root
`/home/HSI/workspace/merkrise`:

```sh
# 1. build the Rust core .so (x86_64) + Kotlin bindings
sh scripts/build-core-android.sh      # cargo-ndk -> android/app/src/main/jniLibs
sh scripts/gen-bindings.sh            # uniffi -> committed Kotlin bindings

# 2. (re)bundle the data into app assets  [only needed if data changed]
sh scripts/bundle-assets.sh                      # A2 seed.json + mp3 -> assets
python3 -I scripts/build-lesson01.py             # merges resources/01 as a chapter
python3 -I scripts/import-pack.py resources/packs/<pack>.zip   # merge content packs (optional)

# 3. build the APK
cd android && JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```
`./gradlew assembleDebug` also re-runs the `.so` build automatically (a Gradle
`buildRustCore` task wired into `preBuild`), so step 1 is optional once set up.

**Run on the emulator** (the user does this from *their* Android Studio terminal,
which has the display — Claude's shell is headless/Wayland and the headless
emulator is sluggish). One-liner:

```sh
/home/HSI/Android/Sdk/emulator/emulator -avd Pixel_9 >/tmp/emu.log 2>&1 & \
ADB=/home/HSI/Android/Sdk/platform-tools/adb; $ADB wait-for-device; \
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; \
$ADB install -r /home/HSI/workspace/merkrise/dist/merkrise-debug.apk; \
$ADB shell am start -n com.hsi.merkrise/.MainActivity
```

**Core tests (desktop, fast):** `cargo test -p merkrise-core` (6 tests).

---

## 3. Repository & data layout — everything is in this folder

```
/home/HSI/workspace/merkrise/
├─ HANDOFF.md              ← this file
├─ Cargo.toml              ← Rust workspace (members = ["core"])
├─ core/                   ← Rust crate `merkrise-core` (the engine)
│  ├─ Cargo.toml           ← uniffi 0.32.2, serde, serde_json, thiserror, fastrand
│  └─ src/{lib.rs, bin/uniffi-bindgen.rs}, uniffi.toml
├─ android/                ← Gradle app (Kotlin, plain Views, com.hsi.merkrise)
│  └─ app/src/main/{java/com/hsi/merkrise/MainActivity.kt,
│                   java/com/hsi/merkrise/core/merkrise_core.kt (generated),
│                   AndroidManifest.xml, jniLibs/ (.so, git-ignored),
│                   assets/ (bundled data, git-ignored — see below)}
├─ scripts/                ← build-core-android.sh, gen-bindings.sh,
│                            bundle-assets.sh, build-lesson01.py, import-pack.py
├─ docs/                   ← specs/plans + LEARNING_ENGINE.md + CONTENT_PACK_FORMAT.md
├─ dist/                   ← built APKs (git-ignored)
├─ resources/ *DATA*       ← ALL source content (git-ignored, LOCAL ONLY — see resources/README.md)
│   ├─ memrise/            ← everything downloaded from Memrise
│   │   ├─ 690189-a2-beyond-beginner/   (A2: seed.json, raw/, audio/ 3178 mp3)
│   │   └─ 37726-advanced-vocabulary/   (Advanced: seed.json, raw/, audio/ 99)
│   ├─ 01/                 ← example custom lesson (folder-per-item format)
│   └─ packs/              ← drop content packs (.zip) here to import (§CONTENT_PACK_FORMAT)
└─ target/                 ← Rust build cache (git-ignored, regenerable)
```

### ⚠ The data is LOCAL-ONLY — keep it with this folder
The **code and docs are on GitHub** (`git@github.com:dokkandar/Merkrise.git`,
branch `main`). The **data and voice files (`resources/`) are git-ignored and are
NOT on GitHub** (large and personal). They exist only inside this folder. **A
fresh git clone will NOT have them.** Do not delete `resources/`, and back it up
separately — the captured audio is irreplaceable (Memrise is sunsetting the
source site).

### The data assets
| Location | What | Size |
|---|---|---|
| `resources/memrise/690189-a2-beyond-beginner/seed.json` | **A2 dataset** — 51 levels, **1,617 words** (term, meaning, pos, audio refs, typing alternatives, the user's Memrise progress) + `raw/` | ~17 MB |
| `resources/memrise/690189-a2-beyond-beginner/audio/` | **A2 voices — 3,178 mp3** (2 per word) | 118 MB |
| `resources/memrise/37726-advanced-vocabulary/` | **Advanced dataset** — 65 levels, **2,084 words** + `raw/` + `audio/` (only 99 — mostly text) | ~5 MB |
| `resources/01/` | example custom lesson — 64 words (`Files/` text + `simon/`,`helmut/` **383 wav**) | 62 MB |
| `resources/packs/` | drop zone for content packs (`.zip`) — see `docs/CONTENT_PACK_FORMAT.md` | — |
| `android/app/src/main/assets/` | **bundled, merged** data that ships in the APK: seed.json (52 levels / 1,681 words = A2 + Lesson 01) + audio (3,242 files). *Derived* — regenerate with the §2 scripts. | 125 MB |

`resources/` holds the masters; `android/app/src/main/assets/` is the derived,
shipped copy. The Advanced course (37726) is captured but **not yet wired into the
app** (it's text-only; integrate as another course when wanted).

---

## 4. Architecture

```
Android app (Kotlin/Compose-free Views)  --uniffi/JNI-->  merkrise-core (Rust)
  MainActivity: home chapter list, quiz screens, audio                |
                                                                 in-memory model
                                                                 (Vec<Word>, no DB yet)
  bundled assets: seed.json + audio/*  --load on first run-->   loaded via load_seed(json)
```

**Rust owns all logic** (data model, quiz/session building, answer checking,
scoring); **Kotlin only draws and plays audio**. The dependency points one way:
UI → core, never the reverse.

### uniffi-exported core API (`core/src/lib.rs`)
```
core_version() -> String
load_seed(json) -> u32                 // parse bundled seed.json, returns word count
word_count() -> u32 ; get_card(i) -> WordCard?    // simple browse
levels() -> Vec<LevelInfo>             // chapter list (index, title, count)
start_session(level: Option<u32>, size) -> u32    // None = whole course
current_question() -> Question?        // {position,total,kind,prompt,prompt_audio,choices}
submit_answer(input) -> AnswerFeedback // {correct, expected, finished}
session_progress() -> SessionProgress? // {total, answered, correct, finished}
```
Enums/records: `QKind{MultipleChoice,Typing}`, `WordCard`, `Question`,
`AnswerFeedback`, `SessionProgress`, `LevelInfo`, `CoreError`.
Answer checking normalizes case/space and folds `ä→ae ö→oe ü→ue ß→ss`, and
accepts the captured `typing_alternatives`.

### How the data was obtained (for re-capture if ever needed)
A2 course was captured once from the live Memrise API with the user's session
cookie: `POST community-courses.memrise.com/v1.25/learning_sessions/preview/`
with body `{"session_source_id":690189,"session_source_type":
"course_id_and_level_index","session_source_sub_index":<1..51>}`. Audio on
`static.memrise.com` is public. (The throwaway capture scripts were not kept;
`capture/` holds the results.) The old `/v1.17/` API is gone (410).

---

## 5. Toolchain (verified on this machine)
- Rust 1.95, `cargo-ndk` 4.1.2, **NDK r27c** (`27.2.12479018`, at
  `$ANDROID_HOME/ndk/`), uniffi **0.32.2** (pin the crate and bindgen to the same
  version or Kotlin panics on a checksum mismatch).
- Android: AGP **8.6.0**, Gradle **8.7** (cached), Kotlin **1.9.24**, JNA aar,
  compileSdk/targetSdk **35**, minSdk **26**, ABI **x86_64** (emulator only; add
  `arm64-v8a` for real devices). `JAVA_HOME=/opt/android-studio/jbr` (JDK 21).
- `ANDROID_HOME=/home/HSI/Android/Sdk`, emulator AVD **Pixel_9**.
- **Build on ext4 only** (`/home/HSI/workspace/merkrise`); never on the NTFS
  `/run/media/HSI/Data` (its driver corrupts fresh binaries).

---

## 6. Design docs (deeper detail)
- **⭐ Learning engine (how teaching + spaced repetition work):**
  `docs/LEARNING_ENGINE.md` — the authority for the SRS/teaching flow (the
  difficulty + time ladders, sessions, progress seeding, schema, pseudocode).
  Read this before building step 1 of §7.
- **⭐ Content pack format (how to feed new content in):**
  `docs/CONTENT_PACK_FORMAT.md` — the `.zip` import rule (AI-friendly); reference
  importer is `scripts/import-pack.py`.
- **Spec (overall design authority):** `docs/superpowers/specs/2026-10-20-merkrise-v1-design.md`
  — full schema, the SM-2 + word-length SRS design, the 5-stage Memrise
  progression, Memrise→SM-2 progress-import mapping, the bulk-import format
  (§16) and folder lesson-package format (§16.6).
- **M1 plan:** `docs/superpowers/plans/2026-10-20-merkrise-m1-toolchain-bridge.md`.
- **Import examples:** `docs/examples/` (sample `.tsv` + how-to).

---

## 7. Next steps (recommended order)
1. **Persistence + spaced repetition** (the big one): implement
   **`docs/LEARNING_ENGINE.md`** — SQLite `review_state`, the two-ladder scheduler
   (12h→…→180d intervals coupled to MC→reversed→audio→typing), learn-new +
   review sessions, and seeding from the user's captured `memrise_progress` so
   mastered words aren't re-taught. Replaces today's random sessions.
2. Settings (pace / length-weight), streaks, progress; the reversed-MC and
   audio-MC modes.
3. Runtime importers: the folder lesson-package (§16.6) and TSV bulk-import
   (§16), so new lessons can be added on-device without the build-time script.
4. Clear the deferred debt: 16 KB alignment (`-C link-arg=-Wl,-z,max-page-size=16384`
   + a 16 KB-aligned JNA), `allowBackup=false`, strip the release `.so`.
   Consider migrating the UI to Jetpack Compose.

## 8. Gotchas
- **uniffi version skew** → Kotlin checksum panic; keep crate + bindgen at 0.32.2.
- **Android 15 edge-to-edge** (targetSdk 35) draws under the status bar — the app
  uses `fitsSystemWindows` + `NoActionBar`; keep that or top content hides.
- The emulator's **16 KB compatibility** popup on launch is harmless (tap OK).
- Lesson 01 English meanings were **translated by Claude** (see
  `scripts/build-lesson01.py` `EN` dict) — the user may want to review them.
- Regenerating assets re-copies 124 MB; `build-lesson01.py` is idempotent.
