# German Trainer — Implementation Handoff Brief

**Audience:** an implementation agent (Claude Code) building this project from
scratch. Assume **no** prior conversation context — everything you need is here.
**Date written:** 2026-10-16
**Author:** mentor/planning session (Opus 4.8)
**Status:** design approved by the user; **not yet started** (empty project
folder + this doc only).

---

## 0. TL;DR — what you're building and what to do first

A **personal, offline Android app** for the user to study one Memrise
community course ("A2 German: Beyond Beginner", course id **690189**). The
course content is captured once from Memrise and then studied locally.

Architecture the user explicitly chose: **Rust core compiled to an Android
native library via the NDK, with a Kotlin + Jetpack Compose UI calling it over
a uniffi bridge.** All the interesting logic (data, database, spaced-repetition
scheduling, answer checking) lives in Rust; Kotlin only draws and handles input.

**First three actions, in order:**
1. **Set up the toolchain** (Section 9) — `cargo-ndk`, Rust Android targets, a
   modern NDK. Verify a trivial Rust `.so` builds and loads in the app before
   writing real code.
2. **Capture the course data** (Section 8) — this is **time-sensitive**:
   Memrise is sunsetting the community-courses site. Get `seed.sqlite` +
   `seed.json` locked down early. Needs the user's Memrise login (they will
   supply a session token — never ask for a password).
3. **Build the Rust core engine desktop-first** (Sections 5–6), unit-tested on
   the PC with no emulator, then bridge to Kotlin.

**Do not skip the brainstorming/planning discipline:** before writing product
code, confirm the plan with the user. This doc is the design brief; turn it
into a concrete step plan (the `superpowers:writing-plans` skill) and get the
user's go-ahead on milestones.

---

## 1. Purpose & scope

- **Who:** the user, personally. Single user, their own device(s).
- **Why:** study the A2 German vocabulary from the Memrise course, offline,
  after the Memrise site goes away.
- **Non-goals:** **No publishing, no app store, no distribution, no
  monetization.** This matters: because it is strictly personal use of content
  the user is capturing from their own Memrise account, there is no content-
  rights work to do. If scope ever changes to sharing/publishing, STOP and
  raise the content-licensing question first — the community course is
  user-generated content under Memrise's terms.
- **Platform:** Android (emulator first — see Section 9; the user has no
  physical device connected). Keep the Rust core portable so a desktop build
  stays possible, purely to make testing fast.

---

## 2. Decisions locked with the user

