# Tango Tori — Stage 2 Feedback & Bug Report

Screenshots reference: `スタジオに入った瞬間、空気が変わった。` with `入る` expanded showing disambiguation and dictionary entry.

Good progress. The core layout is substantially improved from Stage 1 — inline sentence flow, furigana above kanji, POS underlines, and dictionary entries with numbered senses are all working. What follows is everything still missing or broken before Stage 3 can begin.

---

## 1. JLPT Level Badge Missing

**Severity: High** (explicitly noted by user)

### Problem
The expanded dictionary entry for `入る` shows a "Common" badge but no JLPT level badge. `入る` is an N4 word and should show an "N4" badge. This applies to all words with a known JLPT level.

### Fix
The JLPT level is stored in the JMdict database. Render it as a badge next to the "Common" badge in the entry header. Style it similarly to "Common" but with a distinct color — suggested: outlined badge with the primary red `#C0392B` border and text, versus the green filled "Common" badge.

Placement: `入る　いる　　[Common] [N4]` — both badges on the same line as the headword and reading.

If JLPT level is null, no badge is shown. Do not show an empty placeholder.

---

## 2. Disambiguation Tabs Visually Identical

**Severity: High**

### Problem
When a word has multiple JMdict entries (the case for `入る`), the disambiguation tabs both show `入る` with no way to tell them apart at a glance. The user has to tap each one to see which is which. This defeats the purpose of the disambiguation UI.

### Fix
Each tab should show the reading that distinguishes the entries. For `入る`:
- Tab 1: `入る（いる）`
- Tab 2: `入る（はいる）`

If the readings are identical across entries (rarer case), use the first gloss of the first sense as the disambiguator, truncated:
- Tab 1: `入る — to be needed`
- Tab 2: `入る — to enter`

The currently selected tab should have a clear active state (filled background, distinct border). The unselected tab should be visually receded. Right now both tabs have outlines of similar weight making selection state ambiguous.

---

## 3. Furigana Still Covering Okurigana

**Severity: High** (same bug, partially fixed from Stage 1, but not fully resolved)

### Problem
Looking at the sentence view:
- `入った` shows `はい` above what appears to be the full `入っ` span
- `変わった` shows `か` above `変わっ` instead of just above `変`

The furigana label is still spanning the kanji plus some okurigana characters rather than stopping at the kanji boundary.

### Expected behavior
- `入った`: furigana `はい` sits above only the single character `入`, then `った` renders inline at full size to the right
- `変わった`: furigana `か` sits above only the single character `変`, then `わった` renders inline

The width of the furigana container should match the width of the kanji character(s) only, not the full surface form token. If the furigana text is wider than the kanji below it, the furigana should be allowed to slightly overhang, but the kanji text below must not stretch to match the furigana width.

This is a layout constraint issue in the `FuriganaText` composable — the furigana `Box` is likely being sized to the full token width rather than just the kanji character width.

---

## 4. `、` (Japanese Comma) Still Has a Colored Underline in Sentence View

**Severity: Medium**

### Problem
In the sentence view, the `、` character has a colored underline applied to it, treating it as a vocabulary chip. It should be plain unstyled text.

### Fix
`、` is tagged by Sudachi as `補助記号-読点` (supplementary symbol, comma). This and all `補助記号` tokens must be rendered as plain `Text()` composables with no underline, no color, no tap interaction, and no furigana area reserved above them. They should flow inline between surrounding word chips at minimal spacing (0–2dp margin).

---

## 5. "Other Forms" Section Missing from Entry

**Severity: Medium**

### Problem
The JMdict entry for `入る` contains alternate kanji forms (e.g. `這入る`). The spec calls for an "Other forms" section below the senses, as shown in the Kanji Study screenshot provided during spec creation. This section is not present.

### Fix
After the numbered senses, if the entry has additional kanji headwords beyond the primary one, render an "Other forms" section:

```
Other forms
こわ
毀れる ③
```

Style: section label in muted color (`#78909C`), forms listed below with their reading above in small text, matching the Kanji Study visual treatment. If there are no alternate forms, the section is omitted entirely.

