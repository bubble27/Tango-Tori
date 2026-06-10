"""
generate_n5_deck.py — Build a full JLPT N5 sentence-card deck.

Reads jlpt_n5_sentences.csv (columns: word, reading, meaning, character,
example_sentence) and creates one "Tango Tori v5" card per row using the
full format: kanji breakdown, furigana, sentence HTML with ruby and links.

Verb forms: words given in polite ます form are converted to their dictionary
form by running them through Sudachi, which handles all conjugation patterns
correctly. The JMdict entry for the dictionary form is used for the card.

Usage:
    python scripts/generate_n5_deck.py [output.apkg]
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent.resolve()))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import genanki
from tango_tori import (
    CardData,
    PartOfSpeech,
    TokenWithEntry,
    _get_db,
    _get_tokenizer,
    _japanese_breakdown,
    build_furigana,
    build_furigana_html,
    build_meaning_html,
    build_sentence_html,
)

# ─── Paths ────────────────────────────────────────────────────────────────────

_HERE      = Path(__file__).parent.resolve()
_PROJ_ROOT = _HERE.parent
_CSV       = _PROJ_ROOT / "Anki JLPT" / "jlpt_n5_sentences.csv"

# ─── Anki model (Tango Tori v5) ───────────────────────────────────────────────

_MODEL_ID = 1_607_392_319

_FRONT_TEMPLATE = """\
<div class="card-body">
  <div class="word-block">{{Word}}</div>
