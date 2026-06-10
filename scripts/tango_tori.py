"""
tango_tori.py – Python port of Tango Tori's Anki-card creation pipeline.

Supports Japanese and Chinese (Simplified + Traditional). Faithfully
replicates every field the Android app produces for both languages.

Requirements
------------
    pip install sudachipy          # Japanese tokenizer
    pip install jieba pypinyin     # Chinese tokenizer + pinyin

Asset databases (resolved automatically relative to this file):
    app/src/main/assets/jmdict.db       — Japanese dictionary
    app/src/main/assets/cedict.db       — Chinese dictionary (CC-CEDICT)
    app/src/main/assets/sudachi/system_core.dic

Quick start
-----------
    from tango_tori import create_card

    # Japanese (auto-detected)
    card = create_card(word="食べる", sentence="毎日食べるものが美味しい。")
    print(card.word_ruby)     # <ruby>食<rt>た</rt></ruby>べる

    # Chinese (auto-detected)
    card = create_card(word="中国", sentence="我喜欢中国的文化。")
    print(card.word_ruby)     # <ruby>中<rt>zhōng</rt></ruby><ruby>国<rt>guó</rt></ruby>

    print(card.to_field_array())   # list of 12 strings for AnkiConnect
    print(card.tags())
"""

from __future__ import annotations

import json
import re
import sqlite3
import urllib.parse
from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Callable, Optional

# ── Default asset paths (project root is one level above scripts/) ────────────
_HERE           = Path(__file__).parent.resolve()
_PROJ_ROOT      = _HERE.parent   # scripts/ → project root
_DEFAULT_DB     = _PROJ_ROOT / "app" / "src" / "main" / "assets" / "jmdict.db"
_DEFAULT_CEDICT = _PROJ_ROOT / "app" / "src" / "main" / "assets" / "cedict.db"
_DEFAULT_DIC    = _PROJ_ROOT / "app" / "src" / "main" / "assets" / "sudachi" / "system_core.dic"


# ═════════════════════════════════════════════════════════════════════════════
# Unicode helpers
# ═════════════════════════════════════════════════════════════════════════════

def _kata2hira(s: str) -> str:
    """Katakana → hiragana (ァ–ヶ range). Matches KatakanaToHiragana.kt."""
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)


def _is_kanji(c: str) -> bool:
    n = ord(c)
    return (0x3400 <= n <= 0x4DBF or
            0x4E00 <= n <= 0x9FFF or
            0xF900 <= n <= 0xFAFF)


def _is_ideograph(c: str) -> bool:
    return _is_kanji(c)


def _is_cjk(c: str) -> bool:
    """Matches ChineseTokenizer.isCjk() and PinyinBuilder.isCjkChar()."""
    return _is_kanji(c)


def _html_escape(s: str) -> str:
    """Mirrors HtmlEscape.kt — only the five essential escapes."""
    return (s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace('"', "&quot;")
             .replace("'", "&#39;"))