---

## 6. Sense Misc / Field / Dialect Notes Missing

**Severity: Medium**

### Problem
JMdict senses can carry additional metadata:
- `misc`: e.g. "usually written in kana alone", "honorific", "colloquial", "obsolete"
- `field`: domain label, e.g. "mathematics", "medicine", "baseball"
- `dialect`: e.g. "Kansai dialect"

None of these are rendering in the expanded entry. For many words these are important context — `misc = "usually written in kana"` on a kanji entry is something a learner genuinely needs to know.

### Fix
Below each sense's POS label and above the numbered glosses, render any `misc`, `field`, and `dialect` values in small italic muted text (same `#78909C` color used for POS). Example:

```
Godan verb (-ru), Intransitive verb
  Usually written in kana alone
1. to enter; to go in...
```

If none of these fields are present on a sense, nothing extra is shown.

---

## 7. Katakana Words Showing Redundant Kana Reading

**Severity: Medium**

### Problem
`スタジオ` in the word list shows `すたじお` as its reading. This is redundant — katakana already is the phonetic representation. Showing a hiragana transliteration of a katakana loanword adds noise without helping the user.

### Fix
For tokens where the surface form (or dictionary form) is pure katakana, suppress the reading label in the word list. The word itself is already its own reading.

Same logic applies in the sentence view: `スタジオ` should have no furigana above it (this appears to be working correctly already in the sentence view — the fix is only needed in the word list).

---

## 8. Particles Not De-emphasized in Word List

**Severity: Medium**

### Problem
`に` appears in the word list at the same visual weight and size as content words like `入る` and `瞬間`. Particles and auxiliary verbs clutter the list since users almost never want to make Anki cards for them.

### Fix
Particles (`助詞`), auxiliary verbs (`助動詞`), and copulas should render in the word list at:
- Smaller font size: ~13sp for the word, ~11sp for the reading
- Reduced opacity: ~50% alpha on the whole row
- No "Select this word" button when expanded — or if shown, it should be greyed out with a tooltip "Particles cannot be added as cards"

They should still be expandable (the user might be curious what JMdict says about `に`) but they must be visually subordinate to content words.

---

## 9. Entry Header Reading Placement Inconsistent Between Tabs

**Severity: Low-Medium**

### Problem
In the two screenshots, the header of the expanded `入る` entry shows:
- Image 1 (tab 1 selected): `入る　いる` — reading appears next to the headword inline
- Image 2 (tab 2 selected): `入る　はいる` — same pattern

This is actually fine, but comparing the two tabs, it appears the reading shown in the header changes correctly when switching tabs. However the furigana *above* the headword kanji in the entry header does not appear to update. If there's a separate furigana display above `入る` in the header, it must also update when the tab changes to reflect the selected entry's reading.

Verify that all three places where the reading appears (disambiguation tab label, entry header inline reading, furigana above headword) all reflect the currently selected entry.

---

## 10. No Visual Indicator That "Select This Word" Advances to Stage 3

**Severity: Low**

### Problem
The "Select this word" button is present and correctly placed, but there is no affordance communicating what happens next. First-time users may not know this is the gateway to card creation.

### Fix
Add a small subtitle below the button or use button icon + label: `Select this word  →` or `Select this word  [card icon]`. Alternatively, the button label itself could read `Create Anki card for 入る` to make the intent explicit. This is a minor UX polish item.

---

## Summary — Priority Order for Stage 2 Fixes

1. JLPT badge — data is in the DB, just needs to render
2. Disambiguation tab labels — show reading in each tab
3. Furigana okurigana boundary — kanji-width container only
4. `、` punctuation underline — filter `補助記号` from chip styling
5. Other forms section — render alternate kanji headwords
6. Sense misc/field/dialect notes — render below POS label per sense
7. Katakana reading suppression in word list
8. Particle de-emphasis in word list
9. Entry header reading consistency across tab switch
10. "Select this word" button label clarity (low priority, can defer)
