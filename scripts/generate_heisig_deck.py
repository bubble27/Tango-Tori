"""
generate_heisig_deck.py — Build a full Heisig Hanzi image-card deck.

Reads simplified_heisig_cosmi.txt, matches keywords to images in D:\\@Memes
(exact first, then morphological/fuzzy), and exports TangoTori_Heisig.apkg
in Heisig order. Cards with no image leave the field blank.

Usage:
    python scripts/generate_heisig_deck.py [output.apkg]
"""

from __future__ import annotations

import csv
import difflib
import io
import re
import sys
from dataclasses import replace
from pathlib import Path
from typing import Optional

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, str(Path(__file__).parent.resolve()))

import genanki
from tango_tori import (
    ImageCardData,
    Language,
    build_pinyin,
    build_pinyin_html,
    create_image_card,
)

# ─── Paths ────────────────────────────────────────────────────────────────────

_HERE      = Path(__file__).parent.resolve()
_PROJ_ROOT = _HERE.parent
_HEISIG    = _PROJ_ROOT / "Anki JLPT" / "Heisig Hanzi" / "simplified_heisig_cosmi.txt"
_MEMES_DIR = Path(r"D:\@Memes")
_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".jfif"}

_FUZZY_WARNING = (
    "Fuzzily matched image to keyword. "
    "There is a possibility this mem is wrong."
)

# ─── Anki model ───────────────────────────────────────────────────────────────

_MODEL_ID = 1_607_392_320

_FRONT = '<div class="card-body"><div class="word-block">{{Word}}</div></div>'
_BACK  = """\
<div class="card-body">
  <div class="word-block">{{WordRuby}}</div>
  <hr>
  <div class="meaning">{{Meaning}}</div>
  <hr>
  {{#Image}}<div class="image-section">{{Image}}</div>{{/Image}}
  {{#UserSentence}}<div class="user-sentence">{{UserSentence}}</div>{{/UserSentence}}
</div>"""

_CSS = """\
.card {
  font-family: -apple-system, "Hiragino Sans", "Yu Gothic UI", "Noto Sans CJK JP", sans-serif;
  padding: 16px;
}
.card-body { text-align: center; }
.word-block { font-size: 56px; margin: 28px 0 10px 0; line-height: 1.9; }
.word-block ruby rt { font-size: 0.36em; color: #5A6B75; line-height: 1.0; padding-bottom: 4px; }
hr { border: none; border-top: 1px solid #DDD; margin: 18px 0; }
.meaning { text-align: left; }
.pos-label { font-size: 14px; color: #78909C; font-style: italic; margin: 10px 0 6px 0; }
.heisig-keyword { font-size: 20px; margin: 4px 0 10px 0; }
.senses { padding-left: 24px; margin: 0 0 10px 0; }
.senses li { font-size: 18px; line-height: 1.55; margin-bottom: 6px; }
.senses li::marker { color: #999; }
.image-section { margin: 16px auto; text-align: center; }
.image-section img { max-width: 100%; max-height: 300px; border-radius: 8px; object-fit: contain; }
.user-sentence { margin: 14px auto 8px; font-size: 16px; line-height: 1.6;
  max-width: 92%; text-align: center; color: #888; font-style: italic; }
.night_mode hr { border-top-color: #444; }
.night_mode .pos-label, .night_mode .word-block ruby rt { color: #90A4AE; }
.night_mode .senses li::marker { color: #BBB; }
.night_mode .user-sentence { color: #999; }"""


def _build_model() -> genanki.Model:
    return genanki.Model(
        _MODEL_ID,
        ImageCardData.NOTE_TYPE_NAME,
        fields=[{"name": f} for f in ImageCardData.FIELD_NAMES],
        templates=[{"name": "Card 1", "qfmt": _FRONT, "afmt": _BACK}],
        css=_CSS,
    )


# ─── Image index ──────────────────────────────────────────────────────────────

def _normalize(s: str) -> str:
    """Lowercase, unify apostrophes, strip trailing apostrophe/whitespace."""
    s = s.lower().strip()
    s = re.sub(r"[''`‘’]", "'", s)
    return s.rstrip("'").strip()


