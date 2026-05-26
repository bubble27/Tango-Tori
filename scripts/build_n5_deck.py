"""
build_n5_deck.py  –  Generate 'Tango Tori - N5.apkg' from n5_vocab_raw.csv.

Reads the JLPT N5 vocabulary CSV (Shift-JIS, from passjapanesetest.com),
runs create_card() on every word+example pair, and writes a complete Anki
package using the exact same note type, templates, and CSS as the Android app.

Usage:
    python build_n5_deck.py
Output:
    Tango Tori - N5.apkg   (ready to import into Anki / AnkiDroid)
    n5_build_log.txt        (one line per word: OK / SKIP + reason)
"""

import csv
import hashlib
import sys
import time
from pathlib import Path

import genanki

# tango_tori.py must be in the same directory
from tango_tori import CardData, create_card

# ── Paths ─────────────────────────────────────────────────────────────────────
HERE       = Path(__file__).parent
CSV_PATH   = HERE / "n5_vocab_raw.csv"
OUT_APKG   = HERE / "Tango Tori - N5.apkg"
LOG_PATH   = HERE / "n5_build_log.txt"

# ── Stable model / deck IDs (deterministic hash so re-imports don't duplicate) ─
def _id(name: str) -> int:
    return int(hashlib.md5(name.encode()).hexdigest()[:8], 16)

MODEL_ID = _id("TangoTori_v5_model")
DECK_ID  = _id("TangoTori_N5_deck")

# ── Anki templates + CSS  (identical to AnkiCardRepository.kt) ────────────────
_FRONT = """\
<div class="card-body">
  <div class="word-block">{{Word}}</div>
</div>"""

_BACK = """\
<div class="card-body">
  <div class="word-block">{{WordRuby}}</div>
  <hr>
  {{#KanjiBreakdown}}<div class="kanji-section">{{KanjiBreakdown}}</div>
  <hr>{{/KanjiBreakdown}}
  <div class="meaning">{{Meaning}}</div>
  <hr>
  {{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
</div>"""

_CSS = """\
.card {
  font-family: -apple-system, "Hiragino Sans", "Yu Gothic UI", "Noto Sans CJK JP", sans-serif;
  padding: 16px;
}
.card-body { text-align: center; }
.word-block {
  text-align: center;
  font-size: 56px;
  margin: 28px 0 10px 0;
  line-height: 1.9;
}
.word-block ruby rt {
  font-size: 0.36em;
  color: #5A6B75;
  line-height: 1.0;
  padding-bottom: 4px;
}
.sentence {
  margin: 18px auto 10px auto;
  font-size: 22px;
  line-height: 2.1;
  max-width: 92%;
  text-align: center;
}
.sentence a {
  color: inherit;
  text-decoration: none;
}
.sentence rt { color: #5A6B75; font-size: 0.55em; }
.sentence .target-word { color: #C0392B; font-weight: normal; }
.sentence .target-word a { color: #C0392B; }
hr {
  border: none;
  border-top: 1px solid #DDD;
  margin: 18px 0;
}
.meaning { text-align: left; }
.pos-label {
  font-size: 14px;
  color: #78909C;
  font-style: italic;
  margin: 10px 0 6px 0;
}
.senses {
  padding-left: 24px;
  margin: 0 0 10px 0;
}
.senses li {
  font-size: 18px;
  line-height: 1.55;
  margin-bottom: 6px;
}
.senses li::marker {
  color: #999;
}
.kanji-section {
  display: flex;
  flex-direction: row;
  justify-content: center;
  flex-wrap: wrap;
  gap: 16px;
  margin: 12px 0;
}
.kanji-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 80px;
}
.kanji-reading {
  font-size: 15px;
  color: #5A6B75;
  line-height: 1.0;
  margin-bottom: 2px;
  min-height: 18px;
}
.kanji-char {
  font-size: 40px;
  line-height: 1.0;
}
.kanji-meaning {
  font-size: 13px;
  color: #999;
  text-align: center;
  margin-top: 4px;
}
.night_mode hr { border-top-color: #444; }
.night_mode .pos-label,
.night_mode .word-block ruby rt,
.night_mode .sentence rt,
.night_mode .kanji-reading { color: #90A4AE; }
.night_mode .senses li::marker { color: #BBB; }
.night_mode .sentence .target-word,
.night_mode .sentence .target-word a { color: #E07B6A; }
"""

