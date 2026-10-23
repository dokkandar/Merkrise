//! The engine against the real bundled course (1,681 words), through the public API only.
use merkrise_core::*;
use std::collections::BTreeMap;
use std::sync::Mutex;

const NOW: i64 = 1_800_000_000;
// both tests drive the one global core
static LOCK: Mutex<()> = Mutex::new(());

fn read(path: &str) -> String {
    std::fs::read_to_string(format!("{}/../{path}", env!("CARGO_MANIFEST_DIR"))).expect(path)
}

/// The input that answers `q` correctly, found through the public API.
fn right(q: &Question) -> String {
    if let Some(a) = &q.answer {
        return a.clone();
    }
    let w = get_card(q.word).unwrap();
    match q.kind {
        QKind::Reversed | QKind::MeaningTyping => w.meaning,
        QKind::Arrange => w.term, // the UI joins the tiles with spaces
        _ => w.term,
    }
}

/// Play a whole session. `answer` maps a question to the input to submit.
fn play(answer: impl Fn(&Question) -> String) -> (Vec<QKind>, u32, u32) {
    let mut kinds = Vec::new();
    let (mut correct, mut scored) = (0, 0);
    while let Some(q) = current_question() {
        kinds.push(q.kind);
        let fb = submit_answer(if q.kind == QKind::Presentation { String::new() } else { answer(&q) });
        if fb.scored {
            scored += 1;
            correct += fb.correct as u32;
        }
        if fb.finished {
            break;
        }
    }
    (kinds, correct, scored)
}

/// The owner's saved progress (keyed on the pre-lesson seed) must land on the same words after the re-chunk.
#[test]
fn saved_progress_survives_the_rechunk() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    load_seed(read("capture/seed-merged.json")).unwrap();
    for _ in 0..3 {
        start_session(None, 20, NOW).unwrap();
        play(right);
    }
    let saved = export_state();
    let keys = serde_json::from_str::<serde_json::Value>(&saved).unwrap()["reviews"]
        .as_object()
        .unwrap()
        .len() as u32;
    assert!(keys >= 20, "only {keys} words touched");
    let before = stats(NOW);
    load_seed(read("android/app/src/main/assets/seed.json")).unwrap();
    assert_eq!(import_state(saved).unwrap(), keys, "saved keys lost in the re-chunk");
    let after = stats(NOW);
    assert_eq!((before.known, before.learning, before.new_words), (after.known, after.learning, after.new_words));
}

#[test]
fn learn_new_real_lesson_has_the_fixed_task_mix() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    load_seed(read("android/app/src/main/assets/seed.json")).unwrap();
    let l01 = levels().into_iter().find(|l| l.title.starts_with("Lesson 01")).unwrap().index;
    assert_eq!(start_learn_new(Some(l01), 5, NOW).unwrap(), 5);
    let mut by_word: BTreeMap<u32, Vec<QKind>> = BTreeMap::new();
    let mut intros = 0;
    while let Some(q) = current_question() {
        if q.kind == QKind::Presentation {
            intros += 1;
            assert_eq!(q.task_position, 0);
            assert_eq!(q.task_total, 3);
        } else {
            by_word.entry(q.word).or_default().push(q.kind);
            assert!((1..=q.task_total).contains(&q.task_position));
            assert_eq!(q.task_total, 3);
        }
        if submit_answer(if q.kind == QKind::Presentation { String::new() } else { right(&q) }).finished {
            break;
        }
    }
    assert_eq!(intros, 5);
    assert_eq!(by_word.len(), 5);
    for kinds in by_word.values() {
        assert_eq!(kinds.len(), 3, "{kinds:?}");
        assert_eq!(kinds[0], QKind::Typing);
        assert_eq!(kinds[1], QKind::Mc);
        assert_eq!(kinds[2], QKind::Arrange);
    }
}

#[test]
fn real_politik_gets_a_sentence_with_the_word() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    load_seed(read("android/app/src/main/assets/seed.json")).unwrap();
    let level = levels()
        .into_iter()
        .map(|l| l.index)
        .find(|&ix| level_words(ix).iter().any(|w| w.term == "die Politik"))
        .expect("die Politik");
    let words = level_words(level);
    let target = words.iter().find(|w| w.term == "die Politik").unwrap().index;
    for w in &words {
        if w.index != target {
            mark_known(w.index);
        }
    }
    assert_eq!(start_learn_new(Some(level), 5, NOW).unwrap(), 1);
    let mut sentences = Vec::new();
    while let Some(q) = current_question() {
        if q.kind == QKind::Arrange {
            let sentence = q.answer.clone().expect("sentence to make");
            assert!(sentence.contains("Politik"), "not a sentence with the word: {sentence}");
            assert!(!q.tiles.is_empty());
            sentences.push(sentence);
        }
        if submit_answer(if q.kind == QKind::Presentation { String::new() } else { right(&q) }).finished {
            break;
        }
    }
    assert_eq!(sentences.len(), 1, "one sentence-making task expected: {sentences:?}");
}

