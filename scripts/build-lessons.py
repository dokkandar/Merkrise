"""Re-chunk seed.json levels into lessons of 15-25 items (spec 2026-10-21 §2).

    python -I scripts/build-lessons.py capture/seed-merged.json android/app/src/main/assets/seed.json
    python -I scripts/build-lessons.py --test

Input levels carry a `set` tag (A2, Lesson 01, each upload); lessons never mix sets.
Every item keeps its own `level` and `id`: the core's progress key is "{item.level}:{term}".
"""
import json
import math
import statistics
import sys
from collections import Counter, defaultdict

MIN, MAX = 15, 25
HERE = __file__.replace("\\", "/").rsplit("/", 1)[0]


def bucket(item):
    n = len(item["term"].split())
    return 0 if n <= 2 else 1 if n <= 5 else 2  # short / mid / long


def topic_of(title):
    head, _, last = title.rpartition(" ")
    return head if head and last.isdigit() else title


def even(total, n):
    return [total // n + (1 if k < total % n else 0) for k in range(n)]


def deal(items, sizes):
    """Fill lessons of the given sizes bucket by bucket, each bucket spread in proportion to size;
    Memrise order kept inside each lesson. Equal sizes = plain round-robin."""
    pos = {id(it): p for p, it in enumerate(items)}
    out = [[] for _ in sizes]
    for b in (0, 1, 2):
        for it in items:
            if bucket(it) == b:
                j = min((k for k in range(len(sizes)) if len(out[k]) < sizes[k]),
                        key=lambda k: (len(out[k]) / sizes[k], k))
                out[j].append(it)
    return [sorted(l, key=lambda it: pos[id(it)]) for l in out]


def chunk_set(levels):
    """One source set -> [(topic names, items)] (spec §2.2 rules 1-5).
    A carry-over (< MIN items of earlier topics) joins only the next topic's first lesson."""
    topics = {}
    for lv in levels:
        topics.setdefault(topic_of(lv["title"]), []).extend(lv["items"])
    lessons, carry, carried = [], [], []
    for topic, items in topics.items():
        total = len(carry) + len(items)
        if total < MIN:
            carry, carried = carry + items, carried + [topic]
            continue
        n = math.ceil(total / MAX)
        if total / n < MIN:  # 26..29: one lesson of MIN, the rest of this topic rolls forward
            take = MIN - len(carry)
            lessons.append((carried + [topic], carry + items[:take]))
            carry, carried = items[take:], [topic]
            continue
        sizes = even(total, n)
        sizes[0] -= len(carry)
        parts = deal(items, sizes)
        lessons.append((carried + [topic], carry + parts[0]))
        lessons += [([topic], p) for p in parts[1:]]
        carry, carried = [], []
    if carry:  # tail < MIN: re-deal it with the set's last lesson into 1 or 2 lessons
        prev_names, prev = lessons.pop() if lessons else ([], [])
        names = prev_names + [t for t in carried if t not in prev_names]
        pool = prev + carry
        lessons += [(names, p) for p in deal(pool, even(len(pool), 1 if len(pool) <= MAX else 2))]
    return lessons


def chunk(levels):
    """All levels -> lessons [{title, topic, set, items}]; consecutive levels of one `set` chunk together."""
    out, groups = [], []
    for lv in levels:
        s = lv.get("set") or "level %d" % lv["index"]
        if groups and groups[-1][0] == s:
            groups[-1][1].append(lv)
        else:
            groups.append((s, [lv]))
    for s, group in groups:
        count = Counter()
        for names, items in chunk_set(group):
            base = " + ".join(names)
            count[base] += 1
            out.append({"title": "%s %d" % (base, count[base]), "topic": names[0], "set": s, "items": items})
    return out


def build(seed):
    meta = seed.get("meta", {})
    if meta.get("chunked"):
        sys.exit("already chunked: build from capture/seed-merged.json, not from the output")
    seen = Counter((i["level"], i["term"]) for lv in seed["levels"] for i in lv["items"])
    dup = [k for k, c in seen.items() if c > 1]
    if dup:
        sys.exit("duplicate (level, term) would share one progress key: %r" % dup[:5])
    lessons = chunk(seed["levels"])
    levels = [{"index": k, "title": l["title"], "topic": l["topic"], "set": l["set"],
               "item_count": len(l["items"]), "items": l["items"]} for k, l in enumerate(lessons, 1)]
    return {"meta": dict(meta, chunked=True, level_count=len(levels)), "levels": levels}


# ------------------------------------------------------------------ self-test

def _items(n, level=1):
    # term lengths cycle 1..8 words so every bucket is present
    return [{"term": " ".join(["w%d" % k] * (1 + (k * 7) % 8)), "level": level} for k in range(n)]


def _long_share(items):
    return sum(1 for i in items if len(i["term"].split()) >= 6) / len(items)


def self_test():
    for n, want in [(9, [9]), (14, [14]), (15, [15]), (25, [25]), (26, [13, 13]), (29, [15, 14]),
                    (30, [15, 15]), (49, [25, 24]), (64, [22, 21, 21])]:
        got = [len(l["items"]) for l in chunk([{"index": 1, "title": "Upload", "set": "U", "items": _items(n)}])]
        assert got == want, (n, got, want)

    src = json.load(open(HERE + "/../capture/seed-merged.json", encoding="utf-8"))
    out = build(src)
    lessons = out["levels"]
    key = lambda i: (i["level"], i["term"])
    assert Counter(key(i) for l in src["levels"] for i in l["items"]) == \
        Counter(key(i) for l in lessons for i in l["items"]), "items changed"
    assert sum(len(l["items"]) for l in lessons) == 1681
    set_of = {l["index"]: l["set"] for l in src["levels"]}
    for l in lessons:
        assert len({set_of[i["level"]] for i in l["items"]}) == 1, ("mixes sets", l["title"])
    a2 = [l for l in lessons if l["set"] == "A2"]
    l01 = [l for l in lessons if l["set"] == "Lesson 01"]
    assert len(a2) == 74 and all(MIN <= len(l["items"]) <= MAX for l in a2), [len(l["items"]) for l in a2]
    assert [len(l["items"]) for l in l01] == [22, 21, 21]
    assert [l["index"] for l in lessons] == list(range(1, len(lessons) + 1))
    assert all(l["item_count"] == len(l["items"]) and l["topic"] and l["title"] for l in lessons)
    assert out["meta"]["chunked"] is True and out["meta"]["level_count"] == len(lessons)

    # a carry-over joins only the NEXT topic's first lesson: no topic disappears, no topic is smeared
    assert {topic_of(l["title"]) for l in src["levels"]} == {l["topic"] for l in lessons}
    merged = Counter(l["title"].rsplit(" ", 1)[0] for l in lessons if " + " in l["title"])
    assert all(c == 1 for c in merged.values()), merged

    by_topic = defaultdict(list)
    for l in lessons:
        by_topic[l["topic"]].append(_long_share(l["items"]))
    spread = statistics.mean(statistics.pstdev(v) for v in by_topic.values() if len(v) > 1)
    assert spread < 0.03, spread

    for bad, msg in [({"meta": {"chunked": True}, "levels": []}, "already chunked"),
                     ({"meta": {}, "levels": [{"index": 1, "title": "T", "set": "U",
                                               "items": [{"term": "a", "level": 1}, {"term": "a", "level": 1}]}]},
                      "duplicate")]:
        try:
            build(bad)
            raise AssertionError("accepted: " + msg)
        except SystemExit as e:
            assert msg in str(e.code), e.code
    print("ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--test"]:
        self_test()
    elif len(sys.argv) == 3:
        out = build(json.load(open(sys.argv[1], encoding="utf-8")))
        with open(sys.argv[2], "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False)
        print(len(out["levels"]), "lessons,", sum(len(l["items"]) for l in out["levels"]), "items")
    else:
        sys.exit(__doc__)
