# Merkrise v1 — Design Spec

**Date:** 2026-10-20
**Status:** approved in chat; written for user review before planning.
**Supersedes the "what to build" parts of** `HANDOFF.md` (which remains the
environment/toolchain reference). Where they differ, this spec wins.

---

## 1. Purpose & scope

**Merkrise** is a **personal, offline Android app** for one person (the user) to
study one Memrise community course — *A2 German: Beyond Beginner* (course
**690189**) — after Memrise sunsets its community site. The course content has
already been captured (see §3); this spec covers building the study app around
it.

The app re-creates the **classic Memrise study experience** (the five card
types, the planting/watering progression) on top of a spaced-repetition engine,
and imports the user's real Memrise learning history so they continue rather
than restart.

### In scope for v1
- Native **Android** app: **Rust core (NDK `.so`) + Kotlin/Jetpack Compose UI**,
  bridged with **uniffi** (decision D2).
- The full **five-step Memrise flow**: presentation → multiple choice →
  reversed multiple choice → audio multiple choice → typing, driven as SRS
  stages.
- **SM-2** scheduling with the **word-length pace rule** (decision D5).
- **Import of the user's captured Memrise progress** (growth/interval/dates).
- **Bundled audio** (3,178 mp3) for the course, played directly (offline).
- A **provision for user-added vocabulary** via a bulk-import file format, so
  new words/decks can be added beyond the captured course (see **§16**).
- Emulator target: **Pixel_9** (x86_64); keep the core host-compilable for fast
  tests.

### Out of scope for v1 (explicitly deferred)
- Publishing / app store / distribution / monetization (decision D1).
- More than one *Memrise* course; Memrise re-sync; cloud sync; multi-device.
  (User decks via the §16 bulk-import format **are** in scope.)
- FSRS (SM-2 lives behind a trait so FSRS can replace it later).
- Accounts / login inside the app (it is single-user, local).
- An in-app word *editor* UI (v1 adds words by importing files; a single
  `add_word` entry point exists in the core for a future screen — §16).

If scope ever moves toward sharing/publishing, **stop** and raise content
licensing first — the community course is user-generated content under Memrise's
terms (decision D1).

---

## 2. Locked decisions

| # | Decision |
|---|----------|
| D1 | Personal use only; no publishing. |
| D2 | Architecture: Rust core (NDK `.so`) + Kotlin/Compose UI, bridged with uniffi. Rust owns data/DB/SRS/answer-check/session; Kotlin only draws & plays audio. |
| D3 | Name = **Merkrise**. App id `com.hsi.merkrise`; Rust crate `merkrise-core`; Gradle app "Merkrise". Project folder = `merkrise` (renamed from `german-trainer` on 2026-10-20). |
| D4 | v1 includes the **full** five-step Memrise flow (not a lean subset). |
| D5 | **Word-length-aware, user-adjustable pace**: longer German terms become due sooner and get more answer time. Tunable via `length_weight` (0 = off). |
| D6 | **Import the user's captured Memrise progress** so mastered words aren't re-taught. |
| D7 | **Audio is bundled** inside the app for v1 (simplest, fully offline). |
| D8 | Build order = **full Android from day one**: toolchain+bridge first, then core engine, importer, study UI. |
| D9 | Build on ext4 only (`/home/HSI/workspace/merkrise`); never on the NTFS `/run/media/HSI/Data`. Keep `target/` on ext4. |

SRS algorithm = SM-2 behind a `Scheduler` trait (swap to FSRS later without
touching the UI).

---

## 3. Captured data (already done, 2026-10-16)

All under `capture/` (git-ignored). The capture is complete and verified:

- **51 levels** (with titles), **1,617 words/phrases**, **3,178 audio mp3**
  (~118 MB), **100%** audio and progress coverage.