def _index_memes() -> dict[str, Path]:
    """Normalized-stem → best-extension Path."""
    priority = {ext: i for i, ext in enumerate([".jpg", ".jpeg", ".png", ".gif", ".webp", ".jfif"])}
    best: dict[str, tuple[int, Path]] = {}
    for f in _MEMES_DIR.iterdir():
        if not f.is_file() or f.suffix.lower() not in _IMAGE_EXTS:
            continue
        stem  = _normalize(f.stem)
        score = priority.get(f.suffix.lower(), 99)
        if stem not in best or score < best[stem][0]:
            best[stem] = (score, f)
    return {stem: path for stem, (_, path) in best.items()}


# ─── Fuzzy matching ───────────────────────────────────────────────────────────

def _candidates(kw: str) -> list[str]:
    """Rule-based morphological variants of a normalized keyword."""
    out: list[str] = []

    # plural / singular
    if kw.endswith("s") and not kw.endswith("ss"):
        out.append(kw[:-1])
    else:
        out.append(kw + "s")
    if kw.endswith("ies") and len(kw) > 4:
        out.append(kw[:-3] + "y")
    if kw.endswith("y") and len(kw) > 2:
        out.append(kw[:-1] + "ies")
    if kw.endswith("es") and len(kw) > 3:
        out.append(kw[:-2])

    # adverb -ly ↔ adjective
    if kw.endswith("ly") and len(kw) > 4:
        out.append(kw[:-2])
    else:
        out.append(kw + "ly")

    # past / participle
    if kw.endswith("ed"):
        out += [kw[:-2], kw[:-1]]   # "worked"→"work", "excited"→"excite"
    else:
        out += [kw + "d", kw + "ed"]

    # present participle
    if kw.endswith("ing") and len(kw) > 5:
        out += [kw[:-3], kw[:-3] + "e"]

    # multi-word: trim last word / add/remove trailing s on last word
    parts = kw.split()
    if len(parts) >= 2:
        out.append(" ".join(parts[:-1]))
        last = parts[-1]
        out.append(" ".join(parts[:-1] + [last + "s"]))
        out.append(" ".join(parts[:-1] + [last + "es"]))
        if last.endswith("s") and not last.endswith("ss"):
            out.append(" ".join(parts[:-1] + [last[:-1]]))

    return [c for c in out if c and len(c) >= 2]


def _fuzzy_match(keyword: str, stems: dict[str, Path],
                 stem_list_long: list[str]) -> tuple[Optional[Path], bool]:
    """
    Returns (path, is_fuzzy). Tries normalized exact match first, then
    rule-based morphological candidates, then difflib (long keywords only).
    """
    kw = _normalize(keyword)

    # Exact after normalization
    if kw in stems:
        return stems[kw], False

    # Rule-based candidates
    for c in _candidates(kw):
        if c in stems:
            return stems[c], True

    # Difflib fallback: only for keywords ≥8 chars, matched stem ≥6 chars,
    # length within 3 chars to avoid embedded-substring false positives.
    if len(kw) >= 8:
        nearby = [s for s in stem_list_long if abs(len(s) - len(kw)) <= 3]
        m = difflib.get_close_matches(kw, nearby, n=1, cutoff=0.90)
        if m:
            return stems[m[0]], True

    return None, False


# ─── Heisig data ──────────────────────────────────────────────────────────────

def _load_heisig() -> list[dict]:
    rows = []
    with open(_HEISIG, encoding="utf-8-sig") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            hanzi   = row.get("Hanzi",   "").strip()
            keyword = row.get("Keyword", "").strip()
            number  = row.get("Number",  "").strip()
            pinyin  = row.get("Pinyin",  "").strip()
            if hanzi and keyword and number:
                rows.append({
                    "hanzi":   hanzi,
                    "keyword": keyword,
                    "number":  int(number),
                    "pinyin":  pinyin,
                })
    return rows


def _clean_pinyin(raw: str) -> str:
    """'{sān,sàn}' → 'sān';  'yī' → 'yī'."""
    m = re.match(r"\{([^,}]+)", raw.strip())
    return m.group(1).strip() if m else raw.strip()