</div>"""

_BACK_TEMPLATE = """\
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
  text-align: center; font-size: 56px; margin: 28px 0 10px 0; line-height: 1.9;
}
.word-block ruby rt {
  font-size: 0.36em; color: #5A6B75; line-height: 1.0; padding-bottom: 4px;
}
.sentence {
  margin: 18px auto 10px auto; font-size: 22px; line-height: 2.1;
  max-width: 92%; text-align: center;
}
.sentence a { color: inherit; text-decoration: none; }
.sentence rt { color: #5A6B75; font-size: 0.55em; }
.sentence .target-word { color: #C0392B; font-weight: normal; }
.sentence .target-word a { color: #C0392B; }
hr { border: none; border-top: 1px solid #DDD; margin: 18px 0; }
.meaning { text-align: left; }
.pos-label { font-size: 14px; color: #78909C; font-style: italic; margin: 10px 0 6px 0; }
.senses { padding-left: 24px; margin: 0 0 10px 0; }
.senses li { font-size: 18px; line-height: 1.55; margin-bottom: 6px; }
.senses li::marker { color: #999; }
.kanji-section {
  display: flex; flex-direction: row; justify-content: center;
  flex-wrap: wrap; gap: 16px; margin: 12px 0;
}
.kanji-tile { display: flex; flex-direction: column; align-items: center; min-width: 80px; }
.kanji-reading { font-size: 15px; color: #5A6B75; line-height: 1.0; margin-bottom: 2px; min-height: 18px; }
.kanji-char { font-size: 40px; line-height: 1.0; }
.kanji-meaning { font-size: 13px; color: #999; text-align: center; margin-top: 4px; }
.night_mode hr { border-top-color: #444; }
.night_mode .pos-label,
.night_mode .word-block ruby rt,
.night_mode .sentence rt,
.night_mode .kanji-reading { color: #90A4AE; }
.night_mode .senses li::marker { color: #BBB; }
.night_mode .sentence .target-word,
.night_mode .sentence .target-word a { color: #E07B6A; }"""


def _build_model() -> genanki.Model:
    return genanki.Model(
        _MODEL_ID,
        CardData.NOTE_TYPE_NAME,
        fields=[{"name": f} for f in CardData.FIELD_NAMES],
        templates=[{
            "name": "Card 1",
            "qfmt": _FRONT_TEMPLATE,
            "afmt": _BACK_TEMPLATE,
        }],
        css=_CSS,
    )


# ─── Word cleaning ────────────────────────────────────────────────────────────

def _clean_word(word: str) -> str:
    """Strip 〜 prefix, （...） parentheticals, and take first option if slash-separated."""
    w = word.lstrip("〜")
    w = re.sub(r"[（(][^）)]*[）)]", "", w).strip()
    w = w.split("／")[0].split("/")[0].strip()
    return w or word.lstrip("〜").strip()


def _clean_reading(reading: str) -> str:
    """Take the first option when the CSV uses slash notation (きゅう／く)."""
    return reading.split("／")[0].split("/")[0].strip()


# ─── Card builder ─────────────────────────────────────────────────────────────

_UNLINKABLE = frozenset({
    PartOfSpeech.PUNCTUATION,
    PartOfSpeech.PARTICLE,
    PartOfSpeech.AUXILIARY_VERB,
})


def _lookup_entry(cleaned: str, reading: str, db, tok):
    """
    Try multiple lookup strategies in order:
      1. Cleaned word directly in JMdict
      2. Dictionary form obtained by running the word through Sudachi
         (handles ます verbs → dict form correctly)
      3. Cleaned reading directly
      4. Dictionary form of the reading via Sudachi
    """
    # 1. Direct
    entries = db.lookup(cleaned)
    if entries:
        return entries

    # 2. Sudachi dict form of the word
    word_tokens = tok.tokenize(cleaned)
    if word_tokens:
        df = word_tokens[0].dictionary_form
        if df and df != cleaned:
            entries = db.lookup(df)
            if entries:
                return entries

    # 3. Direct reading
    entries = db.lookup(reading)
    if entries:
        return entries

    # 4. Sudachi dict form of the reading
    read_tokens = tok.tokenize(reading)
    if read_tokens:
        df = read_tokens[0].dictionary_form
        if df and df != reading:
            entries = db.lookup(df)
            if entries:
                return entries

    return []


def _build_card(entry, sentence: str, db, tok) -> CardData:
    """Build a CardData from a JMdict entry + sentence string."""
    sent_tokens = tok.tokenize(sentence)

    tokens_with_entries = []
    for t in sent_tokens:
        if t.part_of_speech in _UNLINKABLE:
            eid = None
        else:
            lu = db.lookup(t.dictionary_form, t.dictionary_reading)
            eid = lu[0].id if lu else None
        tokens_with_entries.append(TokenWithEntry(token=t, entry_id=eid))

    word_str    = entry.headword
    reading_str = entry.primary_reading
    furigana    = build_furigana(word_str, reading_str)
    breakdown   = _japanese_breakdown(word_str, furigana, db)

    return CardData(
        word=word_str,
        reading=reading_str,
        furigana=furigana,
        word_ruby=build_furigana_html(furigana),
        meaning_html=build_meaning_html(entry.senses),
        part_of_speech=", ".join(entry.senses[0].part_of_speech) if entry.senses else "",
        jlpt=entry.jlpt_level or "",
        is_common=entry.is_common,
        sentence_html=build_sentence_html(tokens_with_entries, entry.id),
        sentence_raw=sentence,
        source="JLPT N5",
        kanji_breakdown_html=breakdown,
    )


def _sanitize_tags(tags: set[str]) -> list[str]:
    return sorted(t.replace(" ", "-") for t in tags)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    out_path = (
        Path(sys.argv[1]) if len(sys.argv) > 1
        else _PROJ_ROOT / "TangoTori_JLPT_N5.apkg"
    )

    print("Loading N5 CSV…")
    with open(_CSV, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    print(f"  {len(rows)} rows")

    print("Warming up Sudachi + JMdict…")
    db  = _get_db(None)
    tok = _get_tokenizer(None)
    # Force Sudachi init
    tok.tokenize("日本語")
    print("  Ready")

    model = _build_model()
    deck  = genanki.Deck(deck_id=2_059_400_113, name="Tango Tori — JLPT N5")

    counts = {"ok": 0, "skip": 0}
    skipped = []

    print(f"\nBuilding {len(rows)} cards…")
    for i, row in enumerate(rows, 1):
        word_csv    = row["word"]
        reading_csv = _clean_reading(row["reading"])
        sentence    = row["example_sentence"]

        cleaned = _clean_word(word_csv)
        entries = _lookup_entry(cleaned, reading_csv, db, tok)

        if not entries:
            counts["skip"] += 1
            skipped.append(word_csv)
            continue

        card = _build_card(entries[0], sentence, db, tok)
        note = genanki.Note(
            model=model,
            fields=card.to_field_array(),
            tags=_sanitize_tags(card.tags() | {"jlpt-n5"}),
        )
        deck.add_note(note)
        counts["ok"] += 1

        if i % 10 == 0 or i == len(rows):
            print(f"  {i}/{len(rows)}  {word_csv}", flush=True)

    print(f"\n  Cards created : {counts['ok']}")
    print(f"  Skipped       : {counts['skip']}")

    print("\nWriting package…")
    pkg = genanki.Package(deck)
    pkg.write_to_file(str(out_path))
    print(f"Done → {out_path.resolve()}")

    if skipped:
        print("  Skipped words:")
        for w in skipped:
            print(f"    {w}")


if __name__ == "__main__":
    main()