- `capture/seed.json` (1.9 MB) — the consolidated, cleaned dataset this app
  imports. Per item:
  - `id` (Memrise learnable id, u64), `level` (1..51), `pos` (order in level)
  - `term` (German), `meaning` (English)
  - `item_type` ("word" — includes example sentences)
  - `pos_tag` (part of speech, e.g. "n", "v", "adj"; may be null)
  - `all_forms` (e.g. plural "-en"; may be null)
  - `literal` (literal translation; may be null)
  - `audio` (list of 1–2 mp3 URLs on static.memrise.com → bundled locally)
  - `typing_alternatives` (accepted typed answers incl. umlaut/ß variants)
  - `memrise_progress` ({interval, growth_level, next_date, last_date,
    created_date, correct, attempts, current_streak, total_streak, starred,
    is_difficult, ignored})
- `capture/raw/level_001..051.json` — full raw API responses (kept as backup).
- `capture/audio/` — the 3,178 mp3, named by the URL basename.

Source API (needs a login cookie; recorded for reference only — we don't call it
at runtime): `POST community-courses.memrise.com/v1.25/learning_sessions/preview/`.

---

## 4. Architecture

```
┌─────────────────────────────┐   uniffi   ┌───────────────────────────┐
│ Android app (Kotlin/Compose)│◄──────────►│  merkrise-core  (Rust)    │
│  screens, navigation,       │   (JNI)    │  model, SQLite (rusqlite),│
│  text input, audio playback │            │  SM-2+length scheduler,   │
│  (MediaPlayer)              │            │  answer check, session,   │
│                             │            │  seed import              │
└─────────────────────────────┘            └─────────────┬─────────────┘
                                                          │
                                                    ┌─────▼──────┐
                                                    │ app.sqlite │ (on device)
                                                    └────────────┘
   Bundled app assets: seed.json + audio/*.mp3  ──► imported on first run
```

**Runtime flow:** Compose screen → calls core (`start_session`, `next_card`,
`submit_answer`) → core runs SRS + updates SQLite → returns the next `Card`
and/or an `AnswerResult` → Compose renders. The `Card` names which of the five
templates to show and (for choice cards) carries the choices; Compose plays
`audio_path` with `MediaPlayer`.

**Dependency direction:** UI depends on core; core never depends on UI. Any
scheduling/answer logic in Kotlin is a smell — it belongs in the core.

---

## 5. Data model — SQLite schema (owned by the Rust core)

```sql
levels(
  id INTEGER PRIMARY KEY,        -- Memrise level_id
  idx INTEGER,                   -- 1..51 (display order)
  title TEXT
);

words(
  id INTEGER PRIMARY KEY,        -- Memrise learnable id, or a synthetic id for user words
  level_idx INTEGER,             -- 1..51 for the course; 52+ for user decks
  position INTEGER,              -- order within the level/deck
  term TEXT NOT NULL,            -- German
  meaning TEXT NOT NULL,         -- English
  pos_tag TEXT NULL,             -- part of speech
  all_forms TEXT NULL,
  literal TEXT NULL,
  item_type TEXT,                -- "word" (incl. sentences)
  source TEXT DEFAULT 'memrise'  -- 'memrise' | 'user'  (see §16)
);

word_audio(
  word_id INTEGER REFERENCES words(id),
  path TEXT NOT NULL,            -- local asset path to the mp3
  ord INTEGER                    -- 0,1 (speaker variants)
);

typing_answers(
  word_id INTEGER REFERENCES words(id),
  accepted TEXT NOT NULL         -- one accepted typed form (normalized form stored too)
);

review_state(
  word_id INTEGER PRIMARY KEY REFERENCES words(id),
  ease REAL DEFAULT 2.5,         -- SM-2 ease factor, floor 1.3
  interval_days REAL DEFAULT 0,
  reps INTEGER DEFAULT 0,
  lapses INTEGER DEFAULT 0,
  due_epoch INTEGER DEFAULT 0,   -- unix seconds
  stage TEXT DEFAULT 'new',      -- new|presentation|mc|reversed|audio|typing|known
  starred INTEGER DEFAULT 0,
  last_grade INTEGER NULL,
  last_seen_epoch INTEGER NULL
);

settings(key TEXT PRIMARY KEY, value TEXT);   -- holds PaceSettings etc.
session_log(id INTEGER PRIMARY KEY, started_epoch INTEGER, mode TEXT, summary_json TEXT);

import_log(                                   -- idempotent bulk import (§16)
  filename TEXT PRIMARY KEY, content_hash TEXT, imported_epoch INTEGER,
  words_added INTEGER, words_updated INTEGER);
```