| # | Decision |
|---|----------|
| D1 | Personal use only; no publishing. |
| D2 | Architecture: **Rust core (NDK `.so`) + Kotlin/Jetpack Compose UI**, bridged with **uniffi**. |
| D3 | Rust owns: data model, SQLite DB, SRS scheduler, answer checking, session building. Kotlin owns: screens, navigation, text input, audio playback, TTS. UI depends on core, never the reverse. |
| D4 | Study modes: **SRS spaced repetition** (backbone), **typing test**, **multiple choice**, **listening/audio**. |
| D5 | **Adaptive review speed based on word length** — longer words are treated as harder (reviewed sooner + more answer time), and this is **user-adjustable**. This is a deliberate, non-default feature the user asked for specifically. |
| D6 | Content captured once from Memrise course **690189** using the user's own account, into a local DB the user owns. |
| D7 | Project lives at **`/home/HSI/workspace/merkrise`** (ext4). **Never build on `/run/media/HSI/Data`** — that is an NTFS disk whose `ntfs3` driver corrupts freshly-built binaries under load (it SIGSEGV'd a Rust build there earlier). Keep `target/` on ext4. |

## 3. Open decisions (defaults chosen — fine to proceed, revisit if the user objects)

| # | Default | Note |
|---|---------|------|
| O1 | SRS algorithm = **SM-2** (classic Anki) behind a `Scheduler` trait. | Chosen for simplicity + teachability; swap to FSRS later without touching UI. |
| O2 | App name = **Merkrise** (decided 2026-10-20; see spec D3). Rust crate `merkrise-core`, app id `com.hsi.merkrise`, folder `merkrise`. | Superseded — this O2 default no longer applies. |
| O3 | SQLite owned by Rust via `rusqlite` (bundled). Kotlin never opens the DB. | Keeps single source of truth in core. |
| O4 | Listening mode uses captured audio when present; **Android TTS** as fallback for words with no audio. | Confirm during capture whether 690189 ships audio. |

---

## 4. Architecture

Three components:

```
┌─────────────────────────────┐        ┌──────────────────────────┐
│ Android app (Kotlin/Compose)│  uniffi │  german-core  (Rust)     │
│  screens, nav, text input,  │◄───────►│  data model, SQLite,     │
│  audio playback, TTS        │  (JNI)  │  SRS scheduler, answer    │
│                             │         │  check, session builder  │
└─────────────────────────────┘        └──────────────┬───────────┘
                                                       │ rusqlite
                                                 ┌─────▼──────┐
                                                 │ app.sqlite │  (on device)
                                                 └────────────┘

   memgrab (Rust CLI, run once on PC) ──► seed.sqlite + seed.json
          │ (Memrise course 690189, user's session)
          └─► bundled into app assets / imported on first run
```

**Runtime data flow:** Compose screen → calls core (`start_session`,
`next_card`, `submit_answer`) → core runs SRS logic + updates SQLite → returns
the next `Card` and/or an `AnswerResult` → Compose renders. Audio: core returns
an audio reference/path; Kotlin plays it (`MediaPlayer`) or synthesizes via
`TextToSpeech`.

**Workspace layout (proposed):**
```
merkrise/
├─ HANDOFF.md                 ← this file
├─ docs/                      ← specs, plans, notes
├─ core/                      ← Rust crate `german-core` (cdylib + rlib)
│  ├─ Cargo.toml
│  └─ src/{lib.rs, model.rs, db.rs, srs.rs, session.rs, answer.rs, ffi.rs}
├─ memgrab/                   ← Rust CLI to pull Memrise data (bin crate)
├─ android/                   ← Gradle project (Kotlin + Compose)
│  └─ app/src/main/...        ← uniffi-generated bindings land here
└─ Cargo.toml                 ← workspace (members = ["core", "memgrab"])
```
(Keep the Android Gradle project either nested at `android/` or as a sibling;
pick whichever makes `cargo-ndk` output paths cleanest and document it.)

---

## 5. Rust core — API, model, schema

### 5.1 uniffi-exported API (sketch — refine as you build)
Use **uniffi** proc-macros (`#[uniffi::export]`, `#[derive(uniffi::Record/Enum)]`).

```
fn init(db_path: String) -> Result<(), CoreError>
fn import_seed(seed_path: String) -> Result<ImportStats, CoreError>  // idempotent
fn due_count() -> u32
fn start_session(mode: ReviewMode, max_cards: u32) -> Result<(), CoreError>
fn next_card() -> Option<Card>
fn submit_answer(card_id: u64, user_input: String) -> AnswerResult
fn skip_card(card_id: u64)
fn end_session() -> SessionSummary
fn set_pace_settings(s: PaceSettings)
fn get_pace_settings() -> PaceSettings
fn stats() -> Stats
```

### 5.2 Core types
```
Word { id, term /*German*/, meaning /*English*/, pos: Option<String>,
       audio_path: Option<String>, level_id, position }
ReviewMode { Srs, Typing, MultipleChoice, Listening }
PromptMode { ShowTerm, ShowMeaning, PlayAudio }   // what the card presents
Card { word_id, prompt_mode, prompt_text, audio_path: Option<String>,
       choices: Option<Vec<String>>,  // populated for MultipleChoice
       answer_time_ms: u32 }          // time allowance, length-adjusted
AnswerResult { correct: bool, expected: String, normalized_input: String,
               grade: u8, next_due_epoch: i64 }
PaceSettings { base_new_per_day: u32, base_interval_scale: f32,
               length_weight: f32 /*0.0 = ignore length … 1.0 = strong*/ }
SessionSummary { reviewed, correct, new_introduced, duration_ms }
Stats { total_words, learned, due_today, streak_days }
```

### 5.3 SQLite schema (owned by Rust)
```
levels(id INTEGER PK, course_id INTEGER, position INTEGER, title TEXT)
words(id INTEGER PK, level_id INTEGER, position INTEGER,
      term TEXT, meaning TEXT, pos TEXT NULL, audio_path TEXT NULL,
      attrs_json TEXT NULL)
review_state(word_id INTEGER PK REFERENCES words(id),
      ease REAL DEFAULT 2.5, interval_days REAL DEFAULT 0, reps INTEGER DEFAULT 0,
      lapses INTEGER DEFAULT 0, due_epoch INTEGER DEFAULT 0,
      stage TEXT DEFAULT 'new',   -- new | mc | typing | recall | known
      last_grade INTEGER NULL, last_seen_epoch INTEGER NULL)
settings(key TEXT PK, value TEXT)   -- holds PaceSettings etc.
session_log(id INTEGER PK, started_epoch INTEGER, mode TEXT, summary_json TEXT)
```

---

## 6. SRS engine spec

- **Base = SM-2.** Per card: `ease` (default 2.5, floor 1.3), `interval_days`,
  `reps`, `lapses`, `due_epoch`. On a correct answer advance the interval
  (`reps 0→1 day, 1→6 days, else interval*ease`); on a wrong answer reset reps
  and shorten (lapse). Put this behind a `trait Scheduler` so FSRS can replace
  it later.
- **Word-length modifier (feature D5):**
  - Compute `len_factor` from the German term length (e.g. normalize character
    count against the course's mean length → a multiplier roughly in `[0.6,
    1.6]`). Longer = harder.
  - Apply to the scheduled interval: `interval *= (1 - length_weight) +
    length_weight / len_factor` so longer words become due **sooner** (smaller
    interval). With `length_weight = 0` the rule is off and it's pure SM-2.
  - Apply to the per-card **answer-time allowance** in timed modes:
    `answer_time_ms = base_time * len_factor` (longer words get more time).
  - Both governed by `PaceSettings` so the user tunes `length_weight` and base
    pace in a settings screen.
- **Session building:** today's due cards (`due_epoch <= now`) + up to
  `base_new_per_day` new words. New words graduate through stages
  `new → mc → typing → recall → known` (the Memrise-style progression), which
  also selects which sub-mode the SRS review presents.
- **Must be unit-tested on desktop** (deterministic clock injected) — this is
  the main reason the logic lives in Rust.

---

## 7. Study modes (UI ↔ core)

- **SRS review** — the default. Core returns due/new cards; the card's `stage`
  decides the sub-mode shown (new → multiple choice, mid → typing, mature →
  free recall). This *is* the main loop.
- **Typing** — Compose text field; send raw text to `submit_answer`. Core
  normalizes: trim, case-fold, and treat `ä/ö/ü/ß` ⇄ `ae/oe/ue/ss` as
  equivalent (German learners type both). Report the canonical expected answer.
- **Multiple choice** — core fills `Card.choices` with the right answer + N
  plausible distractors drawn from the same course/level; Compose renders
  buttons.
- **Listening** — `prompt_mode = PlayAudio`; Compose plays `audio_path`
  (`MediaPlayer`) or, if absent, speaks the German via Android `TextToSpeech`
  (German locale); user recalls the meaning.

---

## 8. Content capture (`memgrab`) — TIME-SENSITIVE

The course page at
`https://community-courses.memrise.com/community/course/690189/a2-german-beyond-beginner/`
is a **JavaScript single-page app** — the raw HTML has no vocabulary; the data
is fetched from a backend the frontend calls. So:

1. **Discover the endpoint (investigative, do with the user):** have the user
   open the course **while logged in**, open browser DevTools → Network, and
   capture the XHR/fetch request(s) that return the level/word JSON (and the
   request headers, especially the session cookie — likely `sessionid` — and
   any CSRF token). Record the exact URL shape and pagination (courses are
   split into "levels"; each level lists items with columns = term, meaning,
   and attribute/audio columns).
2. **Replicate in Rust** (`reqwest` + `serde_json`): authenticate with the
   user-supplied session cookie, walk every level, extract `term`, `meaning`,
   any part-of-speech/attribute columns, and **audio file URLs**; download the
   audio files locally.
3. **Output:** `seed.sqlite` (the schema in 5.3, `review_state` empty) **and**
   `seed.json` (a human-readable backup the user keeps forever). Put audio in
   `assets/audio/`.
4. **Security — mandatory:**
   - **Never** ask the user for their Memrise password, and never put
     credentials in this chat or in committed files.
   - Read the session token from an **environment variable or a local,
     git-ignored file** the user creates (e.g. `memgrab/.secret.env`).
   - Add `*.secret*`, `.env`, and any token file to `.gitignore` from the
     start.
   - The token is the user's — treat captured data as personal data.

If the endpoint can't be reached (site already down / login blocked), fall back
to: user exports via Memrise's own "download" if available, or the
`decks`/community archive, or a manual DevTools JSON copy. Flag to the user
immediately if capture is at risk — the data is the one irreplaceable input.

---

## 9. Environment — verified state (2026-10-16) and what to install

**Already present on this machine (ext4 `/home/HSI`):**
- Rust `rustc`/`cargo` **1.95.0**, `rustup` 1.29.0.
- Android Studio at **`/opt/android-studio`** (bundled JDK: OpenJDK **21** at
  `/opt/android-studio/jbr`). Use that JBR as `JAVA_HOME` for Gradle.
- Android SDK at **`/home/HSI/Android/Sdk`**
  - platforms: **android-34, android-35, android-36.1**
  - build-tools: 34.0.0, 35.0.0, 36.1.0, 37.0.0
  - `adb` at `$SDK/platform-tools/adb` (works; **no physical device**
    connected)
  - emulator AVD **`Pixel_9`** exists (`~/.android/avd`) — this is the test
    target.
  - **NDK: only `21.4.7075529`** — old; r21 dropped `libgcc` handling that
    fights modern Rust. **Install a current NDK (r26 or r27)** via Android
    Studio's SDK Manager (or `sdkmanager "ndk;27.x"`; note `cmdline-tools`
    appeared empty, so the GUI SDK Manager is the reliable route).

