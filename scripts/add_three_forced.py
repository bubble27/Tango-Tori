"""
add_three_forced.py  –  Force-add the 3 words that build_n5_full.py skipped.

Each case needs a small manual workaround:
  会う      – sentence uses 会えた (potential past); we force that token to link
              to the 会う entry so the card highlights the right word.
  終る      – archaic kanji form; JMdict resolves it to 終わる; we look up
              終わる so the sentence token 終わった matches normally.
  ラジオカセ – truncated word not in JMdict; we use the ラジオカセット entry
              but bridge to the ラジオカセ token in the sentence.
"""

import hashlib
import sys
from pathlib import Path

import genanki
import tango_tori as tt
from tango_tori import (
    CardData, PartOfSpeech, TokenWithEntry,
    build_furigana, build_furigana_html, build_meaning_html,
    build_sentence_html, build_kanji_breakdown_html,
    _get_db, _get_tokenizer, _is_kanji,
)

HERE     = Path(__file__).parent
OUT_APKG = HERE / "Tango Tori - N5 (forced).apkg"

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

_UNLINKABLE = {PartOfSpeech.PUNCTUATION, PartOfSpeech.PARTICLE, PartOfSpeech.AUXILIARY_VERB}


def _build_tokens_with_entries(
    sentence: str,
    db: tt.JmdictDB,
    tok: tt.TangoToriTokenizer,
    overrides: dict[str, int],   # surface → entry_id to force
) -> list[TokenWithEntry]:
    """
    Tokenize sentence and look up each token.
    overrides lets callers force a specific entry_id for a given token surface.
    """
    tokens = tok.tokenize(sentence)
    result = []
    for t in tokens:
        if t.surface in overrides:
            eid = overrides[t.surface]
        elif t.part_of_speech in _UNLINKABLE:
            eid = None
        else:
            entries = db.lookup(t.dictionary_form, t.dictionary_reading)
            eid = entries[0].id if entries else None
        result.append(TokenWithEntry(token=t, entry_id=eid))
    return result


def _make_card(entry: tt.DictEntry, sentence: str, tokens_with_entries: list[TokenWithEntry]) -> CardData:
    word_str    = entry.headword
    reading_str = entry.primary_reading
    furigana    = build_furigana(word_str, reading_str)

    kanji_chars = list(dict.fromkeys(c for c in word_str if _is_kanji(c)))
    kanji_data  = {c: db.get_kanji_dic(c) for c in kanji_chars}
    def meanings_fn(c: str): return kanji_data.get(c, ([], set()))[0]
    def readings_fn(c: str): return kanji_data.get(c, ([], set()))[1]

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
        kanji_breakdown_html=build_kanji_breakdown_html(furigana, meanings_fn, readings_fn),
    )


sys.stdout = open(sys.stdout.fileno(), mode="w", encoding="utf-8", buffering=1)

db  = _get_db(None)
tok = _get_tokenizer(None)

deck = genanki.Deck(DECK_ID, "Tango Tori - N5")
cards: list[tuple[str, CardData]] = []

# ── 1. 会う ──────────────────────────────────────────────────────────────────
# Sentence uses 会えた (potential past). We force that token to carry 会う's
# entry ID so it gets highlighted as the target word.
print("Building: 会う")
s_au    = "「サシャ、また会えたね」とゾーイが草の上で静かに言う。"
entry_au = db.lookup("会う")[0]
twes_au  = _build_tokens_with_entries(s_au, db, tok, overrides={"会えた": entry_au.id})
card_au  = _make_card(entry_au, s_au, twes_au)
cards.append(("会う", card_au))
print(f"  → Word={card_au.word}, JLPT={card_au.jlpt}, common={card_au.is_common}")

# ── 2. 終る ──────────────────────────────────────────────────────────────────
# Archaic kanji form; JMdict resolves both 終る and 終わる to the same entry
# (headword 終わる). Looking up 終わる means the sentence token 終わった
# (dict form 終わる) matches normally — no override needed.
print("Building: 終る → 終わる")
s_ow    = "「終わった後の静かさって、始まる前と同じかな」とゾーイが言う。"
card_ow = tt.create_card("終わる", s_ow)
assert card_ow is not None, "終わる lookup failed unexpectedly"
cards.append(("終る", card_ow))
print(f"  → Word={card_ow.word}, JLPT={card_ow.jlpt}, common={card_ow.is_common}")

# ── 3. ラジオカセ ─────────────────────────────────────────────────────────────
# Truncated word; JMdict has ラジオカセット. We use that entry but force the
# ラジオカセ surface token to carry its entry ID so it gets highlighted.
print("Building: ラジオカセ → ラジオカセット")
s_rc     = "「サシャ、ラジオカセから流れる歌はなぜこんなに遠く感じるのかな」とゾーイが言う。"
entry_rc = db.lookup("ラジオカセット")[0]
twes_rc  = _build_tokens_with_entries(s_rc, db, tok, overrides={"ラジオカセ": entry_rc.id})
card_rc  = _make_card(entry_rc, s_rc, twes_rc)
cards.append(("ラジオカセ", card_rc))
print(f"  → Word={card_rc.word}, JLPT={card_rc.jlpt}, common={card_rc.is_common}")

# ── Write deck ────────────────────────────────────────────────────────────────
print()
for expr, card in cards:
    note = genanki.Note(
        model=_model,
        fields=card.to_field_array(),
        tags=sorted(card.tags()),
        guid=genanki.guid_for(card.word, card.sentence_raw),
    )
    deck.add_note(note)
    print(f"Added: {expr} → {card.word}")

genanki.Package(deck).write_to_file(str(OUT_APKG))
print(f"\nOutput: {OUT_APKG}")
print(f"Cards:  {len(cards)}")
