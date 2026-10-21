# Merkrise — Lessons, repeats, notifications, magic wand (design)

**Date:** 2026-10-21 · **Status:** approved by the owner 2026-10-21
**Builds on:** `2026-10-20-merkrise-v1-design.md` (stage ladder §6.1, SM-2 §6.2,
length rule §6.3 — all already implemented in `core/src/lib.rs`).
**Split:** core + scripts = session "hsili-34"; Kotlin = session "Merkrise build".

## 1. What the owner asked for (decisions locked 2026-10-21)

| # | Requirement | Decision |
|---|---|---|
| 1 | Each lesson has 15–25 items (a word or a phrase or a sentence = 1 item) | A2 is re-chunked; it currently has 9–54 per level |
| 2 | The short-word / long-sentence mix follows the captured A2 course | Every lesson of a topic gets that topic's mix |
| 3 | Right/wrong drives repeat intervals chosen by the app; notify when ≥ 5 words are due | Keep the existing SM-2 + length rule; add in-session retry and an alarm |
| 4 | UI is self-explanatory | Checked against the Figma once built |
| 5 | Any new word set gets rule 1 | The owner hands over files, we rebuild the APK (no on-phone importer) |
| 6 | Repeats of a sentence: tap its words in order; a magic wand reveals it word by word | A wand-revealed card counts as a **miss** |

## 2. Lessons (items 1, 2, 5)

### 2.1 Facts from the captured A2 course (`capture/seed.json`)
1,617 items in 51 levels across 18 topics ("Sports 1".."Sports 5" = topic
"Sports"). Level size 9–54, median 31. By German word count: 40 % short (1–2),
25 % mid (3–5), 35 % long (6+), interleaved in Memrise's order. The mix varies by
topic (Useful Phrases = all short; Tell Me about Your Life = mostly sentences).

### 2.2 Rule
Runs per **source set** (the A2 course, Lesson 01, each future upload). Lessons
never mix items from two source sets.

1. Pool the levels of one topic, in course order (topic = title minus its
   trailing number).
2. If the pool (plus any carry-over from the previous topic of the same set)
   has < 15 items, carry it forward into the next topic.
3. Otherwise `n = ceil(pool / 25)`. If `pool / n < 15` (only 26–29 items), emit
   one lesson of 15 and carry the rest forward.
4. Deal the pool into `n` lessons bucket by bucket (all short items round-robin,
   then mid, then long), then restore Memrise order inside each lesson. This gives
   every lesson of a topic the same short/long mix.
5. A tail that is still < 15 at the end of a set is pooled with the set's last
   lesson and re-dealt into 1 lesson (≤ 25) or 2.
6. Lesson title: topic name + number ("Sports 3"); a merged lesson uses
   "Topic A + Topic B".
7. No item is dropped, merged or deduplicated. The 5 terms that occur in two
   A2 levels (die Beziehung, Amerika, das Boot, "ich habe einen Fehler
   gemacht", gesund) stay two items, each with its own saved progress.

Unavoidable exceptions (no 15–25 split exists): a whole set of < 15 items is
one short lesson; a whole set of 26–29 becomes two lessons of 13–15.

### 2.3 Measured on the real data (prototype run 2026-10-21)
| | lessons | size min / median / max | outside 15–25 | long-% spread inside a topic (stdev) |
|---|---|---|---|---|
| A2 as captured | 51 | 9 / 31 / 54 | 38 | — |
| naive split by size | 84 | 9 / 18 / 29 | 8 | 10.0 pts |
| **this rule** | **74** | **15 / 23 / 25** | **0** | **1.4 pts** |

Lesson 01 (64 items) → 22 / 21 / 21. Uploads: 9 → [9], 26 → [13, 13],
29 → [15, 14], 30 → [15, 15], 49 → [25, 24].

### 2.4 Where it runs
A build-time script `scripts/build-lessons.py IN OUT`. On this Windows tree
the Linux scripts (`bundle-assets.sh`, `build-lesson01.py`) were never run:
`assets/seed.json` is already the merged, un-chunked 52-level file. So it is
copied once to `capture/seed-merged.json` (local-only, not shipped), and every
build runs `build-lessons.py capture/seed-merged.json assets/seed.json`. A new
word set is appended to `seed-merged.json` as one more source level. The output
lists `levels` as lessons. The Rust core's `levels()`
API is unchanged (a lesson is just a level); the only core change for lessons is
the progress key (§2.5). The script refuses a
file already marked `meta.chunked = true` (running it twice would re-pool merged
lessons). A future word set is added as one more source set before this step
(its importer is written when the files arrive; spec §16.6 is the format).
The script carries an assert-based self-check (`python -I scripts/build-lessons.py --test`)
covering the sizes in §2.3.

### 2.5 Saved progress must survive the re-chunk
Today a progress key is `"{parent level index}:{term}"` (`Word.key`, used by
`export_state` / `import_state`). Re-chunking renumbers the levels, so every
saved key would stop matching, and `import_state` drops unknown keys silently:
the owner's `progress.json` (on the phone since 15:07) would vanish without an
error. The Memrise `id` is not a usable key: 5 ids occur twice (same term in two
levels), and `words[]` / `reviews[]` are parallel arrays.

Fix, no migration: the chunker **keeps every item's own `level` field** (its
source level; equal to the parent level for all 1,681 items today), and the core
builds the key from that field (`"{item.level}:{term}"`) instead of the lesson
index. `Word.level` stays the parent lesson (it feeds the distractor pool and
`WordCard.level`); only `Word.key` uses the source level. Keys stay
byte-identical to today's, so the existing `progress.json`
loads unchanged and `SavedState.version` stays 1. `(level, term)` has zero
duplicates today. A future set gets a new, unused source level number. A
regression test exports progress on the old seed, imports it on the chunked
seed, and asserts every key is applied.