**Must install / configure (the "setup phase"):**
- Rust Android targets: `rustup target add aarch64-linux-android
  x86_64-linux-android armv7-linux-androideabi i686-linux-android`
  (x86_64 is needed for the Pixel_9 emulator; aarch64 for real ARM devices).
- `cargo install cargo-ndk`.
- **uniffi**: add `uniffi` to the core crate; set up the Gradle plugin /
  binding-generation step so Kotlin bindings are produced on build.
- Export `ANDROID_HOME=/home/HSI/Android/Sdk` and
  `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>` for builds.
- **Prove the pipeline with a hello-world `.so`** (one exported function
  returning a string, shown on screen via uniffi) **before** building the real
  engine. Do not build features on an unproven bridge.

---

## 10. Build & run workflow (emulator)

- Build the Rust `.so` for the emulator ABI: `cargo ndk -t x86_64 -o
  android/app/src/main/jniLibs build --release` (and `-t arm64-v8a` for real
  devices). Confirm the final `cargo-ndk` invocation and output path once the
  Gradle project exists.
- Boot the emulator headlessly from here:
  `$SDK/emulator/emulator -avd Pixel_9 -no-window -no-audio -gpu swiftshader_indirect &`
  then wait for `adb wait-for-device` + `sys.boot_completed`.
