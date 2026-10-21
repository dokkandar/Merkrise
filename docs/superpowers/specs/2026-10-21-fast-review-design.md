# Merkrise — Fast review + "not too similar" options (design)

**Date:** 2026-10-21 · **Status:** approved by the owner in chat 2026-10-21 (defaults below accepted)
**Split:** core = "Merkrise Plan" session, Kotlin (button, timer bar, time-up screen) = "Merkrise build".

## Owner's request
A timed review: given an English word/phrase, pick the right German phrase within a time limit. The
session starts easy (easier phrases, more time) and gets harder (harder phrases, less time). Wrong
options must not repeat or look alike. Answers count for the learning schedule like a normal choice card.

## 1. Options that are not too similar (all multiple-choice cards)
Wrong options for Mc / Reversed / AudioMc cards come from the card's own lesson first, then the whole
course. A candidate is rejected if it is *too similar* to the right answer or to an option already
chosen. On normalized text (case, umlaut spelling and punctuation ignored), `too_similar(a, b)` is true when
- the shared-word share (Jaccard) is >= 0.4, or
- they share a run of >= 3 words in a row, or
- spelling similarity (1 - Levenshtein / longer length) is >= 0.7.
If fewer than 3 clean options exist (tiny seeds) the rule is relaxed so a card always has 4 options.
Measured on the real course (lessons of 15-25): 2.9% of cards had a lookalike with the old random pick;
no lesson runs out of clean options.

## 2. Fast review
- Core: `start_fast_session(level: Option<u32>, size: u32, now: i64) -> Result<u32, CoreError>`;
  `Question.time_limit_ms: u32` (0 = untimed). Kotlin calls it from a "Fast review" button on Home
  (all words) and on a lesson (that lesson). Default size 12.
- Words: only words already met (stage != New); never two items with the same normalized phrase.
  Fewer than 4 words in the course -> NoWords. **Home (all words): words practised in this app
  (Review.touched) first, the older imported Memrise words only fill up when fewer than 12 were
  practised (owner, 2026-10-21). A lesson's own review draws from all met words of that lesson.**
  Inside each group, due words come first, then the rest, random order.
- Cards: all QKind::Mc (English prompt, four German phrases). No presentation cards, no second try.
- Order = easy to hard: ascending number of words in the German phrase (ties random).
- Ramp t = k / (n - 1) for card k of n (0 for the first, 1 for the last).
- Time limit = (8 s + 1 s x words) x (1 - 0.55 t). A 2-word first card gets 10 s, an 8-word last card 7.2 s.
- Distractor hardness = t: early cards pick the least alike wrong options, late cards the most alike
  ones (still under the too-similar line).
- Time-up: Kotlin calls `submit_answer("")`: a miss (wrong, scored, demotes, due in 10 minutes), like any
  wrong answer. Kotlin shows "Time's up" and the right answer. Right answers promote like a normal card.

## 3. Tests
Unit tests in lib.rs: the similarity rule examples; on the real course no card shows lookalike options;
fast sessions are ordered easy to hard with falling time limits; new words and duplicate phrases are
skipped; a time-out is a miss without a retry; late options are more alike than early ones.

## 4. Speed round inside normal lessons (owner: "i want it in regular lessons too", 2026-10-21)
Every normal session (`start_session`: a lesson, Study all, due today) ends with a short timed speed
round, built like a fast review: the last up to **4** cards about words already met (never more than
half of them; a new word's intro and first card stay together and untimed; no phrase twice) are moved to
the end of the session as timed Mc cards, easy to hard, with the same clock and option hardness as
section 2. Session length is unchanged. A second try after a miss is placed at most just before the
round, never inside it. A session with only new words has no round. Answers count for the schedule like
any choice card; a time-out is a miss. Typing limit and speed round are independent: the round is
built first, then the 10% typing limit.