## 3. Repeats (item 3)

Existing, kept as is: a scored answer runs `schedule()` (SM-2 + word-length
factor; a miss demotes one stage, resets reps, due in 10 minutes; a hit
promotes one stage, first interval 1 day, then 6 days, then × ease).

New: **in-session retry.** A missed card is shown once more about 3 cards later
in the same session (at the end if fewer than 3 remain). The retry is practice:
it does not call `schedule()` and does not count toward the score, so a lucky
second try cannot undo the miss. The 10-minute due time then brings the word
back in the next session.

- `Question` gains `retry: bool`; the UI shows "Second try — not scored" and
  hides the "next review" line.
- A retry is a fresh card: choices are reshuffled (otherwise it tests the
  position), Arrange tiles are reshuffled and the wand counter is reset.
- Inserting a retry adds one card, so "Card 7 / 21" and the progress bar move
  by one. This is intended; do not "fix" it.

## 4. Notification when ≥ 5 words are due (item 3)

- Core: `next_review_alarm(min_words: u32, now: i64, last_notified: i64) -> ReviewAlarm { at: i64, count: u32 }`.
  `at` = the due time of the `min_words`-th soonest due word (already past ⇒
  `now`); `count` = words due by `at`; `at = -1` when fewer than `min_words`
  words have ever been studied. "Due" uses the same definition as the Home
  tile (`stats().due_today`), so the two never disagree.
- **Backlog (owner decided 2026-10-21: minimum gap).** The Memrise progress
  import makes all 1,617 A2 words overdue (last reviewed 2021), so "≥ 5 due" is
  true from day one until the backlog is cleared. Notifications are therefore
  spaced at least 4 hours apart: the alarm time is
  `max(at, last_notified + 4 h)`, then moved out of quiet hours; Kotlin keeps
  `last_notified` in prefs. Inside the 08:00-22:00 window the backlog gives up to
  4 reminders a day (08, 12, 16, 20). Kotlin passes `max(last_notified, last study
  or app open)` as the gate, so no reminder arrives within 4 h of studying (otherwise a
  "20+ words ready" notification could fire in the middle of a session while the
  backlog is overdue). Normal 5th-word timing takes over once it is cleared.
- Kotlin, after every answer and on app start: cancel and set one
  `AlarmManager.setAndAllowWhileIdle` alarm at `at` (inexact; no exact-alarm
  permission). Store `at` and `count` in prefs.
- Clock: the core's clock is epoch + the local time-zone offset
  (`MainActivity.now()`); convert back to real epoch before setting the alarm.
- Quiet hours 22:00–08:00: an alarm inside them moves to 08:00.
- When it fires, at least `count` words are due by construction, because any
  study reschedules the alarm. After posting, the receiver re-arms itself
  headless (load seed + progress, `next_review_alarm`), so reminders recur
  while words stay due; boot uses the same re-arm, never a stored time. It posts
  "N words ready to review", with N capped as "20+" (the backlog would
  otherwise say 1617), and records `last_notified`.