def _is_punct_char(c: str) -> bool:
    """Mirrors ChineseTokenizer.isPunctuationChar() / PosMapper.isPunctuationChar()."""
    n = ord(c)
    if 0x3000 <= n <= 0x303F: return True   # CJK Symbols and Punctuation
    if 0xFF00 <= n <= 0xFFEF: return True   # Fullwidth forms
    if 0x2000 <= n <= 0x206F: return True   # General Punctuation
    return c in r"""!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""


# ═════════════════════════════════════════════════════════════════════════════
# Language detection  (mirrors LanguageDetector.kt)
# ═════════════════════════════════════════════════════════════════════════════

class Language(Enum):
    JAPANESE            = auto()
    CHINESE_SIMPLIFIED  = auto()
    CHINESE_TRADITIONAL = auto()


_TRADITIONAL_ONLY_CHARS: frozenset[str] = frozenset(
    '們這時來說對從發為會傳當頭進問關開電車麼'
    '動過長現萬種體機與學點歡讓還後裡邊誰啊嗎'
    '呢吧嘛麻險廣愛書語話讀聽寫詞難簡繁舊幫實'
    '際響聲請認識辦員樣條'
)


def detect_language(text: str) -> Language:
    """
    Heuristic language detection. Mirrors LanguageDetector.detect().
    Hiragana/katakana → JAPANESE; traditional-only chars → CHINESE_TRADITIONAL;
    otherwise CJK → CHINESE_SIMPLIFIED; no CJK → JAPANESE.
    """
    has_cjk = False
    has_traditional = False
    for c in text:
        n = ord(c)
        if 0x3040 <= n <= 0x309F:
            return Language.JAPANESE
        if 0x30A0 <= n <= 0x30FF and c != 'ー':
            return Language.JAPANESE
        if 0x4E00 <= n <= 0x9FFF or 0x3400 <= n <= 0x4DBF or 0x20000 <= n <= 0x2A6DF:
            has_cjk = True
            if c in _TRADITIONAL_ONLY_CHARS:
                has_traditional = True
    if not has_cjk:
        return Language.JAPANESE
    return Language.CHINESE_TRADITIONAL if has_traditional else Language.CHINESE_SIMPLIFIED


# ═════════════════════════════════════════════════════════════════════════════
# Domain models  (mirrors app/domain/models/*.kt)
# ═════════════════════════════════════════════════════════════════════════════

@dataclass
class Gloss:
    text: str
    language: str = "eng"


@dataclass
class Sense:
    part_of_speech: list[str]
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
    NOUN              = auto()
    VERB              = auto()
    I_ADJECTIVE       = auto()
    NA_ADJECTIVE      = auto()
    PARTICLE          = auto()
    ADVERB            = auto()
    AUXILIARY_VERB    = auto()
    CONJUNCTION_OTHER = auto()
    PUNCTUATION       = auto()


@dataclass
class Token:
    surface: str
    dictionary_form: str
    reading: str             # hiragana for Japanese; space-separated pinyin for Chinese
    dictionary_reading: str
    part_of_speech: PartOfSpeech
    raw_pos_tag: str         # comma-joined Sudachi POS fields; "" for Chinese


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


@dataclass
class ImageCardData:
    """
    Mirrors ImageCardData.kt — image-based card with 12 fields.

    Replaces the Sentence/SentenceRaw fields with Image/UserSentence.
    The UserSentence field starts empty; the user fills it in inside AnkiDroid.

    Field order in to_field_array():
        Word, Reading, Furigana, WordRuby, Meaning, PartOfSpeech,
        JLPT, IsCommon, Image, UserSentence, Source, KanjiBreakdown
    """
    word: str
    reading: str
    furigana: str
    word_ruby: str
    meaning_html: str
    part_of_speech: str
    jlpt: str
    is_common: bool
    image_html: str = ""       # e.g. '<img src="cat.jpg">'; empty = no image
    user_sentence: str = ""    # blank — user writes their own sentence in Anki
    source: str = ""
    kanji_breakdown_html: str = ""

    NOTE_TYPE_NAME = "Tango Tori Image v1"
    FIELD_NAMES = [
        "Word", "Reading", "Furigana", "WordRuby", "Meaning",
        "PartOfSpeech", "JLPT", "IsCommon",
        "Image", "UserSentence", "Source", "KanjiBreakdown",
    ]

    def to_field_array(self) -> list[str]:
        return [
            self.word, self.reading, self.furigana, self.word_ruby,
            self.meaning_html, self.part_of_speech, self.jlpt,
            "1" if self.is_common else "0",
            self.image_html, self.user_sentence, self.source,
            self.kanji_breakdown_html,
        ]

    def tags(self) -> set[str]:
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
    seen: dict[str, None] = {}
    for p in raw.split(";"):
        p = p.strip()
        if p:
            seen[_POS_LABELS.get(p, p)] = None
    return ", ".join(seen)


# ═════════════════════════════════════════════════════════════════════════════
# PinyinToneConverter  (mirrors PinyinToneConverter.kt)
# ═════════════════════════════════════════════════════════════════════════════

_TONE_MARKS: dict[str, list[str]] = {
    'a': ["ā", "á", "ǎ", "à"],
    'e': ["ē", "é", "ě", "è"],
    'i': ["ī", "í", "ǐ", "ì"],
    'o': ["ō", "ó", "ǒ", "ò"],
    'u': ["ū", "ú", "ǔ", "ù"],
    'ü': ["ǖ", "ǘ", "ǚ", "ǜ"],
}


def _pinyin_tone_position(syllable: str) -> Optional[int]:
    s = syllable.lower()
    i = s.find('a')
    if i >= 0: return i
    i = s.find('e')
    if i >= 0: return i
    i = s.find("ou")
    if i >= 0: return i
    for i in range(len(s) - 1, -1, -1):
        if s[i] in "iouü":
            return i
    return None


def convert_pinyin_syllable(pinyin_num: str) -> str:
    """
    "zhong1" → "zhōng", "ma5" → "ma", "guo2" → "guó".
    Mirrors PinyinToneConverter.convertSyllable().
    """
    if not pinyin_num:
        return pinyin_num
    last = pinyin_num[-1]
    if not last.isdigit():
        return pinyin_num
    tone = int(last)
    base = pinyin_num[:-1].replace("u:", "ü").replace("v", "ü")
    if tone in (0, 5):
        return base
    idx = _pinyin_tone_position(base)
    if idx is None:
        return base
    vowel = base[idx].lower()
    marks = _TONE_MARKS.get(vowel)
    if not marks or tone < 1 or tone > 4:
        return base
    marked = marks[tone - 1]
    if base[idx].isupper():
        marked = marked.upper()
    return base[:idx] + marked + base[idx + 1:]


def convert_pinyin_word(pinyin_numbers: str) -> str:
    """
    "zhong1 guo2" → "zhōng guó".
    Mirrors PinyinToneConverter.convertWord().
    """
    return " ".join(convert_pinyin_syllable(s) for s in pinyin_numbers.strip().split())


# ═════════════════════════════════════════════════════════════════════════════
# FuriganaBuilder  (mirrors FuriganaBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def _find_kana_match_furigana(reading: str, from_idx: int, needle: str) -> int:
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
# PinyinBuilder  (mirrors PinyinBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def build_pinyin(surface: str, pinyin_marks: str) -> str:
    """
    Build bracket markup for Chinese: "中国" + "zhōng guó" → "中[zhōng]国[guó]".
    Mirrors PinyinBuilder.build(). Falls back to surface[pinyin_marks] on mismatch.
    """
    if not surface:
        return surface
    if not any(_is_cjk(c) for c in surface):
        return surface
    syllables = [s for s in pinyin_marks.strip().split() if s]
    cjk_count = sum(1 for c in surface if _is_cjk(c))
    if len(syllables) != cjk_count:
        return f"{surface}[{pinyin_marks}]"
    out: list[str] = []
    syl_idx = 0
    for c in surface:
        if _is_cjk(c):
            out.append(f"{c}[{syllables[syl_idx]}]")
            syl_idx += 1
        else:
            out.append(c)
    return "".join(out)


# ═════════════════════════════════════════════════════════════════════════════
# PinyinHtmlBuilder  (mirrors PinyinHtmlBuilder.kt)
# ═════════════════════════════════════════════════════════════════════════════

def build_pinyin_html(pinyin_markup: str) -> str:
    """
    "中[zhōng]国[guó]" → "<ruby>中<rt>zhōng</rt></ruby><ruby>国<rt>guó</rt></ruby>"
    Mirrors PinyinHtmlBuilder.build().
    """
    if not pinyin_markup:
        return ""
    out: list[str] = []
    i = 0
    while i < len(pinyin_markup):
        open_bracket = pinyin_markup.find('[', i)
        if open_bracket < 0:
            out.append(_html_escape(pinyin_markup[i:]))
            break
        char_start = open_bracket - 1
        if char_start < i:
            out.append(_html_escape(pinyin_markup[i:open_bracket]))
            i = open_bracket
            continue
        if char_start > i:
            out.append(_html_escape(pinyin_markup[i:char_start]))
        hanzi_char = pinyin_markup[char_start:open_bracket]
        close_bracket = pinyin_markup.find(']', open_bracket + 1)
        if close_bracket < 0:
            out.append(_html_escape(pinyin_markup[char_start:]))
            break
        pinyin = pinyin_markup[open_bracket + 1:close_bracket]
        out.append(
            f"<ruby>{_html_escape(hanzi_char)}<rt>{_html_escape(pinyin)}</rt></ruby>"
        )
        i = close_bracket + 1
    return "".join(out)


# ═════════════════════════════════════════════════════════════════════════════
# HanziBreakdownHtmlBuilder  (mirrors HanziBreakdownBuilder + HanziBreakdownHtmlBuilder)
# ═════════════════════════════════════════════════════════════════════════════

def build_hanzi_breakdown_html(
    surface: str,
    word_pinyin_marks: str,
    char_meanings_fn: Callable[[str], list[str]],
    max_meanings: int = 3,
) -> str:
    """
    Per-hanzi tile HTML for the KanjiBreakdown field.
    Mirrors HanziBreakdownBuilder.build() + HanziBreakdownHtmlBuilder.build().
    """
    syllables = [s for s in word_pinyin_marks.strip().split() if s]
    hanzi_chars = [c for c in surface if _is_cjk(c)]
    if not hanzi_chars:
        return ""
    sb: list[str] = []
    for idx, c in enumerate(hanzi_chars):
        pinyin = syllables[idx] if idx < len(syllables) else ""
        meanings = char_meanings_fn(c)[:max_meanings]
        meaning_text = _html_escape(", ".join(meanings))
        sb.append('<div class="kanji-tile">')
        sb.append(f'<div class="kanji-reading">{_html_escape(pinyin)}</div>')
        sb.append(f'<div class="kanji-char">{_html_escape(c)}</div>')
        if meaning_text:
            sb.append(f'<div class="kanji-meaning">{meaning_text}</div>')
        sb.append("</div>")
    return "".join(sb)


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
            misc_lbl  = _format_codes(sense.misc,    _MISC_LABELS)
            field_lbl = _format_codes(sense.field,   _FIELD_LABELS)
            dial_lbl  = _format_codes(sense.dialect, _DIALECT_LABELS)
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
    idx = from_idx
    while idx + len(needle) <= len(reading):
        if reading[idx: idx + len(needle)] == needle:
            return idx
        idx += 1
    return -1


def _build_japanese_ruby(surface: str, reading: str) -> str:
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


def _build_pinyin_ruby(surface: str, pinyin_marks: str) -> str:
    """
    Per-token Chinese pinyin ruby. Mirrors SentenceHtmlBuilder.buildPinyinRuby().
    Distributes space-separated syllables across CJK characters.
    """
    if not pinyin_marks:
        return _html_escape(surface)
    syllables = [s for s in pinyin_marks.strip().split() if s]
    out: list[str] = []
    syl_idx = 0
    for c in surface:
        if _is_kanji(c):
            syllable = syllables[syl_idx] if syl_idx < len(syllables) else ""
            syl_idx += 1
            out.append(
                f"<ruby>{_html_escape(c)}<rt>{_html_escape(syllable)}</rt></ruby>"
            )
        else:
            out.append(_html_escape(c))
    return "".join(out)


def _ruby_or_plain(token: Token, is_chinese: bool = False) -> str:
    if not token.surface.strip():
        return ""
    if not any(_is_kanji(c) for c in token.surface):
        return _html_escape(token.surface)
    if is_chinese:
        return _build_pinyin_ruby(token.surface, token.reading)
    return _build_japanese_ruby(token.surface, token.reading)


def build_sentence_html(
    tokens: list[TokenWithEntry],
    target_entry_id: Optional[int],
    is_chinese: bool = False,
) -> str:
    """
    Render the sentence as HTML with ruby annotations, links, and target-word
    highlighting. Mirrors SentenceHtmlBuilder.build().
    Japanese → jisho.org links. Chinese → MDBG links.
    """
    sb: list[str] = []
    for te in tokens:
        token, entry_id = te.token, te.entry_id
        is_target = entry_id is not None and entry_id == target_entry_id
        body = _ruby_or_plain(token, is_chinese)

        if token.part_of_speech in (PartOfSpeech.PARTICLE, PartOfSpeech.PUNCTUATION):
            rendered = body
        elif entry_id is not None:
            encoded = urllib.parse.quote(token.dictionary_form, safe="")
            if is_chinese:
                href = f"https://www.mdbg.net/chinese/dictionary?page=worddict&wdrst=0&wdqb={encoded}"
            else:
                href = f"https://jisho.org/search/{encoded}"
            rendered = f'<a href="{href}">{body}</a>'
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
    def recurse(k_idx: int, r_idx: int, acc: list[str]) -> Optional[list[str]]:
        if k_idx == len(kanji_run):
            return acc[:] if r_idx == len(reading) else None
        char = kanji_run[k_idx]
        base = [r for r in kanji_readings_fn(char) if r]
        is_first = k_idx == 0
        is_last  = k_idx == len(kanji_run) - 1

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
    """Per-kanji tile HTML. Mirrors KanjiBreakdownHtmlBuilder.build()."""
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
    """Thin wrapper around the app's bundled jmdict.db SQLite file."""

    def __init__(self, db_path: str | Path):
        self._con = sqlite3.connect(str(db_path), check_same_thread=False)
        self._con.row_factory = sqlite3.Row

    def close(self) -> None:
        self._con.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def lookup(self, dictionary_form: str, reading: Optional[str] = None) -> list[DictEntry]:
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
        row = self._con.execute(
            "SELECT meanings, readings FROM kanji_dic WHERE character=?", (char,)
        ).fetchone()
        if not row:
            return [], set()
        meanings = [m.strip() for m in row["meanings"].split(";") if m.strip()]
        readings = {r.strip() for r in row["readings"].split(";") if r.strip()}
        return meanings, readings