---

## 6. Core engine

### 6.1 The Memrise progression (how v1 "feels like Memrise")

Each word has a **stage**. The stage selects which card type is shown and climbs
as the user succeeds:

```
new → presentation → mc → reversed → audio → typing → known
```

- **new** — never seen. First encounter shows **presentation** (screen 1: term,
  meaning, audio, attributes), then immediately an **mc** card.
- **mc** — multiple choice, pick the German for an English prompt.
- **reversed** — multiple choice, pick the English for a German prompt.
- **audio** — audio multiple choice (hear it, pick the right word).
- **typing** — type the German; accepts the captured `typing_alternatives`.
- **known** — mature; continues to be scheduled by SM-2 but is reviewed with
  **typing** (the hardest recall). Never "graduates out" — spaced forever.

**Advance / demote:** a correct answer advances the stage by one (up to known)
*and* applies the SM-2 interval step. A wrong answer demotes one stage (floor =
mc), records a lapse, and shortens the interval. Distractors for choice cards
are drawn from other words in the same level (fallbacks: same course) — the core
generates them; it does **not** reuse Memrise's captured choice lists (those
were a fixed snapshot).

### 6.2 SM-2 scheduling (behind `trait Scheduler`)

Per word: `ease` (default 2.5, floor 1.3), `interval_days`, `reps`, `lapses`,
`due_epoch`.
- Correct: `reps 0→1` sets interval 1 day; `1→2` sets 6 days; else
  `interval *= ease`. On a clearly-easy grade, nudge `ease += 0.1`.
- Wrong: `reps → 0`, `lapses += 1`, `ease -= 0.2` (floor 1.3), interval reset to
  a short relearning step (e.g. 10 minutes → 1 day).

### 6.3 Word-length pace rule (D5)

Computed from the German `term` length, tunable via `PaceSettings`:
- `mean_len` = mean `term` character count across the course (precomputed once).
- `len_factor = clamp(term_len / mean_len, 0.6, 1.6)` (longer = larger).
- **Interval:** `interval *= (1 - length_weight) + length_weight / len_factor`
  → longer words come due **sooner**. `length_weight = 0` ⇒ pure SM-2.
- **Answer time (timed modes):** `answer_time_ms = base_time_ms * len_factor`
  → longer words get **more** time.
- `PaceSettings { base_new_per_day, base_interval_scale, length_weight,
  base_answer_time_ms }`, stored in `settings`, editable in a settings screen.
  - `base_new_per_day` — new words introduced per day (default **15**).
  - `base_interval_scale` — global multiplier on every SM-2 interval; `1.0` =
    default, `<1` reviews everything more often, `>1` less often (default
    **1.0**).
  - `length_weight` — strength of the word-length rule, `0.0`–`1.0` (default
    **0.3**; `0` turns the rule off).
  - `base_answer_time_ms` — base per-card time allowance before the length
    adjustment (default **8000**).

### 6.4 Answer checking

Normalize before comparing: trim, collapse internal whitespace, case-fold, and
treat `ä/ö/ü/ß ⇄ ae/oe/ue/ss` as equivalent. Accept any value in the word's
`typing_alternatives` (already normalized at import). Return the canonical
expected answer for display.

