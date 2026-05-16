# Tango Tori 🐦 — Android App Specification

## Overview

Tango Tori (単語取り) is an Android app for Japanese learners. The user pastes a Japanese sentence, the app tokenizes it with furigana and part-of-speech color coding, shows dictionary entries for each word from a bundled JMdict database, and allows the user to create a custom Anki card with the word, its readings, meanings, and the original sentence as context — with every word in the sentence linked to its Kanji Study dictionary page.

---

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** API 26 (Android 8.0) — covers ~95%+ of active devices
- **Target SDK:** Latest stable
- **UI:** Jetpack Compose with Material 3
- **Architecture:** MVVM with Repository pattern, Hilt for DI
- **Tokenizer:** Sudachi (com.worksap.nlp.sudachi) with SudachiDict (downloaded on first launch)
- **Dictionary:** JMdict bundled as SQLite via Room (pre-built DB asset, or download on first launch alongside Sudachi)
- **Anki integration:** AnkiDroid API (com.ichi2.anki.api)
- **Async:** Kotlin Coroutines + Flow
- **Theming:** Dynamic light/dark with custom Tango Tori palette

---

## Visual Design

### Color Palette

Derived from the ink-brush logo (black ink, red accent, cream/parchment background):

```
Primary:         #C0392B  (logo red)
OnPrimary:       #FFFFFF
Surface (light): #F5EFE6  (parchment)
Surface (dark):  #1A1A1A  (deep ink)
Background:      matches Surface
Accent/FAB:      #C0392B
```

### Part-of-Speech Colors

Used for word chips in the sentence view. Must be legible on both light and dark surfaces — use these as text/underline colors rather than backgrounds.

| Part of Speech         | Color     | Hex       |
|------------------------|-----------|-----------|
| Noun (名詞)             | Parchment | `#C8A882` |
| Verb (動詞)             | Logo red  | `#C0392B` |
| i-Adjective (形容詞)    | Burnt orange | `#E07B54` |
| na-Adjective (形容動詞) | Ochre     | `#D4956A` |
| Particle (助詞)         | Grey      | `#888888` |
| Adverb (副詞)           | Olive     | `#8B9E6E` |
| Auxiliary verb (助動詞) | Muted red | `#A05050` |
| Conjunction/Other      | Light grey | `#AAAAAA` |

### Typography

- Japanese text: system default (Noto Sans JP will be used by Android if available)
- Furigana rendered above kanji using a custom composable (small text above parent text)
- UI chrome: standard Material 3 type scale

### Motion

- Smooth spring animations for word chip selection (scale + elevation)
- Animated expand/collapse for dictionary entries in the word list
- Shared element transitions between sentence view and card creation screen
- First-launch download screen: animated bird mascot (Lottie or simple custom animation)

---

## App Structure

Three screens, implemented in stages:

```
MainActivity
├── SentenceScreen       (Stage 1 + 2)
│   ├── InputArea
│   ├── TokenizedSentenceView
│   └── WordListView
└── CardCreationScreen   (Stage 3)
```

Navigation: Jetpack Navigation Compose.

---

## Stage 1 — Sentence Tokenization

### Behavior

1. User pastes or types a Japanese sentence into the input field.
2. Tokenization triggers automatically on input change (debounced ~500ms) — no button required.
3. Sudachi tokenizes the sentence into morphemes, extracting for each token:
   - Surface form (as it appears in the sentence)
   - Dictionary form (base form for JMdict lookup)
   - Reading (kana)
   - Part of speech (POS tag mapped to the 8 categories above)
4. The tokenized sentence renders in the **TokenizedSentenceView** as an inline row of chips.
5. Below the sentence view, the **WordListView** renders a card for each non-punctuation token.

### TokenizedSentenceView

- Each token is a tappable chip.
- Furigana appears above each chip using a custom `FuriganaText` composable:
  - Small kana text (approx. 10sp) centered above the main kanji text (approx. 18sp).
  - If a token is pure kana, no furigana is shown (avoid redundancy).
- The chip's bottom border or text color indicates part of speech using the POS color table above.
- Tapping a chip selects it: spring-scale animation, elevated shadow, and scrolls the WordListView to that word's card and expands it.

### WordListView

- Vertical lazy list below the sentence view.
- Each item shows the word in its dictionary form with its reading and a one-line gloss (first JMdict sense, first gloss).
- Items are collapsed by default.
- Tapping a word in either the chip row or the list expands that list item to show the full dictionary entry (see Stage 2).
- Particles and punctuation are shown in the list but visually de-emphasized (smaller, greyed out) — they are not selectable as card targets.

