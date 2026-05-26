"""
tango_tori.py – Python port of Tango Tori's Anki-card creation pipeline.

Faithfully replicates every field the Android app produces, including
furigana markup, ruby HTML, meaning HTML, sentence HTML with per-token ruby
and target-word highlighting, and the per-kanji breakdown tiles.

Requirements
------------
    pip install sudachipy sudachi-dictionary-core

The JMdict database and Sudachi dictionary are resolved automatically from
the app's asset directory (relative to this file). Override via arguments if
you've moved them.

Quick start
-----------
    from tango_tori import create_card

    card = create_card(
        word="食べる",
        sentence="毎日食べるものが美味しい。",
    )
    print(card.word)          # 食べる
    print(card.word_ruby)     # <ruby>食<rt>た</rt></ruby>べる
    print(card.meaning_html)  # <div class="pos-label">…</div>…
    print(card.to_field_array())   # list of 12 strings for AnkiConnect
    print(card.tags())        # {'tango-tori', 'n5', 'v1', …}
"""

from __future__ import annotations

import json
import sqlite3
import urllib.parse
from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Callable, Optional

# ── Default asset paths (relative to this file) ───────────────────────────────
_HERE        = Path(__file__).parent.resolve()
_DEFAULT_DB  = _HERE / "app" / "src" / "main" / "assets" / "jmdict.db"
_DEFAULT_DIC = _HERE / "app" / "src" / "main" / "assets" / "sudachi" / "system_core.dic"


# ═════════════════════════════════════════════════════════════════════════════
# Unicode helpers
# ═════════════════════════════════════════════════════════════════════════════

def _kata2hira(s: str) -> str:
    """Katakana → hiragana (ァ–ヶ range). Matches KatakanaToHiragana.kt."""
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)


def _is_kanji(c: str) -> bool:
    n = ord(c)
    return (0x3400 <= n <= 0x4DBF or   # CJK Extension A
            0x4E00 <= n <= 0x9FFF or   # CJK Unified Ideographs
            0xF900 <= n <= 0xFAFF)     # CJK Compatibility Ideographs


def _is_ideograph(c: str) -> bool:
    """Same range check used by FuriganaHtmlBuilder."""
    return _is_kanji(c)


def _html_escape(s: str) -> str:
    """Mirrors HtmlEscape.kt — only the five essential escapes."""
    return (s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace('"', "&quot;")
             .replace("'", "&#39;"))