# ═════════════════════════════════════════════════════════════════════════════
# CC-CEDICT database layer  (mirrors CedictRepository.kt)
# ═════════════════════════════════════════════════════════════════════════════

_PINYIN_BRACKET_RE = re.compile(r'\[([a-z][a-z0-9: ]*[0-9][^\]]*)\]', re.IGNORECASE)

_HSK_ORDER_SQL = """
    CASE hsk_level
        WHEN 'HSK 1' THEN 0 WHEN 'HSK 2' THEN 1 WHEN 'HSK 3' THEN 2
        WHEN 'HSK 4' THEN 3 WHEN 'HSK 5' THEN 4 WHEN 'HSK 6' THEN 5
        ELSE 6
    END ASC, is_common DESC
"""


def _format_gloss(raw: str) -> str:
    """
    Converts number-tone pinyin inside brackets to tone-marked form.
    "variant of 皮草[pi2 cao3]" → "variant of 皮草 (pí cǎo)"
    Mirrors CedictRepository.formatGloss().
    """
    def replace_match(m: re.Match) -> str:
        marked = " ".join(convert_pinyin_syllable(s) for s in m.group(1).strip().split())
        return f" ({marked})"
    return _PINYIN_BRACKET_RE.sub(replace_match, raw)


def _extract_brief_meaning(gloss: str) -> str:
    """
    Strip leading parenthetical notes and truncate to 20 chars.
    Mirrors CedictRepository.extractBriefMeaning().
    """
    s = gloss.strip()
    while s.startswith("("):
        close = s.find(")")
        if close < 0:
            break
        s = s[close + 1:].lstrip()
    s = s.split("/")[0].strip()
    return (s[:20] + "…") if len(s) > 20 else s


