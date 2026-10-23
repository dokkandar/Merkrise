uniffi::setup_scaffolding!();

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::Mutex;

#[uniffi::export]
pub fn core_version() -> String {
    "Merkrise core 0.3.0".into()
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("seed parse error: {0}")]
    Parse(String),
    #[error("state parse error: {0}")]
    State(String),
    #[error("no words loaded")]
    NoWords,
}

// ---------------------------------------------------------------- types

/// Where a word is on the Memrise ladder. The stage selects the next card type:
/// New = presentation + multiple choice, then Mc -> Reversed -> Audio -> Typing -> Known.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, uniffi::Enum)]
pub enum Stage {
    New,
    Mc,
    Reversed,
    Audio,
    Typing,
    Known,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum QKind {
    /// "Learn new word" card: term, meaning, audio. Not scored.
    Presentation,
    /// English prompt, pick the German.
    Mc,
    /// German prompt (+audio), pick the English.
    Reversed,
    /// Audio prompt, pick the German.
    AudioMc,
    /// English prompt, type the German.
    Typing,
    /// Audio prompt, type what you hear.
    Hearing,
    /// German prompt (+audio), type the English meaning.
    MeaningTyping,
    /// English prompt, put the German words in order.
    Arrange,
    /// Context cloze: a real course sentence with the word blanked, pick the German word.
    Cloze,
    /// Context cloze: a real course sentence with the word blanked, pick its English meaning.
    ClozeMeaning,
    /// Context cloze: a real course sentence with the word blanked, type the German word.
    ClozeTyping,
    /// Yes/no recognition: does this word mean the shown translation? (non-typed filler)
    YesNo,
    /// Sentence selecting: read a real sentence containing the word and pick its English meaning.
    SentenceChoice,
}

/// A word for the browse screen.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WordCard {
    pub term: String,
    pub meaning: String,
    pub pos: Option<String>,
    pub audio_file: Option<String>,
    pub level: u32,
}

/// One word of a chapter, with its learning state (lesson grid + word list).
#[derive(Debug, Clone, uniffi::Record)]
pub struct WordRow {
    pub index: u32,
    pub term: String,
    pub meaning: String,
    pub audio_file: Option<String>,
    pub stage: Stage,
    pub reps: u32,
    pub due: i64,
    pub starred: bool,
}