### 6.5 Session builder

A session = today's due cards (`due_epoch <= now`) + up to `base_new_per_day`
new words (lowest level/position first). Overdue backlog (expected after the
import — see §7) is fed gradually because new+review volume is capped per day.
Order within a session interleaves stages so it doesn't feel monotonous.

### 6.6 Progress import (D6) — Memrise → SM-2 initial state

On first run, for each `memrise_progress` present:
- `interval_days = progress.interval`
- `due_epoch = parse(progress.next_date)` (many are in the past → due now;
  the daily cap spreads them out)
- `reps = progress.correct`; `lapses = max(0, attempts - correct)`
- `ease = 2.5`, then `-0.3` if `is_difficult` (floor 1.3)
- `starred = progress.starred`
- `stage` derived from `interval_days`:
  `0 → new` · `<4 → mc` · `<10 → reversed` · `<30 → audio` · `<60 → typing` ·
  `≥60 → known`
- `ignored == true` → still imported but marked known (user had dismissed it).
Words with no progress start `stage = new`, `due = now`.

---

## 7. uniffi-exported API (core ↔ Kotlin)

```
fn init(db_path: String, assets_dir: String) -> Result<(), CoreError>
fn import_seed(seed_path: String) -> Result<ImportStats, CoreError>  // idempotent
fn due_count() -> u32
fn start_session(max_cards: u32) -> Result<(), CoreError>
fn next_card() -> Option<Card>
fn submit_answer(word_id: u64, user_input: String) -> AnswerResult
fn skip_card(word_id: u64)
fn end_session() -> SessionSummary
fn get_pace_settings() -> PaceSettings
fn set_pace_settings(s: PaceSettings)
fn stats() -> Stats
fn scan_import_dir(dir: String) -> Result<ImportReport, CoreError>   // bulk add, §16
fn add_word(deck: String, term: String, meaning: String, pos: Option<String>,
            forms: Option<String>, literal: Option<String>,
            alt: Vec<String>, audio_path: Option<String>) -> Result<u64, CoreError>
```

Types (uniffi Records/Enums):
```
CardTemplate = Presentation | Mc | Reversed | Audio | Typing
Card { word_id, template, prompt_text, prompt_audio: Option<String>,
       meaning_hint: Option<String>, choices: Option<Vec<Choice>>,
       answer_time_ms: u32 }
Choice { text, audio_path: Option<String> }      // audio cards show playable choices
AnswerResult { correct, expected, normalized_input, new_stage, next_due_epoch }
PaceSettings { base_new_per_day, base_interval_scale, length_weight, base_answer_time_ms }
ImportStats { levels, words, audio_linked, progress_imported }
ImportReport { files_seen, files_imported, words_added, words_updated, errors: Vec<String> }
SessionSummary { reviewed, correct, new_introduced, duration_ms }
Stats { total_words, known, due_today, streak_days }
```

---

## 8. Study UI (Compose)

- **Home** — course title, due-today count, "Start session", current streak,
  quick link to Settings.
- **Session loop** — one composable per template:
  - *Presentation*: term (large), meaning, part-of-speech / all-forms / literal,
    a play-audio button; "Got it" advances.
  - *MC / Reversed / Audio*: prompt (+play button for audio), 2×N choice
    buttons, immediate right/wrong feedback, auto-advance.
  - *Typing*: German prompt hidden, text field (with an umlaut helper row
    `ä ö ü ß`), submit → feedback showing the canonical answer.
  - A thin progress bar + the per-card timer (from `answer_time_ms`).
- **Settings** — `base_new_per_day`, `length_weight` slider (with a plain-words
  explanation), answer-time base.
- **Stats** — total / known / due, streak, per-level progress.

Audio: the `Card`/`Choice` carry a local asset path; Kotlin plays it with
`MediaPlayer`.

---

## 9. Audio & assets (D7)