class CedictDB:
    """
    Thin wrapper around the app's bundled cedict.db SQLite file.
    Mirrors CedictRepository.kt.
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

    def lookup(self, word: str) -> list[DictEntry]:
        """Exact match on simplified OR traditional, ordered by HSK then is_common."""
        if not word.strip():
            return []
        cur = self._con.execute(
            f"SELECT * FROM cedict_entry WHERE simplified = ? OR traditional = ? "
            f"ORDER BY {_HSK_ORDER_SQL}",
            (word, word),
        )
        return [self._hydrate(row) for row in cur.fetchall()]

    def lookup_char(self, char: str) -> Optional[tuple[str, list[str]]]:
        """
        Single-character lookup. Returns (pinyin_marks, brief_meanings) or None.
        Mirrors CedictRepository.lookupChar().
        """
        row = self._con.execute(
            f"SELECT * FROM cedict_entry WHERE simplified = ? AND length(simplified) = 1 "
            f"ORDER BY {_HSK_ORDER_SQL} LIMIT 1",
            (char,),
        ).fetchone()
        if not row:
            return None
        raw_glosses = self._fetch_raw_glosses(row["id"])
        brief = [m for m in (_extract_brief_meaning(g) for g in raw_glosses) if m][:2]
        return row["pinyin_marks"], brief

    def lookup_by_chars(self, word: str) -> list[DictEntry]:
        """Per-character fallback lookup. Mirrors CedictRepository.lookupByChars()."""
        results: list[DictEntry] = []
        for c in word:
            row = self._con.execute(
                f"SELECT * FROM cedict_entry WHERE simplified = ? "
                f"ORDER BY {_HSK_ORDER_SQL} LIMIT 1",
                (c,),
            ).fetchone()
            if row:
                results.append(self._hydrate(row))
        return results

    def _hydrate(self, row: sqlite3.Row) -> DictEntry:
        entry_id    = row["id"]
        simplified  = row["simplified"]
        traditional = row["traditional"]
        pinyin_marks = row["pinyin_marks"]
        hsk_level   = row["hsk_level"]
        is_common   = bool(row["is_common"])

        raw_glosses = self._fetch_raw_glosses(entry_id)
        senses = [
            Sense(part_of_speech=[], glosses=[Gloss(_format_gloss(g))])
            for g in raw_glosses
        ]

        if simplified == traditional:
            kanji_forms = [KanjiForm(simplified)]
        else:
            kanji_forms = [KanjiForm(simplified), KanjiForm(traditional)]

        return DictEntry(
            id=entry_id,
            is_common=is_common,
            jlpt_level=hsk_level,
            kanji_forms=kanji_forms,
            readings=[Reading(pinyin_marks)],
            senses=senses,
        )

    def _fetch_raw_glosses(self, entry_id: int) -> list[str]:
        rows = self._con.execute(
            "SELECT gloss FROM cedict_sense WHERE entry_id = ?", (entry_id,)
        ).fetchall()
        return [r["gloss"] for r in rows if not r["gloss"].startswith("CL:")]


# ═════════════════════════════════════════════════════════════════════════════
# Sudachi tokenizer wrapper  (mirrors SudachiTokenizer.kt + PosMapper.kt)
# ═════════════════════════════════════════════════════════════════════════════

class TangoToriTokenizer:
    """Wraps sudachipy with the app's bundled system_core.dic."""

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
            pos_list     = list(m.part_of_speech())
            surface      = m.surface()
            dict_form    = m.dictionary_form() or surface
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
# Chinese tokenizer  (mirrors ChineseTokenizer.kt)
# ═════════════════════════════════════════════════════════════════════════════

