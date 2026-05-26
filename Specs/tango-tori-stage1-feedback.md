# Tango Tori — Stage 1 Feedback & Bug Report

Screenshot reference: `スタジオに入った瞬間、空気が変わった。`

This document lists every visible issue with the current Stage 1 implementation, with clear descriptions of what is wrong and what the correct behavior should be. Issues are grouped by area.

---

## 1. Punctuation Appearing as Words

**Severity: High**

### Problem
Japanese punctuation (`、` `。` `「` `」` `・` etc.) is appearing both as chips in the sentence view and as entries in the word list below. In the screenshot, `、` and `。` each have their own list entries with a dot reading below them.

### Fix
Filter out all punctuation tokens before rendering. In Sudachi's POS system, punctuation is tagged as `補助記号` (supplementary symbol). These tokens should:
- Still be rendered inline in the sentence view as plain unstyled text (so the sentence looks like the original), but they must **not** be tappable chips and must **not** appear in the word list.
- Specifically exclude: `。` `、` `！` `？` `…` `・` `「` `」` `『` `』` `（` `）` `〜` and any other `補助記号` tokens.

The sentence view should feel like reading the original sentence with the words highlighted — punctuation is part of that visual but not part of the vocabulary system.

---

## 2. Sentence View Layout — Words Not Flowing Inline

**Severity: High**

### Problem
The tokenized sentence is not rendering as a continuous inline sentence. Words are breaking onto new lines in a jagged, unnatural way. In the screenshot, `入っ` and `変わっ` appear on the line below where they should sit, and there is inconsistent horizontal spacing between tokens. The sentence looks like a broken grid rather than flowing text.

### Root Cause (Likely)
This is almost certainly a `FlowRow` / `LazyRow` layout issue. If the composable is using a `Row` that doesn't wrap, or a `LazyRow`, tokens will overflow. If it's using a manually-constructed flow layout, the furigana height is probably not being accounted for uniformly across all chips, causing baseline shifting.

### Fix
Use Jetpack Compose's `FlowRow` (from `androidx.compose.foundation.layout`) for the sentence view. Every token — including pure-kana tokens with no furigana — must reserve the same vertical space for the furigana area. Tokens without furigana should have an invisible spacer of the same height as the furigana text, so all chips share a common baseline and the row height is uniform.

```
[furigana area — fixed height, empty if no furigana]
[word text]
```

This fixed-height two-row structure per chip ensures consistent vertical rhythm regardless of which tokens have furigana.

---

## 3. Furigana Shown for Non-Kanji Tokens

**Severity: High**

### Problem
Furigana is being displayed above tokens that contain no kanji — pure kana words do not need furigana because the reading is identical to the word itself. In the screenshot, `入っ` shows `はいっ` above it, which is fine since `入` is kanji. However the logic must reliably suppress furigana for any token that contains zero kanji characters.

### Fix
Before rendering the furigana label, check whether the surface form of the token contains any character in the CJK Unified Ideographs range (U+4E00–U+9FFF, U+3400–U+4DBF, and the extension blocks). If it contains no kanji, render the chip with an empty/invisible furigana area (same height, no text). Do not render a furigana label at all.

Tokens to suppress furigana for:
- Pure hiragana: `に` `た` `が` `て` etc.
- Pure katakana: `スタジオ` `コーヒー` etc.
- Numerals: `１` `２` etc.
- Mixed kana-only (no kanji present in the surface form)

---

## 4. Furigana Showing Surface Form Reading Instead of Kanji-Aligned Reading

**Severity: High**

### Problem
The furigana above kanji-containing tokens is showing the reading of the entire surface form token rather than the reading aligned to just the kanji characters. In the screenshot:
- `入っ` shows `はいっ` — the reading of the conjugated surface form `入った`, not just `入`
- `変わっ` shows `かわっ` — the reading of the conjugated surface form, not just `変`

This is incorrect. Furigana should appear only above the kanji portion of a token, and it should show only the reading for that kanji portion, not the reading of the whole conjugated form.

### Fix
This requires kanji-reading alignment. The approach:

1. Sudachi provides the full token reading (e.g. `いった` for `入った`).
2. Identify which characters in the surface form are kanji vs. kana.
3. The kana characters at the end of a verb form (okurigana, e.g. `った`) will match the end of the reading. Strip the matching okurigana suffix from both the surface form and the reading.
4. What remains: the kanji portion (`入`) and its isolated reading (`い`).
5. Render the furigana `い` above `入`, and render `った` as plain text inline.

For a token like `瞬間` (all kanji, reading `しゅんかん`): the full reading sits above the full kanji word — no okurigana stripping needed.

For a token like `変わっ` (kanji `変` + okurigana `わっ`, reading `かわっ`): furigana `か` above `変`, then `わっ` inline.

**Important:** The furigana label above a chip should only cover the kanji span. The okurigana renders at the same size as the main word text, inline after the kanji.

---

## 5. POS Colors Applied Only to Readings, Not to Word Text

**Severity: High**