- All 3,178 mp3 are **bundled in the app** (under `assets/audio/`), plus
  `assets/seed.json`. On first run `import_seed` populates the DB and links each
  `word_audio.path` to its bundled asset. ~118 MB app payload — acceptable for a
  personal sideloaded build.
- **User-added words (§16)** supply their own mp3 (copied in and linked) or, if
  none is given, are spoken with **Android TTS** (German locale) at runtime. TTS
  is used *only* for user words that lack audio — course words always use their
  bundled recordings.
- (Noted for later, not v1: if size becomes a problem, copy audio to app storage
  on first run and ship a smaller APK — the schema already stores paths, so this
  is a non-breaking change.)

---

## 10. Toolchain, build & run (Android)

Per `HANDOFF.md` §9–10 (the verified environment reference):
- Install a modern **NDK (r27)** via Android Studio SDK Manager (r21.4 present is
  too old). Add Rust targets (`aarch64/x86_64/armv7/i686-linux-android`),
  `cargo install cargo-ndk`, add `uniffi` to the core, wire the Gradle
  binding-generation step. Export `ANDROID_HOME`, `ANDROID_NDK_HOME`.
- Build the `.so` for the emulator ABI:
  `cargo ndk -t x86_64 -o android/app/src/main/jniLibs build --release`
  (and `-t arm64-v8a` for real devices).
- **Prove a hello-world `.so` through uniffi on the Pixel_9 emulator before any
  real feature.** This is the single fiddliest integration point.
- Boot emulator headless, `:app:installDebug`, launch
  `com.hsi.merkrise/.MainActivity`; logs via `adb logcat`; wire `android_logger`
  in the core's `init`.

---

## 11. Testing

- **Core (desktop, no emulator)** — the bulk of correctness: SM-2 math, the
  word-length modifier, answer normalization (umlaut/ß + alternatives),
  distractor generation, the stage progression, and the progress-import mapping.
  Inject a mock clock for deterministic scheduling.
- **Importer** — unit test against a trimmed `seed.json` fixture; assert counts,
  audio linkage, and a few known progress mappings.
- **Bridge** — a tiny host harness (or cargo test) that calls the core the way
  Kotlin will, to catch API-shape problems before the emulator.
- **App** — smoke-test on Pixel_9: one full session touching each card type,
  typing with umlauts, audio playback, streak increment.

---

## 12. Build order / milestones (each ends runnable + user sign-off)

1. **Toolchain + bridge** — workspace, `merkrise-core` crate, nested Gradle app,
   hello-world `.so` through uniffi shown on the emulator. *(NDK install step may
   need the user at the SDK Manager GUI.)*
2. **Core engine** — model, DB, SM-2 + length rule, answer check, stage
   progression, session builder; fully unit-tested on desktop.
3. **Importer** — bundle `seed.json` + audio; `import_seed` populates the DB and
   imports progress; verified counts on the emulator.
4. **Study UI** — the five card composables + session loop, home, settings,
   stats; audio playback.
5. **Polish** — streaks, daily-new cap tuning, import-on-first-run UX.

---

## 13. Project layout

```
merkrise/                       (app = "Merkrise")
├─ HANDOFF.md                   environment/toolchain reference
├─ docs/superpowers/specs/      this spec + future specs
├─ capture/                     captured course data (git-ignored)
├─ core/                        Rust crate `merkrise-core` (cdylib + rlib)
│  └─ src/{lib.rs, model.rs, db.rs, srs.rs, session.rs, answer.rs,
│          import.rs, ffi.rs}
├─ android/                     Gradle project (Kotlin + Compose)
│  └─ app/src/main/{assets/, jniLibs/, java/...}
└─ Cargo.toml                   workspace
```

---

## 14. Privacy & security

Single-user, offline, personal. The captured course data and the user's
progress are personal data kept local; `capture/` is git-ignored and never
committed. The Memrise session token used for capture was one-time and has been
invalidated (user logged out). No credentials in the repo or app.