- Quiet hours and the 4-hour gap are computed **in the core** (plan
  2026-10-21): the core clock already is local time (epoch + tz offset), so
  time of day = `t mod 86400`, and the rule is covered by `cargo test`.
  `next_review_alarm` takes `last_notified` and returns the adjusted time;
  Kotlin only converts it to real epoch. 22:00–08:00 crosses midnight. A
  daylight-saving switch between scheduling and firing can shift it by 1 h,
  which is accepted.
- Tapping the notification: `MainActivity` becomes
  `android:launchMode="singleTop"`; the intent carries a `start_due` extra,
  handled in `onNewIntent()` and, on a cold start, in `onCreate()` after the
  seed and progress are loaded. If a session is already in progress the tap
  only brings it to the front; it never calls `startSession()` over it.
- A notification channel is created (minSdk 26), and a small-icon vector
  drawable is added (the app has none). The boot receiver is
  `exported="true"`, the alarm receiver `exported="false"`.
- `BOOT_COMPLETED` re-sets the stored alarm (past ⇒ fire now).
- Android 13+: ask for `POST_NOTIFICATIONS` once, after the first finished
  session (not on first launch). Manifest adds `POST_NOTIFICATIONS` and
  `RECEIVE_BOOT_COMPLETED`.

## 5. Sentence repeats and the magic wand (item 6)

- `pick_kind()`: at the repeat stages (Typing, Known) an item of 3+ words
  becomes an Arrange card (today: 40 % chance and only 3–10 words; the 10-word
  cap goes, the longest item is 15). That is 965 of 1,681 items, and 1,506
  words are at Known today. **Owner decided 2026-10-21: always Arrange** for
  3+ word items at Typing/Known (no Typing/Hearing roll for them); 1–2 word
  items keep today's Typing/Hearing roll. Learning stages (Mc, Reversed,
  Audio) are unchanged.
- Arrange screen (exists): tiles are the German words, shuffled; the user taps
  them into the answer line in order. A punctuation-only token (a lone "-" or
  "?", 4 cases) is merged into the previous tile; the checker already ignores
  punctuation. 81 items repeat a word, so tiles can be duplicates.
- Wand: `wand_hint(placed: Vec<u32>) -> Vec<u32>`. The core finds `p`, the
  length of the longest correct prefix of `placed` (comparing normalized tile
  text, so duplicate words work), returns the first `max(k, p) + 1` correct
  tile indices (capped at the sentence length) and sets `k` to that. So a user
  who has 4 of 7 right gets 5, never fewer. Indices, not strings, so duplicate
  words map to the right tile. The UI replaces the answer line with exactly
  those tiles, which also clears wrong words after the prefix. The first call
  marks the card hinted.
- `submit_answer` on a hinted card: scored as a miss (demote, 10-minute due,
  in-session retry), even if the final order is right.
  `AnswerFeedback` gains `hinted: bool`; the feedback chip says
  "revealed with the wand" instead of "not quite".
- The wand is a button at the top-right of the answer line. The Figma Arrange
  frames have no wand, so it is drawn in the existing palette (yellow #FEE73E
  on surface #241431).

## 6. UI check (item 4)

Grouping (**owner decided 2026-10-21: group by topic**): the Figma is Course →
level cards → lesson panels. The home list shows one card per topic (title
minus its trailing number; a merged "A + B" lesson goes under its first
topic; 18 A2 topics + Lesson 01) with its lessons inside. Kotlin only, no core
change.

After the build, the Arrange + wand screen, the feedback chip, the
lesson list and the notification are compared with the Figma
Arrange / lesson-list frames (file `mn2UhPbM42aShaAzVOBbAk`). The owner walks
through the screens on the phone; nobody injects taps into it.

## 7. Testing

- `cargo test -p merkrise-core`: retry happens once and is unscored; a hinted
  card is a miss; `wand_hint` prefixes and duplicate words; `next_review_alarm`
  (fewer than 5, exactly 5, already due); `pick_kind` gives Arrange for 3+ words
  at Typing/Known; progress keys survive the re-chunk (§2.5). The existing
  `tests/real_seed.rs` hard-codes 52 levels, 64 new words and `level_words(52)`;
  it is updated for the lessons.
- Kotlin: the Home header "A2 Beyond Beginner" is hard-coded; it stays, but the
  lesson count shown comes from `levels()`.
- `python -I scripts/build-lessons.py --test`.
- Build per HANDOFF §2 order: `build-core-android.sh` → `gen-bindings.sh` →
  `cargo test` → `gradlew :app:assembleDebug`. One session builds at a time.

## 8. Not in scope

On-phone importer; changing the SM-2 intervals; notification settings screen
(quiet hours and the 5-word threshold are constants until asked for).
