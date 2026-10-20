uniffi::setup_scaffolding!();

use serde::Deserialize;
use std::sync::Mutex;

/// Version banner returned across the uniffi bridge — the Milestone-1 proof.
#[uniffi::export]
pub fn core_version() -> String {
    "Merkrise core 0.1.0".into()
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("seed parse error: {0}")]
    Parse(String),
}

/// One studyable word handed to the UI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WordCard {
    pub term: String,            // German
    pub meaning: String,         // English
    pub pos: Option<String>,     // part of speech
    pub audio_file: Option<String>, // basename of the mp3 in assets/audio/
    pub level: u32,
}

// --- shape of capture/seed.json (only the fields we read) ---
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
}

static WORDS: Mutex<Vec<WordCard>> = Mutex::new(Vec::new());

fn audio_basename(url: &str) -> String {
    url.rsplit('/').next().unwrap_or(url).to_string()
}

/// Parse the bundled seed.json and load every word into memory.
/// Returns how many words were loaded.
#[uniffi::export]
pub fn load_seed(json: String) -> Result<u32, CoreError> {
    let parsed: SeedFile =
        serde_json::from_str(&json).map_err(|e| CoreError::Parse(e.to_string()))?;
    let mut words = WORDS.lock().unwrap();
    words.clear();
    for level in parsed.levels {
        for item in level.items {
            words.push(WordCard {
                term: item.term,
                meaning: item.meaning,
                pos: item.pos_tag,
                audio_file: item.audio.first().map(|u| audio_basename(u)),
                level: level.index,
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
    WORDS.lock().unwrap().get(index as usize).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"{"levels":[{"index":1,"items":[
      {"term":"die Konversation","meaning":"the conversation","pos_tag":"n",
       "audio":["https://x/60980926_a.mp3","https://x/60980926_b.mp3"]},
      {"term":"das Wetter","meaning":"the weather","pos_tag":"n","audio":[]}
    ]}]}"#;

    #[test]
    fn version_banner_is_stable() {
        assert_eq!(core_version(), "Merkrise core 0.1.0");
    }

    #[test]
    fn load_seed_parses_words_and_audio() {
        let n = load_seed(SAMPLE.to_string()).expect("parse");
        assert_eq!(n, 2);
        assert_eq!(word_count(), 2);
        let c0 = get_card(0).unwrap();
        assert_eq!(c0.term, "die Konversation");
        assert_eq!(c0.meaning, "the conversation");
        assert_eq!(c0.pos.as_deref(), Some("n"));
        assert_eq!(c0.audio_file.as_deref(), Some("60980926_a.mp3"));
        assert_eq!(get_card(1).unwrap().audio_file, None);
        assert!(get_card(2).is_none());
    }
}
