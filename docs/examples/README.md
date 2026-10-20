# Bulk-import examples

How to add your own words to Merkrise (full spec: §16 of the v1 design).

## Quick version
1. Make a **tab-separated** file (`.tsv`) named `merkrise.<DeckName>.tsv`.
   - The `<DeckName>` becomes a new deck; `-` and `_` turn into spaces.
   - e.g. `merkrise.Food-Vocabulary.tsv` → deck **"Food Vocabulary"**.
2. First line is the header. Required columns: **`term`**, **`meaning`**.
   Optional: `pos`, `forms`, `literal`, `alt`, `audio`.
   - `alt` = extra accepted typed answers, separated by `|`.
   - `audio` = an mp3 filename you also drop in the `audio/` subfolder; leave it
     blank to have the app read the word aloud with German text-to-speech.
3. Save it **UTF-8** (so ä ö ü ß survive).
4. Put the file in the app's import folder on the device
   (`Documents/Merkrise/import/`; on the emulator, `adb push` it there) and tap
   **"Scan for new words"**.

Re-importing the same file just updates it — no duplicates. Split a big deck with
`merkrise.<Deck>.01.tsv`, `merkrise.<Deck>.02.tsv`, …

See `merkrise.Sample-Deck.tsv` in this folder for a working example. A `.csv`
(comma-separated) file works too if that's easier to export from a spreadsheet.
