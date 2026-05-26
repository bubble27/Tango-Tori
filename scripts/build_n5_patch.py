"""
build_n5_patch.py  –  Add missing/new N5 words to 'Tango Tori - N5.apkg'.

Reads jlpt_n5_with_examples.csv, processes only the words that were either
previously skipped or are brand-new vs the original CSV, and writes
'Tango Tori - N5 (patch).apkg'.

Import the patch deck into Anki — because it uses the same deck name and note
type as the original, the new notes land in the same deck alongside the 645
existing cards.
"""

import csv
import sys
from pathlib import Path

import genanki

from tango_tori import CardData, create_card

# ── Paths ─────────────────────────────────────────────────────────────────────
HERE        = Path(__file__).parent
OLD_CSV     = HERE / "n5_vocab_raw.csv"
NEW_CSV     = HERE / "tools" / "jmdict-builder" / ".cache" / "jlpt_n5_with_examples.csv"
OLD_LOG     = HERE / "n5_build_log.txt"
OUT_APKG    = HERE / "Tango Tori - N5 (patch).apkg"
LOG_PATH    = HERE / "n5_patch_log.txt"

# Same IDs as the main deck so cards land in the right place
import hashlib
def _id(name: str) -> int:
    return int(hashlib.md5(name.encode()).hexdigest()[:8], 16)

MODEL_ID = _id("TangoTori_v5_model")
DECK_ID  = _id("TangoTori_N5_deck")

# ── Templates + CSS (identical to build_n5_deck.py) ──────────────────────────
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
.sentence a { color: inherit; text-decoration: none; }
.sentence rt { color: #5A6B75; font-size: 0.55em; }
.sentence .target-word { color: #C0392B; font-weight: normal; }
.sentence .target-word a { color: #C0392B; }
hr { border: none; border-top: 1px solid #DDD; margin: 18px 0; }
.meaning { text-align: left; }
.pos-label {
  font-size: 14px; color: #78909C; font-style: italic; margin: 10px 0 6px 0;
}
.senses { padding-left: 24px; margin: 0 0 10px 0; }
.senses li { font-size: 18px; line-height: 1.55; margin-bottom: 6px; }
.senses li::marker { color: #999; }
.kanji-section {
  display: flex; flex-direction: row; justify-content: center;
  flex-wrap: wrap; gap: 16px; margin: 12px 0;
}
.kanji-tile {
  display: flex; flex-direction: column; align-items: center; min-width: 80px;
}
.kanji-reading {
  font-size: 15px; color: #5A6B75; line-height: 1.0;
  margin-bottom: 2px; min-height: 18px;
}
.kanji-char { font-size: 40px; line-height: 1.0; }
.kanji-meaning { font-size: 13px; color: #999; text-align: center; margin-top: 4px; }
.night_mode hr { border-top-color: #444; }
.night_mode .pos-label,
.night_mode .word-block ruby rt,
.night_mode .sentence rt,
.night_mode .kanji-reading { color: #90A4AE; }
.night_mode .senses li::marker { color: #BBB; }
.night_mode .sentence .target-word,
.night_mode .sentence .target-word a { color: #E07B6A; }
"""

_model = genanki.Model(
    MODEL_ID,
    CardData.NOTE_TYPE_NAME,
    fields=[{"name": f} for f in CardData.FIELD_NAMES],
    templates=[{"name": "Card 1", "qfmt": _FRONT, "afmt": _BACK}],
    css=_CSS,
)


def _candidate_words(expression: str, reading: str) -> list[str]:
    """
    Return the forms to try with create_card(), in priority order.
    Handles:
      - tilde prefixes:  ～か月  →  か月
      - multi-form:      いい; よい  →  [いい, よい]
      - reading fallback
    """
    # Strip tilde variants
    cleaned = expression.lstrip("～〜~〜").strip()

    candidates: list[str] = []
    # Expand semicolon-separated multi-forms
    for part in cleaned.split(";"):
        w = part.strip()
        if w:
            candidates.append(w)

    # Also try the plain reading
    clean_reading = reading.lstrip("～〜~〜").strip()
    for part in clean_reading.split(";"):
        w = part.strip()
        if w and w not in candidates:
            candidates.append(w)

    return candidates


def main() -> None:
    sys.stdout = open(sys.stdout.fileno(), mode="w", encoding="utf-8", buffering=1)

    # ── Load the set of expressions that were already processed ──────────────
    old_expressions: set[str] = set()
    with open(OLD_CSV, encoding="shift_jis") as f:
        for row in csv.DictReader(f):
            old_expressions.add(row["Kanji"].strip().lstrip("〜").strip())

    skipped_words: set[str] = set()
    for line in OLD_LOG.read_text(encoding="utf-8").splitlines():
        if line.startswith("SKIP"):
            skipped_words.add(line.split("\t")[1])

    # ── Load new CSV ──────────────────────────────────────────────────────────
    with open(NEW_CSV, encoding="utf-8-sig") as f:
        new_rows = list(csv.DictReader(f))

    # ── Decide which rows to process ─────────────────────────────────────────
    to_process = []
    for row in new_rows:
        expr    = row["expression"].strip()
        reading = row["reading"].strip()
        example = row["example"].strip()

        # Tilde-stripped form used for comparison
        bare = expr.lstrip("～〜~〜").strip()
        # For multi-form expressions take the first part for comparison
        bare_first = bare.split(";")[0].strip()

        is_previously_skipped = (bare_first in skipped_words or
                                  reading.lstrip("～〜~〜").split(";")[0].strip() in skipped_words)
        is_brand_new = bare_first not in old_expressions

        if is_previously_skipped or is_brand_new:
            to_process.append(row)

    print(f"New file: {len(new_rows)} rows")
    print(f"To process: {len(to_process)} entries\n")

    deck      = genanki.Deck(DECK_ID, "Tango Tori - N5")
    log_lines : list[str] = []
    n_ok = n_skip = 0

    for i, row in enumerate(to_process, 1):
        expr    = row["expression"].strip()
        reading = row["reading"].strip()
        sentence = row["example"].strip()

        if not sentence:
            print(f"[{i:3}/{len(to_process)}] SKIP  {expr!r}  — no example sentence")
            log_lines.append(f"SKIP\t{expr}\tno example sentence")
            n_skip += 1
            continue

        card = None
        for word in _candidate_words(expr, reading):
            card = create_card(word, sentence)
            if card is not None:
                break

        if card is None:
            print(f"[{i:3}/{len(to_process)}] SKIP  {expr!r}  — not found in JMdict/sentence")
            log_lines.append(f"SKIP\t{expr}\tnot found\t{sentence}")
            n_skip += 1
            continue

        note = genanki.Note(
            model=_model,
            fields=card.to_field_array(),
            tags=sorted(card.tags()),
            guid=genanki.guid_for(card.word, card.sentence_raw),
        )
        deck.add_note(note)
        n_ok += 1
        print(f"[{i:3}/{len(to_process)}] OK    {card.word}")
        log_lines.append(f"OK\t{card.word}\t{card.reading}\t{card.jlpt}")

    genanki.Package(deck).write_to_file(str(OUT_APKG))
    LOG_PATH.write_text("\n".join(log_lines), encoding="utf-8")

    print(f"\n{'─'*60}")
    print(f"Cards written : {n_ok}")
    print(f"Skipped       : {n_skip}")
    print(f"Output        : {OUT_APKG}")
    if n_skip:
        print("\nSkipped entries:")
        for l in log_lines:
            if l.startswith("SKIP"):
                parts = l.split("\t")
                print(f"  {parts[1]}: {parts[2]}")


if __name__ == "__main__":
    main()