/// One quiz question for the UI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Question {
    pub position: u32, // 1-based within the session
    pub total: u32,
    pub kind: QKind,
    pub word: u32, // global word index, for mark_known / set_starred / rate_easy
    pub prompt: String, // may be empty (audio-only prompts)
    pub prompt_audio: Option<String>,
    pub choices: Vec<String>, // Mc / Reversed / AudioMc (4)
    pub tiles: Vec<String>,   // Arrange: shuffled words
    pub meaning: Option<String>, // Presentation only
    pub detail: Option<String>,  // Presentation only: part of speech / forms
    pub note: Option<String>,    // Presentation only: literal translation / examples
    pub stage: Stage,
    pub reps: u32,
    pub starred: bool,
    pub retry: bool, // second try of a card missed earlier in this session (not scored)
    /// Fast review: the time to answer in ms (the UI submits "" when it runs out); 0 = untimed.
    pub time_limit_ms: u32,
    /// Learn new: 1-based task within this word's set; 0 on its introduction card and elsewhere.
    pub task_position: u32,
    /// Learn new: tasks in this word's set (5-6); 0 in regular sessions.
    pub task_total: u32,
    /// Context cloze answer (the word that was blanked); None on other cards.
    pub answer: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AnswerFeedback {
    pub correct: bool,
    pub expected: String, // the canonical correct answer to show
    pub finished: bool,   // was that the last question
    pub scored: bool,     // false for presentation cards
    pub stage: Stage,     // the word's stage after this answer
    pub next_due: i64,    // when it is due again (session clock); 0 if unscheduled
    pub hinted: bool,     // the magic wand was used: scored as a miss
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionProgress {
    pub total: u32,  // all cards, presentation included
    pub answered: u32,
    pub correct: u32,
    pub scored: u32, // cards that count towards the score
    pub finished: bool,
}

/// A chapter/level for the home list.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LevelInfo {
    pub index: u32,
    pub title: String,
    pub topic: String, // lessons of one topic are grouped on the home screen; defaults to the title
    pub count: u32,
    pub known: u32,
    pub learning: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Stats {
    pub total_words: u32,
    pub known: u32,
    pub learning: u32,
    pub new_words: u32,
    pub due_today: u32,
    pub streak_days: u32,
    /// Every correct answer ever: the imported Memrise history plus the scored answers given in this app.
    pub correct_total: u64,
}

/// User-adjustable pace (spec §6.3).
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PaceSettings {
    /// New words introduced in one session.
    pub new_per_session: u32,
    /// Global multiplier on every interval (<1 reviews more often).
    pub interval_scale: f32,
    /// 0 = pure SM-2; higher = longer words come due sooner.
    pub length_weight: f32,
}

impl PaceSettings {
    const fn default_const() -> Self {
        PaceSettings { new_per_session: 15, interval_scale: 1.0, length_weight: 0.3 }
    }
}

// ------------------------------------------------------- internal model

#[derive(Clone)]
struct Word {
    key: String, // "source level:term" — stable id for saved state, survives the lesson re-chunk
    term: String,
    meaning: String,
    pos: Option<String>,
    forms: Option<String>,
    literal: Option<String>,
    audio_file: Option<String>,
    level: u32,
    len: usize,    // term length in chars
    tokens: usize, // whitespace-separated words in the term
    accepted: Vec<String>, // normalized acceptable typed answers
}

/// Per-word SRS state (spec §5 review_state).
#[derive(Clone, Serialize, Deserialize)]
struct Review {
    stage: Stage,
    ease: f32,
    interval_days: f32,
    reps: u32,
    lapses: u32,
    due: i64, // unix seconds, session clock
    starred: bool,
    #[serde(skip)]
    touched: bool, // changed by the user -> included in export_state
    /// Learn new: position in this word's deterministic context-phrase deck (shuffle bag).
    #[serde(default)]
    context_cursor: u32,
    /// Learn new: fingerprint of the deck order, so a seed change resets the cursor.
    #[serde(default)]
    context_deck: u32,
}

impl Review {
    fn new() -> Self {
        Review {
            stage: Stage::New,
            ease: 2.5,
            interval_days: 0.0,
            reps: 0,
            lapses: 0,
            due: 0,
            starred: false,
            touched: false,
            context_cursor: 0,
            context_deck: 0,
        }
    }
}

struct Q {
    word: usize,
    kind: QKind,
    choices: Vec<String>,
    tiles: Vec<String>,
    order: Vec<usize>, // Arrange: tile indices in the correct order
    revealed: usize,   // Arrange: words the wand has shown
    hinted: bool,      // the wand was used: the answer counts as a miss
    retry: bool,       // second try of a missed card: practice, not scored or scheduled
    time_ms: u32,      // fast review: time to answer; 0 = untimed
    task_pos: u32,     // Learn new: 1-based task within the word's set; 0 elsewhere
    task_total: u32,   // Learn new: tasks in the word's set; 0 elsewhere
    srs: bool,         // Learn new: this card applies the word's one schedule update
    prompt_override: Option<String>, // context cloze: the sentence with the word blanked
    answer_override: Option<String>, // context cloze: the word that fills the blank
}

struct Session {
    questions: Vec<Q>,
    pos: usize,
    correct: u32,
    learn_faults: BTreeSet<usize>, // Learn new: words with at least one missed task
}

struct LevelMeta {
    index: u32,
    title: String,
    topic: String,
    members: Vec<usize>, // indices into words
}

struct Core {
    words: Vec<Word>,
    levels: Vec<LevelMeta>,
    initial: Vec<Review>, // derived from the imported Memrise progress
    reviews: Vec<Review>,
    pace: PaceSettings,
    days: BTreeSet<i64>, // days (session clock / 86400) with at least one answer
    /// Saved progress whose key this seed does not have (a stale or different bundle). Kept and
    /// written back, so a seed change can never silently delete the owner's progress.
    orphans: BTreeMap<String, Review>,
    /// Unused typing allowance carried between sessions, in tenths of a typed card (see `limit_typing`).
    typing_credit: u32,
    /// Correct scored answers given in this app (the header's total adds the Memrise history).
    correct_total: u64,
    mean_len: f32,
    now: i64, // clock of the current session
    session: Option<Session>,
    /// Learn new: for each word, other course entries whose term contains its head word.
    contexts: Vec<Vec<usize>>,
}

impl Core {
    const fn new() -> Self {
        Core {
            words: Vec::new(),
            levels: Vec::new(),
            initial: Vec::new(),
            reviews: Vec::new(),
            pace: PaceSettings::default_const(),
            days: BTreeSet::new(),
            orphans: BTreeMap::new(),
            typing_credit: 0,
            correct_total: 0,
            mean_len: 1.0,
            now: 0,
            session: None,
            contexts: Vec::new(),
        }
    }
}

// One lock for everything: no lock-order rules to get wrong.
static CORE: Mutex<Core> = Mutex::new(Core::new());

// --- seed.json shape (only the fields we read) ---
#[derive(Deserialize)]
struct SeedFile {
    levels: Vec<SeedLevel>,
}
#[derive(Deserialize)]
struct SeedLevel {
    index: u32,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    topic: Option<String>,
    #[serde(default)]
    items: Vec<SeedItem>,
}
#[derive(Deserialize)]
struct SeedItem {
    term: String,
    meaning: String,
    /// The item's source level; lessons re-chunk items, the progress key must not move.
    #[serde(default)]
    level: Option<u32>,
    #[serde(default)]
    pos_tag: Option<String>,
    #[serde(default)]
    all_forms: Option<String>,
    #[serde(default)]
    literal: Option<String>,
    #[serde(default)]
    audio: Vec<String>,
    #[serde(default)]
    typing_alternatives: Vec<String>,
    #[serde(default)]
    memrise_progress: Option<MemriseProgress>,
}
#[derive(Deserialize, Default)]
#[serde(default)]
struct MemriseProgress {
    interval: f64,
    next_date: Option<String>,
    correct: u32,
    attempts: u32,
    starred: bool,
    is_difficult: bool,
    ignored: bool,
}

// -------------------------------------------------------------- helpers

/// Case/space-insensitive, umlaut-folding normalization for answer checking.
/// Punctuation is ignored, so "über (etwas) reden?" == "ueber etwas reden".
fn normalize(s: &str) -> String {
    let folded = s
        .trim()
        .to_lowercase()
        .replace('ä', "ae")
        .replace('ö', "oe")
        .replace('ü', "ue")
        .replace('ß', "ss");
    let kept: String = folded
        .chars()
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect();
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Function words that make poor cloze targets or context anchors.
const STOP: &[&str] = &[
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
    "und", "oder", "aber", "ich", "du", "er", "sie", "es", "wir", "ihr", "zu", "in", "im", "an",
    "am", "auf", "mit", "von", "vom", "fuer", "fur", "ueber", "uber", "ist", "sind", "war",
    "waren", "nicht", "kein", "keine", "sich", "mir", "mich", "dir", "dich", "uns", "euch", "als",
    "wie", "wenn", "dass", "man", "sein", "haben", "hat", "habe", "werden", "wird", "wurde",
    "aus", "bei", "beim", "nach", "vor", "durch", "gegen", "ohne", "um", "bis", "seit", "waehrend",
    "wahrend", "auch", "noch", "nur", "schon", "sehr", "mehr", "kann", "koennen", "muss",
    "muessen", "wollen", "will", "soll", "sollen", "darf", "duerfen",
];

fn is_stop(t: &str) -> bool {
    STOP.contains(&t)
}

/// Content words of a term, normalized; used only to index contexts.
fn content_tokens(term: &str) -> Vec<String> {
    term.split_whitespace()
        .map(normalize)
        .filter(|t| t.chars().count() >= 3 && !is_stop(t))
        .collect()
}

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h = 0xcbf29ce484222325u64;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

fn build_accepted(term: &str, alts: &[String]) -> Vec<String> {
    let mut v = vec![normalize(term)];
    for a in alts {
        v.push(normalize(a));
    }
    v.sort();
    v.dedup();
    v
}

/// Arrange tiles: the term's words. A punctuation-only token ("-", "?") rides on the
/// previous word (or the next, when it comes first): it is no word to tap.
fn tiles_of(term: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut lead: Option<String> = None;
    for t in term.split_whitespace() {
        if !t.chars().any(char::is_alphanumeric) {
            match out.last_mut() {
                Some(last) => {
                    last.push(' ');
                    last.push_str(t);
                }
                None => lead = Some(lead.map_or_else(|| t.to_string(), |l| format!("{l} {t}"))),
            }
        } else {
            out.push(lead.take().map_or_else(|| t.to_string(), |l| format!("{l} {t}")));
        }
    }
    out.extend(lead); // a term without any word
    out
}

fn audio_basename(url: &str) -> String {
    url.rsplit('/').next().unwrap_or(url).to_string()
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12; // March = 0
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// "2021-10-24T03:29:42Z" -> unix seconds.
fn parse_iso(s: &str) -> Option<i64> {
    let n = |a: usize, z: usize| s.get(a..z)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, se) = (n(0, 4)?, n(5, 7)?, n(8, 10)?, n(11, 13)?, n(14, 16)?, n(17, 19)?);
    Some(days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se)
}

/// Memrise -> SM-2 initial state (spec §6.6).
fn review_from_progress(p: &MemriseProgress) -> Review {
    let interval = p.interval.max(0.0) as f32;
    let mut stage = match interval {
        i if i <= 0.0 => Stage::New,
        i if i < 4.0 => Stage::Mc,
        i if i < 10.0 => Stage::Reversed,
        i if i < 30.0 => Stage::Audio,
        i if i < 60.0 => Stage::Typing,
        _ => Stage::Known,
    };
    if p.ignored {
        stage = Stage::Known;
    }
    Review {
        stage,
        ease: (2.5f32 - if p.is_difficult { 0.3 } else { 0.0 }).max(1.3),
        interval_days: interval,
        reps: p.correct,
        lapses: p.attempts.saturating_sub(p.correct),
        due: p.next_date.as_deref().and_then(parse_iso).unwrap_or(0),
        starred: p.starred,
        touched: false,
        ..Review::new()
    }
}

/// For every short term, the real course phrases that contain its head word exactly once. Only
/// phrases of three or more words are used, so a blank is always inside a real sentence.
fn build_contexts(words: &[Word]) -> Vec<Vec<usize>> {
    let mut index: HashMap<String, Vec<usize>> = HashMap::new();
    for (j, w) in words.iter().enumerate() {
        if w.tokens < 3 {
            continue;
        }
        let mut seen = BTreeSet::new();
        for t in content_tokens(&w.term) {
            if seen.insert(t.clone()) {
                index.entry(t).or_default().push(j);
            }
        }
    }
    let mut out = vec![Vec::new(); words.len()];
    for (i, w) in words.iter().enumerate() {
        if w.tokens > 2 {
            continue; // long terms are their own context (arrange/typing)
        }
        let Some(head) = content_tokens(&w.term).last().cloned() else { continue };
        let Some(cands) = index.get(&head) else { continue };
        let mut ctx: Vec<usize> = cands
            .iter()
            .copied()
            .filter(|&j| {
                j != i && content_tokens(&words[j].term).iter().filter(|t| **t == head).count() == 1
            })
            .collect();
        ctx.sort_by(|&a, &b| words[a].key.cmp(&words[b].key));
        ctx.dedup();
        out[i] = ctx;
    }
    out
}

fn promote(s: Stage) -> Stage {
    match s {
        Stage::New | Stage::Mc => Stage::Reversed,
        Stage::Reversed => Stage::Audio,
        Stage::Audio => Stage::Typing,
        Stage::Typing | Stage::Known => Stage::Known,
    }
}

fn demote(s: Stage) -> Stage {
    match s {
        Stage::Known => Stage::Typing,
        Stage::Typing => Stage::Audio,
        Stage::Audio => Stage::Reversed,
        Stage::Reversed | Stage::Mc | Stage::New => Stage::Mc,
    }
}

/// SM-2 step plus the word-length pace rule (spec §6.2, §6.3).
fn schedule(r: &mut Review, correct: bool, now: i64, pace: &PaceSettings, len_factor: f32) {
    if correct {
        r.interval_days = match r.reps {
            0 => 1.0,
            1 => 6.0,
            _ => r.interval_days.max(1.0) * r.ease,
        };
        r.reps += 1;
        let w = pace.length_weight.clamp(0.0, 1.0);
        r.interval_days *= (1.0 - w) + w / len_factor;
        r.interval_days = (r.interval_days * pace.interval_scale).clamp(0.01, 365.0);
        r.stage = promote(r.stage);
    } else {
        r.reps = 0;
        r.lapses += 1;
        r.ease = (r.ease - 0.2).max(1.3);
        r.interval_days = 10.0 / 1440.0; // relearn in 10 minutes
        r.stage = demote(r.stage);
    }
    r.due = now + (r.interval_days * 86_400.0).round() as i64;
    r.touched = true;
}

fn streak(days: &BTreeSet<i64>, today: i64) -> u32 {
    let mut d = if days.contains(&today) { today } else { today - 1 };
    let mut n = 0;
    while days.contains(&d) {
        n += 1;
        d -= 1;
    }
    n
}

fn pick_kind(w: &Word, stage: Stage, can_choose: bool) -> QKind {
    let has_audio = w.audio_file.is_some();
    match stage {
        Stage::New | Stage::Mc if can_choose => QKind::Mc,
        Stage::Reversed if can_choose => QKind::Reversed,
        Stage::Audio if can_choose && has_audio => QKind::AudioMc,
        Stage::Audio if can_choose => QKind::Reversed,
        _ => {
            // Typing and Known (and choice stages with too few words): the hard recalls.
            // A sentence is always rebuilt word by word (owner, 2026-10-21).
            let roll = fastrand::u32(0..10);
            if w.tokens >= 3 {
                QKind::Arrange
            } else if has_audio && roll < 7 {
                QKind::Hearing
            } else {
                QKind::Typing
            }
        }
    }
}

// ---- options that are not too similar (spec 2026-10-21-fast-review §1); inputs are normalized

/// 1 = identical, 0 = nothing in common: 1 - Levenshtein distance / the longer length.
fn spelling_sim(a: &str, b: &str) -> f32 {
    let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
    let longest = a.len().max(b.len());
    if longest == 0 {
        return 1.0;
    }
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    for (i, ca) in a.iter().enumerate() {
        let mut cur = vec![i + 1];
        for (j, cb) in b.iter().enumerate() {
            cur.push((prev[j] + (ca != cb) as usize).min(prev[j + 1] + 1).min(cur[j] + 1));
        }
        prev = cur;
    }
    1.0 - prev[b.len()] as f32 / longest as f32
}

/// Share of words the two phrases have in common (Jaccard).
fn word_overlap(a: &str, b: &str) -> f32 {
    let (x, y): (BTreeSet<&str>, BTreeSet<&str>) = (a.split_whitespace().collect(), b.split_whitespace().collect());
    let union = x.union(&y).count();
    if union == 0 { 0.0 } else { x.intersection(&y).count() as f32 / union as f32 }
}

/// The longest run of identical words, in order, that both phrases contain.
fn common_run(a: &str, b: &str) -> usize {
    let (x, y): (Vec<&str>, Vec<&str>) = (a.split_whitespace().collect(), b.split_whitespace().collect());
    let (mut best, mut prev) = (0, vec![0usize; y.len() + 1]);
    for xi in &x {
        let mut cur = vec![0usize; y.len() + 1];
        for (j, yj) in y.iter().enumerate() {
            if xi == yj {
                cur[j + 1] = prev[j] + 1;
                best = best.max(cur[j + 1]);
            }
        }
        prev = cur;
    }
    best
}

/// How alike two phrases look, 0..1: the larger of word overlap and spelling similarity.
fn alike(a: &str, b: &str) -> f32 {
    word_overlap(a, b).max(spelling_sim(a, b))
}

/// Owner rule: two options are too alike to be offered together when they share >= 40 % of their
/// words, or 3 words in a row, or are >= 70 % the same spelling.
fn too_similar(a: &str, b: &str) -> bool {
    word_overlap(a, b) >= 0.4 || common_run(a, b) >= 3 || spelling_sim(a, b) >= 0.7
}

/// The three wrong options come from the card's own lesson first, then the whole course. None may be
/// `too_similar` to the right answer or to each other; only if too few clean ones exist is the rule
/// dropped, so a card always has four options. `hardness` 0..1 (fast review) walks from the least
/// alike candidates to the most alike ones; None = random.
fn choices_with(c: &Core, i: usize, kind: QKind, hardness: Option<f32>) -> Vec<String> {
    let show = |w: &Word| if kind == QKind::Reversed { w.meaning.clone() } else { w.term.clone() };
    let correct = show(&c.words[i]);
    let right = normalize(&correct);
    let lesson: Vec<usize> = c
        .levels
        .iter()
        .find(|l| l.index == c.words[i].level)
        .map(|l| l.members.clone())
        .unwrap_or_default();
    let course: Vec<usize> = (0..c.words.len()).collect();
    let mut out: Vec<(String, String)> = Vec::new(); // (normalized, shown)
    for (pool, strict) in [(lesson, true), (course.clone(), true), (course, false)] {
        if out.len() == 3 {
            break;
        }
        let mut cands: Vec<(String, String)> = Vec::new();
        for j in pool {
            if j == i {
                continue;
            }
            let shown = show(&c.words[j]);
            let n = normalize(&shown);
            if n == right || out.iter().chain(&cands).any(|(m, _)| *m == n) || (strict && too_similar(&right, &n)) {
                continue;
            }
            cands.push((n, shown));
        }
        fastrand::shuffle(&mut cands);
        if let Some(h) = hardness {
            cands.sort_by(|a, b| alike(&right, &a.0).total_cmp(&alike(&right, &b.0))); // least alike first
            let target = h.clamp(0.0, 1.0) * cands.len().saturating_sub(1) as f32;
            let mut keyed: Vec<(f32, (String, String))> = cands
                .into_iter()
                .enumerate()
                .map(|(rank, cand)| ((rank as f32 - target).abs() + fastrand::f32() * 1.5, cand))
                .collect();
            keyed.sort_by(|a, b| a.0.total_cmp(&b.0));
            cands = keyed.into_iter().map(|(_, cand)| cand).collect();
        }
        for (n, shown) in cands {
            if out.len() == 3 {
                break;
            }
            if !strict || out.iter().all(|(m, _)| !too_similar(m, &n)) {
                out.push((n, shown));
            }
        }
    }
    let mut options: Vec<String> = out.into_iter().map(|(_, shown)| shown).collect();
    options.push(correct);
    fastrand::shuffle(&mut options);
    options
}

#[cfg(test)]
fn choices_for(c: &Core, i: usize, kind: QKind) -> Vec<String> {
    choices_with(c, i, kind, None)
}

fn make_q(c: &Core, i: usize, kind: QKind) -> Q {
    make_q_h(c, i, kind, None)
}

fn make_q_h(c: &Core, i: usize, kind: QKind, hardness: Option<f32>) -> Q {
    let choices = match kind {
        QKind::Mc | QKind::Reversed | QKind::AudioMc => choices_with(c, i, kind, hardness),
        _ => Vec::new(),
    };
    let (tiles, order) = if kind == QKind::Arrange {
        arrange_parts(&c.words[i].term)
    } else {
        (Vec::new(), Vec::new())
    };
    Q { word: i, kind, choices, tiles, order, revealed: 0, hinted: false, retry: false, time_ms: 0,
        task_pos: 0, task_total: 0, srs: false, prompt_override: None, answer_override: None }
}

/// Shuffled tiles for a sentence and the index of each tile in the correct order. Never returns the
/// already-correct order when there is more than one tile.
fn arrange_parts(term: &str) -> (Vec<String>, Vec<usize>) {
    let original = tiles_of(term);
    let mut perm: Vec<usize> = (0..original.len()).collect();
    let mut tiles = original.clone();
    for _ in 0..5 {
        fastrand::shuffle(&mut perm);
        tiles = perm.iter().map(|&k| original[k].clone()).collect();
        if tiles != original {
            break;
        }
    }
    let mut order = vec![0; perm.len()];
    for (pos, &k) in perm.iter().enumerate() {
        order[k] = pos;
    }
    (tiles, order)
}

/// The cards for one word: new words get a presentation card, then multiple choice.
fn plan(c: &Core, i: usize) -> Vec<Q> {
    let r = &c.reviews[i];
    let can_choose = c.words.len() >= 4;
    if r.stage == Stage::New {
        let mc = if can_choose { QKind::Mc } else { QKind::Typing };
        vec![make_q(c, i, QKind::Presentation), make_q(c, i, mc)]
    } else {
        vec![make_q(c, i, pick_kind(&c.words[i], r.stage, can_choose))]
    }
}

/// A word's context phrases in a deterministic Fisher-Yates order, plus a fingerprint of the order.
/// The cursor stored in the review is the only saved state, so the bag survives app restarts.
fn context_deck(c: &Core, i: usize) -> (Vec<usize>, u32) {
    let mut deck = c.contexts.get(i).cloned().unwrap_or_default();
    if deck.is_empty() {
        return (deck, 0);
    }
    let mut rng = fastrand::Rng::with_seed(fnv1a(c.words[i].key.as_bytes()));
    rng.shuffle(&mut deck);
    let keys: String = deck.iter().map(|&j| c.words[j].key.as_str()).collect::<Vec<_>>().join("|");
    (deck, fnv1a(keys.as_bytes()) as u32)
}

/// A real sentence containing the word; pick its English meaning. Falls back to the word's own
/// term when the course has no sentence for it.
/// Order a real course sentence that contains the target word. Falls back to the word's own
/// term when the course has no sentence for it.
fn context_arrange_q(c: &Core, target: usize, context: usize) -> Q {
    let cw = &c.words[context];
    let (tiles, order) = arrange_parts(&cw.term);
    let mut q = make_q(c, target, QKind::Arrange);
    q.tiles = tiles;
    q.order = order;
    q.prompt_override = Some(cw.meaning.clone());
    q.answer_override = Some(cw.term.clone());
    q
}

/// The fixed Learn new set: 1 typing, 1 picking, 1 sentence making. The sentence is a real
/// course sentence containing the word and is drawn from the persistent shuffle bag.
fn learn_group(c: &mut Core, i: usize) -> Vec<Q> {
    let (deck, fingerprint) = context_deck(c, i);
    let stored = c.reviews[i].context_deck;
    let mut cursor = c.reviews[i].context_cursor as usize;
    if stored != fingerprint || cursor >= deck.len() {
        cursor = 0;
    }
    let take = 1.min(deck.len().saturating_sub(cursor));
    let drawn: Vec<usize> = deck[cursor..cursor + take].to_vec();
    let next_cursor = if cursor + take >= deck.len() { 0 } else { (cursor + take) as u32 };

    let tasks = vec![
        make_q(c, i, QKind::Typing), // type the German word
        make_q(c, i, QKind::Mc),     // pick the German word from the English meaning
        match drawn.first() {
            Some(&j) => context_arrange_q(c, i, j), // make the sentence that contains the word
            None => make_q(c, i, QKind::Arrange),   // no course sentence: order the word itself
        },
    ];

    let total = tasks.len() as u32;
    let mut intro = make_q(c, i, QKind::Presentation);
    intro.task_total = total;
    let mut qs = vec![intro];
    for (k, mut q) in tasks.into_iter().enumerate() {
        q.task_pos = k as u32 + 1;
        q.task_total = total;
        q.srs = q.task_pos == total;
        qs.push(q);
    }
    c.reviews[i].context_cursor = next_cursor;
    c.reviews[i].context_deck = fingerprint;
    qs
}

/// Typing is boring (owner, 2026-10-21): at most 10 % of a session's tasks are typed
/// (Typing + Hearing); every other such word gets a choice card instead. The unused allowance
/// carries into the next session, so short sessions still average 10 %. Returns the new credit.
fn limit_typing(c: &Core, qs: &mut [Q]) -> u32 {
    let tasks = qs.iter().filter(|q| q.kind != QKind::Presentation).count() as u32;
    let budget = tasks + c.typing_credit; // tenths of a typed card: every task adds 0.1
    let allowed = (budget / 10) as usize;
    let can_choose = c.words.len() >= 4; // a tiny seed cannot offer four options
    let mut kept = 0u32;
    for q in qs.iter_mut().filter(|q| matches!(q.kind, QKind::Typing | QKind::Hearing)) {
        if kept < allowed as u32 || !can_choose {
            kept += 1;
            continue;
        }
        let mut kinds = vec![QKind::Mc, QKind::Reversed];
        if c.words[q.word].audio_file.is_some() {
            kinds.push(QKind::AudioMc);
        }
        *q = make_q(c, q.word, kinds[fastrand::usize(0..kinds.len())]);
    }
    budget.saturating_sub(10 * kept).min(20)
}

fn scored(q: &Q) -> bool {
    q.kind != QKind::Presentation && !q.retry
}


// ------------------------------------------------------------ the API

#[uniffi::export]
pub fn load_seed(json: String) -> Result<u32, CoreError> {
    let parsed: SeedFile =
        serde_json::from_str(&json).map_err(|e| CoreError::Parse(e.to_string()))?;
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    c.words.clear();
    c.levels.clear();
    c.initial.clear();
    c.days.clear();
    c.orphans.clear();
    c.contexts.clear();
    c.typing_credit = 0;
    c.correct_total = 0;
    c.session = None;
    for level in parsed.levels {
        let title = level
            .title
            .unwrap_or_else(|| format!("Level {}", level.index));
        let mut members = Vec::new();
        for item in level.items {
            let accepted = build_accepted(&item.term, &item.typing_alternatives);
            members.push(c.words.len());
            c.initial.push(match &item.memrise_progress {
                Some(p) => review_from_progress(p),
                None => Review::new(),
            });
            c.words.push(Word {
                key: format!("{}:{}", item.level.unwrap_or(level.index), item.term),
                len: item.term.chars().count(),
                tokens: tiles_of(&item.term).len(),
                term: item.term,
                meaning: item.meaning,
                pos: item.pos_tag,
                forms: item.all_forms,
                literal: item.literal,
                audio_file: item.audio.first().map(|u| audio_basename(u)),
                level: level.index,
                accepted,
            });
        }
        c.levels.push(LevelMeta { index: level.index, topic: level.topic.unwrap_or_else(|| title.clone()), title, members });
    }
    let n = c.words.len();
    c.mean_len = if n == 0 {
        1.0
    } else {
        (c.words.iter().map(|w| w.len).sum::<usize>() as f32 / n as f32).max(1.0)
    };
    c.reviews = c.initial.clone();
    c.contexts = build_contexts(&c.words);
    Ok(n as u32)
}

#[uniffi::export]
pub fn levels() -> Vec<LevelInfo> {
    let c = CORE.lock().unwrap();
    c.levels
        .iter()
        .map(|l| {
            let count_in = |f: fn(Stage) -> bool| {
                l.members.iter().filter(|&&i| f(c.reviews[i].stage)).count() as u32
            };
            LevelInfo {
                index: l.index,
                title: l.title.clone(),
                topic: l.topic.clone(),
                count: l.members.len() as u32,
                known: count_in(|s| s == Stage::Known),
                learning: count_in(|s| s != Stage::Known && s != Stage::New),
            }
        })
        .collect()
}

#[uniffi::export]
pub fn level_words(level: u32) -> Vec<WordRow> {
    let c = CORE.lock().unwrap();
    let Some(l) = c.levels.iter().find(|l| l.index == level) else {
        return Vec::new();
    };
    l.members
        .iter()
        .map(|&i| {
            let (w, r) = (&c.words[i], &c.reviews[i]);
            WordRow {
                index: i as u32,
                term: w.term.clone(),
                meaning: w.meaning.clone(),
                audio_file: w.audio_file.clone(),
                stage: r.stage,
                reps: r.reps,
                due: r.due,
                starred: r.starred,
            }
        })
        .collect()
}

#[uniffi::export]
pub fn word_count() -> u32 {
    CORE.lock().unwrap().words.len() as u32
}

#[uniffi::export]
pub fn get_card(index: u32) -> Option<WordCard> {
    CORE.lock().unwrap().words.get(index as usize).map(|w| WordCard {
        term: w.term.clone(),
        meaning: w.meaning.clone(),
        pos: w.pos.clone(),
        audio_file: w.audio_file.clone(),
        level: w.level,
    })
}

/// Build a session of up to `size` words: due cards first, then new words
/// (capped by the pace settings), then not-yet-due practice so it is never empty.
/// `level` None studies the whole course; Some(index) just that chapter.
/// `now` is unix seconds shifted to local time (it also decides the streak day).
#[uniffi::export]
pub fn start_session(level: Option<u32>, size: u32, now: i64) -> Result<u32, CoreError> {
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    if c.words.is_empty() {
        return Err(CoreError::NoWords);
    }
    let candidates: Vec<usize> = match level {
        Some(ix) => c
            .levels
            .iter()
            .find(|l| l.index == ix)
            .map(|l| l.members.clone())
            .unwrap_or_default(),
        None => (0..c.words.len()).collect(),
    };
    if candidates.is_empty() {
        return Err(CoreError::NoWords);
    }
    let size = (size as usize).clamp(1, candidates.len());
    let (mut due, mut fresh, mut rest) = (Vec::new(), Vec::new(), Vec::new());
    for &i in &candidates {
        let r = &c.reviews[i];
        if r.stage == Stage::New {
            fresh.push(i);
        } else if r.due <= now {
            due.push(i);
        } else {
            rest.push(i);
        }
    }
    fastrand::shuffle(&mut due);
    fastrand::shuffle(&mut rest);
    fresh.truncate(c.pace.new_per_session as usize);
    let mut picked: Vec<usize> = due.into_iter().chain(fresh).chain(rest).take(size).collect();
    if picked.is_empty() {
        // new_per_session = 0 and nothing else to study in this chapter
        picked = candidates;
        fastrand::shuffle(&mut picked);
        picked.truncate(size);
    }
    let mut groups: Vec<Vec<Q>> = picked.iter().map(|&i| plan(c, i)).collect();
    fastrand::shuffle(&mut groups); // keeps each presentation next to its first card
    let mut questions: Vec<Q> = groups.into_iter().flatten().collect();
    speed_round(c, &mut questions);
    c.typing_credit = limit_typing(c, &mut questions);
    c.now = now;
    c.session = Some(Session { questions, pos: 0, correct: 0, learn_faults: BTreeSet::new() });
    Ok(picked.len() as u32)
}

/// "Learn new" (owner, 2026-10-22): random words from the chapter that the owner has not studied
/// in this app yet. Each shows its presentation card (term, translation, audio) with an
/// "already known" option, then asks the English prompt with four German choices.
/// Returns the number of words.
#[uniffi::export]
pub fn start_learn_new(level: Option<u32>, size: u32, now: i64) -> Result<u32, CoreError> {
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    if c.words.len() < 4 {
        return Err(CoreError::NoWords); // four options need four words
    }
    let candidates: Vec<usize> = match level {
        Some(ix) => c
            .levels
            .iter()
            .find(|l| l.index == ix)
            .map(|l| l.members.clone())
            .unwrap_or_default(),
        None => (0..c.words.len()).collect(),
    };
    let mut fresh: Vec<usize> = candidates.into_iter().filter(|&i| !c.reviews[i].touched).collect();
    if fresh.is_empty() {
        return Err(CoreError::NoWords); // every word here has been studied already
    }
    fastrand::shuffle(&mut fresh);
    fresh.truncate(size.max(1) as usize);
    let mut groups: Vec<Vec<Q>> = Vec::new();
    for &i in &fresh {
        groups.push(learn_group(c, i));
    }
    fastrand::shuffle(&mut groups); // keeps each presentation next to its task set
    let questions: Vec<Q> = groups.into_iter().flatten().collect();
    c.now = now;
    c.session = Some(Session { questions, pos: 0, correct: 0, learn_faults: BTreeSet::new() });
    Ok(fresh.len() as u32)
}

/// A timed choice card for `word`; t = 0 is the easy end (long clock, unlike wrong options), t = 1 the
/// hard end (short clock, wrong options that look alike). Clock = (8 s + 1 s per word) x (1 - 0.55 t).
fn timed_card(c: &Core, word: usize, t: f32) -> Q {
    let mut q = make_q_h(c, word, QKind::Mc, Some(t));
    q.time_ms = ((8.0 + c.words[word].tokens as f32) * (1.0 - 0.55 * t) * 1000.0).round() as u32;
    q
}

/// Speed round (owner, 2026-10-21): the last cards of a normal session become a short timed round,
/// like a fast review. Only cards about words already met are used (a new word's intro and first card
/// stay together and untimed), at most 4 and at most half of them; no phrase twice. The round is
/// moved to the end of the session and goes easy to hard.
const SPEED_ROUND: usize = 4;

fn speed_round(c: &Core, qs: &mut Vec<Q>) {
    if c.words.len() < 4 {
        return; // four options need four words
    }
    let met: Vec<usize> = qs
        .iter()
        .enumerate()
        .filter(|(_, q)| q.kind != QKind::Presentation && c.reviews[q.word].stage != Stage::New)
        .map(|(k, _)| k)
        .collect();
    let want = (met.len() / 2).min(SPEED_ROUND);
    let mut seen: HashSet<String> = HashSet::new();
    let mut take: Vec<usize> = Vec::new(); // positions, highest first, so removing keeps the rest valid
    for &k in met.iter().rev() {
        if take.len() == want {
            break;
        }
        if seen.insert(normalize(&c.words[qs[k].word].term)) {
            take.push(k);
        }
    }
    let mut words: Vec<usize> = take.iter().map(|&k| qs[k].word).collect();
    for &k in &take {
        qs.remove(k);
    }
    words.sort_by_key(|&w| c.words[w].tokens);
    let n = words.len();
    for (j, &w) in words.iter().enumerate() {
        qs.push(timed_card(c, w, if n > 1 { j as f32 / (n - 1) as f32 } else { 0.0 }));
    }
}

/// Fast review (spec 2026-10-21-fast-review): timed choice cards over words already met, due ones
/// first, never the same phrase twice, ordered easy to hard (fewest words first). Time per card =
/// (8 s + 1 s per word) x (1 - 0.55 t), t = 0..1 across the session; the wrong options get more
/// alike as t grows. Returns the number of cards.
#[uniffi::export]
pub fn start_fast_session(level: Option<u32>, size: u32, now: i64) -> Result<u32, CoreError> {
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    if c.words.len() < 4 {
        return Err(CoreError::NoWords); // four options need four words
    }
    let candidates: Vec<usize> = match level {
        Some(ix) => c.levels.iter().find(|l| l.index == ix).map(|l| l.members.clone()).unwrap_or_default(),
        None => (0..c.words.len()).collect(),
    };
    // a review, not a first meeting: words already met only
    let mut eligible: Vec<usize> = candidates.iter().copied().filter(|&i| c.reviews[i].stage != Stage::New).collect();
    fastrand::shuffle(&mut eligible);
    // Whole-course review (level None): words practised in this app come first (owner, 2026-10-21),
    // the older imported Memrise words only fill up. Inside each group, due words come first.
    let home = level.is_none();
    eligible.sort_by_key(|&i| (home && !c.reviews[i].touched, c.reviews[i].due > now));
    let mut seen: HashSet<String> = HashSet::new();
    let mut picked: Vec<usize> = Vec::new();
    for i in eligible {
        if picked.len() == size.max(1) as usize {
            break;
        }
        if seen.insert(normalize(&c.words[i].term)) {
            picked.push(i);
        }
    }
    if picked.is_empty() {
        return Err(CoreError::NoWords);
    }
    fastrand::shuffle(&mut picked);
    picked.sort_by_key(|&i| c.words[i].tokens); // stable: equal lengths stay in random order
    let n = picked.len();
    let questions: Vec<Q> = picked
        .iter()
        .enumerate()
        .map(|(k, &i)| {
            timed_card(c, i, if n > 1 { k as f32 / (n - 1) as f32 } else { 0.0 })
        })
        .collect();
    c.now = now;
    c.session = Some(Session { questions, pos: 0, correct: 0, learn_faults: BTreeSet::new() });
    Ok(n as u32)
}

#[uniffi::export]
pub fn current_question() -> Option<Question> {
    let guard = CORE.lock().unwrap();
    let c = &*guard;
    let s = c.session.as_ref()?;
    let q = s.questions.get(s.pos)?;
    let (w, r) = (&c.words[q.word], &c.reviews[q.word]);
    let (prompt, audio) = if let Some(p) = &q.prompt_override {
        (p.clone(), None) // context cloze: the sentence with the blank
    } else {
        match q.kind {
            QKind::Presentation | QKind::Reversed | QKind::MeaningTyping => (w.term.clone(), w.audio_file.clone()),
            QKind::Mc | QKind::Typing | QKind::Arrange => (w.meaning.clone(), None),
            QKind::AudioMc | QKind::Hearing => (String::new(), w.audio_file.clone()),
            QKind::Cloze | QKind::ClozeMeaning | QKind::ClozeTyping | QKind::YesNo | QKind::SentenceChoice => (String::new(), None),
        }
    };
    let presentation = q.kind == QKind::Presentation;
    let detail = [w.pos.clone(), w.forms.clone()]
        .into_iter()
        .flatten()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" · ");
    Some(Question {
        position: (s.pos + 1) as u32,
        total: s.questions.len() as u32,
        kind: q.kind,
        word: q.word as u32,
        prompt,
        prompt_audio: audio,
        choices: q.choices.clone(),
        tiles: q.tiles.clone(),
        meaning: presentation.then(|| w.meaning.clone()),
        detail: (presentation && !detail.is_empty()).then_some(detail),
        note: if presentation { w.literal.clone().filter(|s| !s.is_empty()) } else { None },
        stage: r.stage,
        reps: r.reps,
        starred: r.starred,
        retry: q.retry,
        time_limit_ms: q.time_ms,
        task_position: q.task_pos,
        task_total: q.task_total,
        answer: q.answer_override.clone(),
    })
}

#[uniffi::export]
pub fn submit_answer(user_input: String) -> AnswerFeedback {
    let mut guard = CORE.lock().unwrap();
    let Core { words, reviews, pace, days, mean_len, now, session, correct_total, .. } = &mut *guard;
    let s = match session.as_mut() {
        Some(s) if s.pos < s.questions.len() => s,
        _ => {
            return AnswerFeedback {
                correct: false,
                expected: String::new(),
                finished: true,
                scored: false,
                stage: Stage::New,
                next_due: 0,
                hinted: false,
            }
        }
    };
    let q = &s.questions[s.pos];
    let (word, kind, timed) = (q.word, q.kind, q.time_ms > 0);
    let learn = q.task_total > 0 && q.kind != QKind::Presentation;
    let (srs, task_pos, task_total) = (q.srs, q.task_pos, q.task_total);
    let answer_override = q.answer_override.clone();
    let hinted = q.hinted;
    let is_scored = scored(q);
    let (w, r) = (&words[word], &mut reviews[word]);
    let typed = normalize(&user_input);
    let (correct, expected) = match kind {
        QKind::Presentation => (true, w.meaning.clone()),
        QKind::Mc | QKind::AudioMc => (typed == normalize(&w.term), w.term.clone()),
        QKind::Reversed | QKind::MeaningTyping => (typed == normalize(&w.meaning), w.meaning.clone()),
        QKind::Typing | QKind::Hearing => (w.accepted.contains(&typed), w.term.clone()),
        QKind::Arrange => {
            if let Some(a) = &answer_override {
                (typed == normalize(a), a.clone())
            } else {
                (w.accepted.contains(&typed), w.term.clone())
            }
        }
        QKind::Cloze | QKind::ClozeTyping | QKind::YesNo | QKind::SentenceChoice => {
            let a = answer_override.clone().unwrap_or_default();
            (typed == normalize(&a), a)
        }
        QKind::ClozeMeaning => (typed == normalize(&w.meaning), w.meaning.clone()),
    };
    let correct = correct && !hinted;
    let mut next_due = 0;
    if is_scored {
        let len_factor = (w.len as f32 / *mean_len).clamp(0.6, 1.6);
        if learn {
            // A Learn new word is scheduled once, after its last task, and only if no task was missed.
            if !correct {
                s.learn_faults.insert(word);
            }
            if srs {
                let all_ok = correct && !s.learn_faults.contains(&word);
                schedule(r, all_ok, *now, pace, len_factor);
                next_due = r.due;
            }
        } else {
            schedule(r, correct, *now, pace, len_factor);
            next_due = r.due;
        }
        days.insert(now.div_euclid(86_400));
        if correct {
            s.correct += 1;
            *correct_total += 1;
        }
    }
    let stage = r.stage;
    s.pos += 1;
    // A scored miss comes back once, ~3 cards later, as a fresh unscored card (spec §3), except in a
    // timed fast review, which has no second tries. make_q needs the whole Core, so the retry is
    // built after the field borrows above end.
    if is_scored && !correct && !timed && !learn {
        let mut session = guard.session.take().expect("session checked above");
        let mut retry = make_q(&guard, word, kind);
        retry.retry = true;
        retry.task_pos = task_pos; // the retry belongs to the task it repeats on the egg indicator
        retry.task_total = task_total;
        retry.srs = false;
        // ~3 cards later, but never inside the timed speed round at the end of the session
        let first_timed = session.questions.iter().position(|q| q.time_ms > 0).unwrap_or(session.questions.len());
        let at = (session.pos + 3).min(first_timed).max(session.pos);
        session.questions.insert(at, retry);
        guard.session = Some(session);
    }
    let finished = guard.session.as_ref().map_or(true, |s| s.pos >= s.questions.len());
    AnswerFeedback { correct, expected, finished, scored: is_scored, stage, next_due, hinted }
}

/// Which words of an answer are wrong, for highlighting. Index-aligned to `split_whitespace()` of
/// each string: `typed[i]` = the i-th typed word is wrong (not in the longest common run of words),
/// `expected[i]` = the i-th correct word was missed or mistyped. Compared like the answer checker:
/// case, umlaut spelling and punctuation are ignored, and a lone "?" or "-" is never a mistake.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WordMarks {
    pub typed: Vec<bool>,
    pub expected: Vec<bool>,
}