# ── Note model ────────────────────────────────────────────────────────────────
_model = genanki.Model(
    MODEL_ID,
    CardData.NOTE_TYPE_NAME,
    fields=[{"name": f} for f in CardData.FIELD_NAMES],
    templates=[{"name": "Card 1", "qfmt": _FRONT, "afmt": _BACK}],
    css=_CSS,
)


# ── CSV helpers ───────────────────────────────────────────────────────────────

def _clean_word(raw: str) -> str:
    """Strip the 〜 counter prefix and surrounding whitespace."""
    return raw.lstrip("～〜~〜").strip()


def _japanese_sentence(example_field: str) -> str:
    """
    The Example column contains:
        Japanese sentence.\nEnglish translation.
    Return only the Japanese part.
    """
    return example_field.split("\n")[0].strip()


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    # Redirect stdout to UTF-8 so Japanese prints correctly on Windows
    sys.stdout = open(sys.stdout.fileno(), mode="w", encoding="utf-8", buffering=1)

    with open(CSV_PATH, encoding="shift_jis") as f:
        rows = list(csv.DictReader(f))

    total = len(rows)
    print(f"Processing {total} entries from {CSV_PATH.name} …\n")

    deck  = genanki.Deck(DECK_ID, "Tango Tori - N5")
    log_lines: list[str] = []
    n_ok = n_skip = 0
    t0 = time.time()

    for i, row in enumerate(rows, 1):
        raw_word  = row.get("Kanji", "").strip()
        word      = _clean_word(raw_word)
        kana      = _clean_word(row.get("Kana", ""))
        example   = row.get("Example", "").strip()

        # ── Guard: no example sentence ──
        if not example:
            reason = "no example sentence"
            print(f"[{i:3}/{total}] SKIP  {raw_word!r}  — {reason}")
            log_lines.append(f"SKIP\t{raw_word}\t{reason}")
            n_skip += 1
            continue

        sentence = _japanese_sentence(example)
        if not sentence:
            reason = "empty Japanese sentence"
            print(f"[{i:3}/{total}] SKIP  {raw_word!r}  — {reason}")
            log_lines.append(f"SKIP\t{raw_word}\t{reason}")
            n_skip += 1
            continue

        # ── Try kanji form, then kana fallback ──
        card = create_card(word, sentence)
        if card is None and kana and kana != word:
            card = create_card(kana, sentence)

        if card is None:
            reason = "word not found in JMdict / sentence"
            print(f"[{i:3}/{total}] SKIP  {raw_word!r}  — {reason}")
            log_lines.append(f"SKIP\t{raw_word}\t{reason}\t{sentence}")
            n_skip += 1
            continue

        # ── Add to deck ──
        note = genanki.Note(
            model=_model,
            fields=card.to_field_array(),
            tags=sorted(card.tags()),
            # Stable GUID: same word + sentence always maps to same note.
            guid=genanki.guid_for(card.word, card.sentence_raw),
        )
        deck.add_note(note)
        n_ok += 1

        elapsed = time.time() - t0
        rate    = i / elapsed
        eta     = (total - i) / rate if rate else 0
        print(
            f"[{i:3}/{total}] OK    {card.word}"
            f"  ({elapsed:.0f}s elapsed, ETA {eta:.0f}s)"
        )
        log_lines.append(f"OK\t{card.word}\t{card.reading}\t{card.jlpt}")

    # ── Write package ──
    genanki.Package(deck).write_to_file(str(OUT_APKG))
    LOG_PATH.write_text("\n".join(log_lines), encoding="utf-8")

    elapsed = time.time() - t0
    print(f"\n{'─'*60}")
    print(f"Done in {elapsed:.1f}s")
    print(f"Cards written : {n_ok}")
    print(f"Skipped       : {n_skip}")
    print(f"Output        : {OUT_APKG}")
    print(f"Log           : {LOG_PATH}")


if __name__ == "__main__":
    main()
