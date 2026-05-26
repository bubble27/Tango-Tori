# Tango Tori — Stage 3 Card Formatting Feedback

Screenshots reference: `仲間` (なかま) card as it currently renders in AnkiDroid.

The card creation pipeline is working — data is reaching Anki correctly. Everything below is about how the card template HTML and CSS need to change to make the cards actually readable and beautiful.

---

## Overview of Both Card Sides

Image 1 appears to be the current card back. Image 2 appears to be either the front or an alternate rendering. The issues span both sides and the card template CSS.

---

## FRONT OF CARD

### Issue 1: Word Not Centered

**Severity: High**

The headword `仲間` renders left-aligned. It should be centered horizontally on the card.

```css
.word {
  text-align: center;
  width: 100%;
}
```

### Issue 2: Word Too Small, Reading Displayed Below Instead of as Furigana Above

**Severity: High**

The word should be large and prominent — this is the thing the user is trying to learn. The reading (`なかま`) should appear as furigana above the kanji, not as a separate line below it. Use an HTML `<ruby>` tag for this.

The correct structure for the front of the card:

```html
<div class="word-block">
  <ruby>仲間<rt>なかま</rt></ruby>
</div>
```

```css
.word-block {
  text-align: center;
  font-size: 52px;
  margin-top: 24px;
}
.word-block rt {
  font-size: 18px;
  color: #78909C;
}
```

For words with per-kanji furigana (from the `Furigana` field in the format `仲[なか]間[ま]`), the template should parse this into individual ruby pairs:

```html
<ruby>仲<rt>なか</rt></ruby><ruby>間<rt>ま</rt></ruby>
```

This gives each kanji its own reading above it rather than one reading floating over the whole word. The app should generate this correctly from the `Furigana` field when building the card HTML.

---

## BACK OF CARD

### Issue 3: Part of Speech Shows "n" Twice Instead of Full Word

**Severity: High**

The current card shows:
```
n

n
1. companion, fellow, friend...
```

Two problems: `n` is the JMdict abbreviation code for "Noun", not a human-readable label. And it's appearing twice — once as a standalone line and once before the sense list.

**Fix:**
Map all JMdict POS abbreviations to full English labels before writing to the card field. The most common ones:

| JMdict code | Display label |
|-------------|---------------|
| `n` | Noun |
| `v1` | Ichidan verb |
| `v5r`, `v5k`, `v5s`, etc. | Godan verb |
| `adj-i` | i-Adjective |
| `adj-na` | na-Adjective |
| `adv` | Adverb |
| `aux-v` | Auxiliary verb |
| `conj` | Conjunction |
| `prt` | Particle |
| `exp` | Expression |
| `vs` | Suru verb |
| `vk` | Kuru verb |
| `int` | Interjection |

The POS label should appear **once**, above the numbered senses, in the muted color `#78909C`, small and italic. It must not repeat.

### Issue 4: JLPT Badge Should Not Appear on the Card Back

**Severity: Medium**

The N3 badge is currently at the bottom of the card back. JLPT level is metadata, not part of what you're being tested on. Remove it from the card template entirely.

If the user wants JLPT info accessible, it can live in a non-tested field (like a note or tag) but it should not render in the card body.

### Issue 5: Context Sentence Has Blue Hyperlink Underlines

**Severity: High**

The sentence `泥棒仲間にも仁義がある。` is rendering with blue underlined hyperlinks on each word. This is default browser/WebView anchor styling bleeding through. The links need to exist (so tapping a word in the sentence opens Kanji Study) but they must not look like links.

```css
.sentence a {
  color: inherit;
  text-decoration: none;
}
```

The sentence should read as plain natural Japanese text. The only visual distinction should be the target word — `仲間` in this sentence — which should be colored in the app's primary red `#C0392B` rather than bold.

```css
.sentence .target-word {
  color: #C0392B;
  font-weight: normal;
}
```

The furigana (ruby text) above each word in the sentence should remain visible but in a muted color, not the link blue.

```css
.sentence rt {
  color: #78909C;
}
```

### Issue 6: Meanings Section Formatting

**Severity: Medium**

Beyond the "n" abbreviation issue (Issue 3), the meanings block has layout problems. Currently glosses within a single sense are all on one line separated by commas, which for sense 1 of 仲間 produces a very long run-on line. The numbered format is inconsistent.

The meanings field HTML written by the app should follow this structure exactly:

```html
<div class="pos-label">Noun</div>
<ol class="senses">
  <li>companion, fellow, friend, mate, comrade, partner, colleague, coworker, associate</li>
  <li>group, company, circle, set, gang</li>
  <li>member of the same category (family, class)</li>
</ol>
```

