# Merkrise — Handoff

**For:** the next developer or agent taking over this project.
**Updated:** 2026-10-22 (all work described here was done on 2026-10-21).
**Branch:** `lessons-srs-fast-review` (2 commits on top of `480f17e`, **not pushed**; `origin` =
`git@github.com:dokkandar/Merkrise.git`, trunk = `main`). Supersedes the 2026-10-21 morning handoff,
which described a random-quiz prototype; everything below is the state after the full build-out.

Merkrise is a **personal, offline Android app** for studying German vocabulary: a self-hosted
Memrise-style trainer built from the owner's own captured *A2 German: Beyond Beginner* Memrise course
(id 690189, 1,617 words) plus a custom lesson ("Lesson 01 · Dating & Feelings", 64 words). Rust core
(NDK `.so`) + Kotlin UI (plain Views) bridged with **uniffi**. Personal use only, no publishing.

---

## 0. Read this first

1. **Where the work lives.** On this Windows PC the working tree is **`E:\merkriser\build\merkrise`**
   (it has the real `.git`, `capture/`, `resources/` and the bundled assets). `E:\merkriser\merkrise.zip` is
   the original 1.2 GB upload; `E:\merkriser\merkrise` is a half-extracted leftover: ignore it.
   The latest debug APK is copied to **`E:\merkriser\merkrise-debug-arm64.apk`** (both ABIs, ~131 MB).
2. **Rust owns all logic, Kotlin only draws.** Stages, scheduling, session building, answer checking,
   scoring, stats and the reminder time all live in `core/src/lib.rs`. UI → core, never the reverse.
3. **Two rules that protect the owner's progress** (see §4.5 and §9): the saved-progress key is
   `"{item.level}:{term}"`, and **never copy `capture/seed.json` over the bundled `seed.json`**.
4. **After any core API change:** rebuild the `.so`, regenerate the Kotlin bindings, run `cargo test`,
   then Gradle (§2.3). The generated bindings are committed.
5. **The owner uses the phone while you work.** No `adb` taps and no installs without asking (§2.5).
6. Build recipe for this PC is in §2.1; the traps that cost time are in §9.

---

## 1. Current status — what works today

### Learning engine (core)
- **Stage ladder** per word: `New → Mc → Reversed → Audio → Typing → Known`. A correct answer promotes
  one stage, a wrong answer demotes one (floor `Mc`). The stage picks the next card type.
- **SM-2 scheduling** with the **word-length rule** and a global spacing multiplier (§4.3).
- **Memrise progress import**: 1,617 words carry the owner's old Memrise state (interval, due date,
  correct/attempts, starred, difficult, ignored) and start in the mapped stage; ~1,506 of 1,681 words are
  `Known` at first launch and **~1,617 are overdue** (their last review dates are from 2021).
- **Persistence**: one JSON file, `filesDir/progress.json`, written after every answer (§4.5).
- **Pace settings**, **stats** (known / learning / new / due today / streak days).

### Lessons
- The A2 course was **re-chunked into 77 lessons of 15–25 items in 19 topics** (`scripts/build-lessons.py`;
  rule in `docs/superpowers/specs/2026-10-21-merkrise-lessons-srs-design.md` §2). Lesson 01 is 3 lessons.
  Every lesson of a topic keeps the same short / mid / long phrase mix as the captured course.
- A **topic** = title minus its trailing number ("Sports 3" → "Sports"); a merged lesson is titled
  "A + B 1" and filed under the first topic ("Useful Phrases + Interests 1" → "Useful Phrases").

### Seven card types
| Kind (`QKind`) | Prompt | Answer |
|---|---|---|
| `Presentation` ("Learn new word") | German term + audio + meaning, optional part of speech / forms / literal | Continue (not scored) |
| `Mc` | English | pick the German (4 options) |
| `Reversed` | German (+ audio) | pick the English |
| `AudioMc` | audio only | pick the German |
| `Typing` | English | type the German |
| `Hearing` ("Write the hearing") | audio only | type the German |
| `Arrange` ("Arrange the sentence") | English | tap the German words (tiles) into order |

- New words get a `Presentation` card, then an `Mc` card, kept together.
- **Every item of 3+ German words is always an `Arrange` card at the Typing / Known stages** (up to 15
  tiles; duplicate words are separate tiles; a punctuation-only token is merged into its neighbour tile).
- **Typing + Hearing are capped at 10 % of a session's tasks** (owner: "typing is boring"); the other
  such words become choice cards. Unused allowance carries into the next session (§4.4).
- Answer checking ignores case, umlaut spelling (`über = ueber = uber`, `ß = ss`), punctuation and extra
  spaces, and accepts the captured `typing_alternatives`.
- **Wrong options are never lookalikes** of the right answer or of each other (all choice cards).

