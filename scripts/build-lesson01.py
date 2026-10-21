#!/usr/bin/env python3
"""Merge the resources/01 folder lesson into the app's bundled data as a chapter.
Reads the German headwords from resources/01/Files, pairs them with the English
translations below, copies each word's native-speaker audio into the app assets,
and appends a level to android/app/src/main/assets/seed.json. No-ops if the
lesson folder isn't present (it's personal/git-ignored)."""
import json, os, shutil, sys

PROJ = "/home/HSI/workspace/merkrise"
LESSON = os.path.join(PROJ, "resources", "01")
FILES = os.path.join(LESSON, "Files")
HELMUT = os.path.join(LESSON, "helmut", "german voices")
SIMON = os.path.join(LESSON, "simon")
ASSETS = os.path.join(PROJ, "android", "app", "src", "main", "assets")
AUDIO = os.path.join(ASSETS, "audio")
SEED = os.path.join(ASSETS, "seed.json")

EN = {
    1: "newly in love", 2: "excitement / nervousness", 3: "butterflies in the stomach",
    4: "to assess / gauge", 5: "sexual fantasies", 6: "community", 7: "real talk",
    8: "there was a spark", 9: "self-critical", 10: "dissatisfied", 11: "to feel / sense",
    12: "it fills me with joy", 13: "sexual intercourse", 14: "physical contact", 15: "input",
    16: "reserved / reticent", 17: "further developed / evolved", 18: "gastrointestinal complaints",
    19: "embarrassing", 20: "to panic", 21: "tutorial", 22: "to open a relationship",
    23: "jealousy", 24: "fear of losing the person", 25: "to manage / pull off",
    26: "to game (video games)", 27: "problem / issue", 28: "(kitchen) sink",
    29: "to do the dishes", 30: "a nasty question", 31: "to make decisions",
    32: "to cheat (be unfaithful)", 33: "happiest moment", 34: "opera ball", 35: "crash course",
    36: "standard (ballroom) dances", 37: "basic steps", 38: "to make out / snog",
    39: "a little kiss / peck", 40: "stable (slang: awesome)", 41: "to cruise around",
    42: "embellished / decorated", 43: "married", 44: "fear of loss / abandonment",
    45: "grew up / was raised", 46: "burdened / stressed", 47: "upbringing / parenting",
    48: "depression", 49: "ready", 50: "dining table", 51: "to hold back tears",
    52: "to avoid", 53: "grateful", 54: "to propose (marriage)", 55: "double-edged sword",
    56: "ready", 57: "to jump for joy", 58: "to be patient / wait", 59: "love letter",
    60: "gesture", 61: "globe", 62: "in the slightest / remotely",
    63: "to lay the world at someone's feet", 64: "to find out",
}

if not os.path.isdir(FILES):
    print("resources/01 not present — skipping lesson merge")
    sys.exit(0)

def read(name):
    try:
        return open(os.path.join(FILES, name), encoding="utf-8").read().strip()
    except FileNotFoundError:
        return ""

os.makedirs(AUDIO, exist_ok=True)
items = []
copied = 0
for n in range(1, 65):
    term = read(f"{n}.txt")
    if not term:
        continue
    meaning = EN.get(n, "")
    # headword audio: prefer helmut, else simon
    audio = []
    for src in (os.path.join(HELMUT, f"{n}.wav"), os.path.join(SIMON, f"{n}.wav")):
        if os.path.isfile(src):
            dest_name = f"l01_{n}.wav"
            shutil.copyfile(src, os.path.join(AUDIO, dest_name))
            audio = [dest_name]
            copied += 1
            break
    examples = [e for e in (read(f"{n}.1.txt"), read(f"{n}.2.txt")) if e]
    items.append({
        "id": 1_000_000 + n,          # synthetic ids, well clear of Memrise ids
        "level": 52,
        "pos": n - 1,
        "term": term,
        "meaning": meaning,
        "item_type": "word",
        "pos_tag": None,
        "all_forms": None,
        "literal": "; ".join(examples) if examples else None,
        "audio": audio,
        "typing_alternatives": [term],
        "memrise_progress": None,
    })

level = {"index": 52, "level_id": 10052, "title": "Lesson 01 · Dating & Feelings",
         "item_count": len(items), "items": items}

seed = json.load(open(SEED, encoding="utf-8"))
# drop any previous lesson-52 so re-runs are idempotent
seed["levels"] = [lv for lv in seed["levels"] if lv.get("index") != 52]
seed["levels"].append(level)
seed["meta"]["item_count"] = sum(len(lv["items"]) for lv in seed["levels"])
seed["meta"]["level_count"] = len(seed["levels"])
json.dump(seed, open(SEED, "w", encoding="utf-8"), ensure_ascii=False)

print(f"merged Lesson 01: {len(items)} words, {copied} audio files -> assets")
print(f"seed now has {seed['meta']['level_count']} levels, {seed['meta']['item_count']} words")