---

## Stage 2 — Dictionary Data

### Data Source

JMdict (Japanese-Multilingual Dictionary), distributed as a pre-built SQLite database bundled with the app or downloaded at first launch alongside the Sudachi dictionary file.

- JMdict entries are identified by a numeric ID (this is the same ID used by Kanji Study / WWWJDIC).
- From the JMdict entry ID, construct the Kanji Study deep link: `kanjistudy://word?id=ENTRY_ID`

### Database Schema (Room)

```
Entry
  id: Long (JMdict/WWWJDIC entry ID)
  isCommon: Boolean
  jlptLevel: String? ("N5"..."N1" or null)

Kanji (0..n per entry)
  entryId: Long
  text: String
  info: String? (e.g. "rarely used kanji form")
  priority: String?

Reading (1..n per entry)
  entryId: Long
  text: String (kana)
  info: String?
  priority: String?
  noKanji: Boolean

Sense (1..n per entry)
  entryId: Long
  partOfSpeech: String (semicolon-separated POS tags)
  misc: String? (e.g. "usually written in kana")
  field: String? (domain, e.g. "mathematics")
  dialect: String?

Gloss (1..n per sense)
  senseId: Long
  text: String
  language: String (default "eng")
```

### Lookup Strategy

1. Sudachi provides the dictionary form of each token.
2. Query JMdict: exact match on kanji text OR reading text = dictionary form.
3. If multiple entries match, return all, ranked by `isCommon` then JLPT level.
4. If no exact match, fall back to reading-only match.

### Expanded Word Card UI