#[test]
fn real_course_plays_end_to_end() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(load_seed(read("android/app/src/main/assets/seed.json")).unwrap(), 1681);
    let all = levels();
    assert_eq!(all.len(), 77);
    assert!(all.iter().all(|l| (15..=25).contains(&l.count)), "lesson sizes {:?}", all.iter().map(|l| l.count).collect::<Vec<_>>());

    // the imported Memrise progress: most words are already known, Lesson 01 is all new
    let st = stats(NOW);
    assert!(st.known > 1000, "known after import: {}", st.known);
    assert_eq!(st.new_words, 64);
    let l01: Vec<u32> = all.iter().filter(|l| l.title.starts_with("Lesson 01")).map(|l| l.index).collect();
    assert_eq!(l01.len(), 3);
    let lesson01: Vec<WordRow> = l01.iter().flat_map(|&i| level_words(i)).collect();
    assert_eq!(lesson01.len(), 64);
    assert!(lesson01.iter().all(|w| w.stage == Stage::New));

    // whole-course sessions answered correctly: every card checks out, every kind shows up
    let mut seen: BTreeMap<String, u32> = BTreeMap::new();
    for _ in 0..40 {
        start_session(None, 20, NOW).unwrap();
        let (kinds, correct, scored) = play(right);
        assert_eq!(correct, scored, "a correct answer was rejected");
        assert!(scored >= 20);
        for k in kinds {
            *seen.entry(format!("{k:?}")).or_default() += 1;
        }
    }
    for kind in ["Typing", "Hearing", "Arrange"] {
        assert!(seen.contains_key(kind), "never saw {kind}: {seen:?}");
    }
    // typing is boring: at most 10 % of all tasks (owner, 2026-10-21); the allowance carries over, +1 slack
    let typed = seen["Typing"] + seen["Hearing"];
    let tasks: u32 = seen.iter().filter(|(k, _)| k.as_str() != "Presentation").map(|(_, n)| n).sum();
    assert!(typed * 10 <= tasks + 10, "typing is {typed} of {tasks} tasks: {seen:?}");
    assert!(typed * 20 >= tasks, "typing nearly vanished: {typed} of {tasks}");

    // Lesson 01: presentation first, then multiple choice, and the stage moves up
    set_pace(PaceSettings { new_per_session: 15, interval_scale: 1.0, length_weight: 0.3 });
    start_session(Some(l01[0]), 10, NOW).unwrap();
    let first = current_question().unwrap();
    assert_eq!(first.kind, QKind::Presentation);
    assert!(first.meaning.is_some() && first.prompt_audio.is_some());
    let (kinds, correct, scored) = play(right);
    assert_eq!(correct, scored);
    assert_eq!(kinds.iter().filter(|k| **k == QKind::Presentation).count(), 10);
    assert_eq!(kinds.iter().filter(|k| **k == QKind::Mc).count(), 10);
    let advanced = level_words(l01[0]).iter().filter(|w| w.stage == Stage::Reversed).count();
    assert_eq!(advanced, 10);

    // every choice card has four unique options and contains the right one
    start_session(Some(1), 20, NOW).unwrap();
    while let Some(q) = current_question() {
        if matches!(q.kind, QKind::Mc | QKind::Reversed | QKind::AudioMc) {
            assert_eq!(q.choices.len(), 4);
            let mut u = q.choices.clone();
            u.sort();
            u.dedup();
            assert_eq!(u.len(), 4, "duplicate choices for '{}'", q.prompt);
            assert!(q.choices.contains(&right(&q)));
        }
        if q.kind == QKind::Arrange {
            assert!(q.tiles.len() >= 3);
        }
        if submit_answer(right(&q)).finished {
            break;
        }
    }

    // wrong answers demote and never panic
    start_session(None, 20, NOW).unwrap();
    let (_, correct, scored) = play(|_| "zzzz".into());
    assert_eq!(correct, 0);
    assert!(scored > 0);

    // saved state is small (only touched words) and survives a reload
    let saved = export_state();
    assert!(saved.len() < 200_000, "state file is {} bytes", saved.len());
    let before = stats(NOW);
    reset_progress();
    let applied = import_state(saved).unwrap();
    assert!(applied > 0);
    let after = stats(NOW);
    assert_eq!((before.known, before.learning, before.new_words), (after.known, after.learning, after.new_words));
    assert!(after.streak_days >= 1);
}