# ─── Card builders ────────────────────────────────────────────────────────────

def _prepend_keyword(meaning_html: str, keyword: str) -> str:
    block = (
        f'<div class="pos-label">Heisig keyword</div>'
        f'<div class="heisig-keyword">{keyword}</div>'
    )
    return block + meaning_html


def _fallback_card(hanzi: str, pinyin: str, keyword: str,
                   image_html: str, user_sentence: str) -> ImageCardData:
    pinyin_markup = build_pinyin(hanzi, pinyin)
    return ImageCardData(
        word=hanzi,
        reading=pinyin,
        furigana=pinyin_markup,
        word_ruby=build_pinyin_html(pinyin_markup),
        meaning_html=(
            f'<div class="pos-label">Heisig keyword</div>'
            f'<div class="heisig-keyword">{keyword}</div>'
        ),
        part_of_speech="",
        jlpt="",
        is_common=False,
        image_html=image_html,
        user_sentence=user_sentence,
        source="",
        kanji_breakdown_html="",
    )


def _sanitize_tags(tags: set[str]) -> list[str]:
    return sorted(t.replace(" ", "-") for t in tags)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    out_path = (
        Path(sys.argv[1]) if len(sys.argv) > 1
        else _PROJ_ROOT / "TangoTori_Heisig.apkg"
    )

    print("Loading Heisig database…")
    heisig_rows = _load_heisig()
    print(f"  {len(heisig_rows)} entries")

    print("Indexing D:\\@Memes…")
    stems = _index_memes()
    stem_list_long = sorted(s for s in stems if len(s) >= 8)
    print(f"  {len(stems)} unique image stems")

    model = _build_model()
    deck  = genanki.Deck(deck_id=2_059_400_112, name="Tango Tori — Heisig Hanzi")
    media_files: list[str] = []
    media_seen:  set[str]  = set()

    counts = {"exact": 0, "fuzzy": 0, "no_image": 0, "fallback": 0}

    print(f"\nBuilding {len(heisig_rows)} cards…")
    for i, row in enumerate(heisig_rows, 1):
        hanzi, keyword, number = row["hanzi"], row["keyword"], row["number"]
        pinyin = _clean_pinyin(row["pinyin"])

        img_path, is_fuzzy = _fuzzy_match(keyword, stems, stem_list_long)

        if img_path:
            img_html      = img_path.name
            user_sentence = _FUZZY_WARNING if is_fuzzy else ""
            if str(img_path) not in media_seen:
                media_files.append(str(img_path))
                media_seen.add(str(img_path))
            if is_fuzzy:
                counts["fuzzy"] += 1
            else:
                counts["exact"] += 1
        else:
            img_html      = ""
            user_sentence = ""
            counts["no_image"] += 1

        card = create_image_card(
            hanzi,
            image=img_html,
            language=Language.CHINESE_SIMPLIFIED,
            include_breakdown=False,
        )

        if card is None:
            card = _fallback_card(hanzi, pinyin, keyword, img_html, user_sentence)
            counts["fallback"] += 1
        else:
            card = replace(
                card,
                meaning_html=_prepend_keyword(card.meaning_html, keyword),
                user_sentence=user_sentence,
            )

        note = genanki.Note(
            model=model,
            fields=card.to_field_array(),
            tags=_sanitize_tags(card.tags() | {"heisig"}),
        )
        deck.add_note(note)

        if i % 300 == 0 or i == len(heisig_rows):
            print(f"  {i}/{len(heisig_rows)}")

    print(f"\n  exact image match  : {counts['exact']}")
    print(f"  fuzzy image match  : {counts['fuzzy']}")
    print(f"  no image           : {counts['no_image']}")
    print(f"  CEDICT miss        : {counts['fallback']}")
    print(f"  bundled media files: {len(media_files)}")

    print("\nWriting package…")
    pkg = genanki.Package(deck, media_files=media_files)
    pkg.write_to_file(str(out_path))
    print(f"Done → {out_path.resolve()}")


if __name__ == "__main__":
    main()
