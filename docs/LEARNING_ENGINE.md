# Merkrise — Learning Engine Spec

**For:** the coding agent implementing the study/SRS engine.
**Status:** approved design (2026-10-23). Build this *faithful to Memrise first*;
the "alter it" customizations are clearly marked as a later layer (§10).
**Read first:** `HANDOFF.md` (project state) and
`docs/superpowers/specs/2026-10-20-merkrise-v1-design.md` (schema/architecture).
This document is the authority for *how teaching and scheduling work*.

Everything below is derived from the real captured Memrise data in `capture/`
(course 690189 = A2, course 37726 = Advanced), not guessed.

---

## 0. One-paragraph summary

A word climbs **two coupled ladders** as the learner studies it over days: a
**difficulty ladder** (presentation → multiple choice → reversed MC → audio MC →
typing) and a **time ladder** (12h → 1d → 6d → 12d → 24d → 48d → 96d → 180d).
New words are introduced a few at a time and "planted" within one session; after
that, each successful review bumps the word up one rung on *both* ladders, so it
is seen less often and tested harder until it reaches the top (180 days = known).
Wrong answers drop it back down.

---

## 1. The data you have (per word, from `seed.json`)

Each item in `android/app/src/main/assets/seed.json` → `levels[].items[]`:

| field | meaning |
|---|---|
| `id` | stable word id |
| `level` | chapter index (1..N) |
| `term` | German |
| `meaning` | English |
| `pos_tag` | part of speech (may be null) |
| `all_forms`, `literal` | extra hint text (may be null) |
| `audio` | list of local audio filenames (may be **empty** — see §9) |
| `typing_alternatives` | accepted typed answers (umlaut/ß variants) |
| `memrise_progress` | the user's prior Memrise state — **seed from this** (§8). null if never studied / course not studied. |

Two courses differ sharply: **A2 = 100% audio**; **Advanced (37726) ≈ 3% audio**.
The engine must degrade gracefully when a word has no audio (§9).

---

## 2. The difficulty ladder (test types) — faithful to Memrise

Verified from the `screens` panels in every captured word. A word is taught/tested
with these, in this order of difficulty:

| stage | test type | what the learner does |
|---|---|---|
| 0 | **PRESENTATION** | see term + meaning + play audio (not a test — the "meet it" panel) |
| 1 | **MULTIPLE_CHOICE** | English prompt → pick the German (from 4–6) |
| 2 | **REVERSED_MC** | German prompt → pick the English |
| 3 | **AUDIO_MC** | hear audio → pick the right word *(skip if no audio, §9)* |
| 4 | **TYPING** | English prompt → type the German (hardest) |
| — | **TAPPING** | *(phrases only)* tap the words into order — optional for v1 (§9) |

---

## 3. The time ladder (spaced repetition) — measured from real data

The exact interval rungs observed in the user's A2 progress:

```
rung:   0     1    2     3     4     5     6     7
days:  0.5    1    6    12    24    48    96   180   (cap = "known")
```
(12 hours, 1 day, 6, 12, 24, 48, 96, 180 days.) Store this as a constant array.

**Scheduling rules** (`rung` is an integer 0..7 per word):
- **Correct review:** `rung = min(rung + 1, 7)`; `next_due = now + LADDER[rung] days`.
- **Wrong review:** `rung = max(rung - 2, 0)`; `next_due = now + LADDER[rung] days`
  (so a lapse drops it ~two rungs and brings it back soon).
- A word at `rung 7` (180 days) is **known**; it still comes back every 180 days.

---

## 4. Coupling the two ladders — `rung` drives *both*

The test type shown at a review is a function of the word's current `rung`:

```
test_type_for(rung, has_audio):
    0 -> MULTIPLE_CHOICE
    1 -> REVERSED_MC
    2 -> AUDIO_MC  (if has_audio else TYPING)
    >=3 -> TYPING
```
So as a word survives reviews, its interval lengthens *and* its test hardens,
locking into typing from rung 3 onward. This is the whole "two ladders climb
together" behavior in one rule.

---

## 5. The session model

There are two session kinds; a normal day mixes them.

### 5a. Learn-new session ("plant the seeds")
- Take the **next N not-yet-started words** from the chosen chapter, in `pos`
  order. **Default N = 5** (setting, range 3–10).
