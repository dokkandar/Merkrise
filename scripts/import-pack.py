#!/usr/bin/env python3
"""Build-time importer for Merkrise content packs (docs/CONTENT_PACK_FORMAT.md).
Reference implementation of the ingestion rule: merge every section of a pack
into the app's bundled seed.json as new chapters, copying any audio into assets.
Idempotent: re-importing a pack replaces its own chapters (matched by pack name).

Usage:
    python3 -I scripts/import-pack.py <pack.zip | pack-folder>
    python3 -I scripts/import-pack.py resources/packs/my-content.zip
Run `sh scripts/bundle-assets.sh` first so assets/seed.json exists.
"""
import json, os, re, sys, shutil, zipfile, hashlib, tempfile

PROJ = "/home/HSI/workspace/merkrise"
ASSETS = os.path.join(PROJ, "android", "app", "src", "main", "assets")
SEED = os.path.join(ASSETS, "seed.json")
AUDIO_DIR = os.path.join(ASSETS, "audio")

def die(msg):
    print("ERROR:", msg, file=sys.stderr); sys.exit(1)

def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", (s or "pack").lower()).strip("-") or "pack"

def load_pack(path):
    """Return (pack_dict, src_dir) where src_dir holds audio/. Handles zip or folder."""
    if os.path.isdir(path):
        pj = os.path.join(path, "pack.json")
        if not os.path.isfile(pj):
            die(f"no pack.json in folder {path}")
        return json.load(open(pj, encoding="utf-8")), path
    if zipfile.is_zipfile(path):
        tmp = tempfile.mkdtemp(prefix="merkrise-pack-")
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():                     # guard against zip-slip
                dest = os.path.realpath(os.path.join(tmp, name))
                if not dest.startswith(os.path.realpath(tmp) + os.sep) and dest != os.path.realpath(tmp):
                    die(f"unsafe path in zip: {name}")
            z.extractall(tmp)
        # pack.json may be at root or one level down
        for root, _, files in os.walk(tmp):
            if "pack.json" in files:
                return json.load(open(os.path.join(root, "pack.json"), encoding="utf-8")), root
        die("no pack.json found in zip")
    die(f"not a folder or zip: {path}")

def main():
    if len(sys.argv) < 2:
        die("usage: import-pack.py <pack.zip | pack-folder>")
    if not os.path.isfile(SEED):
        die(f"{SEED} missing — run scripts/bundle-assets.sh first")

    pack, src = load_pack(sys.argv[1])
    sections = pack.get("sections")
    if not isinstance(sections, list) or not sections:
        die("pack has no 'sections'")
    pack_name = pack.get("name") or os.path.splitext(os.path.basename(sys.argv[1]))[0]
    pslug = slug(pack_name)
    audio_src = os.path.join(src, "audio")

    seed = json.load(open(SEED, encoding="utf-8"))
    # idempotent: drop this pack's previously-imported chapters
    seed["levels"] = [lv for lv in seed["levels"] if lv.get("pack") != pack_name]
    next_idx = max((lv["index"] for lv in seed["levels"]), default=0) + 1

    os.makedirs(AUDIO_DIR, exist_ok=True)
    added_words = added_audio = skipped = 0
    errors = []
    for si, section in enumerate(sections):
        title = (section.get("title") or "").strip()
        if not title:
            errors.append(f"section {si}: missing title"); continue
        items = []
        for ii, it in enumerate(section.get("items", []) or []):
            term = (it.get("term") or "").strip()
            meaning = (it.get("meaning") or "").strip()
            if not term or not meaning:
                skipped += 1; errors.append(f"[{title}] item {ii}: missing term/meaning"); continue
            audio = []
            af = it.get("audio")
            if af:
                srcf = os.path.join(audio_src, af)
                if os.path.isfile(srcf):
                    dest = f"{pslug}_{os.path.basename(af)}"
                    shutil.copyfile(srcf, os.path.join(AUDIO_DIR, dest))
                    audio = [dest]; added_audio += 1
                else:
                    errors.append(f"[{title}] {term}: audio '{af}' not found")
            examples = [e for e in (it.get("examples") or []) if isinstance(e, str)]
            alt = [a for a in (it.get("alt") or []) if isinstance(a, str)] or [term]
            items.append({
                "id": None, "level": next_idx, "pos": len(items),
                "term": term, "meaning": meaning, "item_type": "word",
                "pos_tag": it.get("pos"), "all_forms": None,
                "literal": "; ".join(examples) if examples else None,
                "audio": audio, "typing_alternatives": alt, "memrise_progress": None,
            })
            added_words += 1
        # stable synthetic ids (hash of pack+title+term) so re-imports are consistent
        for it in items:
            h = hashlib.sha1(f"{pack_name}|{title}|{it['term']}".encode()).hexdigest()[:12]
            it["id"] = 2_000_000_000 + int(h, 16) % 1_000_000_000
        seed["levels"].append({
            "index": next_idx, "level_id": 20000 + next_idx,
            "title": f"{title}", "pack": pack_name,
            "item_count": len(items), "items": items,
        })
        next_idx += 1

    seed["meta"]["level_count"] = len(seed["levels"])
    seed["meta"]["item_count"] = sum(len(lv["items"]) for lv in seed["levels"])
    json.dump(seed, open(SEED, "w", encoding="utf-8"), ensure_ascii=False)

    print(f"imported pack '{pack_name}': {len(sections)} section(s), "
          f"{added_words} words, {added_audio} audio, {skipped} skipped")
    print(f"seed now: {seed['meta']['level_count']} levels / {seed['meta']['item_count']} words")
    if errors:
        print("notes:")
        for e in errors[:20]:
            print("  -", e)

if __name__ == "__main__":
    main()