When a word is expanded in the WordListView, show (matching Kanji Study's level of detail):

- **Header:** dictionary form with furigana reading, JLPT badge if present, "Common" badge if `isCommon`
- **Part of speech:** displayed in the POS color, italic, comma-separated (e.g. "Ichidan verb, Intransitive verb")
- **Senses:** numbered list. Each sense:
  - POS label (if it changes between senses)
  - Numbered glosses (e.g. "1. to break, to fall apart")
  - Misc/field notes in smaller italic text
- **Other forms:** alternate kanji writings if present
- **"Select this word" button** — prominent, logo-red, rounded. This designates the word as the card target and navigates to CardCreationScreen.

If a sentence token matched multiple JMdict entries (disambiguation needed), show them as tabs or a segmented control within the expanded card. User picks the correct entry before selecting.

---

## Stage 3 — Anki Card Creation

### AnkiDroid Integration

Uses the AnkiDroid public API (`com.ichi2.anki.api.AddContentApi`).

- Request AnkiDroid permission at runtime on first use.
- If AnkiDroid is not installed, show a prompt with a Play Store link.

### Tango Tori Note Type

Create a custom note type named **"Tango Tori v1"** on first card creation if it doesn't already exist.

**Fields:**

| Field           | Description |
|-----------------|-------------|
| `Word`          | Dictionary form of the target word |
| `Reading`       | Kana reading of the word |
| `Furigana`      | Furigana markup: `膝[ひざ]立[だ]ち` format |
| `Meaning`       | Full HTML meaning block (see below) |
| `PartOfSpeech`  | POS string (e.g. "Ichidan verb, Intransitive") |
| `JLPT`          | e.g. "N4" or empty |
| `IsCommon`      | "1" or "0" |
| `Sentence`      | HTML context sentence with furigana and hyperlinks (see below) |
| `SentenceRaw`   | Plain text of the original sentence (for search/reference) |
| `Source`        | User-editable field for source (manga title, website, etc.) — left blank by default |

**Card Template (front):**
```html
<div class="word">{{Word}}</div>
<div class="reading">{{Reading}}</div>
{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
```

**Card Template (back):**
```html
{{FrontSide}}
<hr>
<div class="pos">{{PartOfSpeech}}</div>
<div class="meaning">{{Meaning}}</div>
{{#JLPT}}<div class="badge">{{JLPT}}</div>{{/JLPT}}
```

### Meaning Field Format

HTML matching Kanji Study's style:

```html
<small><font color='#78909C'>Ichidan verb, Intransitive verb</font></small><br>
1. to break, to fall apart, to collapse<br>
2. to stop working<br>
3. to fall through (of a plan, deal, etc.)
```

### Sentence Field Format

Each word in the original sentence is rendered with furigana and linked to its Kanji Study page:

```html
<a href="kanjistudy://word?id=1234567">
  <ruby>壊<rt>こわ</rt></ruby>れる
</a>
<a href="kanjistudy://word?id=...">
  <ruby>物<rt>もの</rt></ruby>
</a>
...
```

- The target word (the one being turned into a card) is additionally wrapped in `<b>` or a highlight span.
- Particles and punctuation are included as plain text (no link, no ruby needed unless they have a reading to show).
- Pure kana words: linked but no ruby markup needed.

### CardCreationScreen UI

- **Top:** Preview of the sentence with the target word highlighted.
- **Middle:** Editable fields for Word, Reading, and Source. Meaning is shown read-only with an edit toggle.
- **Bottom buttons:**
  - **"Add to [Default Deck]"** — one tap, uses the stored default deck.
  - **"Choose Deck"** — opens AnkiDroid deck picker. On first use (or if no default set), this is the primary CTA. After a deck is chosen, offer "Set as default" inline.
- On success: brief snackbar confirmation ("Card added to [Deck Name] ✓"), then pop back to SentenceScreen with the sentence intact so the user can immediately pick another word.

---

## First Launch Flow

1. Splash screen shows the Tango Tori logo.
2. First-launch screen: explains that the app needs to download the Sudachi dictionary (~50MB). Shows estimated size and a "Download Now" button.
3. Download progress bar with the bird mascot.
4. On completion, proceed to SentenceScreen normally.
5. Dictionary database (JMdict SQLite) is bundled as an asset in the APK (approx. 30–50MB uncompressed) and copied to app storage on first launch.

---

## Settings Screen

Accessible via top-right overflow menu from SentenceScreen.

- **Default Deck:** shows current default deck name, tap to change.
- **Furigana display:** toggle — always show / show only on tap / never show.
- **POS color coding:** toggle on/off.
- **About:** app version, licenses, JMdict attribution.

---

## Implementation Stages

### Stage 1 — Tokenization Only
Goal: paste a sentence, see it correctly split into words with furigana and POS colors.

Deliverables:
- Sudachi integration with first-launch download
- `SentenceScreen` with input field and `TokenizedSentenceView`
- `FuriganaText` composable
- POS color mapping
- Basic collapsed `WordListView` showing surface form + reading

No dictionary lookup yet. Use Sudachi's reading output for furigana only.

### Stage 2 — Dictionary Integration
Goal: every word in the list shows its full JMdict entry when expanded.

Deliverables:
- JMdict SQLite database (bundled asset)
- Room DAOs and repository
- Lookup logic (dictionary form → JMdict entry, with multi-entry disambiguation)
- Expanded word card UI with all senses, POS, JLPT, other forms
- "Select this word" flow

### Stage 3 — Anki Card Creation
Goal: selecting a word creates a properly formatted Anki card and adds it to a deck.

Deliverables:
- AnkiDroid API integration
- "Tango Tori v1" note type creation
- `CardCreationScreen`
- Sentence HTML builder (furigana + Kanji Study links)
- Meaning HTML builder
- Deck chooser with default deck persistence

---

## File / Package Structure

```
com.tangotori.app
├── data
│   ├── db/           Room DB, DAOs, entities
│   ├── jmdict/       JMdict repository, lookup logic
│   ├── sudachi/      Tokenizer wrapper, SudachiToken model
│   └── anki/         AnkiDroid API wrapper
├── domain
│   ├── models/       Token, DictEntry, Sense, Gloss, CardData
│   └── usecases/     TokenizeSentenceUseCase, LookupWordUseCase, CreateCardUseCase
├── ui
│   ├── theme/        Colors, Typography, Theme (light + dark)
│   ├── components/   FuriganaText, WordChip, SenseCard, FuriganaSentence
│   ├── sentence/     SentenceScreen, SentenceViewModel
│   ├── card/         CardCreationScreen, CardViewModel
│   ├── settings/     SettingsScreen
│   └── onboarding/   FirstLaunchScreen, DownloadViewModel
└── di/               Hilt modules
```

---

## External Attribution Required

- **JMdict:** "This app uses the JMdict dictionary file. These files are the property of the Electronic Dictionary Research and Development Group, and are used in conformance with the Group's licence."
- **Sudachi / SudachiDict:** Apache 2.0, WorksApplications Japan
- **AnkiDroid:** LGPL

---

## Out of Scope (Future Versions)

- Sentence history / saved sentences
- Audio playback (pitch accent, TTS)
- Pitch accent visualization (Kanji Study shows the dot-line diagram)
- Kanji breakdown (individual kanji in the word)
- OCR / camera input
- Clipboard auto-detection