#[uniffi::export]
pub fn mark_mistakes(expected: String, typed: String) -> WordMarks {
    let norm = |s: &str| s.split_whitespace().map(normalize).collect::<Vec<_>>();
    let (e, t) = (norm(&expected), norm(&typed));
    let same = |i: usize, j: usize| !e[i].is_empty() && e[i] == t[j];
    let (n, m) = (e.len(), t.len());
    let mut lcs = vec![vec![0usize; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            lcs[i][j] = if same(i, j) { lcs[i + 1][j + 1] + 1 } else { lcs[i + 1][j].max(lcs[i][j + 1]) };
        }
    }
    let (mut wrong_e, mut wrong_t) = (vec![true; n], vec![true; m]);
    let (mut i, mut j) = (0, 0);
    while i < n && j < m {
        if same(i, j) {
            (wrong_e[i], wrong_t[j]) = (false, false);
            i += 1;
            j += 1;
        } else if lcs[i + 1][j] >= lcs[i][j + 1] {
            i += 1;
        } else {
            j += 1;
        }
    }
    for (flags, words) in [(&mut wrong_e, &e), (&mut wrong_t, &t)] {
        for (flag, w) in flags.iter_mut().zip(words) {
            *flag &= !w.is_empty(); // punctuation-only
        }
    }
    WordMarks { typed: wrong_t, expected: wrong_e }
}