```css
.pos-label {
  font-size: 13px;
  color: #78909C;
  font-style: italic;
  margin-bottom: 6px;
}
.senses {
  padding-left: 20px;
  margin: 0;
}
.senses li {
  font-size: 16px;
  line-height: 1.5;
  margin-bottom: 4px;
}
```

If a word has multiple sense groups with different POS (e.g. a word that is both a noun and a suru-verb), render each group with its own `.pos-label` above its `.senses` list.

---

## NEW FEATURE: Kanji Breakdown Section

**Severity: High** (explicitly requested)

### Description

For any word that contains kanji, the card back should include a kanji breakdown section below the meanings. Each kanji in the word gets a small tile showing:
- The individual kanji character, large and centered
- Its reading **as it appears in this specific word** (not all possible readings) — in hiragana always, even if the reading is on'yomi
- Its core meaning (from the kanji's own JMdict/KANJIDIC entry) — the 1–3 most essential English keywords only

For `仲間` (なかま):
- `仲` → reading: `なか` → meaning: "relation, relationship"
- `間` → reading: `ま` → meaning: "interval, space, between"

### Data requirements

This requires the app to:
1. Identify each kanji character in the selected word's headword form
2. Look up each kanji individually in KANJIDIC (or the kanji table in JMdict/KanjiDic2 — whichever is bundled)
3. Extract the kanji's meanings (just the first 2–3 keywords, not the full list)
4. Use the per-character reading from the `Furigana` field already computed (e.g. `仲[なか]間[ま]` → `なか` for `仲`, `ま` for `間`) — this reading is the word-in-context reading, which is correct

**Important:** Use the reading from the furigana alignment (the word-in-context reading), not the list of all possible kanji readings. `間` can read `かん`, `けん`, `ま`, `あいだ`, etc., but in `仲間` it reads `ま`, and that's what should be shown.

### HTML structure for the kanji section

```html
<div class="kanji-section">
  <div class="kanji-tile">
    <div class="kanji-char">仲</div>
    <div class="kanji-reading">なか</div>
    <div class="kanji-meaning">relation, relationship</div>
  </div>
  <div class="kanji-tile">
    <div class="kanji-char">間</div>
    <div class="kanji-reading">ま</div>
    <div class="kanji-meaning">interval, between</div>
  </div>
</div>
```

```css
.kanji-section {
  display: flex;
  flex-direction: row;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #E0D8CE;
}
.kanji-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 80px;
}
.kanji-char {
  font-size: 36px;
  line-height: 1.2;
}
.kanji-reading {
  font-size: 14px;
  color: #78909C;
  margin-top: 2px;
}
.kanji-meaning {
  font-size: 12px;
  color: #999;
  text-align: center;
  margin-top: 2px;
}
```

For a word with only one kanji, the single tile is still centered. For a word with 4+ kanji, tiles wrap or reduce in size to fit. For pure-kana words, the kanji section is omitted entirely.

---

## Card Template Summary — Full Target State

### Front
```
[centered]
  なか ま          ← furigana above each kanji
  仲  間           ← large (52px), centered
```

### Back
```
[centered headword with furigana — same as front]

[divider]

Noun                         ← muted italic, 13px
1. companion, fellow, friend, mate...
2. group, company, circle, set, gang
3. member of the same category (family, class)

[divider]

泥棒仲間にも仁義がある。      ← plain text, furigana above each word,
                               仲間 in red #C0392B, no underlines

[divider]

     仲          間           ← kanji tiles, centered, flexrow
    なか           ま
  relation      interval
```

---

## CSS: Dark Mode Support

AnkiDroid respects the system dark mode and can apply a `.night_mode` class to the card body. The current card has hardcoded white backgrounds and black text that will look broken in dark mode.

Add dark mode overrides:

```css
.card {
  background-color: #F5EFE6;
  color: #1A1A1A;
}
.night_mode .card {
  background-color: #1A1A1A;
  color: #F5EFE6;
}
.night_mode .kanji-section {
  border-top-color: #333;
}
.night_mode .pos-label,
.night_mode .kanji-reading {
  color: #90A4AE;
}
```

---

## Priority Order

1. Per-kanji ruby furigana on the headword (front and back)
2. Sentence: remove link styling, target word in red
3. POS abbreviation → full English label, rendered once
4. Kanji breakdown section (new feature, requires KANJIDIC lookup)
5. Center and size the word on front
6. Remove JLPT badge from back
7. Meanings list using `<ol>` for clean numbering
8. Dark mode CSS