_ICTCLAS_TO_POS: dict[str, PartOfSpeech] = {
    "v": PartOfSpeech.VERB,   "vg": PartOfSpeech.VERB,
    "vi": PartOfSpeech.VERB,  "vf": PartOfSpeech.VERB,
    "vd": PartOfSpeech.ADVERB,     # verb-as-adverb
    "vn": PartOfSpeech.VERB,       # verbal noun
    "a": PartOfSpeech.NA_ADJECTIVE, "ag": PartOfSpeech.NA_ADJECTIVE,
    "b": PartOfSpeech.NA_ADJECTIVE, "z":  PartOfSpeech.NA_ADJECTIVE,
    "an": PartOfSpeech.NA_ADJECTIVE,
    "ad": PartOfSpeech.ADVERB,     # adjective-as-adverb
    "d": PartOfSpeech.ADVERB,  "dg": PartOfSpeech.ADVERB,
    "t": PartOfSpeech.ADVERB,  "tg": PartOfSpeech.ADVERB,  # time words
    "o": PartOfSpeech.ADVERB,      # onomatopoeia
    "u": PartOfSpeech.PARTICLE, "ud": PartOfSpeech.PARTICLE,
    "ug": PartOfSpeech.PARTICLE, "uj": PartOfSpeech.PARTICLE,
    "ul": PartOfSpeech.PARTICLE, "uv": PartOfSpeech.PARTICLE,
    "uz": PartOfSpeech.PARTICLE,
    "y": PartOfSpeech.PARTICLE,    # sentence-final modal
    "e": PartOfSpeech.PARTICLE,    # exclamation
    "p": PartOfSpeech.CONJUNCTION_OTHER,   # preposition
    "c": PartOfSpeech.CONJUNCTION_OTHER,   # conjunction
    "r": PartOfSpeech.CONJUNCTION_OTHER, "rg": PartOfSpeech.CONJUNCTION_OTHER,
    "f": PartOfSpeech.CONJUNCTION_OTHER,   # direction word
    "s": PartOfSpeech.CONJUNCTION_OTHER,   # place word
    "w": PartOfSpeech.PUNCTUATION,
}


