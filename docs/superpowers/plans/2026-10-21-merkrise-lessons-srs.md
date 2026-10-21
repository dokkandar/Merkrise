# Merkrise lessons, repeats, notifications, magic wand — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 15–25-item lessons with the A2 short/long mix, in-session retry of misses, Arrange + magic wand for sentence repeats, and a "≥ 5 words due" notification, without losing the owner's saved progress.

**Architecture:** A Python build script re-chunks `seed.json` into lessons (the core treats a lesson as a level). The Rust core gets the stable progress key, retry, wand and alarm-time logic, all under `cargo test`. Kotlin (the "Merkrise build" session) draws the wand, groups lessons by topic and owns Android alarms and notifications.

**Tech Stack:** Python 3 (stdlib only), Rust core with uniffi 0.32.2, Kotlin plain Views, AGP 8.6 / Gradle 8.7, Android AlarmManager.

**Spec:** `docs/superpowers/specs/2026-10-21-merkrise-lessons-srs-design.md`

## Global Constraints

- Lesson size 15–25 items; exceptions only: a whole set < 15 → one lesson, a whole set of 26–29 → two lessons of 13–15.
- Lessons never mix source sets: the A2 course is `levels[:meta.level_count]` (51); every level after it is its own set (Lesson 01, future uploads).
- No item is dropped, merged or deduplicated (the 5 duplicate-term pairs stay separate).
- Progress key `"{item.level}:{term}"` must stay byte-identical; `SavedState.version` stays 1.
- A wand-revealed card is a miss. A retry is unscored and unscheduled.
- 3+ word items at Typing/Known are always Arrange; 1–2 word items keep today's Typing/Hearing roll.
- Notifications: ≥ 5 due words, min gap 4 h, quiet hours 22:00–08:00, text capped at "20+".
- Core clock = epoch + local tz offset (`MainActivity.now()`); convert to real epoch only at the AlarmManager boundary.
- No new dependencies. No `git commit`, `reset` or `checkout` unless the owner says so: the tree holds the other session's uncommitted redesign.
- One session builds at a time in `E:\merkriser\build\merkrise`. No adb taps on the owner's phone without asking.
- Build env (Git Bash): `JAVA_HOME="C:/Program Files/Huawei/DevEco Studio/jbr"`, `ANDROID_HOME=C:/Users/hsili/AppData/Local/Android/Sdk`, `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865`.

## Review Focus

- The owner's real `progress.json` after the update: the same known / learning counts on Stats before and after (Task 2 regression test; manual check on the phone in Task 8).
- Wand when the user placed the *other* copy of a duplicate word: the prefix must still count (Task 4 test `wand_counts_a_prefix_built_from_either_duplicate_tile`).
- Missing the last card of a session: it must not end the session; the retry still comes (Task 5 test `missing_the_last_card_still_gets_its_retry`).
- Re-running the chunker on its own output, or adding a set whose level number is taken: refuse, not silently re-pool or collide keys (Task 1 tests).
- Notification tap while a session is open: brings it to the front and never replaces it (Task 7, manual check in Task 8).

---

### Task 1: Lesson chunker script (owner: hsili-34)

**Files:**
- Create: `scripts/build-lessons.py`
- Create (one-time copy, local-only, git-ignored): `capture/seed-merged.json` ← today's `android/app/src/main/assets/seed.json`
- Output: `android/app/src/main/assets/seed.json`