/// Magic wand (spec §5): the first k words of the sentence as tile indices. k grows by one per
/// tap and never drops below the correct prefix already placed (compared by text, so either
/// copy of a repeated word counts). Using it makes the card a miss. Empty if not an Arrange card.
#[uniffi::export]
pub fn wand_hint(placed: Vec<u32>) -> Vec<u32> {
    let mut guard = CORE.lock().unwrap();
    let Some(q) = guard.session.as_mut().and_then(|s| s.questions.get_mut(s.pos)) else {
        return Vec::new();
    };
    if q.kind != QKind::Arrange {
        return Vec::new();
    }
    let text = |k: usize| q.tiles.get(k).map(|t| normalize(t));
    let prefix = placed
        .iter()
        .zip(&q.order)
        .take_while(|(&p, &o)| text(p as usize).is_some() && text(p as usize) == text(o))
        .count();
    q.revealed = (q.revealed.max(prefix) + 1).min(q.order.len());
    q.hinted = true;
    q.order[..q.revealed].iter().map(|&k| k as u32).collect()
}

#[uniffi::export]
pub fn session_progress() -> Option<SessionProgress> {
    let guard = CORE.lock().unwrap();
    let s = guard.session.as_ref()?;
    Some(SessionProgress {
        total: s.questions.len() as u32,
        answered: s.pos as u32,
        correct: s.correct,
        scored: s.questions.iter().filter(|q| scored(q)).count() as u32,
        finished: s.pos >= s.questions.len(),
    })
}

/// "Already learnt": the word becomes Known and its remaining cards in this
/// session are dropped. The caller then asks for current_question() again.
#[uniffi::export]
pub fn mark_known(word: u32) {
    let mut guard = CORE.lock().unwrap();
    let Core { reviews, now, session, .. } = &mut *guard;
    let i = word as usize;
    let Some(r) = reviews.get_mut(i) else { return };
    r.stage = Stage::Known;
    r.interval_days = r.interval_days.max(60.0);
    r.due = *now + (r.interval_days * 86_400.0).round() as i64;
    r.touched = true;
    if let Some(s) = session.as_mut() {
        let pos = s.pos;
        let mut idx = 0;
        s.questions.retain(|q| {
            let keep = idx < pos || q.word != i;
            idx += 1;
            keep
        });
    }
}

#[uniffi::export]
pub fn set_starred(word: u32, starred: bool) {
    let mut c = CORE.lock().unwrap();
    if let Some(r) = c.reviews.get_mut(word as usize) {
        r.starred = starred;
        if starred {
            r.ease = (r.ease - 0.15).max(1.3); // "hard": come back sooner
        }
        r.touched = true;
    }
}

/// "Easy": a nudge to the ease factor so the next intervals grow faster.
#[uniffi::export]
pub fn rate_easy(word: u32) {
    let mut c = CORE.lock().unwrap();
    if let Some(r) = c.reviews.get_mut(word as usize) {
        r.ease = (r.ease + 0.15).min(3.0);
        r.touched = true;
    }
}

#[uniffi::export]
pub fn stats(now: i64) -> Stats {
    let c = CORE.lock().unwrap();
    let count = |f: &dyn Fn(&Review) -> bool| c.reviews.iter().filter(|r| f(r)).count() as u32;
    Stats {
        total_words: c.words.len() as u32,
        known: count(&|r| r.stage == Stage::Known),
        learning: count(&|r| r.stage != Stage::Known && r.stage != Stage::New),
        new_words: count(&|r| r.stage == Stage::New),
        due_today: count(&|r| r.stage != Stage::New && r.due <= now),
        streak_days: streak(&c.days, now.div_euclid(86_400)),
        // the imported reps are the Memrise correct counts (see the import mapping)
        correct_total: c.initial.iter().map(|r| r.reps as u64).sum::<u64>() + c.correct_total,
    }
}

/// When to remind the owner (spec §4). All times are core clock (epoch + local tz offset).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ReviewAlarm {
    /// Alarm time; -1 = never (fewer than `min_words` words have ever been studied).
    pub at: i64,
    /// Words due by `at`.
    pub count: u32,
}

const NOTIFY_GAP: i64 = 4 * 3600; // min time between two reminders (the Memrise backlog is overdue on day one)
const QUIET_FROM: i64 = 22 * 3600; // no reminders 22:00 - 08:00
const QUIET_TO: i64 = 8 * 3600;

/// Moves a time out of the quiet hours to 08:00 (the core clock is local, so time of day = t mod 86400).
fn out_of_quiet(t: i64) -> i64 {
    let tod = t.rem_euclid(86_400);
    if tod >= QUIET_FROM {
        t - tod + 86_400 + QUIET_TO
    } else if tod < QUIET_TO {
        t - tod + QUIET_TO
    } else {
        t
    }
}

/// The time the `min_words`-th studied word is due, not before `now`, nor within 4 h of
/// `last_notified`, nor in quiet hours. "Due" is the Home tile's definition (`stats().due_today`).
#[uniffi::export]
pub fn next_review_alarm(min_words: u32, now: i64, last_notified: i64) -> ReviewAlarm {
    let c = CORE.lock().unwrap();
    let mut dues: Vec<i64> = c.reviews.iter().filter(|r| r.stage != Stage::New).map(|r| r.due).collect();
    let need = min_words.max(1) as usize;
    if dues.len() < need {
        return ReviewAlarm { at: -1, count: 0 };
    }
    dues.sort_unstable();
    let at = out_of_quiet(dues[need - 1].max(now).max(last_notified.saturating_add(NOTIFY_GAP)));
    ReviewAlarm { at, count: dues.iter().filter(|&&d| d <= at).count() as u32 }
}

#[uniffi::export]
pub fn get_pace() -> PaceSettings {
    CORE.lock().unwrap().pace.clone()
}

#[uniffi::export]
pub fn set_pace(p: PaceSettings) {
    CORE.lock().unwrap().pace = p;
}

// --- persistence: the UI saves export_state() to a file and feeds it back
// through import_state() after load_seed(). Only words the user touched are saved.
// ponytail: one JSON file rewritten per answer (a few KB); move to SQLite if it grows.

#[derive(Serialize, Deserialize)]
struct SavedState {
    version: u32,
    pace: Option<PaceSettings>,
    days: Vec<i64>,
    reviews: HashMap<String, Review>,
    #[serde(default)]
    correct_total: u64,
}