def _is_punct_char(c: str) -> bool:
    """Mirrors PosMapper.isPunctuationChar()."""
    n = ord(c)
    if 0x3000 <= n <= 0x303F: return True   # CJK Symbols and Punctuation
    if 0x2000 <= n <= 0x206F: return True   # General Punctuation
    return c in r"""!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""


# ═════════════════════════════════════════════════════════════════════════════
# Domain models  (mirrors app/domain/models/*.kt)
# ═════════════════════════════════════════════════════════════════════════════

@dataclass
class Gloss:
    text: str
    language: str = "eng"


@dataclass
class Sense:
    part_of_speech: list[str]   # raw JMdict codes, e.g. ["n", "v5r"]
    glosses: list[Gloss]
    misc: Optional[str] = None
    field: Optional[str] = None
    dialect: Optional[str] = None


@dataclass
class KanjiForm:
    text: str


@dataclass
class Reading:
    text: str
    no_kanji: bool = False


@dataclass
class DictEntry:
    id: int
    is_common: bool
    jlpt_level: Optional[str]
    kanji_forms: list[KanjiForm]
    readings: list[Reading]
    senses: list[Sense]

    @property
    def headword(self) -> str:
        if self.kanji_forms:
            return self.kanji_forms[0].text
        return self.readings[0].text if self.readings else ""

    @property
    def primary_reading(self) -> str:
        return self.readings[0].text if self.readings else ""


class PartOfSpeech(Enum):
    NOUN            = auto()
    VERB            = auto()
    I_ADJECTIVE     = auto()
    NA_ADJECTIVE    = auto()
    PARTICLE        = auto()
    ADVERB          = auto()
    AUXILIARY_VERB  = auto()
    CONJUNCTION_OTHER = auto()
    PUNCTUATION     = auto()


@dataclass
class Token:
    surface: str
    dictionary_form: str
    reading: str             # hiragana, surface (conjugated) reading
    dictionary_reading: str
    part_of_speech: PartOfSpeech
    raw_pos_tag: str         # comma-joined Sudachi POS fields


@dataclass
class TokenWithEntry:
    token: Token
    entry_id: Optional[int]


@dataclass
class CardData:
    """
    Mirrors CardData.kt — the 12 Anki fields plus helper methods.

    Field order in to_field_array() is identical to the app:
        Word, Reading, Furigana, WordRuby, Meaning, PartOfSpeech,
        JLPT, IsCommon, Sentence, SentenceRaw, Source, KanjiBreakdown
    """
    word: str
    reading: str
    furigana: str
    word_ruby: str
    meaning_html: str
    part_of_speech: str
    jlpt: str
    is_common: bool
    sentence_html: str
    sentence_raw: str
    source: str = ""
    kanji_breakdown_html: str = ""

    NOTE_TYPE_NAME = "Tango Tori v5"
    FIELD_NAMES = [
        "Word", "Reading", "Furigana", "WordRuby", "Meaning",
        "PartOfSpeech", "JLPT", "IsCommon", "Sentence",
        "SentenceRaw", "Source", "KanjiBreakdown",
    ]

    def to_field_array(self) -> list[str]:
        return [
            self.word, self.reading, self.furigana, self.word_ruby,
            self.meaning_html, self.part_of_speech, self.jlpt,
            "1" if self.is_common else "0",
            self.sentence_html, self.sentence_raw, self.source,
            self.kanji_breakdown_html,
        ]

    def tags(self) -> set[str]:
        """Mirrors AnkiCardRepository.tagsFor()."""
        t: set[str] = {"tango-tori"}
        if self.is_common:
            t.add("common")
        if self.jlpt:
            t.add(self.jlpt.lower())
        for raw in self.part_of_speech.replace(";", ",").split(","):
            code = raw.strip().replace(" ", "-").lower()
            if code:
                t.add(code)
        return t


# ═════════════════════════════════════════════════════════════════════════════
# JMdict labels  (mirrors JmdictLabels.kt)
# ═════════════════════════════════════════════════════════════════════════════

_POS_LABELS: dict[str, str] = {
    "n": "Noun", "n-pr": "Proper noun", "n-pref": "Noun prefix",
    "n-suf": "Noun suffix", "n-t": "Temporal noun", "n-adv": "Adverbial noun",
    "pn": "Pronoun",
    "adj-i": "I-adjective", "adj-ix": "I-adjective (yoi/ii class)",
    "adj-na": "Na-adjective", "adj-no": "No-adjective",
    "adj-pn": "Pre-noun adjectival", "adj-t": "Taru-adjective",
    "adj-f": "Noun/verb acting prenominally",
    "adv": "Adverb", "adv-to": "Adverb taking と",
    "v1": "Ichidan verb", "v1-s": "Ichidan verb (kureru class)", "v5": "Godan verb",
    "v5b": "Godan verb (-bu)", "v5g": "Godan verb (-gu)", "v5k": "Godan verb (-ku)",
    "v5k-s": "Godan verb (iku/yuku)", "v5m": "Godan verb (-mu)", "v5n": "Godan verb (-nu)",
    "v5r": "Godan verb (-ru)", "v5r-i": "Godan verb (-ru, irregular)",
    "v5s": "Godan verb (-su)", "v5t": "Godan verb (-tsu)", "v5u": "Godan verb (-u)",
    "v5u-s": "Godan verb (-u, special)", "vk": "Kuru verb (irregular)",
    "vs": "Suru verb", "vs-i": "Suru verb (included)", "vs-s": "Suru verb (special)",
    "vt": "Transitive verb", "vi": "Intransitive verb",
    "aux-v": "Auxiliary verb", "aux-adj": "Auxiliary adjective", "aux": "Auxiliary",
    "prt": "Particle", "conj": "Conjunction", "cop": "Copula", "int": "Interjection",
    "exp": "Expression", "ctr": "Counter", "num": "Numeric",
    "pref": "Prefix", "suf": "Suffix", "unc": "Unclassified",
}

_MISC_LABELS: dict[str, str] = {
    "uk": "Usually written in kana alone", "abbr": "Abbreviation", "arch": "Archaism",
    "col": "Colloquial", "fam": "Familiar language", "hon": "Honorific", "hum": "Humble",
    "id": "Idiomatic expression", "obs": "Obsolete", "obsc": "Obscure",
    "on-mim": "Onomatopoeia / mimesis", "poet": "Poetical", "pol": "Polite",
    "rare": "Rare", "sens": "Sensitive", "sl": "Slang", "vulg": "Vulgar",
    "X": "Vulgar / sexual", "yoji": "Yojijukugo (four-character idiom)",
    "derog": "Derogatory", "joc": "Jocular", "chn": "Children's language",
    "fem": "Female speech", "male": "Male speech",
}

_FIELD_LABELS: dict[str, str] = {
    "math": "Mathematics", "comp": "Computing", "med": "Medicine",
    "physics": "Physics", "chem": "Chemistry", "biol": "Biology",
    "bot": "Botany", "zool": "Zoology", "ling": "Linguistics",
    "music": "Music", "sports": "Sports", "baseb": "Baseball",
    "sumo": "Sumo", "MA": "Martial arts", "food": "Food", "law": "Law",
    "econ": "Economics", "finc": "Finance", "Buddh": "Buddhism",
    "Shinto": "Shinto", "Christn": "Christianity", "anat": "Anatomy",
    "astron": "Astronomy", "geol": "Geology", "geogr": "Geography",
    "psych": "Psychology",
}

_DIALECT_LABELS: dict[str, str] = {
    "ksb": "Kansai dialect", "ktb": "Kantou dialect", "kyb": "Kyoto dialect",
    "kyu": "Kyuushuu dialect", "nab": "Nagano dialect", "osb": "Osaka dialect",
    "rkb": "Ryuukyuu dialect", "thb": "Touhoku dialect", "tsb": "Tosa dialect",
    "tsug": "Tsugaru dialect",
}


def _format_codes(raw: Optional[str], labels: dict[str, str]) -> Optional[str]:
    """Mirrors formatCodes() in JmdictLabels.kt."""
    if not raw:
        return None
    seen: dict[str, None] = {}
    for p in raw.split(";"):
        p = p.strip()
        if p:
            seen[labels.get(p, p)] = None
    result = " · ".join(seen)
    return result or None


def _format_pos(raw: str) -> str:
    """Mirrors formatPos() in JmdictLabels.kt."""
    seen: dict[str, None] = {}
    for p in raw.split(";"):
        p = p.strip()
        if p:
            seen[_POS_LABELS.get(p, p)] = None
    return ", ".join(seen)


# ═════════════════════════════════════════════════════════════════════════════
# FuriganaBuilder  (mirrors FuriganaBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def _find_kana_match_furigana(reading: str, from_idx: int, needle: str) -> int:
    """Find needle (converted to hiragana) in reading starting at from_idx."""
    needle_h = _kata2hira(needle)
    idx = from_idx
    while idx + len(needle_h) <= len(reading):
        if reading[idx: idx + len(needle_h)] == needle_h:
            return idx
        idx += 1
    return -1


def build_furigana(surface: str, reading: str) -> str:
    """
    Generate AnkiDroid 'kanji[reading]' furigana markup.
    Mirrors FuriganaBuilder.build().

    Examples:
        食べる + たべる  → "食[た]べる"
        膝立ち + ひざだち → "膝立[ひざだ]ち"
        コーヒー + コーヒー → "コーヒー"
    """
    if not surface or not reading:
        return surface
    if not any(_is_kanji(c) for c in surface):
        return surface

    out: list[str] = []
    i = 0
    r = 0

    while i < len(surface):
        c = surface[i]
        if _is_kanji(c):
            j = i
            while j < len(surface) and _is_kanji(surface[j]):
                j += 1
            kanji_run = surface[i:j]

            next_kana_end = j
            while next_kana_end < len(surface) and not _is_kanji(surface[next_kana_end]):
                next_kana_end += 1
            following_kana = surface[j:next_kana_end]

            r_end = (len(reading) if not following_kana
                     else _find_kana_match_furigana(reading, r, following_kana))

            if r_end < 0:
                # Alignment failed — bracket the whole remainder
                fallback = "".join(out) + surface[i:] + "[" + reading[r:] + "]"
                return surface if "[]" in fallback else fallback

            out.append(kanji_run + "[" + reading[r:r_end] + "]")
            out.append(following_kana)
            i = next_kana_end
            r = r_end + len(following_kana)
        else:
            out.append(c)
            if r < len(reading):
                rc = reading[r]
                if rc == c or rc == _kata2hira(c):
                    r += 1
            i += 1

    result = "".join(out)
    return surface if "[]" in result else result


# ═════════════════════════════════════════════════════════════════════════════
# FuriganaHtmlBuilder  (mirrors FuriganaHtmlBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def build_furigana_html(markup: str) -> str:
    """
    Convert 'kanji[reading]' markup to <ruby> HTML.
    Mirrors FuriganaHtmlBuilder.build().

    Examples:
        "食[た]べる"  → "<ruby>食<rt>た</rt></ruby>べる"
        "スタジオ"     → "スタジオ"
    """
    if not markup:
        return ""
    out: list[str] = []
    i = 0
    while i < len(markup):
        open_bracket = markup.find("[", i)
        if open_bracket < 0:
            out.append(_html_escape(markup[i:]))
            break

        kanji_start = open_bracket
        while kanji_start > i and _is_ideograph(markup[kanji_start - 1]):
            kanji_start -= 1

        if kanji_start > i:
            out.append(_html_escape(markup[i:kanji_start]))

        kanji = markup[kanji_start:open_bracket]
        close_bracket = markup.find("]", open_bracket + 1)
        if close_bracket < 0:
            out.append(_html_escape(markup[kanji_start:]))
            break

        reading = markup[open_bracket + 1:close_bracket]
        out.append(
            f"<ruby>{_html_escape(kanji)}<rt>{_html_escape(reading)}</rt></ruby>"
        )
        i = close_bracket + 1

    return "".join(out)


# ═════════════════════════════════════════════════════════════════════════════
# MeaningHtmlBuilder  (mirrors MeaningHtmlBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def build_meaning_html(senses: list[Sense]) -> str:
    """
    Render grouped POS-label + ordered-list HTML for the Meaning field.
    Mirrors MeaningHtmlBuilder.build().
    """
    if not senses:
        return ""

    # Group consecutive senses by sorted POS key
    groups: list[tuple[str, list[Sense]]] = []
    cur_key: Optional[str] = None
    cur_bucket: list[Sense] = []
    for sense in senses:
        key = ";".join(sorted(sense.part_of_speech))
        if cur_key is None:
            cur_key, cur_bucket = key, [sense]
        elif key == cur_key:
            cur_bucket.append(sense)
        else:
            groups.append((cur_key, cur_bucket))
            cur_key, cur_bucket = key, [sense]
    if cur_key is not None:
        groups.append((cur_key, cur_bucket))

    sb: list[str] = []
    for pos_key, group_senses in groups:
        pos_label = _format_pos(pos_key)
        if pos_label:
            sb.append(f'<div class="pos-label">{_html_escape(pos_label)}</div>')
        sb.append('<ol class="senses">')
        for sense in group_senses:
            glosses = [g for g in sense.glosses if g.text.strip()]
            if not glosses:
                continue
            sb.append("<li>")
            sb.append(", ".join(_html_escape(g.text) for g in glosses))
            misc_lbl   = _format_codes(sense.misc,    _MISC_LABELS)
            field_lbl  = _format_codes(sense.field,   _FIELD_LABELS)
            dial_lbl   = _format_codes(sense.dialect, _DIALECT_LABELS)
            annots = [a for a in [misc_lbl, field_lbl, dial_lbl] if a]
            if annots:
                combined = _html_escape(" · ".join(annots))
                sb.append(f' <small style="color:#78909C;"><i>{combined}</i></small>')
            sb.append("</li>")
        sb.append("</ol>")
    return "".join(sb)


# ═════════════════════════════════════════════════════════════════════════════
# SentenceHtmlBuilder  (mirrors SentenceHtmlBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def _find_kana_match_sentence(reading: str, from_idx: int, needle: str) -> int:
    """Plain string search — no kana conversion (readings already hiragana)."""
    idx = from_idx
    while idx + len(needle) <= len(reading):
        if reading[idx: idx + len(needle)] == needle:
            return idx
        idx += 1
    return -1


def _build_ruby(surface: str, reading: str) -> str:
    """Per-token ruby markup. Mirrors SentenceHtmlBuilder.buildRuby()."""
    if not reading:
        return _html_escape(surface)
    out: list[str] = []
    i = 0
    r = 0
    while i < len(surface):
        c = surface[i]
        if _is_kanji(c):
            j = i
            while j < len(surface) and _is_kanji(surface[j]):
                j += 1
            kanji_run = surface[i:j]

            next_kana_end = j
            while next_kana_end < len(surface) and not _is_kanji(surface[next_kana_end]):
                next_kana_end += 1
            following_kana = surface[j:next_kana_end]

            r_end = (len(reading) if not following_kana
                     else _find_kana_match_sentence(reading, r, following_kana))

            if r_end < 0:
                out.append(
                    f"<ruby>{_html_escape(surface[i:])}"
                    f"<rt>{_html_escape(reading[r:])}</rt></ruby>"
                )
                return "".join(out)

            out.append(
                f"<ruby>{_html_escape(kanji_run)}"
                f"<rt>{_html_escape(reading[r:r_end])}</rt></ruby>"
            )
            out.append(_html_escape(following_kana))
            i = next_kana_end
            r = r_end + len(following_kana)
        else:
            out.append(_html_escape(c))
            if r < len(reading):
                r += 1
            i += 1
    return "".join(out)


def _ruby_or_plain(token: Token) -> str:
    if not token.surface.strip():
        return ""
    if not any(_is_kanji(c) for c in token.surface):
        return _html_escape(token.surface)
    return _build_ruby(token.surface, token.reading)


def build_sentence_html(
    tokens: list[TokenWithEntry],
    target_entry_id: Optional[int],
) -> str:
    """
    Render the sentence as HTML with ruby annotations, jisho.org links, and
    target-word highlighting. Mirrors SentenceHtmlBuilder.build().
    """
    sb: list[str] = []
    for te in tokens:
        token, entry_id = te.token, te.entry_id
        is_target = entry_id is not None and entry_id == target_entry_id
        body = _ruby_or_plain(token)

        if token.part_of_speech in (PartOfSpeech.PARTICLE, PartOfSpeech.PUNCTUATION):
            rendered = body
        elif entry_id is not None:
            encoded = urllib.parse.quote(token.dictionary_form, safe="")
            rendered = f'<a href="https://jisho.org/search/{encoded}">{body}</a>'
        else:
            rendered = body

        if is_target:
            sb.append(f'<span class="target-word">{rendered}</span>')
        else:
            sb.append(rendered)
    return "".join(sb)


# ═════════════════════════════════════════════════════════════════════════════
# KanjiBreakdownBuilder + HtmlBuilder  (mirrors KanjiBreakdownHtmlBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

_RENDAKU_MAP: dict[str, list[str]] = {
    "か": ["が"], "き": ["ぎ"], "く": ["ぐ"], "け": ["げ"], "こ": ["ご"],
    "さ": ["ざ"], "し": ["じ"], "す": ["ず"], "せ": ["ぜ"], "そ": ["ぞ"],
    "た": ["だ"], "ち": ["ぢ", "じ"], "つ": ["づ", "ず"], "て": ["で"], "と": ["ど"],
    "は": ["ば", "ぱ"], "ひ": ["び", "ぴ"], "ふ": ["ぶ", "ぷ"],
    "へ": ["べ", "ぺ"], "ほ": ["ぼ", "ぽ"],
}
_GEMINATION_LAST_MORA: frozenset[str] = frozenset({"く", "き", "ち", "つ", "ふ"})


def _rendaku_variants(reading: str) -> list[str]:
    if not reading:
        return []
    return [v + reading[1:] for v in _RENDAKU_MAP.get(reading[0], [])]


def _gemination_variant(reading: str) -> Optional[str]:
    if len(reading) < 2:
        return None
    return reading[:-1] + "っ" if reading[-1] in _GEMINATION_LAST_MORA else None


def _split_reading_across_kanji(
    kanji_run: str,
    reading: str,
    kanji_readings_fn: Callable[[str], set[str]],
) -> Optional[list[str]]:
    """Backtracking splitter. Mirrors KanjiBreakdownBuilder.splitReadingAcrossKanji()."""

    def recurse(k_idx: int, r_idx: int, acc: list[str]) -> Optional[list[str]]:
        if k_idx == len(kanji_run):
            return acc[:] if r_idx == len(reading) else None
        char = kanji_run[k_idx]
        base = [r for r in kanji_readings_fn(char) if r]
        is_first = k_idx == 0
        is_last  = k_idx == len(kanji_run) - 1

        # candidates: match_form → display_form (insertion-order dedup)
        candidates: dict[str, str] = {}
        for b in base:
            candidates.setdefault(b, b)
        if not is_first:
            for b in base:
                for v in _rendaku_variants(b):
                    candidates.setdefault(v, b)
        if not is_last:
            for b in base:
                gv = _gemination_variant(b)
                if gv:
                    candidates.setdefault(gv, b)

        # Longest match first
        for match_form, display_form in sorted(candidates.items(), key=lambda x: -len(x[0])):
            if not match_form:
                continue
            if r_idx + len(match_form) > len(reading):
                continue
            if reading[r_idx: r_idx + len(match_form)] != match_form:
                continue
            acc.append(display_form)
            result = recurse(k_idx + 1, r_idx + len(match_form), acc)
            if result is not None:
                return result
            acc.pop()
        return None

    return recurse(0, 0, [])


def _explode_kanji_run(
    kanji_run: str,
    reading: str,
    kanji_readings_fn: Callable[[str], set[str]],
) -> list[tuple[str, str]]:
    if not kanji_run:
        return []
    if len(kanji_run) == 1:
        return [(kanji_run, reading)]
    split = _split_reading_across_kanji(kanji_run, reading, kanji_readings_fn)
    if split:
        return [(kanji_run[i], split[i]) for i in range(len(kanji_run))]
    return [(kanji_run[0], reading)] + [(c, "") for c in kanji_run[1:]]


def build_kanji_breakdown_html(
    furigana_markup: str,
    kanji_meanings_fn: Callable[[str], list[str]],
    kanji_readings_fn: Callable[[str], set[str]],
    max_meanings: int = 3,
) -> str:
    """
    Per-kanji tile HTML. Mirrors KanjiBreakdownHtmlBuilder.build().

    Each tile: <div class="kanji-tile">reading / char / meanings</div>
    """
    if not furigana_markup or not any(_is_kanji(c) for c in furigana_markup):
        return ""

    raw_tiles: list[tuple[str, str]] = []
    i = 0
    while i < len(furigana_markup):
        open_bracket = furigana_markup.find("[", i)
        if open_bracket < 0:
            break
        kanji_start = open_bracket
        while kanji_start > i and _is_ideograph(furigana_markup[kanji_start - 1]):
            kanji_start -= 1
        i = kanji_start
        if open_bracket < 0:
            break
        kanji_run = furigana_markup[i:open_bracket]
        close_bracket = furigana_markup.find("]", open_bracket + 1)
        if close_bracket < 0:
            break
        reading = furigana_markup[open_bracket + 1:close_bracket]
        raw_tiles.extend(_explode_kanji_run(kanji_run, reading, kanji_readings_fn))
        i = close_bracket + 1

    if not raw_tiles:
        return ""

    sb: list[str] = []
    for char, reading in raw_tiles:
        meanings = kanji_meanings_fn(char)[:max_meanings]
        meaning_text = _html_escape(", ".join(meanings))
        sb.append('<div class="kanji-tile">')
        sb.append(f'<div class="kanji-reading">{_html_escape(reading)}</div>')
        sb.append(f'<div class="kanji-char">{_html_escape(char)}</div>')
        if meaning_text:
            sb.append(f'<div class="kanji-meaning">{meaning_text}</div>')
        sb.append("</div>")
    return "".join(sb)


# ═════════════════════════════════════════════════════════════════════════════
# POS classifier  (mirrors PosMapper.kt)
# ═════════════════════════════════════════════════════════════════════════════

_POS_MAPPING: dict[str, PartOfSpeech] = {
    "名詞": PartOfSpeech.NOUN,
    "代名詞": PartOfSpeech.NOUN,
    "動詞": PartOfSpeech.VERB,
    "形容詞": PartOfSpeech.I_ADJECTIVE,
    "形状詞": PartOfSpeech.NA_ADJECTIVE,
    "助詞": PartOfSpeech.PARTICLE,
    "副詞": PartOfSpeech.ADVERB,
    "助動詞": PartOfSpeech.AUXILIARY_VERB,
    "接続詞": PartOfSpeech.CONJUNCTION_OTHER,
    "感動詞": PartOfSpeech.CONJUNCTION_OTHER,
    "連体詞": PartOfSpeech.CONJUNCTION_OTHER,
    "接頭辞": PartOfSpeech.CONJUNCTION_OTHER,
    "補助記号": PartOfSpeech.PUNCTUATION,
    "記号": PartOfSpeech.PUNCTUATION,
    "空白": PartOfSpeech.PUNCTUATION,
}
_SUFFIX_MAPPING: dict[str, PartOfSpeech] = {
    "名詞的": PartOfSpeech.NOUN,
    "動詞的": PartOfSpeech.VERB,
    "形容詞的": PartOfSpeech.I_ADJECTIVE,
}


def _classify_pos(surface: str, pos_list: list[str]) -> PartOfSpeech:
    if surface and all(_is_punct_char(c) for c in surface):
        return PartOfSpeech.PUNCTUATION
    if not pos_list:
        return PartOfSpeech.CONJUNCTION_OTHER
    top = pos_list[0]
    sub = pos_list[1] if len(pos_list) > 1 else ""
    if top in _POS_MAPPING:
        return _POS_MAPPING[top]
    if top == "接尾辞":
        return _SUFFIX_MAPPING.get(sub, PartOfSpeech.CONJUNCTION_OTHER)
    return PartOfSpeech.CONJUNCTION_OTHER


# ═════════════════════════════════════════════════════════════════════════════
# Dictionary-form reading derivation  (mirrors KanjiKanaSplit.deriveDictFormReading)
# ═════════════════════════════════════════════════════════════════════════════

def _derive_dict_form_reading(surface: str, surface_reading: str, dict_form: str) -> str:
    """
    Derive the reading of the dictionary/base form from the surface reading.
    Mirrors KanjiKanaSplit.deriveDictFormReading().
    """
    if surface == dict_form:
        return surface_reading
    if not surface_reading or not dict_form:
        return surface_reading

    kanji_indices = [i for i, c in enumerate(surface) if _is_kanji(c)]
    if not kanji_indices:
        return surface_reading

    first_k = kanji_indices[0]
    last_k  = kanji_indices[-1]
    leading_kana  = surface[:first_k]
    trailing_kana = surface[last_k + 1:]
    kanji_run     = surface[first_k:last_k + 1]

    reading_h  = _kata2hira(surface_reading)
    leading_h  = _kata2hira(leading_kana)
    trailing_h = _kata2hira(trailing_kana)

    read_start = len(leading_h) if reading_h.startswith(leading_h) else 0
    if trailing_h and reading_h.endswith(trailing_h):
        read_end = len(reading_h) - len(trailing_h)
    else:
        read_end = len(reading_h)

    if read_end <= read_start:
        return surface_reading

    kanji_reading = surface_reading[read_start:read_end]

    if not dict_form.startswith(kanji_run):
        return surface_reading

    dict_kana_suffix = dict_form[len(kanji_run):]
    return leading_kana + kanji_reading + dict_kana_suffix


# ═════════════════════════════════════════════════════════════════════════════
# TokenMerger  (mirrors TokenMerger.kt)
# ═════════════════════════════════════════════════════════════════════════════

_HEAD_POS: frozenset[PartOfSpeech] = frozenset({
    PartOfSpeech.VERB,
    PartOfSpeech.I_ADJECTIVE,
    PartOfSpeech.NA_ADJECTIVE,
    PartOfSpeech.AUXILIARY_VERB,
})


def _should_absorb(head: Token, nxt: Token) -> bool:
    if head.part_of_speech not in _HEAD_POS:
        return False
    if nxt.part_of_speech == PartOfSpeech.AUXILIARY_VERB:
        return True
    if nxt.part_of_speech == PartOfSpeech.PARTICLE and "接続助詞" in nxt.raw_pos_tag:
        return True
    return False


def _combine_tokens(head: Token, tail: Token) -> Token:
    merged_surface = head.surface + tail.surface
    merged_reading = head.reading + tail.reading
    return Token(
        surface=merged_surface,
        dictionary_form=head.dictionary_form,
        reading=merged_reading,
        dictionary_reading=_derive_dict_form_reading(
            merged_surface, merged_reading, head.dictionary_form),
        part_of_speech=head.part_of_speech,
        raw_pos_tag=head.raw_pos_tag,
    )


def _merge_tokens(tokens: list[Token]) -> list[Token]:
    if len(tokens) < 2:
        return tokens
    out: list[Token] = []
    for token in tokens:
        if out and _should_absorb(out[-1], token):
            out[-1] = _combine_tokens(out[-1], token)
        else:
            out.append(token)
    return out


# ═════════════════════════════════════════════════════════════════════════════
# JMdict database layer  (mirrors JmdictRepository.kt + JmdictDao)
# ═════════════════════════════════════════════════════════════════════════════

class JmdictDB:
    """
    Thin wrapper around the app's bundled jmdict.db SQLite file.
    Thread-safe for reading; connection is kept open for the object's lifetime.
    """

    def __init__(self, db_path: str | Path):
        self._con = sqlite3.connect(str(db_path), check_same_thread=False)
        self._con.row_factory = sqlite3.Row

    def close(self) -> None:
        self._con.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def lookup(
        self,
        dictionary_form: str,
        reading: Optional[str] = None,
    ) -> list[DictEntry]:
        """
        Mirrors JmdictRepository.lookup(): kanji/reading exact match,
        fallback to reading-only, ranked common-first then JLPT ascending.
        """
        if not dictionary_form.strip():
            return []
        rows = self._find_by_form(dictionary_form)
        if not rows and reading:
            rows = self._find_by_reading(reading)
        return [self._hydrate(r) for r in rows]

    def _find_by_form(self, form: str) -> list[sqlite3.Row]:
        cur = self._con.execute("""
            SELECT DISTINCT e.id, e.isCommon, e.jlptLevel
            FROM   entries  e
            LEFT JOIN kanji    k ON k.entryId = e.id
            LEFT JOIN readings r ON r.entryId = e.id
            WHERE  k.text = ? OR r.text = ?
            ORDER  BY e.isCommon DESC,
                      CASE e.jlptLevel
                        WHEN 'N5' THEN 1 WHEN 'N4' THEN 2 WHEN 'N3' THEN 3
                        WHEN 'N2' THEN 4 WHEN 'N1' THEN 5 ELSE 6
                      END
        """, (form, form))
        return cur.fetchall()

    def _find_by_reading(self, reading: str) -> list[sqlite3.Row]:
        cur = self._con.execute("""
            SELECT DISTINCT e.id, e.isCommon, e.jlptLevel
            FROM   entries  e
            JOIN   readings r ON r.entryId = e.id
            WHERE  r.text = ?
            ORDER  BY e.isCommon DESC,
                      CASE e.jlptLevel
                        WHEN 'N5' THEN 1 WHEN 'N4' THEN 2 WHEN 'N3' THEN 3
                        WHEN 'N2' THEN 4 WHEN 'N1' THEN 5 ELSE 6
                      END
        """, (reading,))
        return cur.fetchall()

    def _hydrate(self, row: sqlite3.Row) -> DictEntry:
        eid = row["id"]
        kanji_rows = self._con.execute(
            "SELECT text FROM kanji WHERE entryId=? ORDER BY rowid", (eid,)
        ).fetchall()
        reading_rows = self._con.execute(
            "SELECT text, noKanji FROM readings WHERE entryId=? ORDER BY rowid", (eid,)
        ).fetchall()
        sense_rows = self._con.execute(
            "SELECT senseId, partOfSpeech, misc, field, dialect "
            "FROM senses WHERE entryId=? ORDER BY orderIndex", (eid,)
        ).fetchall()

        senses: list[Sense] = []
        for sr in sense_rows:
            gloss_rows = self._con.execute(
                "SELECT text, language FROM glosses WHERE senseId=? ORDER BY rowid",
                (sr["senseId"],),
            ).fetchall()
            senses.append(Sense(
                part_of_speech=[p for p in sr["partOfSpeech"].split(";") if p.strip()],
                glosses=[Gloss(g["text"], g["language"]) for g in gloss_rows],
                misc=sr["misc"],
                field=sr["field"],
                dialect=sr["dialect"],
            ))

        return DictEntry(
            id=eid,
            is_common=bool(row["isCommon"]),
            jlpt_level=row["jlptLevel"],
            kanji_forms=[KanjiForm(r["text"]) for r in kanji_rows],
            readings=[Reading(r["text"], bool(r["noKanji"])) for r in reading_rows],
            senses=senses,
        )

    def get_kanji_dic(self, char: str) -> tuple[list[str], set[str]]:
        """Return (meanings, readings) from kanjidic for a single CJK character."""
        row = self._con.execute(
            "SELECT meanings, readings FROM kanji_dic WHERE character=?", (char,)
        ).fetchone()
        if not row:
            return [], set()
        meanings = [m.strip() for m in row["meanings"].split(";") if m.strip()]
        readings = {r.strip() for r in row["readings"].split(";") if r.strip()}
        return meanings, readings


# ═════════════════════════════════════════════════════════════════════════════
# Sudachi tokenizer wrapper  (mirrors SudachiTokenizer.kt + PosMapper.kt)
# ═════════════════════════════════════════════════════════════════════════════

class TangoToriTokenizer:
    """
    Wraps sudachipy. The tokenizer is created lazily on first use.

    Install before use:
        pip install sudachipy sudachi-dictionary-core

    Passing dic_path lets you use the app's bundled system_core.dic directly,
    which guarantees bit-for-bit identical tokenization to the Android app.
    Without dic_path, the pip-installed 'core' dictionary is used (same data,
    different install path).
    """

    def __init__(self, dic_path: Optional[str | Path] = None):
        self._dic_path = Path(dic_path) if dic_path else None
        self._tokenizer = None

    def _ensure_ready(self) -> None:
        if self._tokenizer is not None:
            return
        try:
            import sudachipy
        except ImportError as exc:
            raise ImportError(
                "sudachipy is required: pip install sudachipy sudachi-dictionary-core"
            ) from exc

        if self._dic_path and self._dic_path.exists():
            cfg = json.dumps({"systemDict": self._dic_path.as_posix()})
            d = sudachipy.Dictionary(config=cfg)
        else:
            d = sudachipy.Dictionary(dict_type="core")

        self._tokenizer = d.create()

    def tokenize(self, sentence: str) -> list[Token]:
        if not sentence.strip():
            return []
        self._ensure_ready()
        import sudachipy

        morphemes = self._tokenizer.tokenize(sentence, sudachipy.SplitMode.C)
        raw: list[Token] = []
        for m in morphemes:
            pos_list    = list(m.part_of_speech())
            surface     = m.surface()
            dict_form   = m.dictionary_form() or surface
            surf_reading = _kata2hira(m.reading_form())
            raw.append(Token(
                surface=surface,
                dictionary_form=dict_form,
                reading=surf_reading,
                dictionary_reading=_derive_dict_form_reading(
                    surface, surf_reading, dict_form),
                part_of_speech=_classify_pos(surface, pos_list),
                raw_pos_tag=",".join(pos_list),
            ))
        return _merge_tokens(raw)


# ═════════════════════════════════════════════════════════════════════════════
# Singleton state (module-level, reused across calls for performance)
# ═════════════════════════════════════════════════════════════════════════════

_db_cache:  dict[str, JmdictDB]             = {}
_tok_cache: dict[str, TangoToriTokenizer]   = {}


def _get_db(db_path: Optional[str | Path]) -> JmdictDB:
    key = str(db_path or _DEFAULT_DB)
    if key not in _db_cache:
        _db_cache[key] = JmdictDB(key)
    return _db_cache[key]


def _get_tokenizer(dic_path: Optional[str | Path]) -> TangoToriTokenizer:
    key = str(dic_path or _DEFAULT_DIC)
    if key not in _tok_cache:
        _tok_cache[key] = TangoToriTokenizer(dic_path or _DEFAULT_DIC)
    return _tok_cache[key]


# ═════════════════════════════════════════════════════════════════════════════
# Main public API
# ═════════════════════════════════════════════════════════════════════════════

def create_card(
    word: str,
    sentence: str,
    source: str = "",
    db_path: Optional[str | Path] = None,
    dic_path: Optional[str | Path] = None,
    entry_index: int = 0,
) -> Optional[CardData]:
    """
    Build a Tango Tori Anki card identical to what the Android app would produce.

    Parameters
    ----------
    word        : Target word — surface or dictionary form (e.g. "食べた" or "食べる").
    sentence    : Full sentence the word appears in.
    source      : Optional attribution string stored in the Source field.
    db_path     : Path to jmdict.db. Defaults to the app's bundled asset.
    dic_path    : Path to system_core.dic. Defaults to the app's bundled asset.
    entry_index : Which JMdict result to use when multiple entries match
                  (0 = most common / highest JLPT).

    Returns
    -------
    CardData or None if the word cannot be found in JMdict.
    """
    db  = _get_db(db_path)
    tok = _get_tokenizer(dic_path)

    # Step 1: tokenize the sentence
    tokens = tok.tokenize(sentence)

    # Step 2: locate the target token (surface-first, then dictionary-form)
    target_token: Optional[Token] = None
    for t in tokens:
        if t.surface == word or t.dictionary_form == word:
            target_token = t
            break
    if target_token is None:
        for t in tokens:
            if word in t.surface or t.surface in word:
                target_token = t
                break
    if target_token is None:
        return None

    # Step 3: JMdict lookup for the target word
    entries = db.lookup(target_token.dictionary_form, target_token.dictionary_reading)
    if not entries:
        entries = db.lookup(word)
    if not entries:
        return None
    entry = entries[min(entry_index, len(entries) - 1)]

    # Step 4: lookup every sentence token so we can build sentence HTML
    _UNLINKABLE = {PartOfSpeech.PUNCTUATION, PartOfSpeech.PARTICLE, PartOfSpeech.AUXILIARY_VERB}
    tokens_with_entries: list[TokenWithEntry] = []
    for t in tokens:
        if t.part_of_speech in _UNLINKABLE:
            eid = None
        else:
            looked_up = db.lookup(t.dictionary_form, t.dictionary_reading)
            eid = looked_up[0].id if looked_up else None
        tokens_with_entries.append(TokenWithEntry(token=t, entry_id=eid))

    # Step 5: build card fields
    word_str    = entry.headword
    reading_str = entry.primary_reading
    furigana    = build_furigana(word_str, reading_str)

    # Fetch kanjidic data for kanji breakdown
    kanji_chars = list(dict.fromkeys(c for c in word_str if _is_kanji(c)))
    kanji_data  = {c: db.get_kanji_dic(c) for c in kanji_chars}

    def meanings_fn(c: str) -> list[str]:
        return kanji_data.get(c, ([], set()))[0]

    def readings_fn(c: str) -> set[str]:
        return kanji_data.get(c, ([], set()))[1]

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
        source=source,
        kanji_breakdown_html=build_kanji_breakdown_html(furigana, meanings_fn, readings_fn),
    )


# ═════════════════════════════════════════════════════════════════════════════
# CLI demo
# ═════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    import sys

    word     = sys.argv[1] if len(sys.argv) > 1 else "食べる"
    sentence = sys.argv[2] if len(sys.argv) > 2 else f"{word}ことが好きです。"

    print(f"Word: {word!r}  |  Sentence: {sentence!r}\n")
    card = create_card(word, sentence)
    if card is None:
        print("Not found in JMdict.")
        sys.exit(1)

    fields = card.FIELD_NAMES
    values = card.to_field_array()
    for name, value in zip(fields, values):
        print(f"[{name}]\n{value}\n")
    print(f"[Tags]\n{' '.join(sorted(card.tags()))}")
