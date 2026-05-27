#!/usr/bin/env python3
"""
build_cedict.py — builds cedict.db for the Tango Tori Android app.

Usage
-----
1. Download CC-CEDICT from https://www.mdbg.net/chinese/dictionary?page=cc-cedict
   Save it as cedict_ts.u8 (the default download name) next to this script.

2. Run:
       python build_cedict.py

3. The output cedict.db will appear in app/src/main/assets/cedict.db.
   Rebuild the app after this step.

CC-CEDICT license: Creative Commons Attribution-Share Alike 3.0
https://creativecommons.org/licenses/by-sa/3.0/
"""

import re
import sqlite3
import os
import sys
import csv

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
CEDICT_INPUT = os.path.join(SCRIPT_DIR, "cedict_ts.u8")
OUTPUT_DB = os.path.join(PROJECT_DIR, "app", "src", "main", "assets", "cedict.db")

# ── HSK 2021 (3.0) word list — loaded from hsk30.csv ─────────────────────────
# Source: https://github.com/tonghuikang/HSK-3.0-words-list
# 11 000+ words across levels 1-7 (level 7 = combined HSK 7-9).

HSK_WORDS: dict[str, str] = {}

_HSK_CSV = os.path.join(SCRIPT_DIR, "hsk30.csv")
if not os.path.exists(_HSK_CSV):
    print(f"ERROR: HSK word list not found at:\n  {_HSK_CSV}")
    print("Download it from:")
    print("  https://raw.githubusercontent.com/tonghuikang/HSK-3.0-words-list/main/chinese_words.csv")
    print("Save it as hsk30.csv next to this script, then re-run.")
    sys.exit(1)

with open(_HSK_CSV, encoding="utf-8", newline="") as _f:
    for _row in csv.DictReader(_f):
        _simp = _row["simplified"].strip()
        _level = _row["level"].strip()
        # Some entries have alternate forms separated by |; index each form.
        for _form in _simp.split("|"):
            _form = _form.strip()
            if _form and _form not in HSK_WORDS:
                HSK_WORDS[_form] = f"HSK {_level}"

# ── Pinyin number → tone-mark conversion ─────────────────────────────────────

_TONE_MARKS = {
    'a': ['ā', 'á', 'ǎ', 'à'],
    'e': ['ē', 'é', 'ě', 'è'],
    'i': ['ī', 'í', 'ǐ', 'ì'],
    'o': ['ō', 'ó', 'ǒ', 'ò'],
    'u': ['ū', 'ú', 'ǔ', 'ù'],
    'ü': ['ǖ', 'ǘ', 'ǚ', 'ǜ'],
}


def _find_tone_pos(syllable: str) -> int:
    """Return the index of the vowel that gets the tone mark."""
    s = syllable.lower()
    for v in ('a', 'e'):
        idx = s.find(v)
        if idx >= 0:
            return idx
    idx = s.find('ou')
    if idx >= 0:
        return idx
    for i in range(len(s) - 1, -1, -1):
        if s[i] in 'iouü':
            return i
    return -1


def convert_syllable(syllable: str) -> str:
    """Convert a single number-tone syllable to tone-mark form."""
    if not syllable:
        return syllable
    if not syllable[-1].isdigit():
        return syllable
    tone = int(syllable[-1])
    base = syllable[:-1].replace('u:', 'ü').replace('v', 'ü')
    if tone == 5 or tone == 0:
        return base
    pos = _find_tone_pos(base)
    if pos < 0:
        return base
    vowel = base[pos].lower()
    marks = _TONE_MARKS.get(vowel, [])
    if not marks or tone < 1 or tone > 4:
        return base
    marked = marks[tone - 1]
    if base[pos].isupper():
        marked = marked.upper()
    return base[:pos] + marked + base[pos + 1:]


def convert_pinyin(pinyin_numbers: str) -> str:
    """Convert space-separated number-tone pinyin string to tone marks."""
    return ' '.join(convert_syllable(s) for s in pinyin_numbers.strip().split())


# ── CC-CEDICT parser ──────────────────────────────────────────────────────────

LINE_RE = re.compile(
    r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/$'
)


def parse_cedict(path: str):
    """Yield (traditional, simplified, pinyin_numbers, definitions_list) tuples."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            m = LINE_RE.match(line)
            if not m:
                continue
            traditional, simplified, pinyin_raw, defs_raw = m.groups()
            # Skip entries that are purely latin (romanisations, abbreviations).
            if all(ord(c) < 0x3000 or c.isspace() for c in simplified):
                continue
            defs = [d.strip() for d in defs_raw.split('/') if d.strip()]
            yield traditional, simplified, pinyin_raw, defs


# ── Database builder ─────────────────────────────────────────────────────────

def build(input_path: str, output_path: str) -> None:
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    c = conn.cursor()

    c.executescript("""
        CREATE TABLE cedict_entry (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            traditional    TEXT    NOT NULL,
            simplified     TEXT    NOT NULL,
            pinyin_numbers TEXT    NOT NULL,
            pinyin_marks   TEXT    NOT NULL,
            hsk_level      TEXT,
            is_common      INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE cedict_sense (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_id   INTEGER NOT NULL REFERENCES cedict_entry(id),
            gloss      TEXT    NOT NULL
        );
        CREATE INDEX idx_cedict_simplified  ON cedict_entry(simplified);
        CREATE INDEX idx_cedict_traditional ON cedict_entry(traditional);
        CREATE INDEX idx_cedict_sense_entry ON cedict_sense(entry_id);
    """)

    entry_rows = []
    sense_pairs = []  # (entry_index_in_list, gloss)
    entry_id = 0

    print(f"Parsing {input_path} …")
    for traditional, simplified, pinyin_numbers, defs in parse_cedict(input_path):
        pinyin_marks = convert_pinyin(pinyin_numbers)
        hsk = HSK_WORDS.get(simplified)
        is_common = 1 if hsk in ('HSK 1', 'HSK 2', 'HSK 3') else 0
        entry_id += 1
        entry_rows.append((entry_id, traditional, simplified, pinyin_numbers, pinyin_marks, hsk, is_common))
        for g in defs:
            sense_pairs.append((entry_id, g))

        if entry_id % 20000 == 0:
            print(f"  {entry_id} entries …")

    print(f"Inserting {entry_id} entries …")
    c.executemany(
        "INSERT INTO cedict_entry (id, traditional, simplified, pinyin_numbers, pinyin_marks, hsk_level, is_common) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        entry_rows,
    )
    print(f"Inserting {len(sense_pairs)} senses …")
    c.executemany(
        "INSERT INTO cedict_sense (entry_id, gloss) VALUES (?, ?)",
        sense_pairs,
    )

    conn.commit()
    conn.close()
    size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"Done -> {output_path}  ({size_mb:.1f} MB)")


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    if not os.path.exists(CEDICT_INPUT):
        print(f"ERROR: CC-CEDICT file not found at:\n  {CEDICT_INPUT}\n")
        print("Download it from https://www.mdbg.net/chinese/dictionary?page=cc-cedict")
        print("Save the extracted cedict_ts.u8 file next to this script, then re-run.")
        sys.exit(1)
    build(CEDICT_INPUT, OUTPUT_DB)
