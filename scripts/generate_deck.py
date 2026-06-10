"""
generate_deck.py — Export a sample Tango Tori .apkg deck.

Usage:
    python scripts/generate_deck.py [output.apkg]

Creates a deck with a small mix of Japanese and Chinese cards using the same
note types, templates, and CSS that the Android app registers in AnkiDroid.
"""

from __future__ import annotations

import sys
import io
from pathlib import Path

# Force UTF-8 output so CJK characters don't crash on Windows cp1252 terminals.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import genanki

# Make tango_tori importable when run from project root or scripts/
_HERE = Path(__file__).parent.resolve()
sys.path.insert(0, str(_HERE))

from tango_tori import (
    CardData,
    ImageCardData,
    Language,
    create_card,
    create_image_card,
)

# ─── Anki model definitions ───────────────────────────────────────────────────
# IDs are arbitrary large integers; must be stable across exports so Anki
# recognises the same model on reimport without duplicating the note type.
_MODEL_ID_SENTENCE = 1_607_392_319   # "Tango Tori v5"
_MODEL_ID_IMAGE    = 1_607_392_320   # "Tango Tori Image v1"

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

_IMAGE_BACK_TEMPLATE = """\
<div class="card-body">
  <div class="word-block">{{WordRuby}}</div>
  <hr>
  {{#KanjiBreakdown}}<div class="kanji-section">{{KanjiBreakdown}}</div>
  <hr>{{/KanjiBreakdown}}
  <div class="meaning">{{Meaning}}</div>
  <hr>
  {{#Image}}<div class="image-section">{{Image}}</div>{{/Image}}
  {{#UserSentence}}<div class="user-sentence">{{UserSentence}}</div>{{/UserSentence}}
</div>"""

_CARD_CSS = """\
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

_IMAGE_CSS = _CARD_CSS + """
.image-section { margin: 16px auto; text-align: center; }
.image-section img { max-width: 100%; max-height: 300px; border-radius: 8px; object-fit: contain; }
.user-sentence {
  margin: 14px auto 8px auto; font-size: 22px; line-height: 1.8;
  max-width: 92%; text-align: center; color: #555;
}
.night_mode .user-sentence { color: #CCC; }"""


def _sentence_model() -> genanki.Model:
    return genanki.Model(
        _MODEL_ID_SENTENCE,
        CardData.NOTE_TYPE_NAME,
        fields=[{"name": f} for f in CardData.FIELD_NAMES],
        templates=[{
            "name": "Card 1",
            "qfmt": _FRONT_TEMPLATE,
            "afmt": _BACK_TEMPLATE,
        }],
        css=_CARD_CSS,
    )


def _image_model() -> genanki.Model:
    return genanki.Model(
        _MODEL_ID_IMAGE,
        ImageCardData.NOTE_TYPE_NAME,
        fields=[{"name": f} for f in ImageCardData.FIELD_NAMES],
        templates=[{
            "name": "Card 1",
            "qfmt": _FRONT_TEMPLATE,
            "afmt": _IMAGE_BACK_TEMPLATE,
        }],
        css=_IMAGE_CSS,
    )


def _sanitize_tags(tags: set[str]) -> list[str]:
    # Anki tags may not contain spaces — replace with hyphens (e.g. "hsk 1" → "hsk-1").
    return sorted(t.replace(" ", "-") for t in tags)


def _note_from_card(card: CardData, model: genanki.Model) -> genanki.Note:
    return genanki.Note(
        model=model,
        fields=card.to_field_array(),
        tags=_sanitize_tags(card.tags()),
    )


def _note_from_image_card(card: ImageCardData, model: genanki.Model) -> genanki.Note:
    return genanki.Note(
        model=model,
        fields=card.to_field_array(),
        tags=_sanitize_tags(card.tags()),
    )


# ─── Card definitions ─────────────────────────────────────────────────────────

_SENTENCE_CARDS = [
    # (word, sentence)
    ("猫",      "この猫はとてもかわいい。"),
    ("食べる",   "毎日食べることが好きです。"),
    ("勉強する", "毎日日本語を勉強することが大切です。"),
    ("学习",    "我每天都在努力学习汉语。"),
    ("朋友",    "他是我最好的朋友。"),
]

_IMAGE_CARDS = [
    # (word, image_html_or_filename, language)
    ("山",    "",  Language.JAPANESE),
    ("猫",    "",  Language.CHINESE_SIMPLIFIED),
]


def build_deck() -> tuple[genanki.Deck, list]:
    deck = genanki.Deck(deck_id=2_059_400_110, name="Tango Tori Sample")
    sent_model  = _sentence_model()
    image_model = _image_model()
    media_files: list[str] = []

    print("Building sentence cards…")
    for word, sentence in _SENTENCE_CARDS:
        card = create_card(word, sentence)
        if card is None:
            print(f"  SKIP {word!r} — not found in dictionary")
            continue
        deck.add_note(_note_from_card(card, sent_model))
        lang = "JA" if not any(0x4E00 <= ord(c) <= 0x9FFF for c in sentence[:2]) or any(
            0x3040 <= ord(c) <= 0x30FF for c in sentence) else "ZH"
        print(f"  OK   [{lang}] {word}")

    print("Building image cards…")
    for word, image, lang in _IMAGE_CARDS:
        card = create_image_card(word, image=image, language=lang)
        if card is None:
            print(f"  SKIP {word!r} — not found in dictionary")
            continue
        deck.add_note(_note_from_image_card(card, image_model))
        print(f"  OK   [IMG] {word}")

    return deck, media_files


def main() -> None:
    out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        _HERE.parent / "TangoTori_Sample.apkg"
    )
    deck, media_files = build_deck()
    pkg = genanki.Package(deck, media_files=media_files)
    pkg.write_to_file(str(out_path))
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()
