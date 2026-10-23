# Merkrise Content Pack — the import rule

**For:** content generators (you, or an AI) **and** the coding agent building the
importer. This is the single canonical way to feed content into Merkrise. It
supersedes the earlier ad-hoc formats (the TSV in spec §16 and the folder lesson
in §16.6) — build *this* one.

**One sentence:** a **content pack** is a `.zip` holding a `pack.json` manifest
(and optional audio); the app opens it and turns **every section into a new
chapter**, so one upload can add many lessons at once.

---

## 1. Pack layout (what goes in the zip)

```
my-content.zip
├─ pack.json          REQUIRED — the manifest (all the words live here)
└─ audio/             OPTIONAL — audio files referenced by items
   ├─ 0001.mp3
   ├─ frisch.mp3
   └─ ...
```
- A **folder** with the same contents works too (unzipped). The app accepts both.
- UTF-8 everywhere (so ä ö ü ß survive). Audio may be `.mp3`, `.wav`, `.m4a`, `.ogg`.
- A pack may contain **one or many sections**; each becomes its own chapter.

## 2. `pack.json` schema

```json
{
  "format": "merkrise-pack/1",
  "name": "Advanced Phrasal Verbs",        // pack name (optional; defaults to zip name)
  "language": "de",                         // optional, default "de"
  "sections": [
    {
      "title": "Separable Verbs I",         // REQUIRED — becomes one chapter
      "items": [
        {
          "term": "aufstehen",              // REQUIRED — the German
          "meaning": "to get up",           // REQUIRED — the English
          "pos": "v",                       // optional — part of speech
          "examples": [                     // optional — example sentences (German)
            "Ich stehe jeden Tag um 7 Uhr auf."
          ],
          "audio": "aufstehen.mp3",         // optional — a filename inside audio/
          "alt": ["aufstehen", "auf stehen"]// optional — extra accepted typed answers
        }
      ]
    }
  ]
}
```

### Field rules
| field | where | required | notes |
|---|---|---|---|
| `format` | top | recommended | `"merkrise-pack/1"` — lets us version the format |
| `name` | top | no | pack/course name; defaults to the zip filename |
| `language` | top | no | ISO code; default `de` |
| `sections[].title` | section | **yes** | the chapter name shown in the app |
| `items[].term` | item | **yes** | German word/phrase |
| `items[].meaning` | item | **yes** | English meaning |
| `items[].pos` | item | no | `n`/`v`/`adj`/… (used for better distractors) |
| `items[].examples` | item | no | list of example sentences (shown as context) |
| `items[].audio` | item | no | filename present in `audio/`; omit for no audio |
| `items[].alt` | item | no | extra accepted typed answers (umlaut variants etc.) |

A word with **no `audio`** simply has no play button (its audio stage falls
through to typing per `LEARNING_ENGINE.md` §9). So **AI-generated text-only packs
are fully valid** — audio is always optional.

## 3. How the app ingests a pack (the rule the importer follows)

1. Open the zip (or folder). Reject if `pack.json` is missing/invalid, and report
   the row/section at fault — never fail silently.
2. For **each section**: create a new chapter (`levels` row) with the next free
   `level` index, titled `section.title` (prefixed by `name` if you like).
3. For **each item**: insert a `words` row (`source = 'pack'`) with term, meaning,
   pos, examples, `alt` → accepted typed answers. If `audio` is set, copy that
   file into the app's audio store and link it.
4. New words enter the learning engine as **new** (`rung = -1`), so they go through
   the normal plant → ladder flow (`LEARNING_ENGINE.md`).
5. **Idempotent:** importing the same pack again updates matching rows (match on
   section title + term) and skips unchanged files — never duplicates. Track
   imported packs by a content hash.
6. Malformed items are skipped and listed in an import report; the rest import.

### Where packs are dropped
- On device: a user-visible folder (e.g. `Documents/Merkrise/packs/`), imported
  on launch and via a **"Import pack"** button.
- In this repo (dev): drop packs in **`resources/packs/`**; the build-time
  importer `scripts/import-pack.py` merges them into the app data (reference
  implementation of the rule above — the runtime importer mirrors it).

## 4. Generating a pack with AI (the practical workflow)

Ask the model to output exactly a `pack.json` matching §2 — e.g.:

> "Produce a `merkrise-pack/1` JSON. One section titled `<topic>`, ~20 items,
> each with `term` (German), `meaning` (English), `pos`, and one `examples`
> sentence in German. No audio. Return only the JSON."

Save it as `pack.json`, (optionally add an `audio/` folder), zip, drop in
`resources/packs/`, run the importer. That's the whole loop — many sections,
many packs, all fed the same way.

## 5. Minimal example
A tiny, valid example is in `docs/examples/pack-example/pack.json` (text-only,
two sections). Copy it as a starting template.

## 6. Status / build note
The pack **format and the build-time importer** (`scripts/import-pack.py`) are the
deliverable here. The **on-device importer** (open zip in the app → create
chapters) is a coding task for the app milestone; it must implement §3 exactly so
device imports and dev imports behave identically.
