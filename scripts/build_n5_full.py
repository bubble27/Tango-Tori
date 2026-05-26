"""
build_n5_full.py  –  Build 'Tango Tori - N5 (full).apkg' from
                     jlpt_n5_with_examples.csv (all 718 N5 words).

Every row is processed fresh — no dependency on previous build logs.
"""

import csv
import hashlib
import sys
import time
from pathlib import Path

import genanki
from tango_tori import CardData, create_card

HERE     = Path(__file__).parent
CSV_PATH = HERE / "tools" / "jmdict-builder" / ".cache" / "jlpt_n5_with_examples.csv"
OUT_APKG = HERE / "Tango Tori - N5 (full).apkg"
LOG_PATH = HERE / "n5_full_log.txt"

def _id(name: str) -> int:
    return int(hashlib.md5(name.encode()).hexdigest()[:8], 16)

MODEL_ID = _id("TangoTori_v5_model")
DECK_ID  = _id("TangoTori_N5_deck")

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
.kanji-meaning {
  font-size: 13px; color: #999; text-align: center; margin-top: 4px;
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

_model = genanki.Model(
    MODEL_ID, CardData.NOTE_TYPE_NAME,
    fields=[{"name": f} for f in CardData.FIELD_NAMES],
    templates=[{"name": "Card 1", "qfmt": _FRONT, "afmt": _BACK}],
    css=_CSS,
)


def _candidates(expression: str, reading: str) -> list[str]:
    """Word forms to try, in order: stripped expression parts, then reading parts."""
    bare = expression.lstrip("～〜~〜").strip()
    seen: dict[str, None] = {}
    for part in bare.split(";"):
        w = part.strip()
        if w: seen[w] = None
    bare_r = reading.lstrip("～〜~〜").strip()
    for part in bare_r.split(";"):
        w = part.strip()
        if w: seen.setdefault(w, None)
    return list(seen)


def main() -> None:
    sys.stdout = open(sys.stdout.fileno(), mode="w", encoding="utf-8", buffering=1)

    with open(CSV_PATH, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))

    total = len(rows)
    print(f"Processing {total} entries …\n")

    deck      = genanki.Deck(DECK_ID, "Tango Tori - N5")
    log_lines : list[str] = []
    n_ok = n_skip = 0
    t0 = time.time()

    for i, row in enumerate(rows, 1):
        expr     = row["expression"].strip()
        reading  = row["reading"].strip()
        sentence = row["example"].strip()

        if not sentence:
            print(f"[{i:3}/{total}] SKIP  {expr!r}  — no example sentence")
            log_lines.append(f"SKIP\t{expr}\tno example sentence")
            n_skip += 1
            continue

        card = None
        for word in _candidates(expr, reading):
            card = create_card(word, sentence)
            if card is not None:
                break

        if card is None:
            print(f"[{i:3}/{total}] SKIP  {expr!r}  — not found")
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

        elapsed = time.time() - t0
        eta = (total - i) / (i / elapsed) if elapsed else 0
        print(f"[{i:3}/{total}] OK    {card.word}  ({elapsed:.0f}s, ETA {eta:.0f}s)")
        log_lines.append(f"OK\t{card.word}\t{card.reading}\t{card.jlpt}")

    genanki.Package(deck).write_to_file(str(OUT_APKG))
    LOG_PATH.write_text("\n".join(log_lines), encoding="utf-8")

    elapsed = time.time() - t0
    print(f"\n{'─'*60}")
    print(f"Done in {elapsed:.1f}s")
    print(f"Cards written : {n_ok}")
    print(f"Skipped       : {n_skip}")
    print(f"Output        : {OUT_APKG}")
    if n_skip:
        print("\nSkipped:")
        for l in log_lines:
            if l.startswith("SKIP"):
                parts = l.split("\t")
                reason = parts[2] if len(parts) > 2 else ""
                sent   = parts[3] if len(parts) > 3 else ""
                print(f"  {parts[1]!r}  — {reason}" + (f"\n    sentence: {sent}" if sent else ""))


if __name__ == "__main__":
    main()