### Problem
In the current implementation, the part-of-speech color is being applied to the furigana/reading text (small text above chips), but the word text itself is rendered in the default text color. The visual effect is that only tiny labels are colored, which is barely noticeable and not useful.

### Fix
The POS color should be the primary visual indicator on the **word text itself**, not on the furigana. Two acceptable approaches — pick one:

**Option A (Recommended): Colored underline/bottom border on the chip**
The word text renders in the standard text color (black on light, white on dark). A colored bottom border or underline in the POS color visually tags the chip. This is clean and doesn't interfere with readability.

**Option B: Colored word text**
The word text itself is rendered in the POS color. Works well for content words (nouns, verbs) but particles in grey can look too faint. If this approach is used, particles should remain at legible contrast — `#888888` may be too light on the parchment background.

In either case, the furigana text should be rendered in a neutral muted color (e.g. `#78909C` or the muted POS color at 60% opacity), not in the full POS color. Furigana is a reading aid, not the primary color carrier.

---

## 6. Word List — Readings Show Surface Form, Not Dictionary Form

**Severity: High**

### Problem
In the word list below the sentence view, each word's reading (shown in small colored text under the dictionary form) is displaying the reading of the **surface form** (the conjugated/inflected form as it appeared in the sentence), not the reading of the **dictionary form** (the base form shown as the list title).

In the screenshot:
- `入る` shows reading `はいっ` — should show `はいる`
- `変わる` shows reading `かわっ` — should show `かわる`
- `瞬間` shows `しゅんかん` — this one happens to be correct since it's a noun with no conjugation

### Fix
Sudachi provides both the surface form reading and the dictionary form reading. Use the **dictionary form reading** for the reading label in the word list. This is the reading of the lemma/base form, which is what the user needs to learn.

The surface form reading is still used for furigana in the sentence view (since that's showing the actual sentence), but the word list is showing base forms and must use base form readings.

---

## 7. Word List — Particles and Auxiliary Verbs Not Visually De-emphasized

**Severity: Medium**

### Problem
Particles (`に` `が`), auxiliary verbs (`た`), and copulas (`です`) are showing in the word list at the same visual weight as content words like `瞬間` and `空気`. These are grammatical function words that users will rarely want to make Anki cards for. Giving them equal visual prominence clutters the list.

### Fix
As specified in the original spec, particles and punctuation in the word list should be:
- Rendered at smaller font size (e.g. 12sp instead of 16sp for content words)
- Lower opacity (e.g. 50% alpha)
- Not selectable as card targets (tapping them should do nothing or show a small tooltip "Particles cannot be added as cards")
- Ideally grouped or visually separated from content words, though this is a nice-to-have for now

---

## 8. Word List — Too Much Whitespace Between Items

**Severity: Low-Medium**

### Problem
Each word in the list is taking up a disproportionate amount of vertical space. The list feels very sparse — only the dictionary form and a reading label, with large padding around each item. For a sentence of 10 words, the list requires significant scrolling even on a tall screen.

### Fix
Reduce vertical padding on list items. A compact card layout:
- Dictionary form (16sp, normal weight)
- Reading (12sp, muted color) immediately below with ~2dp gap
- Total item height should be approximately 52–60dp
- Add a subtle divider between items instead of large padding

Content word items can have a slightly larger touch target than particle items, reinforcing the visual hierarchy.

---

## 9. Sentence View — Spacing Between Chips Too Large

**Severity: Low-Medium**

### Problem
There is too much horizontal space between tokens in the sentence view. Japanese text in its natural form has no spaces between words — the spaced-out chip layout is acceptable since it communicates word boundaries, but the current gaps are large enough that the sentence loses its readability as a sentence.

### Fix
Reduce the horizontal gap between chips in the `FlowRow` to approximately 4–6dp. This keeps words visually distinct (as separate chips) while maintaining sentence-like density. Punctuation tokens (`、` `。`) should be rendered with near-zero left margin (0–2dp) since Japanese punctuation follows its preceding word without a space.

---

## 10. Word List — `た` Appears Twice as Separate Entries

**Severity: Low**

### Problem
The sentence `スタジオに入った瞬間、空気が変わった。` contains `た` twice (once in `入った` and once in `変わった`). Both appear as separate entries in the word list. While technically correct (they are separate tokens), two identical-looking entries for `た` is confusing.

### Fix
For now, both entries are acceptable since they are genuinely separate tokens. However, the list items should include a subtle disambiguator — for example, show the surface context in parentheses: `た (入った)` and `た (変わった)`. This is a low-priority enhancement; the immediate priority is fixing the above issues first.

---

## Summary — Priority Order

1. Sentence view layout — `FlowRow` with uniform chip height (fixes the broken inline display)
2. Furigana: suppress for non-kanji tokens
3. Furigana: align to kanji characters only, strip okurigana correctly
4. POS colors on word text (not on furigana)
5. Exclude punctuation from word list
6. Word list readings: use dictionary form reading, not surface form reading
7. De-emphasize particles and function words in list
8. Reduce chip spacing in sentence view
9. Reduce whitespace in word list
10. Disambiguate duplicate function word entries (low priority, can defer to later)