---

## 15. Open questions / future (not v1)

- FSRS as an alternative `Scheduler` once v1 is in daily use.
- A "refresh progress" re-capture path if the user wants to re-sync before the
  site disappears entirely.
- Real-device (arm64) build once the emulator loop is solid.

---

## 16. User-added words & bulk import (provision)

Beyond the captured Memrise course, Merkrise accepts user-supplied vocabulary so
new words can be added in bulk. Design goals: hand-editable **or** spreadsheet-
exportable, UTF-8 (umlauts safe), idempotent, and fast for the app to detect and
act on.

### 16.1 File format

UTF-8 **TSV** (tab-separated) is the canonical format; **CSV** (comma, standard
quoting) is also accepted. The first line is a **header**; columns may appear in
any order; unknown columns are ignored (forward-compatible).

| column    | required | meaning |
|-----------|----------|---------|
| `term`    | **yes**  | German word/phrase |
| `meaning` | **yes**  | English meaning |
| `pos`     | no       | part of speech (`n`, `v`, `adj`, …) |
| `forms`   | no       | plural / other forms → `all_forms` |
| `literal` | no       | literal translation |
| `alt`     | no       | extra accepted typed answers, separated by `\|` |
| `audio`   | no       | filename of an accompanying mp3; blank → TTS |

Example `merkrise.Food-Vocabulary.tsv` (columns are **tab**-separated):

```
term	meaning	pos	forms	literal	alt	audio
der Apfel	the apple	n	die Äpfel			
essen	to eat	v				
die Zwiebel	the onion	n	-n		zwiebel|die zwiebel	zwiebel.mp3
```

### 16.2 Filename convention (how the app "quickly acts")

`merkrise.<deck>.tsv`   (or `.csv`)

- **Only files beginning `merkrise.` are scanned**; everything else in the folder
  is ignored. This prefix is the signal the app acts on.
- `<deck>` is the deck/level name; `-` and `_` become spaces
  (`merkrise.Food-Vocabulary.tsv` → deck **"Food Vocabulary"**). A deck becomes a
  new level appended **after** the 51 Memrise levels (`level_idx` 52+).
- Split a large deck across ordered files:
  `merkrise.<deck>.01.tsv`, `merkrise.<deck>.02.tsv`, … — merged into one deck in
  numeric order.

### 16.3 Where files go

A user-visible folder the app watches: **`Documents/Merkrise/import/`** (on the
Pixel_9 emulator, `adb push` files into it). Accompanying mp3s go in
`Documents/Merkrise/import/audio/`. The app imports on launch and via an explicit
**"Scan for new words"** action, then shows an `ImportReport`.

### 16.4 Behaviour

- Each `merkrise.*` file is parsed; a `levels` row is created for the deck if
  absent; rows become `words` with `source = 'user'`.
- **Idempotent & fast:** re-import updates existing rows (matched on *deck +
  term*) and **skips unchanged files** (tracked by `content_hash` in
  `import_log`); never duplicates.
- New user words enter the SRS as `stage = new, due = now` — the same loop and
  five-step progression as course words.
- **Audio:** a named mp3 (placed in `import/audio/`) is copied in and linked; if
  `audio` is blank the word is spoken via **Android TTS** (German locale) at
  runtime.
- **Robust:** malformed rows are skipped and listed in `ImportReport.errors`; the
  rest import. A missing `term`/`meaning` makes a row invalid.

### 16.5 Schema / API (already folded into §5 and §7)

- `words.source` ('memrise' | 'user'); user decks are `levels` rows with
  `idx ≥ 52`.
- `import_log(filename, content_hash, imported_epoch, words_added,
  words_updated)` for idempotent re-scans.
- `scan_import_dir(dir)` → `ImportReport`; `add_word(...)` for a future in-app
  add screen.