#[uniffi::export]
pub fn export_state() -> String {
    let c = CORE.lock().unwrap();
    let mut reviews: HashMap<String, Review> = c
        .words
        .iter()
        .zip(&c.reviews)
        .filter(|(_, r)| r.touched)
        .map(|(w, r)| (w.key.clone(), r.clone()))
        .collect();
    reviews.extend(c.orphans.clone());
    let state = SavedState {
        version: 1,
        pace: Some(c.pace.clone()),
        days: c.days.iter().copied().collect(),
        reviews,
        correct_total: c.correct_total,
    };
    serde_json::to_string(&state).unwrap_or_default()
}

/// Returns how many words' saved state was applied.
#[uniffi::export]
pub fn import_state(json: String) -> Result<u32, CoreError> {
    let saved: SavedState =
        serde_json::from_str(&json).map_err(|e| CoreError::State(e.to_string()))?;
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    let index: HashMap<&str, usize> =
        c.words.iter().enumerate().map(|(i, w)| (w.key.as_str(), i)).collect();
    let mut applied = 0;
    for (key, mut r) in saved.reviews {
        if let Some(&i) = index.get(key.as_str()) {
            r.touched = true;
            c.reviews[i] = r;
            applied += 1;
        } else {
            c.orphans.insert(key, r);
        }
    }
    c.days.extend(saved.days);
    c.correct_total = saved.correct_total;
    if let Some(p) = saved.pace {
        c.pace = p;
    }
    Ok(applied)
}