- Install/run: Gradle `:app:installDebug`, then
  `adb shell am start -n com.hsi.germantrainer/.MainActivity`.
- Logs: `adb logcat`. Rust panics/logs surface via the `android_logger` crate —
  wire it in the core's `init`.

## 11. Testing

- **Core (desktop, no emulator):** unit tests for SM-2 math, the word-length
  modifier, answer normalization (umlaut/ß equivalence), and distractor
  generation. Inject a mock clock so scheduling is deterministic. This is the
  bulk of the correctness work — do it here, fast.
- **Bridge:** a tiny desktop harness (or `cargo test`) that calls the core the
  way Kotlin will, to catch API-shape problems before the emulator.
- **App:** smoke-test each screen on `Pixel_9`; verify one full SRS session,
  typing with umlauts, a multiple-choice round, and audio/TTS playback.

## 12. Suggested phased plan (turn into a real plan via writing-plans)

1. **Setup & bridge** — install toolchain (Section 9); workspace + `core` crate
   + nested Gradle app; hello-world `.so` through uniffi shown on the emulator.
2. **Capture** — `memgrab` → `seed.sqlite` + `seed.json` (+ audio). Lock the
   data down. (Can run in parallel with step 1; it's the time-sensitive one.)
3. **Core engine** — model, DB, SM-2 + word-length rule, answer checking,
   session builder; fully unit-tested on desktop.
4. **Study UI** — Compose screens for SRS loop, typing, multiple choice,
   listening; settings screen for pace/length-weight; progress/stats.
5. **Polish** — audio/TTS, streaks, daily new-card cap, import-on-first-run.

Each phase ends with something runnable on the emulator and the user's sign-off.

## 13. Gotchas / constraints

- **NTFS:** never put `target/` or build output on `/run/media/HSI/Data`
  (ntfs3 corrupts fresh binaries under load). Everything stays on ext4.
- **NDK r21 is too old** for smooth modern Rust Android linking — install r26/r27
  first; don't waste time fighting r21 linker errors.
- **Memrise is sunsetting** the community site — capture data early; it is the
  one input you cannot reconstruct.
- **uniffi + Compose** binding/build wiring is the fiddliest integration point —
  prove it with hello-world before real features.
- **German normalization:** ä/ö/ü/ß ⇄ ae/oe/ue/ss and case/whitespace must be
  handled in `submit_answer`, or typing mode will feel broken.
- **No physical device:** target the `Pixel_9` emulator (x86_64 ABI) for the dev
  loop.
- **Secrets:** Memrise session token via env/git-ignored file only; never
  committed, never in chat.

## 14. Conventions

- Keep logic in Rust; Kotlin is a thin view layer. If you're tempted to put
  scheduling/answer logic in Kotlin, that's a smell — it belongs in `core`.
- Commit the design/plan docs under `docs/`. Initialize git early;
  `.gitignore` must exclude `target/`, `*.so`, secrets, captured personal data,
  and large audio if the user prefers.
- This is a learning project for the user (they want to understand the NDK
  bridge) — favor clear, well-commented, idiomatic code over clever shortcuts,
  and explain the bridge wiring in `docs/`.
