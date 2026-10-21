uniffi::setup_scaffolding!();

use serde::Deserialize;
use std::sync::Mutex;

#[uniffi::export]
pub fn core_version() -> String {
    "Merkrise core 0.1.0".into()
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("seed parse error: {0}")]
    Parse(String),
    #[error("no words loaded")]
    NoWords,
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

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum QKind {
    MultipleChoice,
    Typing,
}

/// One quiz question for the UI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Question {
    pub position: u32, // 1-based within the session
    pub total: u32,
    pub kind: QKind,
    pub prompt: String,              // MC: German term; Typing: English meaning
    pub prompt_audio: Option<String>,
    pub choices: Vec<String>,        // MC only (4), empty for typing
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AnswerFeedback {
    pub correct: bool,
    pub expected: String, // the canonical correct answer to show
    pub finished: bool,   // was that the last question
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionProgress {
    pub total: u32,
    pub answered: u32,
    pub correct: u32,
    pub finished: bool,
}

// --- internal model ---
#[derive(Clone)]
struct Word {
    term: String,
    meaning: String,
    pos: Option<String>,
    audio_file: Option<String>,
    level: u32,
    accepted: Vec<String>, // normalized acceptable typed answers
}

struct Q {
    word: usize,
    kind: QKind,
    choices: Vec<String>,
}

struct Session {
    questions: Vec<Q>,
    pos: usize,
    correct: u32,
}

// --- seed.json shape (only the fields we read) ---
#[derive(Deserialize)]
struct SeedFile {
    levels: Vec<SeedLevel>,
}
#[derive(Deserialize)]
struct SeedLevel {
    index: u32,
    #[serde(default)]
    items: Vec<SeedItem>,
}
#[derive(Deserialize)]
struct SeedItem {
    term: String,
    meaning: String,
    #[serde(default)]
    pos_tag: Option<String>,
    #[serde(default)]
    audio: Vec<String>,
    #[serde(default)]
    typing_alternatives: Vec<String>,
}

static WORDS: Mutex<Vec<Word>> = Mutex::new(Vec::new());
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

/// Case/space-insensitive, umlaut-folding normalization for answer checking.
fn normalize(s: &str) -> String {
    let lower = s.trim().to_lowercase();
    let folded = lower
        .replace('ä', "ae")
        .replace('ö', "oe")
        .replace('ü', "ue")
        .replace('ß', "ss");
    folded.split_whitespace().collect::<Vec<_>>().join(" ")
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

fn audio_basename(url: &str) -> String {
    url.rsplit('/').next().unwrap_or(url).to_string()
}

fn build_mc_choices(correct_idx: usize, words: &[Word]) -> Vec<String> {
    let correct = words[correct_idx].meaning.clone();
    let mut pool: Vec<usize> = (0..words.len())
        .filter(|&i| i != correct_idx && words[i].meaning != correct)
        .collect();
    fastrand::shuffle(&mut pool);
    let mut choices: Vec<String> = pool
        .into_iter()
        .take(3)
        .map(|i| words[i].meaning.clone())
        .collect();
    choices.push(correct);
    fastrand::shuffle(&mut choices);
    choices
}

#[uniffi::export]
pub fn load_seed(json: String) -> Result<u32, CoreError> {
    let parsed: SeedFile =
        serde_json::from_str(&json).map_err(|e| CoreError::Parse(e.to_string()))?;
    let mut words = WORDS.lock().unwrap();
    words.clear();
    for level in parsed.levels {
        for item in level.items {
            let accepted = build_accepted(&item.term, &item.typing_alternatives);
            words.push(Word {
                term: item.term,
                meaning: item.meaning,
                pos: item.pos_tag,
                audio_file: item.audio.first().map(|u| audio_basename(u)),
                level: level.index,
                accepted,
            });
        }
    }
    Ok(words.len() as u32)
}

#[uniffi::export]
pub fn word_count() -> u32 {
    WORDS.lock().unwrap().len() as u32
}

#[uniffi::export]
pub fn get_card(index: u32) -> Option<WordCard> {
    WORDS.lock().unwrap().get(index as usize).map(|w| WordCard {
        term: w.term.clone(),
        meaning: w.meaning.clone(),
        pos: w.pos.clone(),
        audio_file: w.audio_file.clone(),
        level: w.level,
    })
}

/// Build a quiz session of up to `size` random words (mixed MC + typing).
#[uniffi::export]
pub fn start_session(size: u32) -> Result<u32, CoreError> {
    let words = WORDS.lock().unwrap();
    if words.is_empty() {
        return Err(CoreError::NoWords);
    }
    let max = words.len();
    let n = (size as usize).min(max);
    let mut idx: Vec<usize> = (0..max).collect();
    fastrand::shuffle(&mut idx);
    idx.truncate(n);
    let can_mc = max >= 4;
    let questions: Vec<Q> = idx
        .into_iter()
        .map(|i| {
            let kind = if can_mc && fastrand::bool() {
                QKind::MultipleChoice
            } else {
                QKind::Typing
            };
            let choices = if kind == QKind::MultipleChoice {
                build_mc_choices(i, &words)
            } else {
                Vec::new()
            };
            Q { word: i, kind, choices }
        })
        .collect();
    *SESSION.lock().unwrap() = Some(Session { questions, pos: 0, correct: 0 });
    Ok(n as u32)
}

#[uniffi::export]
pub fn current_question() -> Option<Question> {
    let words = WORDS.lock().unwrap();
    let sess = SESSION.lock().unwrap();
    let s = sess.as_ref()?;
    let q = s.questions.get(s.pos)?;
    let w = &words[q.word];
    let (prompt, audio) = match q.kind {
        QKind::MultipleChoice => (w.term.clone(), w.audio_file.clone()),
        QKind::Typing => (w.meaning.clone(), None),
    };
    Some(Question {
        position: (s.pos + 1) as u32,
        total: s.questions.len() as u32,
        kind: q.kind.clone(),
        prompt,
        prompt_audio: audio,
        choices: q.choices.clone(),
    })
}

#[uniffi::export]
pub fn submit_answer(user_input: String) -> AnswerFeedback {
    let words = WORDS.lock().unwrap();
    let mut sess = SESSION.lock().unwrap();
    let s = match sess.as_mut() {
        Some(s) if s.pos < s.questions.len() => s,
        _ => {
            return AnswerFeedback {
                correct: false,
                expected: String::new(),
                finished: true,
            }
        }
    };
    let (correct, expected) = {
        let q = &s.questions[s.pos];
        let w = &words[q.word];
        match q.kind {
            QKind::MultipleChoice => {
                (normalize(&user_input) == normalize(&w.meaning), w.meaning.clone())
            }
            QKind::Typing => (w.accepted.contains(&normalize(&user_input)), w.term.clone()),
        }
    };
    if correct {
        s.correct += 1;
    }
    s.pos += 1;
    AnswerFeedback {
        correct,
        expected,
        finished: s.pos >= s.questions.len(),
    }
}

#[uniffi::export]
pub fn session_progress() -> Option<SessionProgress> {
    let sess = SESSION.lock().unwrap();
    let s = sess.as_ref()?;
    Some(SessionProgress {
        total: s.questions.len() as u32,
        answered: s.pos as u32,
        correct: s.correct,
        finished: s.pos >= s.questions.len(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // serialize tests that touch the global WORDS/SESSION state
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    const SAMPLE: &str = r#"{"levels":[{"index":1,"items":[
      {"term":"die Konversation","meaning":"the conversation","pos_tag":"n","audio":["https://x/a1.mp3","https://x/a2.mp3"],"typing_alternatives":["die Konversation","die konversation"]},
      {"term":"das Wetter","meaning":"the weather","pos_tag":"n","audio":[],"typing_alternatives":["das Wetter"]},
      {"term":"die Politik","meaning":"the politics","pos_tag":"n","audio":[],"typing_alternatives":["die Politik"]},
      {"term":"die Franzosen","meaning":"the French people","pos_tag":"n","audio":[],"typing_alternatives":["die Franzosen"]},
      {"term":"über","meaning":"over","pos_tag":"p","audio":[],"typing_alternatives":["über","uber"]}
    ]}]}"#;

    #[test]
    fn version_banner_is_stable() {
        assert_eq!(core_version(), "Merkrise core 0.1.0");
    }

    #[test]
    fn normalize_folds_case_space_umlaut() {
        assert_eq!(normalize("  Über   Reden "), normalize("über reden"));
        assert_eq!(normalize("ÜBER"), "ueber");
        assert_eq!(normalize("Straße"), "strasse");
    }

    #[test]
    fn accepted_contains_term_and_alts() {
        let acc = build_accepted("die Zwiebel", &["zwiebel".into(), "die zwiebel".into()]);
        assert!(acc.contains(&normalize("die Zwiebel")));
        assert!(acc.contains(&normalize("ZWIEBEL")));
        assert!(acc.contains(&normalize("  die   zwiebel ")));
    }

    #[test]
    fn mc_choices_are_four_unique_including_correct() {
        let _g = TEST_LOCK.lock().unwrap();
        load_seed(SAMPLE.to_string()).unwrap();
        let words = WORDS.lock().unwrap();
        let choices = build_mc_choices(0, &words);
        assert_eq!(choices.len(), 4);
        assert!(choices.contains(&"the conversation".to_string()));
        let mut c = choices.clone();
        c.sort();
        c.dedup();
        assert_eq!(c.len(), 4, "choices must be unique");
    }

    #[test]
    fn full_session_scores_all_correct() {
        let _g = TEST_LOCK.lock().unwrap();
        load_seed(SAMPLE.to_string()).unwrap();
        let n = start_session(5).unwrap();
        assert_eq!(n, 5);
        let mut answered = 0;
        while let Some(q) = current_question() {
            let input = {
                let words = WORDS.lock().unwrap();
                match q.kind {
                    QKind::MultipleChoice => {
                        words.iter().find(|w| w.term == q.prompt).unwrap().meaning.clone()
                    }
                    QKind::Typing => {
                        words.iter().find(|w| w.meaning == q.prompt).unwrap().term.clone()
                    }
                }
            };
            let fb = submit_answer(input);
            assert!(fb.correct, "expected correct for prompt '{}'", q.prompt);
            answered += 1;
            if fb.finished {
                break;
            }
        }
        assert_eq!(answered, 5);
        let p = session_progress().unwrap();
        assert_eq!(p.correct, 5);
        assert!(p.finished);
    }
}