/// Forget all study history; the imported Memrise progress is restored.
#[uniffi::export]
pub fn reset_progress() {
    let mut guard = CORE.lock().unwrap();
    let c = &mut *guard;
    c.reviews = c.initial.clone();
    c.days.clear();
    c.orphans.clear();
    c.typing_credit = 0;
    c.correct_total = 0;
    c.session = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    // serialize tests that touch the global CORE state
    static TEST_LOCK: Mutex<()> = Mutex::new(());
    const NOW: i64 = 1_800_000_000;

    const SAMPLE: &str = r#"{"levels":[{"index":1,"title":"One","items":[
      {"term":"die Konversation","meaning":"the conversation","pos_tag":"n","all_forms":"-en","audio":["https://x/a1.mp3","https://x/a2.mp3"],"typing_alternatives":["die Konversation","die konversation"]},
      {"term":"das Wetter","meaning":"the weather","pos_tag":"n","audio":["w.mp3"],"typing_alternatives":["das Wetter"]},
      {"term":"die Politik","meaning":"the politics","pos_tag":"n","audio":["p.mp3"],"typing_alternatives":["die Politik"]},
      {"term":"die Franzosen","meaning":"the French people","pos_tag":"n","audio":["f.mp3"],"typing_alternatives":["die Franzosen"]},
      {"term":"wir können immer über das Wetter reden","meaning":"we can always talk about the weather","audio":["s.mp3"],"typing_alternatives":[]},
      {"term":"über","meaning":"over","pos_tag":"p","audio":["u.mp3"],"typing_alternatives":["über","uber"],
       "memrise_progress":{"interval":180.0,"next_date":"2021-10-24T03:29:42Z","correct":19,"attempts":21,"starred":true,"is_difficult":true,"ignored":false}}
    ]}]}"#;

    fn setup() -> std::sync::MutexGuard<'static, ()> {
        let g = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        load_seed(SAMPLE.to_string()).unwrap();
        set_pace(PaceSettings::default_const());
        g
    }

    /// The input that answers the current question correctly.
    fn right_answer(q: &Question) -> String {
        if let Some(a) = &q.answer {
            return a.clone();
        }
        let c = CORE.lock().unwrap();
        let w = &c.words[q.word as usize];
        match q.kind {
            QKind::Reversed | QKind::MeaningTyping => w.meaning.clone(),
            QKind::Arrange => w.term.clone(),
            _ => w.term.clone(),
        }
    }

    #[test]
    fn progress_key_uses_the_items_own_level() {
        let _g = setup();
        let seed = |parent: u32| {
            format!(
                r#"{{"levels":[{{"index":{parent},"title":"L","items":[
                {{"term":"das Boot","meaning":"the boat","level":7}},
                {{"term":"das Haus","meaning":"the house"}}]}}]}}"#
            )
        };
        load_seed(seed(1)).unwrap();
        set_starred(0, true);
        set_starred(1, true);
        let saved = export_state();
        assert!(saved.contains("\"7:das Boot\""), "{saved}");
        assert!(!saved.contains("\"1:das Boot\""), "{saved}");
        assert!(saved.contains("\"1:das Haus\""), "no level field falls back to the parent: {saved}");
        // re-chunked under another parent: the item with its own level keeps its key
        load_seed(seed(3)).unwrap();
        assert_eq!(import_state(saved).unwrap(), 1);
    }

    /// Words 0.. become studied (Typing) with these due times; every other word is New.
    fn studied(dues: &[i64]) {
        let mut c = CORE.lock().unwrap();
        for (i, r) in c.reviews.iter_mut().enumerate() {
            match dues.get(i) {
                Some(&due) => {
                    r.stage = Stage::Typing;
                    r.due = due;
                }
                None => r.stage = Stage::New,
            }
        }
    }

    const H: i64 = 3600; // NOW is exactly 08:00 on the core clock (epoch + tz offset)

    #[test]
    fn alarm_waits_for_the_fifth_due_word() {
        let _g = setup();
        studied(&[NOW + H, NOW + 2 * H, NOW + 3 * H, NOW + 4 * H, NOW + 5 * H]);
        let a = next_review_alarm(5, NOW, 0);
        assert_eq!((a.at, a.count), (NOW + 5 * H, 5));
    }

    #[test]
    fn alarm_is_never_with_fewer_than_five_studied() {
        let _g = setup();
        studied(&[NOW - H, NOW - H, NOW - H, NOW - H]);
        let a = next_review_alarm(5, NOW, 0);
        assert_eq!((a.at, a.count), (-1, 0));
    }

    #[test]
    fn alarm_respects_the_4_hour_gap() {
        let _g = setup();
        studied(&[NOW - 86_400; 5]);
        let a = next_review_alarm(5, NOW, NOW - H);
        assert_eq!((a.at, a.count), (NOW + 3 * H, 5));
    }

    #[test]
    fn alarm_skips_quiet_hours() {
        let _g = setup();
        studied(&[NOW - 86_400; 5]);
        // 23:30 -> 08:00 the next day
        assert_eq!(next_review_alarm(5, NOW + 15 * H + H / 2, 0).at, NOW + 24 * H);
        // 03:00 -> 08:00 the same day
        assert_eq!(next_review_alarm(5, NOW - 5 * H, 0).at, NOW);
        // a daytime time is left alone (14:00), and 22:00 exactly is already quiet
        assert_eq!(next_review_alarm(5, NOW + 6 * H, 0).at, NOW + 6 * H);
        assert_eq!(next_review_alarm(5, NOW + 14 * H, 0).at, NOW + 24 * H);
        // the edges: 07:59:59 is still quiet, 08:00:00 and 21:59:59 are not
        assert_eq!(next_review_alarm(5, NOW - 1, 0).at, NOW);
        assert_eq!(next_review_alarm(5, NOW, 0).at, NOW);
        assert_eq!(next_review_alarm(5, NOW + 14 * H - 1, 0).at, NOW + 14 * H - 1);
    }

    /// A seed of `n` one-word, audio-backed words that are all Known and overdue: left alone,
    /// every one of them is a Typing/Hearing card.
    fn all_typing_seed(n: usize) -> String {
        let items: Vec<String> = (0..n)
            .map(|k| {
                format!(
                    r#"{{"term":"wort{k}","meaning":"word {k}","audio":["a{k}.mp3"],
                    "memrise_progress":{{"interval":100.0,"next_date":"2021-01-01T00:00:00Z","correct":9,"attempts":9}}}}"#
                )
            })
            .collect();
        format!(r#"{{"levels":[{{"index":1,"items":[{}]}}]}}"#, items.join(","))
    }

    /// Start a session and read its whole card list: (typing cards, all cards).
    fn typing_in_session(size: u32) -> (usize, usize) {
        start_session(None, size, NOW).unwrap();
        let c = CORE.lock().unwrap();
        let qs = &c.session.as_ref().unwrap().questions;
        let typed = qs.iter().filter(|q| matches!(q.kind, QKind::Typing | QKind::Hearing)).count();
        (typed, qs.len())
    }

    #[test]
    fn typing_is_at_most_a_tenth_of_a_session() {
        let _g = setup();
        load_seed(all_typing_seed(30)).unwrap();
        let (typed, total) = typing_in_session(20);
        assert_eq!(total, 20);
        assert_eq!(typed, 2, "10% of 20 cards");
        // the rest are choice cards, and they all accept the right answer
        let mut kinds = Vec::new();
        while let Some(q) = current_question() {
            kinds.push(q.kind);
            if submit_answer(right_answer(&q)).finished {
                break;
            }
        }
        assert!(kinds.iter().all(|k| matches!(k, QKind::Typing | QKind::Hearing | QKind::Mc | QKind::Reversed | QKind::AudioMc)), "{kinds:?}");
    }

    #[test]
    fn the_typing_allowance_carries_over_short_sessions() {
        let _g = setup();
        load_seed(all_typing_seed(30)).unwrap();
        let typed: usize = (0..10).map(|_| typing_in_session(5).0).sum();
        assert_eq!(typed, 5, "10 sessions of 5 cards = 50 cards = 5 typed");
    }

    #[test]
    fn sentences_and_new_words_are_not_touched_by_the_typing_limit() {
        let _g = setup(); // SAMPLE: one sentence, five short words, one Known word
        {
            let mut c = CORE.lock().unwrap();
            for r in c.reviews.iter_mut() {
                r.stage = Stage::Known;
                r.due = 1;
            }
        }
        for _ in 0..30 {
            start_session(None, 6, NOW).unwrap();
            // the 7-word sentence is tiles, unless it was picked for the timed speed round at the end
            let sentence = {
                let c = CORE.lock().unwrap();
                let q = c.session.as_ref().unwrap().questions.iter().find(|q| q.word == 4).map(|q| (q.kind, q.time_ms));
                q.expect("the sentence is in the session")
            };
            assert!(sentence.0 == QKind::Arrange || sentence.1 > 0, "the sentence became {sentence:?}");
        }
    }

    #[test]
    fn mistakes_flag_only_the_wrong_words() {
        let m = mark_mistakes("wir können immer über das Wetter reden".into(), "wir koennen immer ueber dass Wetter reden".into());
        assert_eq!(m.typed, [false, false, false, false, true, false, false]);
        assert_eq!(m.expected, [false, false, false, false, true, false, false]);
    }

    #[test]
    fn mistakes_handle_missing_extra_swapped_and_empty_answers() {
        let miss = mark_mistakes("wir können immer reden".into(), "wir können reden".into());
        assert_eq!((miss.typed, miss.expected), (vec![false; 3], vec![false, false, true, false]));
        let extra = mark_mistakes("wir reden".into(), "wir können reden".into());
        assert_eq!((extra.typed, extra.expected), (vec![false, true, false], vec![false; 2]));
        let swap = mark_mistakes("a b c".into(), "a c b".into());
        assert_eq!(swap.typed.iter().filter(|&&w| w).count(), 1);
        assert_eq!(swap.expected.iter().filter(|&&w| w).count(), 1);
        let none = mark_mistakes("wir reden".into(), "".into());
        assert_eq!((none.typed, none.expected), (vec![], vec![true, true]));
    }

    #[test]
    fn mistakes_ignore_case_umlaut_spelling_and_punctuation_like_the_checker() {
        let m = mark_mistakes("wie geht es dir ?".into(), "Wie geht's es dir".into());
        assert_eq!(m.typed, [false, true, false, false]); // "geht's" is not "geht"
        assert_eq!(m.expected, [false, true, false, false, false]); // the lone "?" is never a mistake
        let ok = mark_mistakes("über das Wetter".into(), "UEBER das wetter".into());
        assert_eq!((ok.typed, ok.expected), (vec![false; 3], vec![false; 3]));
        let typo = mark_mistakes("die Konversation".into(), "die Konversaton".into());
        assert_eq!((typo.typed, typo.expected), (vec![false, true], vec![false, true]));
    }

    const REAL_SEED: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../android/app/src/main/assets/seed.json");

    #[test]
    fn too_similar_follows_the_owner_rule() {
        assert!(too_similar("dann zieh deinen mantel aus", "dann zieh deinen regenmantel an"));
        assert!(too_similar("wir koennen immer ueber politik reden", "wir koennen immer ueber das wetter reden"), "3 words in a row");
        assert!(too_similar("der hund", "der mund"), "one letter apart");
        assert!(!too_similar("die politik", "die franzosen"));
        assert!(!too_similar("das wetter", "wir koennen immer ueber das wetter reden"));
        assert!(!too_similar("ich mag asiatisches essen", "sie spielt gerne badminton"));
    }

    #[test]
    fn real_course_choices_are_never_lookalikes() {
        let _g = setup();
        load_seed(std::fs::read_to_string(REAL_SEED).unwrap()).unwrap();
        let c = CORE.lock().unwrap();
        let mut bad = Vec::new();
        for i in 0..c.words.len() {
            for kind in [QKind::Mc, QKind::Reversed] {
                let opts: Vec<String> = choices_for(&c, i, kind).iter().map(|o| normalize(o)).collect();
                assert_eq!(opts.len(), 4);
                for (x, a) in opts.iter().enumerate() {
                    for b in &opts[x + 1..] {
                        if a == b || too_similar(a, b) {
                            bad.push(format!("{kind:?}: '{a}' ~ '{b}'"));
                        }
                    }
                }
            }
        }
        assert!(bad.is_empty(), "{} lookalike pairs, e.g. {:?}", bad.len(), &bad[..bad.len().min(4)]);
    }

    /// 14 studied phrases of 1..14 words (distinct vocabulary), one duplicate phrase in a second
    /// level, and two brand-new words.
    fn fast_seed() -> String {
        const V: [&str; 24] = [
            "Haus", "Baum", "Fisch", "Stern", "Wolke", "Tisch", "Lampe", "Brot", "Wasser", "Garten", "Schuh", "Katze",
            "Brille", "Fenster", "Zug", "Insel", "Mond", "Kuchen", "Regen", "Bruecke", "Hafen", "Wiese", "Turm", "Sofa",
        ];
        let known = r#","memrise_progress":{"interval":5.0,"next_date":"2021-01-01T00:00:00Z","correct":3,"attempts":3}"#;
        let phrase = |k: usize| (0..=k).map(|m| V[(k * 5 + m * 7) % 24]).collect::<Vec<_>>().join(" ");
        let mut level1: Vec<String> = (0..14).map(|k| format!(r#"{{"term":"{}","meaning":"m{k}"{known}}}"#, phrase(k))).collect();
        level1.push(r#"{"term":"Neu eins","meaning":"new one"}"#.into());
        level1.push(r#"{"term":"Neu zwei","meaning":"new two"}"#.into());
        let dup = format!(r#"{{"term":"{}","meaning":"m3 again"{known}}}"#, phrase(3).to_lowercase());
        format!(
            r#"{{"levels":[{{"index":1,"items":[{}]}},{{"index":2,"items":[{dup}]}}]}}"#,
            level1.join(",")
        )
    }

    /// The fast session's cards as (German phrase, words, time limit ms, kind).
    fn fast_cards() -> Vec<(String, usize, u32, QKind)> {
        let mut out = Vec::new();
        let n = session_progress().unwrap().total;
        for _ in 0..n {
            let q = current_question().unwrap();
            let term = get_card(q.word).unwrap().term;
            out.push((term.clone(), term.split_whitespace().count(), q.time_limit_ms, q.kind));
            submit_answer(right_answer(&q));
        }
        out
    }

    #[test]
    fn fast_review_goes_from_easy_to_hard_and_from_slow_to_fast() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        assert_eq!(start_fast_session(None, 12, NOW).unwrap(), 12);
        let cards = fast_cards();
        assert_eq!(cards.len(), 12);
        assert!(cards.iter().all(|c| c.3 == QKind::Mc && c.2 > 0), "timed choice cards only: {cards:?}");
        assert!(cards.windows(2).all(|w| w[0].1 <= w[1].1), "easy -> hard by phrase length: {cards:?}");
        let (first, last) = (&cards[0], &cards[11]);
        assert_eq!(first.2, (8 + first.1 as u32) * 1000, "the first card gets the full (8 s + 1 s per word)");
        assert_eq!(last.2, ((8 + last.1) as f32 * 0.45 * 1000.0).round() as u32, "the last card gets 45 %");
        assert!(last.2 < first.2 + 1000 * (last.1 as u32 - first.1 as u32), "the clock tightens even though phrases get longer");
    }

    #[test]
    fn fast_review_only_uses_met_words_and_never_repeats_a_phrase() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        for _ in 0..20 {
            start_fast_session(None, 12, NOW).unwrap();
            let terms: Vec<String> = fast_cards().into_iter().map(|c| normalize(&c.0)).collect();
            let mut u = terms.clone();
            u.sort();
            u.dedup();
            assert_eq!(u.len(), terms.len(), "a phrase came up twice: {terms:?}");
            assert!(terms.iter().all(|t| !t.starts_with("neu ")), "a brand-new word was reviewed: {terms:?}");
        }
        // a lesson-only session stays inside that lesson
        start_fast_session(Some(2), 12, NOW).unwrap();
        assert_eq!(session_progress().unwrap().total, 1);
    }

    /// Mark these words (indices of `fast_seed`) as practised in the app: answered here, so `touched`.
    fn practised(words: &[usize]) {
        let mut c = CORE.lock().unwrap();
        for &w in words {
            c.reviews[w].touched = true;
        }
    }

    fn fast_picks(level: Option<u32>, size: u32) -> Vec<usize> {
        start_fast_session(level, size, NOW).unwrap();
        let n = session_progress().unwrap().total;
        (0..n)
            .map(|_| {
                let q = current_question().unwrap();
                submit_answer(right_answer(&q));
                q.word as usize
            })
            .collect()
    }

    #[test]
    fn home_fast_review_draws_on_words_practised_in_this_app_first() {
        let _g = setup();
        for _ in 0..20 {
            load_seed(fast_seed()).unwrap(); // answering a card practises its word, so start clean each round
            practised(&[1, 4, 6, 9, 12]);
            let mut five = fast_picks(None, 5);
            five.sort();
            assert_eq!(five, [1, 4, 6, 9, 12], "only practised words while there are enough of them");
            // too few practised words: they all come, the older imported ones only fill up
            load_seed(fast_seed()).unwrap();
            practised(&[1, 4, 6, 9, 12]);
            let eight = fast_picks(None, 8);
            assert!([1, 4, 6, 9, 12].iter().all(|w| eight.contains(w)), "{eight:?}");
            assert_eq!(eight.len(), 8);
        }
    }

    #[test]
    fn fast_review_takes_due_words_before_the_rest() {
        let _g = setup();
        for level in [None, Some(1)] {
            for _ in 0..20 {
                load_seed(fast_seed()).unwrap();
                {
                    let mut c = CORE.lock().unwrap();
                    for k in 0..7 {
                        c.reviews[k].due = NOW + 86_400; // not due until tomorrow
                    }
                }
                let picks = fast_picks(level, 5);
                // due words: 7..14 and the duplicate-phrase word 16 (level 2); words 0..7 are not due
                assert!(picks.iter().all(|&w| (7..14).contains(&w) || w == 16), "{level:?}: a word that is not due was picked while due ones remained: {picks:?}");
            }
        }
    }

    #[test]
    fn a_lesson_fast_review_ignores_practice_history() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        practised(&[1, 4, 6]);
        let mut picked_unpractised = false;
        for _ in 0..40 {
            picked_unpractised |= fast_picks(Some(1), 3).iter().any(|w| ![1, 4, 6].contains(w));
        }
        assert!(picked_unpractised, "a lesson review must draw from all met words of the lesson");
    }

    /// A normal session over `fast_seed`: its cards as (word, words in phrase, time limit ms, kind).
    fn normal_session(size: u32) -> Vec<(usize, usize, u32, QKind)> {
        start_session(None, size, NOW).unwrap();
        let c = CORE.lock().unwrap();
        let qs = &c.session.as_ref().unwrap().questions;
        qs.iter().map(|q| (q.word, c.words[q.word].tokens, q.time_ms, q.kind)).collect()
    }

    #[test]
    fn a_normal_session_ends_with_a_timed_speed_round() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        let cards = normal_session(12);
        assert_eq!(cards.len(), 12, "the session keeps its length");
        let (plain, round) = cards.split_at(8);
        assert!(plain.iter().all(|c| c.2 == 0), "the first cards are untimed: {cards:?}");
        assert!(round.iter().all(|c| c.2 > 0 && c.3 == QKind::Mc), "the last 4 are timed choice cards: {cards:?}");
        assert!(round.windows(2).all(|w| w[0].1 <= w[1].1), "the round goes easy -> hard: {cards:?}");
        assert_eq!(round[0].2, (8 + round[0].1 as u32) * 1000, "same clock as a fast review");
        let words: Vec<usize> = cards.iter().map(|c| c.0).collect();
        let mut u = words.clone();
        u.sort();
        u.dedup();
        assert_eq!(u.len(), words.len(), "no word twice");
    }

    #[test]
    fn short_sessions_and_new_words_get_a_smaller_or_no_speed_round() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        // 5 met words: at most half of them (2) are timed
        let timed = normal_session(5).iter().filter(|c| c.2 > 0).count();
        assert_eq!(timed, 2);
        // brand-new words are never timed: their intro and first card stay together and untimed
        let items: Vec<String> = (0..8).map(|k| format!(r#"{{"term":"neues Wort{k}","meaning":"new {k}"}}"#)).collect();
        load_seed(format!(r#"{{"levels":[{{"index":1,"items":[{}]}}]}}"#, items.join(","))).unwrap();
        assert!(normal_session(8).iter().all(|c| c.2 == 0));
    }

    #[test]
    fn a_miss_just_before_the_speed_round_gets_its_second_try_before_it() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        start_session(None, 12, NOW).unwrap();
        for _ in 0..7 {
            answer_right();
        }
        assert!(!submit_answer("zzz".into()).correct); // card 8 of 12: the speed round starts at card 9
        // read under the lock, assert after it: a panic while holding CORE poisons every other test
        let (len, second_try, round_together) = {
            let c = CORE.lock().unwrap();
            let qs = &c.session.as_ref().unwrap().questions;
            (qs.len(), qs.get(8).map(|q| (q.retry, q.time_ms)), qs.get(9..).map(|r| r.iter().all(|q| q.time_ms > 0 && !q.retry)))
        };
        assert_eq!(len, 13);
        assert_eq!(second_try, Some((true, 0)), "the second try comes right after the miss");
        assert_eq!(round_together, Some(true), "the timed round stays together at the end");
    }

    #[test]
    fn the_speed_round_never_repeats_a_phrase() {
        let _g = setup();
        let known = r#","memrise_progress":{"interval":5.0,"next_date":"2021-01-01T00:00:00Z","correct":3,"attempts":3}"#;
        // four met words, two of them the same phrase (as in the real course: "die Beziehung" is in two levels)
        load_seed(format!(
            r#"{{"levels":[{{"index":1,"items":[
                {{"term":"Haus eins","meaning":"a"{known}}},
                {{"term":"Baum zwei drei","meaning":"b"{known}}},
                {{"term":"Fisch vier fuenf sechs","meaning":"c"{known}}}]}},
              {{"index":2,"items":[{{"term":"haus eins","meaning":"a again"{known}}}]}}]}}"#
        ))
        .unwrap();
        for _ in 0..60 {
            let round: Vec<String> = normal_session(4)
                .iter()
                .filter(|c| c.2 > 0)
                .map(|c| normalize(&get_card(c.0 as u32).unwrap().term))
                .collect();
            let mut u = round.clone();
            u.sort();
            u.dedup();
            assert_eq!(u.len(), round.len(), "a phrase came up twice in the round: {round:?}");
        }
    }

    #[test]
    fn a_fast_timeout_is_a_miss_and_gets_no_retry() {
        let _g = setup();
        load_seed(fast_seed()).unwrap();
        start_fast_session(None, 12, NOW).unwrap();
        let q = current_question().unwrap();
        let before = CORE.lock().unwrap().reviews[q.word as usize].stage;
        let fb = submit_answer(String::new()); // the UI submits "" when the time is up
        assert!(!fb.correct && fb.scored && !fb.hinted);
        assert_eq!(fb.stage, demote(before));
        assert_eq!(session_progress().unwrap().total, 12, "no second try in a timed session");
    }

    /// Where a card's wrong options sit among ALL clean candidates of its lesson, independent of
    /// phrase length: 0 = the least alike candidates, 1 = the most alike.
    fn option_percentile(c: &Core, q: &Question) -> f32 {
        let w = &c.words[q.word as usize];
        let right = normalize(&w.term);
        let lesson = c.levels.iter().find(|l| l.index == w.level).unwrap();
        let sims: Vec<f32> = lesson
            .members
            .iter()
            .filter(|&&j| j != q.word as usize)
            .map(|&j| normalize(&c.words[j].term))
            .filter(|n| *n != right && !too_similar(&right, n))
            .map(|n| alike(&right, &n))
            .collect();
        let pct = |s: f32| sims.iter().map(|&x| if x < s { 1.0 } else if x == s { 0.5 } else { 0.0 }).sum::<f32>() / sims.len() as f32;
        let wrong: Vec<f32> = q.choices.iter().map(|o| normalize(o)).filter(|o| *o != right).map(|o| alike(&right, &o)).collect();
        wrong.iter().map(|&s| pct(s)).sum::<f32>() / wrong.len() as f32
    }

    #[test]
    fn fast_review_options_get_more_alike_as_it_goes() {
        let _g = setup();
        load_seed(std::fs::read_to_string(REAL_SEED).unwrap()).unwrap();
        let (mut early, mut late) = (Vec::new(), Vec::new());
        for _ in 0..40 {
            start_fast_session(None, 12, NOW).unwrap();
            while let Some(q) = current_question() {
                let p = option_percentile(&CORE.lock().unwrap(), &q);
                if q.position <= 4 { early.push(p) } else if q.position > 8 { late.push(p) }
                submit_answer(right_answer(&q));
            }
        }
        let mean = |v: &[f32]| v.iter().sum::<f32>() / v.len() as f32;
        let (e, l) = (mean(&early), mean(&late));
        assert!(e < 0.4 && l > 0.6, "first cards {e:.2}, last cards {l:.2}: early options must be the least alike, late the most");
    }

    #[test]
    fn saved_progress_for_unknown_words_survives_a_round_trip() {
        let _g = setup();
        // progress saved under a seed that had a word this seed lacks (e.g. a stale bundle)
        let ghost = r#"{"version":1,"pace":null,"days":[],"reviews":{
            "99:ghost":{"stage":"Known","ease":2.5,"interval_days":30.0,"reps":9,"lapses":0,"due":1,"starred":false},
            "1:das Wetter":{"stage":"Mc","ease":2.5,"interval_days":1.0,"reps":1,"lapses":0,"due":1,"starred":false}}}"#;
        assert_eq!(import_state(ghost.into()).unwrap(), 1, "only the word this seed has is applied");
        let saved = export_state();
        assert!(saved.contains("\"99:ghost\""), "unmatched progress was dropped: {saved}");
        assert!(saved.contains("\"1:das Wetter\""));
        // a later seed that has the word again picks the progress up
        load_seed(r#"{"levels":[{"index":99,"items":[{"term":"ghost","meaning":"m"}]}]}"#.into()).unwrap();
        assert_eq!(import_state(saved).unwrap(), 1);
        assert_eq!(levels()[0].known, 1);
    }

    #[test]
    fn reset_and_reload_forget_unmatched_progress() {
        let _g = setup();
        let ghost = r#"{"version":1,"pace":null,"days":[],"reviews":{
            "99:ghost":{"stage":"Known","ease":2.5,"interval_days":30.0,"reps":9,"lapses":0,"due":1,"starred":false}}}"#;
        import_state(ghost.into()).unwrap();
        reset_progress();
        assert!(!export_state().contains("ghost"), "reset must forget it");
        import_state(ghost.into()).unwrap();
        load_seed(SAMPLE.to_string()).unwrap();
        assert!(!export_state().contains("ghost"), "a fresh seed load starts clean");
    }

    #[test]
    fn levels_report_their_topic() {
        let _g = setup();
        load_seed(
            r#"{"levels":[{"index":1,"title":"Sports 1","topic":"Sports","items":[{"term":"a","meaning":"m"}]},
                          {"index":2,"title":"Weather 2","items":[{"term":"b","meaning":"m"}]}]}"#
                .into(),
        )
        .unwrap();
        let topics: Vec<String> = levels().into_iter().map(|l| l.topic).collect();
        assert_eq!(topics, ["Sports", "Weather 2"]); // no topic -> the title
    }

    #[test]
    fn version_banner_is_stable() {
        assert_eq!(core_version(), "Merkrise core 0.3.0");
    }

    #[test]
    fn normalize_folds_case_space_umlaut_and_punctuation() {
        assert_eq!(normalize("  Über   Reden "), normalize("über reden"));
        assert_eq!(normalize("ÜBER"), "ueber");
        assert_eq!(normalize("Straße"), "strasse");
        assert_eq!(normalize("Über (etwas) reden?"), "ueber etwas reden");
        assert_eq!(normalize("E\u{ad}-Mail!"), "email");
    }

    #[test]
    fn accepted_contains_term_and_alts() {
        let acc = build_accepted("die Zwiebel", &["zwiebel".into(), "die zwiebel".into()]);
        assert!(acc.contains(&normalize("die Zwiebel")));
        assert!(acc.contains(&normalize("ZWIEBEL")));
        assert!(acc.contains(&normalize("  die   zwiebel ")));
    }

    #[test]
    fn iso_dates_parse_to_unix_seconds() {
        assert_eq!(parse_iso("1970-01-02T00:00:10Z"), Some(86_410));
        assert_eq!(parse_iso("2021-10-24T03:29:42Z"), Some(1_635_046_182));
        assert_eq!(parse_iso("garbage"), None);
    }

    #[test]
    fn memrise_progress_maps_to_initial_state() {
        let _g = setup();
        let c = CORE.lock().unwrap();
        let r = &c.initial[5]; // "über"
        assert_eq!(r.stage, Stage::Known); // interval 180 >= 60
        assert_eq!(r.due, 1_635_046_182);
        assert_eq!(r.reps, 19);
        assert_eq!(r.lapses, 2);
        assert!(r.starred);
        assert!((r.ease - 2.2).abs() < 1e-6); // 2.5 - 0.3 (difficult)
        assert_eq!(c.initial[0].stage, Stage::New); // no progress
    }

    #[test]
    fn mc_choices_are_four_unique_including_correct() {
        let _g = setup();
        let c = CORE.lock().unwrap();
        let choices = choices_for(&c, 0, QKind::Mc);
        assert_eq!(choices.len(), 4);
        assert!(choices.contains(&"die Konversation".to_string()));
        let mut u = choices.clone();
        u.sort();
        u.dedup();
        assert_eq!(u.len(), 4, "choices must be unique");
        assert!(choices_for(&c, 0, QKind::Reversed).contains(&"the conversation".to_string()));
    }

    #[test]
    fn levels_report_counts_and_progress() {
        let _g = setup();
        let ls = levels();
        assert_eq!(ls.len(), 1);
        assert_eq!((ls[0].count, ls[0].known, ls[0].learning), (6, 1, 0));
        let rows = level_words(1);
        assert_eq!(rows.len(), 6);
        assert_eq!(rows[5].stage, Stage::Known);
    }

    #[test]
    fn new_words_get_a_presentation_card_then_advance_a_stage() {
        let _g = setup();
        start_session(Some(1), 5, NOW).unwrap(); // 5 new words (the 6th is Known and not due-first)
        let p = session_progress().unwrap();
        assert!(p.total > p.scored, "presentation cards are unscored");
        let mut seen_presentation = 0;
        let mut after_presentation = None;
        while let Some(q) = current_question() {
            let fb = submit_answer(if q.kind == QKind::Presentation { String::new() } else { right_answer(&q) });
            if q.kind == QKind::Presentation {
                seen_presentation += 1;
                assert!(!fb.scored);
                after_presentation = Some(q.word);
            } else {
                assert!(fb.correct, "prompt '{}' kind {:?}", q.prompt, q.kind);
                assert!(fb.scored);
                if Some(q.word) == after_presentation {
                    assert_eq!(fb.stage, Stage::Reversed); // New -> Reversed on a correct mc
                    assert!(fb.next_due > NOW);
                }
            }
            if fb.finished {
                break;
            }
        }
        assert!(seen_presentation >= 1);
        let p = session_progress().unwrap();
        assert_eq!(p.correct, p.scored);
        assert!(p.finished);
    }

    #[test]
    fn sm2_intervals_and_wrong_answer_demotion() {
        let pace = PaceSettings { new_per_session: 15, interval_scale: 1.0, length_weight: 0.0 };
        let mut r = Review::new();
        schedule(&mut r, true, NOW, &pace, 1.0);
        assert_eq!((r.interval_days, r.reps, r.stage), (1.0, 1, Stage::Reversed));
        schedule(&mut r, true, NOW, &pace, 1.0);
        assert_eq!(r.interval_days, 6.0);
        schedule(&mut r, true, NOW, &pace, 1.0);
        assert_eq!(r.interval_days, 15.0); // 6 * 2.5
        assert_eq!(r.stage, Stage::Typing);
        schedule(&mut r, false, NOW, &pace, 1.0);
        assert_eq!((r.reps, r.lapses, r.stage), (0, 1, Stage::Audio));
        assert_eq!(r.due, NOW + 600);
        assert!((r.ease - 2.3).abs() < 1e-6);
    }

    #[test]
    fn longer_words_come_due_sooner() {
        let pace = PaceSettings { new_per_session: 15, interval_scale: 1.0, length_weight: 0.5 };
        let (mut short, mut long) = (Review::new(), Review::new());
        schedule(&mut short, true, 0, &pace, 0.6);
        schedule(&mut long, true, 0, &pace, 1.6);
        assert!(long.interval_days < short.interval_days);
    }

    #[test]
    fn session_prefers_due_then_new_then_practice() {
        let _g = setup();
        {
            let mut c = CORE.lock().unwrap();
            // word 0: due Mc; word 1: not due (Reversed, far future); rest stay New
            c.reviews[0] = Review { stage: Stage::Mc, due: NOW - 10, ..Review::new() };
            c.reviews[1] = Review { stage: Stage::Reversed, due: NOW + 99_999_999, ..Review::new() };
        }
        start_session(Some(1), 3, NOW).unwrap();
        let guard = CORE.lock().unwrap();
        let words: BTreeSet<usize> = guard.session.as_ref().unwrap().questions.iter().map(|q| q.word).collect();
        assert!(words.contains(&0), "the due word must be picked first");
        assert!(!words.contains(&1), "practice words come last");
        assert_eq!(words.len(), 3);
    }

    #[test]
    fn session_is_never_empty_even_with_nothing_due() {
        let _g = setup();
        {
            let mut c = CORE.lock().unwrap();
            for r in c.reviews.iter_mut() {
                *r = Review { stage: Stage::Known, due: NOW + 99_999_999, ..Review::new() };
            }
        }
        assert_eq!(start_session(None, 4, NOW).unwrap(), 4);
        assert!(current_question().is_some());
    }

    #[test]
    fn learn_new_gives_the_fixed_three_task_mix() {
        let _g = setup();
        assert_eq!(start_learn_new(Some(1), 3, NOW).unwrap(), 3);
        let c = CORE.lock().unwrap();
        let mut by_word: BTreeMap<usize, Vec<&Q>> = BTreeMap::new();
        for q in &c.session.as_ref().unwrap().questions {
            by_word.entry(q.word).or_default().push(q);
        }
        assert_eq!(by_word.len(), 3);
        for qs in by_word.values() {
            assert_eq!(qs.iter().filter(|q| q.kind == QKind::Presentation).count(), 1);
            let tasks: Vec<&Q> = qs.iter().copied().filter(|q| q.kind != QKind::Presentation).collect();
            assert_eq!(tasks.len(), 3, "{:?}", tasks.iter().map(|q| q.kind).collect::<Vec<_>>());
            assert_eq!(tasks[0].kind, QKind::Typing);
            assert_eq!(tasks[1].kind, QKind::Mc);
            assert_eq!(tasks[2].kind, QKind::Arrange);
            assert_eq!(tasks.iter().filter(|q| q.srs).count(), 1, "one schedule update per word");
            assert!(tasks.last().unwrap().srs);
            for (k, q) in tasks.iter().enumerate() {
                assert_eq!(q.task_pos, k as u32 + 1);
                assert_eq!(q.task_total, tasks.len() as u32);
            }
            let mut shapes: Vec<QKind> = tasks.iter().map(|q| q.kind).collect();
            shapes.sort_by_key(|k| format!("{k:?}"));
            shapes.dedup();
            assert_eq!(shapes.len(), 3, "a task shape repeated: {shapes:?}");
            assert!(!c.reviews[qs[0].word].touched, "only words not studied in this app are taught");
            assert_eq!(qs[0].task_total, tasks.len() as u32, "the intro shows 0 of the same total");
        }
    }

    #[test]
    fn learn_new_miss_is_not_repeated_within_the_word() {
        let _g = setup();
        start_learn_new(Some(1), 1, NOW).unwrap();
        submit_answer(String::new()); // the introduction
        let total = current_question().unwrap().total;
        assert!(!submit_answer("zzz".into()).correct);
        assert_eq!(current_question().unwrap().total, total, "no retry card is inserted");
    }

    const CONTEXT_SEED: &str = r#"{"levels":[{"index":1,"title":"Contexts","items":[
      {"term":"das Wetter","meaning":"the weather","pos_tag":"n","audio":["w.mp3"]},
      {"term":"die Politik","meaning":"the politics","pos_tag":"n","audio":["p.mp3"]},
      {"term":"der Sport","meaning":"the sport","pos_tag":"n","audio":["s.mp3"]},
      {"term":"das Thema","meaning":"the topic","pos_tag":"n","audio":["t.mp3"]},
      {"term":"die Quelle","meaning":"the source","pos_tag":"n","audio":["q.mp3"]},
      {"term":"das Wetter ist heute schön","meaning":"the weather is nice today","audio":["c1.mp3"]},
      {"term":"ich mag das Wetter im Sommer","meaning":"I like the weather in summer","audio":["c2.mp3"]},
      {"term":"bei dem Wetter bleibe ich zu Hause","meaning":"in this weather I stay home","audio":["c3.mp3"]},
      {"term":"wir reden immer über das Wetter","meaning":"we always talk about the weather","audio":["c4.mp3"]}
    ]}]}"#;

    #[test]
    fn sentence_bag_gives_a_new_sentence_each_session() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        load_seed(CONTEXT_SEED.to_string()).unwrap();
        let target = {
            let c = CORE.lock().unwrap();
            c.words.iter().position(|w| w.term == "das Wetter").unwrap()
        };
        let group = || {
            let mut c = CORE.lock().unwrap();
            learn_group(&mut c, target)
        };
        let g1 = group();
        assert_eq!(g1[1].kind, QKind::Typing);
        assert_eq!(g1[2].kind, QKind::Mc);
        assert_eq!(g1[3].kind, QKind::Arrange);
        let first = g1[3].answer_override.clone().unwrap();
        assert!(first.contains("Wetter"), "not a sentence with the word: {first}");
        assert!(g1[3].tiles.len() >= 3, "not a real sentence to arrange");
        assert!(g1[3].prompt_override.is_some(), "the sentence meaning is the prompt");

        // One context per session: all four are used before the bag refills.
        let mut seen = vec![first.clone()];
        for _ in 0..3 {
            let g = group();
            let answer = g[3].answer_override.clone().unwrap();
            assert!(!seen.contains(&answer), "a sentence repeated before the bag emptied: {answer}");
            seen.push(answer);
        }
        let again = group();
        assert_eq!(again[3].answer_override.clone().unwrap(), first);
    }

    #[test]
    fn learn_new_answer_set_updates_the_word_once() {
        let _g = setup();
        start_learn_new(Some(1), 1, NOW).unwrap();
        let word = {
            let c = CORE.lock().unwrap();
            c.session
                .as_ref()
                .unwrap()
                .questions
                .iter()
                .find(|q| q.kind != QKind::Presentation)
                .unwrap()
                .word
        };
        let before = CORE.lock().unwrap().reviews[word].clone();
        while let Some(q) = current_question() {
            submit_answer(if q.kind == QKind::Presentation { String::new() } else { right_answer(&q) });
        }
        let after = CORE.lock().unwrap().reviews[word].clone();
        assert_eq!(after.reps, before.reps + 1, "exactly one schedule update");
        assert_eq!(after.stage, promote(before.stage));
        assert!(after.due > NOW, "the single update must schedule the word");
    }

    #[test]
    fn learn_new_miss_demotes_once_at_the_end() {
        let _g = setup();
        start_learn_new(Some(1), 1, NOW).unwrap();
        let word = {
            let c = CORE.lock().unwrap();
            c.session
                .as_ref()
                .unwrap()
                .questions
                .iter()
                .find(|q| q.kind != QKind::Presentation)
                .unwrap()
                .word
        };
        let before = CORE.lock().unwrap().reviews[word].clone();
        let q = current_question().unwrap();
        assert_eq!(q.kind, QKind::Presentation);
        submit_answer(String::new());
        let q = current_question().unwrap();
        assert_eq!(q.task_position, 1);
        assert!(!submit_answer("zzz".into()).correct);
        while let Some(q) = current_question() {
            submit_answer(if q.kind == QKind::Presentation { String::new() } else { right_answer(&q) });
        }
        let after = CORE.lock().unwrap().reviews[word].clone();
        assert_eq!(after.reps, 0);
        assert_eq!(after.lapses, before.lapses + 1);
        assert_eq!(after.stage, demote(before.stage));
        assert_eq!(after.due, NOW + 600);
    }

    #[test]
    fn learn_new_needs_unseen_words() {
        let _g = setup();
        assert!(start_learn_new(Some(9), 5, NOW).is_err(), "unknown level");
        {
            let mut c = CORE.lock().unwrap();
            for r in c.reviews.iter_mut() {
                r.touched = true;
            }
        }
        assert!(start_learn_new(None, 5, NOW).is_err(), "every word was studied already");
    }

    #[test]
    fn already_known_drops_the_learn_new_tasks() {
        let _g = setup();
        start_learn_new(Some(1), 1, NOW).unwrap();
        let q = current_question().unwrap();
        assert_eq!(q.kind, QKind::Presentation);
        mark_known(q.word);
        assert!(current_question().is_none(), "its tasks must be gone too");
        assert_eq!(CORE.lock().unwrap().reviews[q.word as usize].stage, Stage::Known);
    }

    #[test]
    fn arrange_cards_use_shuffled_words_and_accept_the_right_order() {
        let _g = setup();
        let mut c = CORE.lock().unwrap();
        let q = make_q(&c, 4, QKind::Arrange);
        let mut sorted = q.tiles.clone();
        sorted.sort();
        let mut want: Vec<String> = c.words[4].term.split_whitespace().map(String::from).collect();
        want.sort();
        assert_eq!(sorted, want);
        c.session = Some(Session { questions: vec![q], pos: 0, correct: 0, learn_faults: BTreeSet::new() });
        c.now = NOW;
        drop(c);
        let fb = submit_answer("Wir können immer über das Wetter reden.".into());
        assert!(fb.correct);
    }

    /// Make `word` the only card of a session, as an Arrange card; returns its correct tile order.
    fn arrange_session(word: usize) -> Vec<u32> {
        let mut c = CORE.lock().unwrap();
        let q = make_q(&c, word, QKind::Arrange);
        let order = q.order.iter().map(|&k| k as u32).collect();
        c.session = Some(Session { questions: vec![q], pos: 0, correct: 0, learn_faults: BTreeSet::new() });
        c.now = NOW;
        order
    }

    /// Install a session whose cards are `kind` cards for `words`, in order.
    fn session_of(words: &[usize], kind: QKind) {
        let mut c = CORE.lock().unwrap();
        let questions = words.iter().map(|&w| make_q(&c, w, kind)).collect();
        c.session = Some(Session { questions, pos: 0, correct: 0, learn_faults: BTreeSet::new() });
        c.now = NOW;
    }

    /// Answer the current card correctly.
    fn answer_right() -> AnswerFeedback {
        let q = current_question().unwrap();
        submit_answer(right_answer(&q))
    }

    #[test]
    fn a_miss_comes_back_three_cards_later_once() {
        let _g = setup();
        session_of(&[0, 1, 2, 3, 4, 5], QKind::Typing);
        assert!(!submit_answer("zzz".into()).correct); // card 1 of 6
        assert_eq!(session_progress().unwrap().total, 7);
        for _ in 0..3 {
            assert!(!current_question().unwrap().retry);
            answer_right();
        }
        let q = current_question().unwrap();
        assert_eq!((q.position, q.word, q.retry), (5, 0, true));
        assert!(!submit_answer("zzz".into()).correct); // missing the retry adds nothing
        assert_eq!(session_progress().unwrap().total, 7);
        while !answer_right().finished {}
    }

    #[test]
    fn retry_is_not_scored_or_scheduled() {
        let _g = setup();
        {
            let mut c = CORE.lock().unwrap();
            c.reviews[0].stage = Stage::Known;
            c.reviews[0].reps = 5;
        }
        session_of(&[0], QKind::Typing);
        let miss = submit_answer("zzz".into());
        assert!(miss.scored && !miss.correct);
        let (stage, reps, lapses, due) = {
            let r = &CORE.lock().unwrap().reviews[0];
            (r.stage, r.reps, r.lapses, r.due)
        };
        assert_eq!((stage, due), (Stage::Typing, NOW + 600));
        let fb = answer_right();
        assert!(fb.correct && !fb.scored && fb.next_due == 0);
        let c = CORE.lock().unwrap();
        let r = &c.reviews[0];
        assert_eq!((r.stage, r.reps, r.lapses, r.due), (stage, reps, lapses, due));
        assert_eq!(c.session.as_ref().unwrap().correct, 0, "a retry must not count towards the score");
    }

    #[test]
    fn retry_is_a_fresh_card() {
        let _g = setup();
        let mut reshuffled = false;
        for _ in 0..20 {
            session_of(&[0], QKind::Mc);
            let first = current_question().unwrap().choices;
            submit_answer("zzz".into());
            let again = current_question().unwrap();
            assert!(again.retry && again.choices.contains(&"die Konversation".to_string()));
            reshuffled |= again.choices != first;
        }
        assert!(reshuffled, "the retry showed the identical choices 20 times");

        session_of(&[4], QKind::Arrange);
        wand_hint(vec![]); // the wand marks the card hinted -> a miss -> a retry
        assert!(!submit_answer("wir können immer über das Wetter reden".into()).correct);
        let c = CORE.lock().unwrap();
        let retry = &c.session.as_ref().unwrap().questions[1];
        assert!(retry.retry && retry.revealed == 0 && !retry.hinted);
    }

    #[test]
    fn missing_the_last_card_still_gets_its_retry() {
        let _g = setup();
        session_of(&[0], QKind::Typing);
        let fb = submit_answer("zzz".into());
        assert!(!fb.finished, "the session ended before the retry");
        assert!(current_question().unwrap().retry);
        assert!(answer_right().finished);
    }

    #[test]
    fn wand_reveals_one_more_word_each_tap() {
        let _g = setup();
        let order = arrange_session(4); // 7 words
        let taps: Vec<Vec<u32>> = (0..8).map(|_| wand_hint(vec![])).collect();
        for (i, got) in taps.iter().enumerate() {
            assert_eq!(*got, order[..(i + 1).min(7)], "tap {}", i + 1);
        }
    }

    #[test]
    fn wand_never_shrinks_a_correct_prefix() {
        let _g = setup();
        let order = arrange_session(4);
        assert_eq!(wand_hint(order[..3].to_vec()), order[..4]);
        // a wrong tile ends the prefix: only what was right counts
        assert_eq!(wand_hint(vec![order[1], order[0]]), order[..5]);
    }

    #[test]
    fn wand_counts_a_prefix_built_from_either_duplicate_tile() {
        let _g = setup();
        load_seed(r#"{"levels":[{"index":1,"items":[{"term":"das Essen und das Trinken","meaning":"m"}]}]}"#.into())
            .unwrap();
        let order = arrange_session(0);
        let tiles = CORE.lock().unwrap().session.as_ref().unwrap().questions[0].tiles.clone();
        // the user tapped the OTHER "das" tile first
        let other_das = (0..5u32).find(|&k| tiles[k as usize] == "das" && k != order[0]).unwrap();
        assert_eq!(wand_hint(vec![other_das, order[1]]), order[..3]);
    }

    #[test]
    fn wand_used_means_a_miss() {
        let _g = setup();
        {
            let mut c = CORE.lock().unwrap();
            c.reviews[4].stage = Stage::Known;
            c.reviews[4].reps = 5;
        }
        arrange_session(4);
        wand_hint(vec![]);
        let fb = submit_answer("wir können immer über das Wetter reden".into());
        assert!(!fb.correct && fb.hinted && fb.scored);
        assert_eq!(fb.stage, Stage::Typing);
        assert_eq!(fb.next_due, NOW + 600);
    }

    #[test]
    fn tiles_merge_punctuation_only_tokens() {
        assert_eq!(tiles_of("wie geht es dir ?"), ["wie", "geht", "es", "dir ?"]);
        assert_eq!(tiles_of("- ja gut"), ["- ja", "gut"]);
        assert_eq!(tiles_of("ja - nein"), ["ja -", "nein"]);
    }

    #[test]
    fn sentences_at_repeat_stages_are_always_arrange() {
        let _g = setup();
        // collect under the lock, assert after it: a panic while holding CORE poisons every other test
        let kinds: Vec<(QKind, QKind)> = {
            let c = CORE.lock().unwrap();
            let (sentence, word) = (&c.words[4], &c.words[5]); // 7 words / "über"
            [Stage::Typing, Stage::Known]
                .iter()
                .flat_map(|&s| (0..50).map(move |_| s))
                .map(|s| (pick_kind(sentence, s, true), pick_kind(word, s, true)))
                .collect()
        };
        assert!(kinds.iter().all(|(s, _)| *s == QKind::Arrange), "{kinds:?}");
        assert!(kinds.iter().all(|(_, w)| *w != QKind::Arrange), "{kinds:?}");
    }

    #[test]
    fn arrange_order_rebuilds_the_term_even_for_15_words() {
        let _g = setup();
        let long = "ich habe gestern mit meinem Bruder über das Wetter und die Politik in Berlin gesprochen";
        assert_eq!(long.split_whitespace().count(), 15);
        load_seed(format!(r#"{{"levels":[{{"index":1,"items":[{{"term":"{long}","meaning":"m"}}]}}]}}"#)).unwrap();
        let q = make_q(&CORE.lock().unwrap(), 0, QKind::Arrange);
        assert_eq!(q.tiles.len(), 15);
        let mut perm = q.order.clone();
        perm.sort();
        assert_eq!(perm, (0..15).collect::<Vec<_>>());
        let rebuilt: Vec<&str> = q.order.iter().map(|&k| q.tiles[k].as_str()).collect();
        assert_eq!(rebuilt.join(" "), long);
    }

    #[test]
    fn mark_known_drops_the_words_remaining_cards() {
        let _g = setup();
        CORE.lock().unwrap().reviews[5].due = NOW + 99_999_999; // nothing due: a new word comes first
        start_session(Some(1), 1, NOW).unwrap();
        let q = current_question().unwrap(); // a presentation card for a new word
        assert_eq!(q.kind, QKind::Presentation);
        mark_known(q.word);
        assert!(current_question().is_none(), "its mc card must be gone too");
        assert_eq!(CORE.lock().unwrap().reviews[q.word as usize].stage, Stage::Known);
    }

    #[test]
    fn saved_state_round_trips_and_reset_restores_the_import() {
        let _g = setup();
        {
            let mut c = CORE.lock().unwrap();
            c.reviews[1] = Review { stage: Stage::Audio, reps: 3, touched: true, ..Review::new() };
            c.days.insert(5);
        }
        let saved = export_state();
        reset_progress();
        assert_eq!(CORE.lock().unwrap().reviews[1].stage, Stage::New);
        assert_eq!(import_state(saved).unwrap(), 1);
        let c = CORE.lock().unwrap();
        assert_eq!((c.reviews[1].stage, c.reviews[1].reps), (Stage::Audio, 3));
        assert!(c.days.contains(&5));
        assert_eq!(c.reviews[5].stage, Stage::Known); // untouched words keep the import
    }

    #[test]
    fn streak_counts_consecutive_days_ending_today_or_yesterday() {
        let days: BTreeSet<i64> = [10, 9, 8, 5].into_iter().collect();
        assert_eq!(streak(&days, 10), 3);
        assert_eq!(streak(&days, 11), 3);
        assert_eq!(streak(&days, 12), 0);
    }

    #[test]
    fn correct_total_adds_app_answers_to_the_memrise_history_and_survives_a_restart() {
        let _g = setup();
        assert_eq!(stats(NOW).correct_total, 19); // "über" was imported with 19 correct
        start_session(Some(1), 6, NOW).unwrap();
        let mut first = true;
        while let Some(q) = current_question() {
            let scored_card = q.kind != QKind::Presentation;
            submit_answer(if first && scored_card { "zzz".into() } else { right_answer(&q) });
            first &= !scored_card;
        }
        let p = session_progress().unwrap();
        assert!(p.correct > 0 && p.correct < p.scored, "one miss, the rest right: {p:?}");
        assert_eq!(stats(NOW).correct_total, 19 + p.correct as u64, "only correct answers count");
        let saved = export_state();
        reset_progress();
        assert_eq!(stats(NOW).correct_total, 19);
        import_state(saved).unwrap();
        assert_eq!(stats(NOW).correct_total, 19 + p.correct as u64);
    }

    #[test]
    fn stats_count_stages_and_due_words() {
        let _g = setup();
        let s = stats(NOW);
        assert_eq!((s.total_words, s.known, s.new_words, s.due_today), (6, 1, 5, 1));
    }
}