- Show each word's **PRESENTATION** panel once, then **drill the batch
  interleaved** with MULTIPLE_CHOICE: cycle through the N words, asking each until
  each has been answered **correctly twice**. A wrong answer re-queues that word a
  few positions later (not immediately).
- **Session ends** when all N are planted (each correct ×2) — ~12–18 taps for 5
  words. Each planted word then enters the schedule at **rung 0** (`next_due =
  now + 12h`).

### 5b. Review session
- **Due set** = words with `next_due <= now`, ordered by `next_due` (most overdue
  first). Each is shown with `test_type_for(rung, has_audio)`.
- **Daily review cap:** default **50** (setting). On import there will be a large
  overdue backlog (§8) — the cap spreads it over days; do **not** show all at once.
- Session ends when the due set (up to the cap) is cleared.

### 5c. A normal day
`Start studying` = reviews first (up to cap), then plant one new batch if the
user wants more. Keep new-vs-review counts visible. Home shows **due-today count**
and **new-available count** per chapter.

---

## 6. Answer checking

Reuse the existing `normalize()` in `core/src/lib.rs`: trim, lowercase, collapse
whitespace, fold `ä→ae ö→oe ü→ue ß→ss`. Then:
- **Typing:** accept if `normalize(input)` is in the word's accepted set =
  `normalize(term)` + each `normalize(typing_alternatives[i])`.
- **Multiple choice / reversed / audio:** correct iff the chosen option equals the
  target (`meaning` for MC/audio, `term` for reversed).
- Always show the canonical correct answer on a wrong attempt.

---

## 7. Distractor generation (for the choice panels)

Pick 3–5 wrong options that are **plausible but clearly different**:
- Draw from **other words in the same chapter** first; fall back to the whole
  course if the chapter is too small.