class ChineseTokenizer:
    """
    Wraps jieba (segmentation + POS) and pypinyin (per-character pinyin).
    Mirrors ChineseTokenizer.kt.

    Install before use:
        pip install jieba pypinyin
    """

    def tokenize(self, text: str) -> list[Token]:
        if not text or not text.strip():
            return []
        try:
            import jieba.posseg as pseg
        except ImportError as exc:
            raise ImportError("jieba is required: pip install jieba") from exc

        tokens: list[Token] = []
        for word, flag in pseg.cut(text):
            pinyin_str = self._surface_pinyin(word)
            pos = self._classify_pos(word, flag)
            tokens.append(Token(
                surface=word,
                dictionary_form=word,
                reading=pinyin_str,
                dictionary_reading=pinyin_str,
                part_of_speech=pos,
                raw_pos_tag="",
            ))
        return tokens

    def _surface_pinyin(self, surface: str) -> str:
        """
        Space-separated tone-marked pinyin for each CJK character in surface.
        Mirrors ChineseTokenizer.surfacePinyin() — per-char lookup then
        PinyinToneConverter to match pinyin4j WITH_TONE_NUMBER + converter.
        """
        try:
            from pypinyin import pinyin as get_pinyin, Style
        except ImportError as exc:
            raise ImportError("pypinyin is required: pip install pypinyin") from exc

        syllables: list[str] = []
        for c in surface:
            if not _is_cjk(c):
                continue
            result = get_pinyin(c, style=Style.TONE3, heteronym=False)
            if result and result[0]:
                syllables.append(convert_pinyin_syllable(result[0][0]))
        return " ".join(syllables)

    def _classify_pos(self, surface: str, flag: str) -> PartOfSpeech:
        if surface and all(_is_punct_char(c) for c in surface):
            return PartOfSpeech.PUNCTUATION
        return _ICTCLAS_TO_POS.get(flag, PartOfSpeech.NOUN)


# ═════════════════════════════════════════════════════════════════════════════
# Singleton state (module-level, reused across calls for performance)
# ═════════════════════════════════════════════════════════════════════════════

_db_cache:     dict[str, JmdictDB]           = {}
_cedict_cache: dict[str, CedictDB]           = {}
_tok_cache:    dict[str, TangoToriTokenizer] = {}
_cn_tok:       Optional[ChineseTokenizer]    = None


def _get_db(db_path: Optional[str | Path]) -> JmdictDB:
    key = str(db_path or _DEFAULT_DB)
    if key not in _db_cache:
        _db_cache[key] = JmdictDB(key)
    return _db_cache[key]


def _get_cedict(cedict_path: Optional[str | Path]) -> CedictDB:
    key = str(cedict_path or _DEFAULT_CEDICT)
    if key not in _cedict_cache:
        _cedict_cache[key] = CedictDB(key)
    return _cedict_cache[key]


def _get_tokenizer(dic_path: Optional[str | Path]) -> TangoToriTokenizer:
    key = str(dic_path or _DEFAULT_DIC)
    if key not in _tok_cache:
        _tok_cache[key] = TangoToriTokenizer(dic_path or _DEFAULT_DIC)
    return _tok_cache[key]


def _get_cn_tokenizer() -> ChineseTokenizer:
    global _cn_tok
    if _cn_tok is None:
        _cn_tok = ChineseTokenizer()
    return _cn_tok


# ═════════════════════════════════════════════════════════════════════════════
# Main public API
# ═════════════════════════════════════════════════════════════════════════════

def create_card(
    word: str,
    sentence: str,
    source: str = "",
    db_path: Optional[str | Path] = None,
    dic_path: Optional[str | Path] = None,
    cedict_path: Optional[str | Path] = None,
    entry_index: int = 0,
    include_breakdown: bool = True,
) -> Optional[CardData]:
    """
    Build a Tango Tori Anki card identical to what the Android app would produce.
    Language is auto-detected from the sentence.

    Parameters
    ----------
    word              : Target word — surface or dictionary form.
    sentence          : Full sentence the word appears in.
    source            : Optional attribution string stored in the Source field.
    db_path           : Path to jmdict.db (Japanese). Defaults to the app's bundled asset.
    dic_path          : Path to system_core.dic (Sudachi). Defaults to the app's bundled asset.
    cedict_path       : Path to cedict.db (Chinese). Defaults to the app's bundled asset.
    entry_index       : Which result to use when multiple entries match (0 = best).
    include_breakdown : Set False to omit the kanji/hanzi breakdown section.

    Returns
    -------
    CardData or None if the word cannot be found in the dictionary.
    """
    lang = detect_language(sentence)
    if lang in (Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL):
        return _create_chinese_card(word, sentence, source, cedict_path, entry_index, include_breakdown)
    return _create_japanese_card(word, sentence, source, db_path, dic_path, entry_index, include_breakdown)