**Interfaces:**
- Produces: `python -I scripts/build-lessons.py IN OUT` and `python -I scripts/build-lessons.py --test`. The output seed has `meta.chunked = true`, lessons as `levels` with `index` 1..N, `title`, `topic` (the first topic's name; "Lesson 01 · Dating & Feelings" for Lesson 01), `item_count` and `items`. Every item keeps its own `level` (source level) and `id` unchanged.

- [ ] **Step 1: Copy the source once.** `cp android/app/src/main/assets/seed.json capture/seed-merged.json`; check it has 52 levels and no `meta.chunked`.
- [ ] **Step 2: Write the self-test `--test` (asserts, runs on synthetic sets):**
  - sizes by set length: 9→[9], 14→[14], 15→[15], 25→[25], 26→[13,13], 29→[15,14], 30→[15,15], 49→[25,24], 64→[22,21,21]
  - on `capture/seed-merged.json`: 1,681 items in, 1,681 out; the multiset of `(level, term)` is identical in and out; A2 gives 74 lessons, all 15–25; Lesson 01 gives 3 lessons (22/21/21); no lesson mixes source sets
  - mix: the average within-topic stdev of the long share is < 3 percentage points (the prototype measured 1.4)
  - input with `meta.chunked = true` → exits non-zero with "already chunked"
  - input with a duplicate `(level, term)` → exits non-zero naming it
- [ ] **Step 3: Run it to verify it fails.** `python -I scripts/build-lessons.py --test` → fails (no implementation yet).
- [ ] **Step 4: Implement the algorithm from spec §2.2.** The working prototype is `C:\Users\hsili\AppData\Local\Temp\claude\C--Users-hsili\de901c7b-9fd8-4985-89f2-ec13a424b115\scratchpad\chunk_proto.py` (`chunk`, `split_pool`, `family`, `bucket`). Port it, add the per-set split, the two refusals and file I/O. Write UTF-8 with `ensure_ascii=False` and copy `meta` with `chunked: true`. Titles: `"{topic} {n}"`; merged lessons are `"{A} + {B} {n}"`.
- [ ] **Step 5: Run the tests.** `python -I scripts/build-lessons.py --test` → `ok`.
- [ ] **Step 6: Generate.** `python -I scripts/build-lessons.py capture/seed-merged.json android/app/src/main/assets/seed.json` → prints 77 lessons, 1,681 items.

### Task 2: Stable progress key + real-seed test for lessons (owner: hsili-34)

**Files:**
- Modify: `core/src/lib.rs` (`SeedItem` ~line 262, `load_seed` key at ~line 539)
- Modify: `core/tests/real_seed.rs`

**Interfaces:**
- Consumes: Task 1 output, and `capture/seed-merged.json` as the "old" seed.
- Produces: `Word.key = format!("{}:{}", item.level.unwrap_or(level.index), item.term)`; `SeedItem` gains `#[serde(default)] level: Option<u32>`. `Word.level` stays the parent lesson index.

- [ ] **Step 1: Unit test `progress_key_uses_the_items_own_level`:** a 2-level sample where an item has `"level": 7` under parent `index: 1`. Answer it, `export_state()`, and assert the JSON contains the key `"7:<term>"`, not `"1:<term>"`. Reload a seed with the same item under parent index 3 and assert `import_state(saved) == Ok(1)`.
- [ ] **Step 2: Integration test `saved_progress_survives_the_rechunk` in `real_seed.rs`:** `load_seed(seed-merged.json)`, play 3 sessions with right answers, `saved = export_state()`, count the keys in `saved["reviews"]` (serde_json). Then `load_seed(assets/seed.json)` and assert `import_state(saved)` equals that count. Assert `stats(NOW)` known / learning / new are equal across the two loads after import.
- [ ] **Step 3: Update `real_course_plays_end_to_end` for lessons:** `levels().len() == 77`, and every level count is in 15..=25. Lesson 01 = the 3 levels whose title starts with "Lesson 01"; their summed `level_words` = 64, all `Stage::New`. `st.new_words == 64` is unchanged. Replace `level_words(52)` accordingly.
- [ ] **Step 4: Run.** `cargo test -p merkrise-core` → the two new tests fail first. Implement the key change, then everything is green.

### Task 3: Arrange tiles for every sentence repeat (owner: hsili-34)

**Files:** Modify `core/src/lib.rs` (`load_seed` tokens, `pick_kind` ~line 418, `make_q` ~line 476)

**Interfaces:**
- Produces: `fn tiles_of(term: &str) -> Vec<String>`: whitespace split, with a punctuation-only token (no alphanumeric char) appended to the previous token after a space, or to the next one if it comes first. `Word.tokens = tiles_of(&term).len()`. `Q` gains `order: Vec<usize>` (tile indices in correct order), used by Task 4.

- [ ] **Step 1: Tests.**
  - `tiles_merge_punctuation_only_tokens`: `tiles_of("wie geht es dir ?") == ["wie","geht","es","dir ?"]`; `tiles_of("- ja gut") == ["- ja","gut"]`.
  - `sentences_at_repeat_stages_are_always_arrange`: set a 3+ word item to `Stage::Typing`, then `Stage::Known`; 50 calls of `pick_kind` all return `QKind::Arrange`. A 1-word item at Typing never returns Arrange.
  - `arrange_tiles_shuffle_and_order_rebuilds_the_term`: `order` mapped through `tiles`, joined with spaces, normalizes to the term; works for a 15-word term.
- [ ] **Step 2: Run, see them fail, implement, run green.** `cargo test -p merkrise-core`. Remove the `(3..=10)` cap and the 40 % roll for 3+ word items; keep the existing Hearing/Typing roll for the rest.

### Task 4: Magic wand (owner: hsili-34)

**Files:** Modify `core/src/lib.rs` (`Q`, `AnswerFeedback`, `submit_answer`, new export)

**Interfaces:**
- Consumes: `Q.order` (Task 3).
- Produces: `#[uniffi::export] pub fn wand_hint(placed: Vec<u32>) -> Vec<u32>`, `AnswerFeedback.hinted: bool`, and `Q` fields `revealed: usize, hinted: bool`. It returns empty when the current card is not Arrange.

- [ ] **Step 1: Tests.**
  - `wand_reveals_one_more_word_each_tap`: on a 4-tile card, `wand_hint(vec![])` returns `order[..1]`, then `order[..2]`, …, capped at 4.
  - `wand_never_shrinks_a_correct_prefix`: placed = the first 3 correct tiles → returns `order[..4]`.
  - `wand_counts_a_prefix_built_from_either_duplicate_tile`: a term with a word appearing twice, where `placed` uses the other copy's tile index. The prefix still counts (compare normalized tile text), so the result has p + 1 entries.
  - `wand_used_means_a_miss`: after one `wand_hint`, submitting the exact term gives `correct == false`, `hinted == true`, a stage demotion and `next_due == NOW + 600`.
- [ ] **Step 2: Run, see them fail, implement (spec §5: `k = max(revealed, p) + 1`, capped), run green.**

### Task 5: In-session retry (owner: hsili-34)

**Files:** Modify `core/src/lib.rs` (`Q`, `Question`, `submit_answer`, `current_question`)

**Interfaces:**
- Produces: `Q.retry: bool`, `Question.retry: bool`. A miss on a non-retry scored card inserts a fresh `make_q(word, same kind)` with `retry = true` at `min(pos + 3, len)` (`pos` = position after the miss). A retry is not scheduled, not counted, and `AnswerFeedback.scored == false`.

- [ ] **Step 1: Tests.**
  - `a_miss_comes_back_three_cards_later_once`: miss card 1 of a 6-card session; `total` becomes 7; the card at position 5 is the same word with `retry == true`. Missing the retry inserts nothing more.
  - `retry_is_not_scored_or_scheduled`: answering the retry right leaves `reps`, `due` and the stage as they were after the miss; `session_progress().correct` is unchanged.
  - `retry_is_a_fresh_card`: over 20 trials, the retry's choices order differs from the original's at least once; for Arrange, `revealed == 0` and `hinted == false`.
  - `missing_the_last_card_still_gets_its_retry`: a miss on the final card gives `finished == false`, and the next question is the retry.
- [ ] **Step 2: Run, see them fail, implement, run green.**

### Task 6: Alarm time + API freeze + bindings (owner: hsili-34)

**Files:** Modify `core/src/lib.rs`; regenerate `android/app/src/main/java/com/hsi/merkrise/core/merkrise_core.kt`; bump `core_version()` to `"Merkrise core 0.3.0"` (and its test).

**Interfaces:**
- Produces: `#[derive(uniffi::Record)] pub struct ReviewAlarm { pub at: i64, pub count: u32 }` and `#[uniffi::export] pub fn next_review_alarm(min_words: u32, now: i64, last_notified: i64) -> ReviewAlarm`. All times are core clock. Also `LevelInfo.topic: String` (from `SeedLevel.topic`, default = title); test `levels_report_their_topic`.
  - Due = `stage != New && due <= t` (same as `stats().due_today`).
  - Fewer than `min_words` non-New words → `at = -1, count = 0`.
  - Otherwise `t = max(min_words-th smallest due, now, last_notified + 4*3600)`. Then if `t mod 86400 >= 22*3600`, move to 08:00 next day; if `< 8*3600`, move to 08:00 the same day. `count` = words due by `t`.

- [ ] **Step 1: Tests.**
  - `alarm_waits_for_the_fifth_due_word`: 5 studied words due at +1 h…+5 h (daytime) → `at` = +5 h, `count == 5`.
  - `alarm_is_never_with_fewer_than_five_studied`: 4 studied → `at == -1`.
  - `alarm_respects_the_4_hour_gap`: everything already due, `last_notified = now - 1h` → `at = now + 3h`.
  - `alarm_skips_quiet_hours`: a raw time of 23:30 → 08:00 next day; 03:00 → 08:00 the same day.
- [ ] **Step 2: Run, see them fail, implement, run green.** `cargo test -p merkrise-core`, all tests.
- [ ] **Step 3: Rebuild the core and bindings.** Ping "Merkrise build" first (one builder). `sh scripts/build-core-android.sh && sh scripts/gen-bindings.sh`; then `cd android && ./gradlew --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: Hand off.** One SendMessage to "Merkrise build" carrying:
  - the exact Kotlin signatures from the regenerated bindings: `wandHint`, `nextReviewAlarm`, `ReviewAlarm`, `Question.retry`, `AnswerFeedback.hinted`
  - "bindings regenerated, APK builds, the tree is yours"

### Task 7: Kotlin — wand, retry line, topic grouping, notifications (owner: Merkrise build)

**Files:**
- Modify: `MainActivity.kt`, `Ui.kt`, `AndroidManifest.xml`
- Create: `Reminders.kt` (alarm scheduling, receivers, channel)
- Create: `res/drawable/ic_notify.xml`, `res/drawable/ic_wand.xml`

**Interfaces:**
- Consumes: Task 6 signatures.

- [ ] **Step 1: Arrange wand.**
  - Yellow `#FEE73E` wand button at the top-right of the answer line, on `#241431`.
  - A tap sets the answer line to exactly `wandHint(chosen)`.
  - Feedback chip: "revealed with the wand" when `fb.hinted`.
- [ ] **Step 2: Retry.** When `q.retry`, the hint line reads "Second try — not scored" and the "next review" line is hidden.
- [ ] **Step 3: Home grouping.** Group `levels()` by `LevelInfo.topic`, in order of first appearance. Topic cards form an accordion on the chapters screen (one open at a time, state in memory). The header lesson count comes from `levels()`.
- [ ] **Step 4: Notifications (spec §4).**
  - Call `nextReviewAlarm(5u, now(), lastNotified)` after every answer and on app start (`lastNotified` defaults to 0 before the first notification). If `at == -1`, cancel only. Otherwise cancel the old alarm and set `setAndAllowWhileIdle` at `(at - tzOffset) * 1000`. Store `at` and `count`.
  - The receiver posts "N words ready to review" (N > 20 → "20+"), stores `lastNotified = now()`, then calls `Reminders.rearm()` inside `goAsync()`. `rearm()` is headless: `loadSeed` from assets + `importState` + `nextReviewAlarm`. So reminders recur every ≥ 4 h while ≥ 5 words are due.
  - `BOOT_COMPLETED` calls the same `rearm()`. It never re-uses a stored time, which could be past or inside quiet hours. App start also calls it.
  - "Session in progress" is an Activity flag, not core state. `MainActivity` gets `android:screenOrientation="portrait"`.
  - Manifest: `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; boot receiver `exported="true"`, alarm receiver `exported="false"`.
  - Create the channel. Ask for the notification permission after the first finished session.
- [ ] **Step 5: Notification tap.**
  - `singleTop`, plus a `start_due` extra handled in `onNewIntent()` and, on a cold start, in `onCreate()` after `loadSeed` + `importState`.
  - With a session in progress, the tap only brings the app to the front.
- [ ] **Step 6: Build.** `./gradlew --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL; copy the APK to `E:\merkriser\merkrise-debug-arm64.apk`.

### Task 8: Walkthrough and Figma check (owner: both + the owner)

- [ ] **Step 1: Progress check.** Note Stats (known / learning / due) on the phone *before* installing. The owner installs the APK, and the numbers must match.
- [ ] **Step 2: Owner walkthrough.**
  - Home grouped by topic, lesson sizes 15–25.
  - A missed card returns 3 cards later as "Second try".
  - A sentence repeat shows tiles; the wand reveals word by word and gives "revealed with the wand".
  - Notification arrives. The quick test is a "Send a test reminder" Settings row, if the owner OKs it (asked at the Task 6 hand-off); otherwise wait for a real one.
  - Samsung One UI can put rarely opened apps to sleep and drop their alarms. Check Settings → Battery for Merkrise.
  - Tapping the notification during a session does not reset it.
- [ ] **Step 3: Figma compare.** Compare the Arrange, feedback and lesson-list screens with the Figma frames (`mn2UhPbM42aShaAzVOBbAk`, the "Memrise" page). Report differences to the owner; fix only what the owner agrees to.
- [ ] **Step 4: Owner report.** Report what passed and what didn't. Ask the owner whether to commit.