### Learning aids
- **In-session retry:** a missed card comes back once, ~3 cards later, as a fresh card (new shuffle) with
  the label "Second try — not scored". It is not scheduled and does not count.
- **Magic wand** on Arrange cards: reveals the sentence word by word (the answer line becomes exactly the
  first *k* correct tiles, never fewer than the user already placed). **A wand-revealed card counts as a
  miss**; the first tap shows a one-time warning toast; the feedback chip says "revealed with the wand".
- **Mistake highlighting** on the feedback screen after a wrong answer: typed answers show "you answered"
  with each wrong **word** in light red and the "Correct answer" with the missed words in yellow (whole
  word, not letters); choice cards show the picked option as a red-outlined card and the right one green.
- **"already learnt"** (word becomes Known, its remaining cards leave the session), **easy** 👌 (ease +0.15)
  and **hard** 😣/★ (starred, ease −0.15) buttons on every untimed card; an egg emoji shows the stage.

### Timed play
- **Fast review** (Home and every lesson): 12 timed multiple-choice cards, easy to hard (more time and
  shorter phrases first, less time and longer phrases last). Time = (8 s + 1 s per German word) ×
  (1 − 0.55·t). A time-out is submitted as `submit_answer("")` = an ordinary miss, with no retry.
- **Speed round** at the end of every normal session: the last ≤ 4 review cards (words already met, at most
  half of the session's review cards, never a new word) become timed `Mc` cards, easy to hard. Header
  reads "⚡ Speed round · <lesson>". A second try is placed before the round, never inside it.
- The timer bar drains yellow → red (last 25 %) with the seconds left; it is driven by
  `SystemClock.elapsedRealtime` per frame (not a `ValueAnimator`, which jumps to its end when system
  animations are off); it stops on pause and resumes with the remaining time (§4.7).

### Screens (Kotlin, `MainActivity.kt`)
Welcome (first launch: asks a name only; **no account/login**) · Home (greeting, "Currently learning" course
card with progress bar, tiles due-today / streak / learning, **Start session**, **Chapters**, **Fast
review**) · Chapters (19 topic cards, accordion, one open at a time) · **Jump list** (all lessons by topic,
current one outlined) · Lesson detail (header **‹ title ▾ ›**, progress, **Study this chapter**, **Fast
review**, **"Lessons in <topic>"** numbered circles that jump between lessons, word list with stage + due
date + audio) · Question screens (all kinds, incl. the custom **QWERTZ keyboard with ä ö ü ß** for typed
answers) · Feedback · Results · **Stats** (chart icon) · **Profile** (person icon) · **Settings** (words per
session 5–50, new words per session 0–30, long-word pace 0–100 %, review spacing ×0.5–×2.0, auto audio,
**Send a test reminder**, Reset progress).

### Reminders
"N words ready to review" notification (text "20+" above 20) when **≥ 5 words are due**, at least **4 h
after the last notification or the last answer / app open**, never between **22:00 and 08:00**. The core
computes the time (`next_review_alarm`); Kotlin sets one inexact `AlarmManager` alarm. Tapping the
notification starts a session of due words (unless a session is already on screen).

### Look and feel
Dark purple / yellow palette from the Figma (§6), Roboto, edge-to-edge, portrait only, launcher icon =
yellow wordmark on purple (adaptive icon, `drawable-nodpi/ic_launcher_foreground.png`).

### Verification status (be honest about this)
- **Core:** `cargo test -p merkrise-core` = **57 tests green** (55 unit + 2 integration on the real
  1,681-word seed). The first stage test was mutation-checked (broke promotion, saw it fail, restored).
- **Progress keys:** checked that all 1,681 old keys exist in the 77-lesson seed (0 missing, 0 extra).
- **Kotlin/UI:** build-checked and read through; **no automated UI tests**. On the phone the build session
  saw Welcome, Home, Settings and the old typing screen; the owner reported the jump list working and
  later asked for the lesson circles and the icon fix (both built afterwards).
- **Not confirmed on a device:** notification delivery (Samsung One UI may put the app to sleep), timer
  pause/resume, the speed round, the mistake highlighting, the lesson circles and the icon fix. Check
  `adb shell dumpsys package com.hsi.merkrise | grep lastUpdateTime` to see which build is installed
  before believing a bug report (the owner once described a stale build).

---

## 2. How to build & run

### 2.1 Windows (this PC) — the recipe that works
Run from **Git Bash** (Gradle's `buildRustCore` task shells out to `sh`).

```sh
cd /e/merkriser/build/merkrise/android
export JAVA_HOME="C:/Program Files/Huawei/DevEco Studio/jbr"          # JDK 21
export ANDROID_HOME="C:/Users/hsili/AppData/Local/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export CARGO_INCREMENTAL=0
./gradlew --no-daemon :app:assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk   (cp it to E:\merkriser\merkrise-debug-arm64.apk)
```
Needed once: `sdkmanager "platforms;android-35" "build-tools;34.0.0"`, `rustup target add aarch64-linux-android
x86_64-linux-android`, `cargo-ndk` 4.1.2. `android/local.properties` (git-ignored) must read
`sdk.dir=C:/Users/hsili/AppData/Local/Android/Sdk` — **forward slashes**.
`android/gradle.properties` is committed with `org.gradle.java.home=C:/Program Files/Huawei/DevEco Studio/jbr`;
on another machine change or override it.

### 2.2 Linux (the original dev machine)
Same steps with the old paths: repo `/home/HSI/workspace/merkrise` (**ext4 only**), `JAVA_HOME=/opt/android-studio/jbr`,
`ANDROID_HOME=/home/HSI/Android/Sdk`, NDK r27c (`27.2.12479018`), AVD `Pixel_9`. The run-on-emulator one-liner:

```sh
/home/HSI/Android/Sdk/emulator/emulator -avd Pixel_9 >/tmp/emu.log 2>&1 & \
ADB=/home/HSI/Android/Sdk/platform-tools/adb; $ADB wait-for-device; \
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; \
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk; \
$ADB shell am start -n com.hsi.merkrise/.MainActivity
```
(The emulator is unusable on the Windows PC: no hypervisor driver.)

### 2.3 After a core change (API or behaviour)
```sh
sh scripts/build-core-android.sh      # cargo-ndk, arm64-v8a + x86_64 -> android/app/src/main/jniLibs
sh scripts/gen-bindings.sh            # uniffi -> android/.../core/merkrise_core.kt (COMMITTED)
cargo test -p merkrise-core           # 55 unit + 2 integration tests
cd android && ./gradlew --no-daemon :app:assembleDebug
```
`gen-bindings.sh` reads the `.so` metadata, so the `.so` must be rebuilt **first**. A plain
`assembleDebug` also rebuilds the `.so` (`buildRustCore` is wired into `preBuild`) but does **not**
regenerate the bindings. Pin the crate and bindgen to the same uniffi (0.32.2) or Kotlin panics on a
checksum mismatch. Only one builder at a time in the tree (shared `target/` and Gradle locks).

### 2.4 Assets and the seed
```sh
sh scripts/bundle-assets.sh
# = python -I scripts/build-lessons.py capture/seed-merged.json android/app/src/main/assets/seed.json
#   + copy capture/audio/*.mp3 into assets/audio (cp -n, idempotent)
python -I scripts/build-lessons.py --test       # the chunker's own self-check
```
- **`capture/seed-merged.json` (local-only) is the MASTER**: A2 + Lesson 01, **un-chunked**, each level
  tagged with a `set` (`A2`, `Lesson 01`, one per future upload). The chunker refuses its own output
  (`meta.chunked = true`).
- `build-lesson01.py` is **superseded** (the merged master already contains Lesson 01).
- A **new word set** = append its levels to `seed-merged.json` with a new `set` tag and **unused** level
  numbers, then run `bundle-assets.sh`. Sets are chunked separately and never mixed. There is no
  on-phone importer (owner decision: he hands over files, we rebuild the APK).
- Lesson 01's 64 audio files are `l01_*.wav` (already in `assets/audio`); A2 has 3,178 mp3.

### 2.5 The phone (owner's device) — rules and commands
Samsung Galaxy XCover6 Pro (SM-G736B), serial **`R5CW213FECX`**, arm64, USB debugging authorised by the
owner tapping Allow (state `unauthorized` until then). `adb` is not on PATH:
`C:\Users\hsili\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- **Ask the owner before every install and never inject taps** (he is often using the phone).
- `adb -s R5CW213FECX install -r <apk>` keeps the data (same debug signature, versionCode 1).
- Read the saved progress passively (debug build): `adb exec-out run-as com.hsi.merkrise cat files/progress.json`.
- In Git Bash set `MSYS_NO_PATHCONV=1` or `/sdcard/...` paths get rewritten; screenshots are in
  `/sdcard/DCIM/Screenshots`.

### 2.6 Tests
`cargo test -p merkrise-core` (≈10 s). Unit tests are in `core/src/lib.rs`; `core/tests/real_seed.rs` plays
the real course through every card type. A test that passes on first run proves nothing: break the
code on purpose once and watch it fail.

---

## 3. Repository & data layout

```
merkrise/                       (E:\merkriser\build\merkrise on the Windows PC)
├─ HANDOFF.md                   ← this file
├─ Cargo.toml                   ← Rust workspace (members = ["core"])
├─ core/                        ← Rust crate `merkrise-core` (the engine, ~2,260 lines)
│  ├─ Cargo.toml                ← uniffi 0.32.2, serde, serde_json, thiserror, fastrand
│  ├─ src/{lib.rs, bin/uniffi-bindgen.rs}, uniffi.toml
│  └─ tests/real_seed.rs        ← integration tests on the real seed
├─ android/                     ← Gradle app (Kotlin, plain Views, com.hsi.merkrise)
│  └─ app/src/main/
│     ├─ java/com/hsi/merkrise/
│     │   MainActivity.kt       ← screens, navigation, session flow, timers (~1,160 lines)
│     │   Ui.kt                 ← palette + Context-extension widgets (text, pill, card, FlowLayout …)
│     │   Keyboard.kt           ← the QWERTZ keyboard with ä ö ü ß
│     │   Reminders.kt          ← alarm scheduling, notification, receivers
│     │   core/merkrise_core.kt ← GENERATED uniffi bindings (committed)
│     ├─ res/                   ← vector icons, launcher icon (adaptive), logo PNG
│     ├─ AndroidManifest.xml    ← singleTop, portrait, 2 permissions, 2 receivers
│     ├─ jniLibs/               ← .so (git-ignored) arm64-v8a + x86_64
│     └─ assets/                ← seed.json + audio/ (git-ignored, derived)
├─ scripts/                     ← build-core-android.sh, gen-bindings.sh, bundle-assets.sh,
│                                 build-lessons.py (chunker), build-lesson01.py (superseded)
├─ docs/superpowers/{specs,plans}  ← design authority (§6) ; docs/examples/ (import samples)
├─ capture/   *DATA*            ← captured A2 course + seed-merged.json (git-ignored, LOCAL ONLY)
├─ resources/ *DATA*            ← Lesson 01 source folder (git-ignored, LOCAL ONLY)
└─ target/                      ← Rust build cache (git-ignored, regenerable)
```

### ⚠ The data is LOCAL-ONLY — keep it with this folder
Code and docs are in git. **The data and voice files are git-ignored and are NOT on GitHub** (large and
personal): `capture/`, `resources/`, `android/app/src/main/assets/`. A fresh clone will NOT have them. Do not
delete `capture/` or `resources/`, and back them up separately: the captured audio is irreplaceable
(Memrise is sunsetting the source site).

### The data assets
| Location | What | Size |
|---|---|---|
| `capture/seed-merged.json` | **MASTER seed**: A2 (51 levels) + Lesson 01 (1 level), un-chunked, each level tagged `set` | 1.4 MB |
| `capture/seed.json` | the A2 course alone — 51 levels, **1,617 words**, with the owner's Memrise progress. **Do not ship this one** | 1.9 MB |
| `capture/raw/level_001..051.json` | raw API responses (backup) | 15 MB |
| `capture/audio/` | **A2 voice files — 3,178 mp3** (native speakers, 2 per word) | 118 MB |
| `resources/01/Files/` | Lesson 01 text: `N.txt` headword + `N.1/N.2.txt` example sentences (64 words) | — |
| `resources/01/{simon,helmut}/` | Lesson 01 voice files — 383 wav (2 speakers) | 62 MB |
| `android/app/src/main/assets/seed.json` | **bundled, derived**: **77 lessons / 19 topics / 1,681 items**, `meta.chunked = true` | 1.4 MB |
| `android/app/src/main/assets/audio/` | 3,178 mp3 + 64 `l01_*.wav` = **3,242 files** | 124 MB |

`.gitignore` also covers `*.so`, `*.apk`, `local.properties`, `/dist/`, `/target/`, `.idea/`.

---

## 4. Architecture

```
Android app (Kotlin, plain Views)  --uniffi/JNI-->  merkrise-core (Rust, one global Mutex<Core>)
  MainActivity: screens, session flow, timers                |
  Reminders:    AlarmManager + notification                  |  words, levels, reviews, session,
  progress.json (filesDir) <-- export_state()/import_state() |  pace, streak days, orphans
  assets: seed.json + audio/*  --load_seed(json)-->          |
```
Kotlin never decides what is correct, due, next or scored; it renders `Question`s, plays audio, saves the
state string the core exports and sets alarms from the time the core returns.

### 4.1 uniffi-exported core API (`core/src/lib.rs`, `core_version() = "Merkrise core 0.3.0"`)
| Group | Functions |
|---|---|
| Data | `load_seed(json) -> u32` · `word_count()` · `get_card(i) -> WordCard?` · `levels() -> [LevelInfo]` · `level_words(level) -> [WordRow]` |
| Sessions | `start_session(level?, size, now)` · `start_fast_session(level?, size, now)` (both `Result<u32, CoreError>`) · `current_question() -> Question?` · `submit_answer(input) -> AnswerFeedback` · `session_progress()` |
| Aids | `wand_hint(placed: [u32]) -> [u32]` · `mark_mistakes(expected, typed) -> WordMarks` · `mark_known(word)` · `set_starred(word, bool)` · `rate_easy(word)` |
| Stats / settings | `stats(now) -> Stats` · `get_pace()` / `set_pace(PaceSettings)` |
| Reminders | `next_review_alarm(min_words, now, last_notified) -> ReviewAlarm {at, count}` |
| State | `export_state() -> String` · `import_state(json) -> u32` · `reset_progress()` |

Key records: `Question` {position, total, kind, word, prompt, prompt_audio, choices, tiles, meaning, detail,
note, stage, reps, starred, **retry**, **time_limit_ms**} · `AnswerFeedback` {correct, expected, finished,
scored, stage, next_due, **hinted**} · `LevelInfo` {index, title, **topic**, count, known, learning} ·
`WordMarks` {typed: [bool], expected: [bool]} (aligned to whitespace-split words) · `Stage` ·
`QKind` · `PaceSettings` {new_per_session, interval_scale, length_weight} · `Stats` ·
`ReviewAlarm`. Kotlin names are camelCase, unsigned ints become `UInt`, enums are UPPER_SNAKE.

**Time convention:** every `now` / due date is **"core clock" = unix seconds + the local time-zone offset**
(`Reminders.coreNow()`); it also decides the streak day. Convert back to real epoch (subtract the offset)
only at the `AlarmManager` boundary.

### 4.2 Stage ladder and import
- The stage a word is in selects the card it gets next (`pick_kind`): `New` = `Presentation` + `Mc`; `Mc` → `Mc`;
  `Reversed` → `Reversed`; `Audio` → `AudioMc` (`Reversed` if the word has no audio); `Typing` / `Known` = the
  hardest recall: `Arrange` for 3+ German words, otherwise `Hearing` (70 %) or `Typing` (30 %), and then
  the 10 % typing cap (§4.4) turns most of those into choice cards. A correct answer promotes
  (`New`/`Mc` → `Reversed` → `Audio` → `Typing` → `Known`), a wrong one demotes (`Known` → `Typing` → `Audio` →
  `Reversed` → `Mc`, floor `Mc`).
- Import (spec §6.6): interval 0 → `New`, < 4 d → `Mc`, < 10 → `Reversed`, < 30 → `Audio`, < 60 → `Typing`,
  ≥ 60 → `Known`; `ignored` → `Known`; ease 2.5 (−0.3 if difficult, floor 1.3); due = Memrise `next_date`;
  reps = correct; lapses = attempts − correct; starred kept.

### 4.3 Scheduling (SM-2 + length rule)
Correct: reps 0 → 1 day, reps 1 → 6 days, else `interval × ease`; then
`interval *= (1 − w) + w / len_factor` with `len_factor = clamp(term_chars / mean_chars, 0.6, 1.6)` and
`w = length_weight`; then `× interval_scale`; clamped to 0.01–365 days; stage promoted. Wrong: reps = 0,
lapses + 1, ease − 0.2 (floor 1.3), due in **10 minutes**, stage demoted. Defaults: `new_per_session` 15,
`interval_scale` 1.0, `length_weight` 0.3.

### 4.4 Session builder (`start_session`)
1. Candidates = the lesson, or the whole course. Split into **due** (stage ≠ New and due ≤ now, shuffled),
   **new** (stage New, capped at `new_per_session`) and **practice** (the rest, shuffled). Take `size` words
   in that order; never empty (falls back to the whole candidate list).
2. Each word → its cards (new word: `Presentation` + `Mc`). Groups are shuffled so a presentation stays next
   to its first card.
3. **Typing cap** (`limit_typing`): Typing + Hearing ≤ 10 % of the tasks; surplus words get `Mc` / `Reversed`
   / `AudioMc` instead; the unused allowance carries over (credit capped at 2 typed cards).
4. **Speed round** (`speed_round`): the last ≤ 4 review cards (words already met, ≤ half of the review cards,
   never a new word, no phrase twice) become timed `Mc` cards, easy to hard.
5. **Retry:** a miss on a scored, non-retry, untimed card inserts a fresh copy `min(pos + 3, len)` cards later
   (before the speed round); the retry is unscored and unscheduled; missing the last card does not end the
   session. A hinted (wand) card is a miss. `total` therefore grows by one per retry.
6. **Options** (`choices_with`): distractors come from the card's own lesson first, then the course; a
   candidate is rejected when `too_similar` (Jaccard word overlap ≥ 0.4, or a run of ≥ 3 shared words, or
   spelling similarity ≥ 0.7). Fast review raises the options' similarity with difficulty (`t`).
7. **Fast session** (`start_fast_session`): only words already met; Home prefers words practised in this app
   (`Review.touched`) and only fills up from the imported Memrise words when fewer than 12 were practised; a
   lesson draws from all its met words. Due words first. Fewer than 4 words → `NoWords`.

### 4.5 Persistence and the progress key
- `export_state()` returns JSON `{version: 1, pace, days, reviews: {key: Review}}` with **only words the user
  touched** (plus `orphans`); Kotlin writes it to `filesDir/progress.json` (tmp file + rename) after every
  answer / mark / setting change and feeds it to `import_state()` right after `load_seed()` at start-up.
- **Key = `"{item.level}:{term}"`** where `item.level` is the item's own *source* level (the original A2 level
  number; 52 for Lesson 01), **not** the lesson index. That is why re-chunking the course changed no keys.
  The 5 terms that exist in two A2 levels (die Beziehung, Amerika, das Boot, "ich habe einen Fehler gemacht",
  gesund) are separate items with separate progress. Memrise `id`s are **not** unique: do not key by id.
- **Orphans:** saved progress whose key the loaded seed does not have is kept in `Core.orphans` and written
  back, so a wrong or changed seed can never silently delete the owner's progress.
- SharedPreferences `merkrise`: `name`, `session_size` (20), `autoplay` (true), `asked_notifications`,
  `wand_warned`, `last_notified`, `last_study` (core clock).
- ponytail: one JSON file rewritten per answer (a few KB); move to SQLite only if it grows (spec §5 sketches the
  schema).

### 4.6 Reminders (`Reminders.kt`)
- `rearm(context, studying)` is called on every `save()`, in `onResume`, from the alarm receiver, on boot and on
  app update. It loads the core headless if the process has no Activity (`ensureCore`: only when
  `word_count() == 0`, so it never wipes a running session).
- The 4-hour gap passed to `next_review_alarm` is `max(last_notified, last_study)`: the first reminder
  never arrives while the owner is studying, and with the 1,617-word backlog it is at most ~4 a day.
- If notifications cannot be shown (permission missing, app or channel switched off) the alarm is **cancelled**
  rather than re-armed; granting the permission re-arms it. `fire()` stamps `last_notified` even when nothing
  could be shown, so a denied permission cannot loop the alarm.
- The notification's tap opens `MainActivity` with the `start_due` extra (`singleTop` + `onNewIntent`); it is
  acted on only for a fresh launch (not a restore, not "launched from history") and never replaces a session
  on screen (Activity flag `inSession`, **not** the core's session state).
- Android 13+: `POST_NOTIFICATIONS` is requested once, after the first finished session. Settings →
  "Send a test reminder" posts the same notification at once (and asks for the permission or opens the
  system settings when needed).

### 4.7 Timed cards (Kotlin)
`timed` holds the timed `Question`; `startTimer` posts a frame callback that compares
`SystemClock.elapsedRealtime()` with the deadline, updates the bar (`scaleX`, colour) and the seconds label,
and calls `timeUp()` at zero. `cardDone` makes answer and time-up mutually exclusive (it also guards every
card type against double taps). `screen()` stops the clock on every screen change; `onPause` stops it and
stores the remaining time; `onResume` restarts with the remainder (floor 500 ms, never a fresh clock). A
timed card has no already-learnt / easy / hard strip. Time-up → `submitAnswer("")` → feedback "time's up"
with only the correct answer.

### 4.8 UI notes
- `Ui.kt` holds the palette (`BG #352542`, `SURFACE #241431`, `YELLOW #FEE73E`, `TEXT #D1D1C5`, …,
  `LIGHT_RED` / `LIGHT_GREEN` for text on the dark background) and `Context` extension helpers; there is no
  XML layout, every screen is built in code.
- Edge-to-edge (targetSdk 35): `applyInsets` pads the header for the status bar, the bottom bar for the
  navigation bar and shrinks the whole frame above the keyboard; the bottom bar hides while the keyboard is up.
- `screen(gravity, chrome, onBack)` resets per-screen state (`inSession`, timer) and sets what Back does.

### 4.9 How the data was obtained (for re-capture if ever needed)
The A2 course was captured once from the live Memrise API with the owner's session cookie:
`POST community-courses.memrise.com/v1.25/learning_sessions/preview/` with body
`{"session_source_id":690189,"session_source_type":"course_id_and_level_index","session_source_sub_index":<1..51>}`.
Audio on `static.memrise.com` is public. The throwaway capture scripts were not kept; `capture/` holds the
results. The old `/v1.17/` API is gone (410).

---

## 5. Toolchain (verified)
| | Windows PC (E:\merkriser\build) | Linux dev machine |
|---|---|---|
| JDK | **DevEco Studio JBR 21** (`C:/Program Files/Huawei/DevEco Studio/jbr`). Android Studio's JBR on D: is JDK 25 = too new for Gradle 8.7 / AGP 8.6 | `/opt/android-studio/jbr` (21) |
| Android SDK | `C:/Users/hsili/AppData/Local/Android/Sdk` (platform 35, build-tools 34.0.0 and 36.0.0) | `/home/HSI/Android/Sdk` |
| NDK | **29.0.14206865** (`ANDROID_NDK_HOME`) | r27c `27.2.12479018` |
| Rust | 1.95, `cargo-ndk` 4.1.2, targets `aarch64-linux-android` + `x86_64-linux-android` | same |
| Gradle / AGP / Kotlin | Gradle **8.7** (wrapper), AGP **8.6.0**, Kotlin **1.9.24**, JNA 5.14.0 aar | same |
| SDK levels | compileSdk / targetSdk **35**, minSdk **26** | same |
| ABIs | **arm64-v8a + x86_64** (device + emulator) | was x86_64 only |
| Python | 3.12 (`python -I` for the scripts; stdlib only) | python3 |

---

## 6. Design docs (deeper detail)
- **v1 design (authority for the engine):** `docs/superpowers/specs/2026-10-20-merkrise-v1-design.md` —
  schema, the SM-2 + length rule, the stage ladder, Memrise import mapping, bulk-import format (§16) and
  folder lesson-package format (§16.6). Not implemented from it: SQLite, the runtime importers, the
  per-card answer time setting.
- **Lessons / repeats / reminders / wand:** `docs/superpowers/specs/2026-10-21-merkrise-lessons-srs-design.md`
  and its plan `docs/superpowers/plans/2026-10-21-merkrise-lessons-srs.md`.
- **Fast review + not-too-similar options + speed round:** `docs/superpowers/specs/2026-10-21-fast-review-design.md`.
- **M1 plan:** `docs/superpowers/plans/2026-10-20-merkrise-m1-toolchain-bridge.md`; import examples in `docs/examples/`.
- **UI design source:** Figma file `mn2UhPbM42aShaAzVOBbAk` (page "Memrise"). It is one huge canvas
  (~760 layers); the phone-size rectangles are blank backgrounds, the screens are the `Group` frames above
  them. Lesson-flow screens (Learn / Pick answer / Translation / Arrange / Write the hearing) sit at
  x ≈ −77000…−35000; the onboarding + course/lesson list strip is `Group 5942` (node 6261:191); the logo is
  node 6138:7. The design is a Turkish sample; the app content is German. `Group 5926` is a requirements slide
  (AI assistant, social, admin): **out of scope**.

---

## 7. Owner decisions and product rules (do not undo without asking)
- Personal use, offline, no publishing, **no accounts** (a local name only).
- **Selecting beats typing:** Typing + Hearing ≤ 10 % of a session. Sentences (3+ words) at the repeat
  stages are **always** `Arrange` tiles.
- **A wand-revealed word counts as a miss.** A time-out is a miss. Fast review and the speed round count
  for the schedule like any choice card.
- Lessons are **15–25 items** with the short / mid / long mix of the A2 course; new word sets follow the
  same rule; new sets arrive as files and are bundled at build time (**no on-phone importer**).
- Reminders: ≥ 5 due words, 4 h minimum gap (also after studying), quiet hours 22:00–08:00, text capped at
  "20+". The owner knows the Memrise backlog makes it fire up to ~4 times a day at first.
- Wrong words are highlighted as **whole words**; wrong choice = red card, right choice = green card.
- Chapter navigation: a title **jump list** with ‹ › arrows, and the numbered circles on a lesson are
  **lessons of the same topic** (he read word circles as chapters).
- Fast review: 12 cards; Home draws on words practised in this app first.
- Not wanted / out of scope: Videos tab, AI assistant, chat groups, admin / course upload (online features).

---

## 8. Next steps and open items (recommended order)
1. **Walk the new features through on the phone** (nobody has seen them all on a device): reminders end to
   end (Settings → Send a test reminder; Samsung "Never sleeping apps" in battery settings), fast review
   and speed-round timers (red phase, lock the screen mid-card, leave via Home and come back), mistake
   highlighting, lesson circles, the icon fix (the wordmark must not look squeezed in One UI).
2. **Commit / merge:** branch `lessons-srs-fast-review` is committed but not pushed; decide with the owner
   about pushing and merging into `main`. The Windows paths in `android/gradle.properties` are committed.
3. **Release hygiene:** signed release build, `allowBackup="true"` → `false` (real data now ships), strip the
   release `.so`, 16 KB page-size alignment (`-C link-arg=-Wl,-z,max-page-size=16384` and a 16 KB-aligned JNA).
4. **Spec deviations to revisit:** `new_per_session` is a cap **per session**, the spec says per day; the
   per-card answer-time setting (`base_answer_time_ms`) was replaced by the fixed fast-review formula; no
   SQLite (JSON file instead).
5. **Small UI debts:** Home still hard-codes "A2 Beyond Beginner" and the "DE" badge; the stage "egg" is an
   emoji row; the Figma's word-image circle and Videos / AI tabs are not built; a mid-session process death
   returns to Home (progress is saved per answer, the session itself is not restored); portrait only.
6. **Runtime importers** (spec §16 TSV, §16.6 folder package) only if the owner changes his mind about
   build-time sets. Optional: migrate the UI to Jetpack Compose.
7. Review Lesson 01's English meanings (translated by Claude, see `scripts/build-lesson01.py` `EN`).

---

## 9. Gotchas
- **Never copy `capture/seed.json` over `assets/seed.json`** (no Lesson 01, un-chunked) — see §4.5. Always go
  through `scripts/bundle-assets.sh`.
- **Progress key** is `"{item.level}:{term}"`: keep each item's own `level` when touching the chunker; do not key
  by Memrise `id` (5 duplicates) or by the lesson index.
- **Core clock** is epoch + tz offset; real epoch only for `AlarmManager`.
- **uniffi version skew** → Kotlin checksum panic; keep crate + bindgen at 0.32.2. Regenerate the bindings
  after every API change and commit them.
- **`local.properties` `sdk.dir` needs forward slashes**; single backslashes are eaten by the properties
  parser and fail with the misleading "filename, directory name, or volume label syntax is incorrect".
- **The original zip contains Linux `app/build/` intermediates**: delete `android/app/build` and
  `android/.gradle` before the first Windows build.
- **JDK 25 (Android Studio's JBR) fails with Gradle 8.7**; use JDK 21.
- **Trailing lambdas bind to the LAST parameter**: `screen(gravity, chrome, onBack)` has `onBack` last on
  purpose. `Activity.title` exists: the `title(...)` helper in `MainActivity` is a function, not a property.
- **Python edits default to cp1252 on Windows**: when patching Kotlin / Markdown with non-ASCII (`›`, `⚡`, `—`)
  pass `encoding="utf-8"`. `python -I` also ignores `PYTHONPATH`.
- **Android 15 edge-to-edge** (targetSdk 35) draws under the system bars; the app handles insets itself
  (`applyInsets`); keep that or content hides.
- `ValueAnimator` jumps to its end when the system animation scale is 0 (Samsung "Remove animations"): do not
  use it for game logic or deadlines.
- A passing "options get harder" style test can be length-confounded: compare each option's percentile among
  the card's own candidates.
- The emulator's 16 KB compatibility popup on launch is harmless (tap OK).
- Regenerating assets re-copies 124 MB (`cp -n` makes re-runs fast); `bundle-assets.sh` is idempotent.
- Linux-only: build on ext4, never on the NTFS data partition (its driver corrupts fresh binaries).

---

## 10. Change log of this work (2026-10-21)

**Pass 1 — Windows build + visual redesign.** Fresh source extracted from the zip into `E:\merkriser\build`;
JDK 21, SDK platform 35, `arm64-v8a` ABI added; first install on the phone. `MainActivity.kt` rewritten in the
Figma look (palette, header with logo, bottom bar, course card, chapter cards, question / feedback / results).

**Pass 2 — engine and the rest of the Figma.** Core rewritten to the v1 spec (stages, SM-2 + length rule,
Memrise progress import, saved state, pace, stats / streak, mark_known / easy / hard); seven card types incl.
Arrange and Hearing; custom QWERTZ keyboard; punctuation-insensitive answer checking; Welcome, Stats,
Profile and Settings screens; chapter detail with word list; `Ui.kt` / `Keyboard.kt` split; 17 unit tests +
a real-seed integration test.

**Pass 3 — lessons, wand, reminders** (with a second Claude session owning `core/src/lib.rs` + `scripts/`).
77-lesson chunker; stable progress key; in-session retry; magic wand (miss); Arrange for every 3+ word
item; `next_review_alarm`; Kotlin reminders (`Reminders.kt`, receivers, channel, permission after the first
session), topic accordion, `singleTop` + portrait.

**Pass 4 — owner feedback.** Typing cap 10 %; whole-word mistake highlighting; title jump list + ‹ ›;
lesson circles (lessons of the topic) replacing the word circles; the leftovers (start_due only on a fresh
launch, cancel the alarm when notifications cannot show, re-arm on resume, wand label + one-time warning,
"Send a test reminder", comment fixes); **Fast review** (+ lookalike-free options on all choice cards);
**speed round** at the end of every normal session; launcher icon (adaptive, pre-padded PNG; an inset-based
version was squeezed by One UI); progress **orphans** safety net; docs, specs and plans committed on
`lessons-srs-fast-review`.

**Working agreement used by the two Claude sessions (useful if you do the same):** one owner per file
group (core + scripts vs Kotlin), hand-off = one message with the frozen API + regenerated bindings, one
builder at a time in the tree, no `git reset` / `checkout`, no phone taps or installs without the owner's OK.