- Distractors must not equal the correct answer or each other; prefer same
  `pos_tag` when available (so a verb's distractors are verbs). Shuffle.
- Generate fresh each time (don't reuse Memrise's captured choice lists — those
  were a fixed snapshot).

---

## 8. Seeding from the user's Memrise progress — DON'T make them restart

For every word with `memrise_progress`, initialize its `review_state` from it so
mastered words aren't re-taught:
- `rung` = the highest ladder index whose value `<= memrise_progress.interval`
  (so a 180-day word starts at rung 7 = known; a 1-day word at rung 1).
- `next_due` = `parse(memrise_progress.next_date)`. Many are years in the past →
  due now; the daily cap (§5b) meters the flood.
- `lapses` ≈ `attempts - correct`; `starred` and `is_difficult` carried over
  (a difficult word may start one rung lower — optional).
- Words with **no** progress (or a course the user never studied, e.g. 37726)
  start as **new** (not yet planted).

A2 has full progress (1,617 words, mostly at the 180-day cap). 37726 has **none**
— it begins entirely new.

---

## 9. Course/word differences the engine must handle

- **No-audio words** (almost all of course 37726): the AUDIO_MC stage is skipped
  (`test_type_for` returns TYPING instead), and the presentation/typing panels
  show no play button. Optionally use Android TTS (German) as a fallback — mark
  this a setting, off by default.
- **Phrases / multi-word items** (Memrise used the TAPPING panel for these):
  for v1, treat them like any word (MC + typing on the full phrase). TAPPING is a
  nice-to-have later; the token lists are in the captured data if wanted.
- **Chapters vary in size** (9 to 74 words) — session/new-word logic must handle a
  chapter smaller than N.

---

## 10. The "then alter it" layer — Merkrise's own twist (build AFTER §1–9 work)

The user's signature customization (design spec D5): **word-length-aware pacing.**
Longer German terms are harder, so they come due sooner and get more answer time.
Keep it OFF by default so the engine first behaves exactly like Memrise, then
expose it as a setting:
- `len_factor = clamp(term_char_count / course_mean_len, 0.6, 1.6)`.
- Scheduled interval `*= (1 - w) + w / len_factor` (longer → sooner); `w =
  length_weight` in [0,1], default 0.
- Timed modes: `answer_time = base * len_factor` (longer → more time).
This layers on top of §3 without changing the ladder itself.

---

## 11. Data model (SQLite, owned by Rust `rusqlite`)

Add persistence (today's engine is in-memory only). Minimum:
```sql
review_state(
  word_id   INTEGER PRIMARY KEY,   -- = seed item id
  rung      INTEGER DEFAULT -1,    -- -1 = new/unplanted; 0..7 once scheduled
  next_due  INTEGER DEFAULT 0,     -- unix seconds
  reps      INTEGER DEFAULT 0,
  lapses    INTEGER DEFAULT 0,
  starred   INTEGER DEFAULT 0,
  last_seen INTEGER
);
settings(key TEXT PRIMARY KEY, value TEXT);   -- new_per_session, review_cap, length_weight, tts_fallback
session_log(id INTEGER PRIMARY KEY, started INTEGER, kind TEXT, planted INTEGER, reviewed INTEGER, correct INTEGER);
```
Import (§8) writes `review_state` once on first run; the DB is the source of truth
thereafter. The deterministic clock must be injectable for tests.

---

## 12. Core API additions (uniffi) — extend what exists

Current engine has `levels`, `start_session(level, size)`, `current_question`,
`submit_answer`, `session_progress`. Evolve to:
```
fn init(db_path) -> Result<(), CoreError>
fn import_seed(seed_json, now_epoch) -> ImportStats     // + seed progress (§8)
fn due_count(level: Option<u32>) -> u32
fn new_count(level: Option<u32>) -> u32
fn start_learn(level: Option<u32>, new_count: u32) -> Result<(), CoreError>   // §5a
fn start_review(level: Option<u32>, cap: u32) -> Result<(), CoreError>        // §5b
fn next_question() -> Option<Question>    // Question gains `test_type` = the stage
fn submit_answer(word_id, input) -> AnswerFeedback   // updates rung + next_due (§3)
fn end_session() -> SessionSummary
fn stats() -> Stats          // total, known(rung==7), due_today, streak_days
```
`Question` should carry the `test_type` (an enum of the §2 stages) so Kotlin draws
the right panel (it already handles MC + typing; add reversed + audio-MC).

---

## 13. Pseudocode (the two rules that matter)

```text
submit_answer(word_id, input):
    w = word(word_id); st = review_state(word_id)
    correct = check(w, current_test_type, input)        # §6
    if correct: st.rung = min(st.rung + 1, 7); st.reps += 1
    else:       st.rung = max(st.rung - 2, 0); st.lapses += 1
    st.next_due = now + LADDER[st.rung] * day            # §3 (apply §10 if enabled)
    st.last_seen = now
    advance session; return {correct, expected, finished}

build_review_queue(level, cap):
    due = review_state where rung >= 0 and next_due <= now (and level matches)
    order by next_due asc ; take cap
    each shown with test_type_for(rung, w.has_audio)      # §4
```

---

## 14. Settings & defaults
| setting | default | range |
|---|---|---|
| new words per learn session | 5 | 3–10 |
| review cap per day | 50 | 20–200 |
| length_weight (§10) | 0 (off) | 0–1 |
| tts_fallback for no-audio words | off | on/off |

## 15. Edge cases to handle
- Huge overdue backlog after import → meter with the daily cap, never dump all.
- Chapter smaller than `new_count` → plant what exists.
- Word with empty `audio` at an audio rung → fall through to typing (§9).
- Re-import must be idempotent (match on word `id`); don't wipe `review_state`.
- Study-all vs per-chapter: both go through the same due/new logic, just scoped.

## 16. Testing (desktop, inject the clock)
Unit-test on the host, no emulator: the ladder math (promote/demote across all 8
rungs), `test_type_for` incl. no-audio fallback, answer normalization incl.
umlaut/ß and `typing_alternatives`, distractor uniqueness, the plant-session
"correct ×2" exit, the review-queue ordering + cap, and the progress-seeding
mapping (interval → rung) for several captured examples. These are the correctness
core — most bugs will be here, so cover them before touching the UI.

## 17. Build order
1. SQLite `review_state` + `import_seed` seeding from `memrise_progress` (§8, §11).
2. The scheduler (§3/§4) + `submit_answer` rule, unit-tested (§16).
3. Review-session queue + daily cap (§5b); wire into the existing quiz UI, add the
   reversed-MC and audio-MC panels.
4. Learn-new "planting" session (§5a).
5. Home shows due/new counts; streaks; settings screen (§14).
6. Only then the §10 length-pacing layer.
```