def create_image_card(
    word: str,
    image: str = "",
    source: str = "",
    db_path: Optional[str | Path] = None,
    dic_path: Optional[str | Path] = None,
    cedict_path: Optional[str | Path] = None,
    entry_index: int = 0,
    include_breakdown: bool = True,
    language: Optional[Language] = None,
) -> Optional[ImageCardData]:
    """
    Build a Tango Tori Image card.
    The UserSentence field is left blank for the user to fill in inside AnkiDroid.

    Parameters
    ----------
    word              : Target word — surface or dictionary form.
    image             : Image to attach. Accepts:
                          • An HTML string already containing <img ...> tags
                          • A bare filename or path (auto-wrapped as <img src="...">)
                          • Empty string — card is created with no image
    source            : Optional attribution string.
    include_breakdown : Set False to omit the kanji/hanzi breakdown section.
    language          : Override language detection. Use Language.JAPANESE,
                        Language.CHINESE_SIMPLIFIED, or Language.CHINESE_TRADITIONAL.
                        Auto-detected from the word when None. Note: a bare kanji
                        like 猫 has no kana to trigger Japanese detection, so pass
                        Language.JAPANESE explicitly when needed.

    Returns
    -------
    ImageCardData or None if the word cannot be found in the dictionary.
    """
    image_html = _normalize_image(image)
    lang = language if language is not None else detect_language(word)
    if lang in (Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL):
        return _create_chinese_image_card(word, image_html, source, cedict_path, entry_index, include_breakdown)
    return _create_japanese_image_card(word, image_html, source, db_path, dic_path, entry_index, include_breakdown)


def _normalize_image(image: str) -> str:
    """Wrap a bare filename/path in an <img> tag; pass through existing HTML unchanged."""
    s = image.strip()
    if not s:
        return ""
    if s.lower().startswith("<"):
        return s
    return f'<img src="{_html_escape(s)}">'


def _create_japanese_card(
    word: str,
    sentence: str,
    source: str,
    db_path: Optional[str | Path],
    dic_path: Optional[str | Path],
    entry_index: int,
    include_breakdown: bool = True,
) -> Optional[CardData]:
    db  = _get_db(db_path)
    tok = _get_tokenizer(dic_path)

    tokens = tok.tokenize(sentence)

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

    entries = db.lookup(target_token.dictionary_form, target_token.dictionary_reading)
    if not entries:
        entries = db.lookup(word)
    if not entries:
        return None
    entry = entries[min(entry_index, len(entries) - 1)]

    _UNLINKABLE = {PartOfSpeech.PUNCTUATION, PartOfSpeech.PARTICLE, PartOfSpeech.AUXILIARY_VERB}
    tokens_with_entries: list[TokenWithEntry] = []
    for t in tokens:
        if t.part_of_speech in _UNLINKABLE:
            eid = None
        else:
            looked_up = db.lookup(t.dictionary_form, t.dictionary_reading)
            eid = looked_up[0].id if looked_up else None
        tokens_with_entries.append(TokenWithEntry(token=t, entry_id=eid))

    word_str    = entry.headword
    reading_str = entry.primary_reading
    furigana    = build_furigana(word_str, reading_str)

    breakdown = _japanese_breakdown(word_str, furigana, db) if include_breakdown else ""

    return CardData(
        word=word_str,
        reading=reading_str,
        furigana=furigana,
        word_ruby=build_furigana_html(furigana),
        meaning_html=build_meaning_html(entry.senses),
        part_of_speech=", ".join(entry.senses[0].part_of_speech) if entry.senses else "",
        jlpt=entry.jlpt_level or "",
        is_common=entry.is_common,
        sentence_html=build_sentence_html(tokens_with_entries, entry.id, is_chinese=False),
        sentence_raw=sentence,
        source=source,
        kanji_breakdown_html=breakdown,
    )


def _create_chinese_card(
    word: str,
    sentence: str,
    source: str,
    cedict_path: Optional[str | Path],
    entry_index: int,
    include_breakdown: bool = True,
) -> Optional[CardData]:
    cedict = _get_cedict(cedict_path)
    tok    = _get_cn_tokenizer()

    tokens = tok.tokenize(sentence)

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

    entries = cedict.lookup(target_token.dictionary_form)
    if not entries:
        entries = cedict.lookup(word)
    if not entries:
        entries = cedict.lookup_by_chars(word)
    if not entries:
        return None
    entry = entries[min(entry_index, len(entries) - 1)]

    _UNLINKABLE = {PartOfSpeech.PUNCTUATION, PartOfSpeech.PARTICLE, PartOfSpeech.AUXILIARY_VERB}
    tokens_with_entries: list[TokenWithEntry] = []
    for t in tokens:
        if t.part_of_speech in _UNLINKABLE:
            eid = None
        elif t.dictionary_form == entry.headword:
            eid = entry.id
        else:
            looked_up = cedict.lookup(t.dictionary_form)
            if not looked_up and len(t.dictionary_form) > 1:
                looked_up = cedict.lookup_by_chars(t.dictionary_form)
            eid = looked_up[0].id if looked_up else None
        tokens_with_entries.append(TokenWithEntry(token=t, entry_id=eid))

    word_str     = entry.headword
    pinyin_marks = entry.primary_reading

    pinyin_markup = build_pinyin(word_str, pinyin_marks)
    word_ruby     = build_pinyin_html(pinyin_markup)
    breakdown     = _chinese_breakdown(word_str, pinyin_marks, cedict) if include_breakdown else ""

    return CardData(
        word=word_str,
        reading=pinyin_marks,
        furigana=pinyin_markup,
        word_ruby=word_ruby,
        meaning_html=build_meaning_html(entry.senses),
        part_of_speech=", ".join(entry.senses[0].part_of_speech) if entry.senses else "",
        jlpt=entry.jlpt_level or "",
        is_common=entry.is_common,
        sentence_html=build_sentence_html(tokens_with_entries, entry.id, is_chinese=True),
        sentence_raw=sentence,
        source=source,
        kanji_breakdown_html=breakdown,
    )


def _create_japanese_image_card(
    word: str,
    image_html: str,
    source: str,
    db_path: Optional[str | Path],
    dic_path: Optional[str | Path],
    entry_index: int,
    include_breakdown: bool,
) -> Optional[ImageCardData]:
    db = _get_db(db_path)

    # Direct lookup first; fall back to tokenizing the word if needed.
    entries = db.lookup(word)
    if not entries:
        tok = _get_tokenizer(dic_path)
        tokens = tok.tokenize(word)
        if tokens:
            t = tokens[0]
            entries = db.lookup(t.dictionary_form, t.dictionary_reading)
    if not entries:
        return None
    entry = entries[min(entry_index, len(entries) - 1)]

    word_str    = entry.headword
    reading_str = entry.primary_reading
    furigana    = build_furigana(word_str, reading_str)
    breakdown   = _japanese_breakdown(word_str, furigana, db) if include_breakdown else ""

    return ImageCardData(
        word=word_str,
        reading=reading_str,
        furigana=furigana,
        word_ruby=build_furigana_html(furigana),
        meaning_html=build_meaning_html(entry.senses),
        part_of_speech=", ".join(entry.senses[0].part_of_speech) if entry.senses else "",
        jlpt=entry.jlpt_level or "",
        is_common=entry.is_common,
        image_html=image_html,
        user_sentence="",
        source=source,
        kanji_breakdown_html=breakdown,
    )


def _create_chinese_image_card(
    word: str,
    image_html: str,
    source: str,
    cedict_path: Optional[str | Path],
    entry_index: int,
    include_breakdown: bool,
) -> Optional[ImageCardData]:
    cedict = _get_cedict(cedict_path)
    entries = cedict.lookup(word)
    if not entries:
        entries = cedict.lookup_by_chars(word)
    if not entries:
        return None
    entry = entries[min(entry_index, len(entries) - 1)]

    word_str     = entry.headword
    pinyin_marks = entry.primary_reading
    pinyin_markup = build_pinyin(word_str, pinyin_marks)
    breakdown     = _chinese_breakdown(word_str, pinyin_marks, cedict) if include_breakdown else ""

    return ImageCardData(
        word=word_str,
        reading=pinyin_marks,
        furigana=pinyin_markup,
        word_ruby=build_pinyin_html(pinyin_markup),
        meaning_html=build_meaning_html(entry.senses),
        part_of_speech=", ".join(entry.senses[0].part_of_speech) if entry.senses else "",
        jlpt=entry.jlpt_level or "",
        is_common=entry.is_common,
        image_html=image_html,
        user_sentence="",
        source=source,
        kanji_breakdown_html=breakdown,
    )


def _japanese_breakdown(word_str: str, furigana: str, db: "JmdictDB") -> str:
    kanji_chars = list(dict.fromkeys(c for c in word_str if _is_kanji(c)))
    kanji_data  = {c: db.get_kanji_dic(c) for c in kanji_chars}
    return build_kanji_breakdown_html(
        furigana,
        lambda c: kanji_data.get(c, ([], set()))[0],
        lambda c: kanji_data.get(c, ([], set()))[1],
    )


def _chinese_breakdown(word_str: str, pinyin_marks: str, cedict: "CedictDB") -> str:
    def char_meanings_fn(c: str) -> list[str]:
        result = cedict.lookup_char(c)
        return result[1] if result else []
    return build_hanzi_breakdown_html(word_str, pinyin_marks, char_meanings_fn)


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
        print("Not found in dictionary.")
        sys.exit(1)

    fields = card.FIELD_NAMES
    values = card.to_field_array()
    for name, value in zip(fields, values):
        print(f"[{name}]\n{value}\n")
    print(f"[Tags]\n{' '.join(sorted(card.tags()))}")
