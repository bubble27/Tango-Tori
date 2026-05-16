# Tango Tori 🐦 — Test Specification

## Philosophy

Tests are organized by stage and layer. Unit tests cover individual functions in isolation. Integration tests cover the tokenizer → dictionary lookup pipeline. UI tests cover user-facing flows. Edge cases are collected separately since many cut across layers.

All test sentences are real Japanese and chosen specifically to stress the parts of the system most likely to fail.

---

## Stage 1 — Tokenization Tests

### Unit: `TokenizeSentenceUseCase`

#### Basic correctness

| Input | Expected tokens (surface) | Notes |
|-------|--------------------------|-------|
| `彼女は学校に行った。` | `彼女` `は` `学校` `に` `行っ` `た` `。` | Basic SOV sentence |
| `食べてみてください。` | `食べ` `て` `み` `て` `ください` `。` | Verb chain |
| `すごく美しい景色ですね。` | `すごく` `美しい` `景色` `です` `ね` `。` | i-adjective + copula |
| `静かな部屋が好きです。` | `静か` `な` `部屋` `が` `好き` `です` `。` | na-adjective |
| `ありがとうございます。` | `ありがとうございます` `。` | Single expression, not split |

#### Readings (furigana correctness)

| Token | Expected reading | Common failure mode |
|-------|-----------------|---------------------|
| `行った` | `いった` | Wrongly reads as `おこなった` |
| `今日` | `きょう` | Wrongly reads as `こんにち` |
| `明日` | `あした` | Wrongly reads as `あす` or `みょうにち` |
| `一人` | `ひとり` | Wrongly reads as `いちにん` |
| `大人` | `おとな` | Wrongly reads as `だいじん` |
| `時間` | `じかん` | Should not read as `じかん` vs `とき` depending on context |
| `私` | `わたし` | Check not `わたくし` in casual sentence |
| `彼女` | `かのじょ` | Not `かれじょ` |
| `上手` | `じょうず` | Not `うわて` — context dependent |
| `下手` | `へた` | Not `したて` |

#### Part-of-speech mapping

Sudachi returns detailed POS tags; these must map to the 8 UI categories correctly.

| Sudachi POS tag | Expected UI category |
|-----------------|---------------------|
| `名詞-普通名詞-一般` | Noun |
| `名詞-固有名詞-人名` | Noun |
| `名詞-普通名詞-サ変可能` | Noun |
| `動詞-一般` | Verb |
| `動詞-非自立可能` | Verb |
| `形容詞-一般` | i-Adjective |
| `形容動詞語幹` | na-Adjective |
| `助詞-格助詞` | Particle |
| `助詞-係助詞` | Particle |
| `助詞-副助詞` | Particle |
| `副詞` | Adverb |
| `助動詞` | Auxiliary verb |
| `接続詞` | Conjunction/Other |
| `感動詞` | Conjunction/Other |
| `補助記号-句点` | Punctuation (de-emphasized, not selectable) |

#### Debounce behavior

- Typing rapidly should not trigger multiple simultaneous tokenization jobs.
- Only the final state after 500ms of inactivity should tokenize.
- If the user clears the field, the sentence view and word list should clear immediately.
- Confirm no coroutine leak: cancelling a tokenization in progress (by typing again) does not cause a crash or stale result display.

---

## Stage 1 — Edge Cases

### Empty and trivial inputs

| Input | Expected behavior |
|-------|-------------------|
| Empty string `""` | No tokenization, sentence view empty, no error shown |
| Single space `" "` | Treated as empty |
| Single kanji `魚` | One token, correct reading `さかな`, POS = Noun |
| Single kana `ね` | One token, no furigana (pure kana), POS = Particle or Auxiliary |
| Single punctuation `。` | Shown de-emphasized, not in word list |
| Whitespace-only `　　　` | Full-width spaces treated as empty |

### Very long sentences

- Input of 200+ characters must not cause UI overflow or crash.
- The chip row should wrap to multiple lines gracefully.
- Word list should scroll without jank.
- Test with: `吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。`

### Non-Japanese input

| Input | Expected behavior |
|-------|-------------------|
| `Hello world` | Tokenizer returns tokens but no meaningful readings; display gracefully without furigana |
| `東京Tokyo` | Mixed: `東京` tokenized as Japanese, `Tokyo` as foreign noun — no crash |
| `123` | Numeric token, no furigana, POS = numeral/other |
| `emoji 🐦` | Emoji should not crash the tokenizer; render as an unlinked token |
| Arabic / Cyrillic | Should not crash; rendered as opaque foreign tokens |

### Special Japanese constructions

| Input | Challenge |
|-------|-----------|
| `彼は来なかった。` | Negative auxiliary `なかった` must not eat the verb |
| `食べさせられた。` | Causative-passive — long verb chain, verify no over-splitting |
| `行ってもいいですか？` | Permission question — `も` as particle between verbs |
| `それは本当に素晴らしかったですね。` | Past i-adjective conjugation — reading must be `すばらしかった` |
| `彼女が来るかもしれない。` | `かもしれない` as set expression — should ideally stay together or split cleanly |
| `バカじゃないの？` | Colloquial, ends in rising intonation particle |
| `やばっ！` | Colloquial exclamation, truncated form |
| `ていうか` | Colloquial conjunction — should not crash |

### Furigana display edge cases

| Case | Expected behavior |
|------|------------------|
| Pure hiragana word `たべる` | No furigana rendered above (redundant) |
| Pure katakana `コーヒー` | No furigana rendered |
| Mixed `食べる` | Furigana `たべる` shown above, but only `た` above `食` and `べる` inline is wrong — verify character-level alignment |
| Kanji with multiple readings `行く` vs `行う` | Furigana matches the actual reading in context, not just the first dictionary reading |
| Very long furigana on short kanji `薔薇(ばら)` | Furigana must not overflow into neighboring chips |
| No reading available | Render token without furigana, no crash |

---

## Stage 2 — Dictionary Lookup Tests

### Unit: `LookupWordUseCase`

#### Exact matches

| Dictionary form | Expected JMdict match |
|----------------|-----------------------|
| `食べる` | Entry for たべる (to eat) |
| `行く` | Entry for いく (to go) |
| `美しい` | Entry for うつくしい |
| `静か` | Entry for しずか (na-adj) |
| `学校` | Entry for がっこう (school) |

#### Readings-only words (no kanji form)

| Input | Expected |
|-------|----------|
| `きれい` | Matches 綺麗 entry via reading; `noKanji` flag on reading row is true |
| `かわいい` | Matches 可愛い; reading-first lookup |
| `ありがとう` | Matches entry; expression type |

#### Multiple JMdict entries for one form

| Form | Ambiguity | Expected handling |
|------|-----------|------------------|
| `橋` `端` `箸` all read `はし` | Three different nouns | All three entries shown as tabs; user picks |
| `聞く` `効く` `利く` | Multiple verbs reading `きく` | All shown; user picks |
| `書く` | Single clear match | No disambiguation UI shown |
| `上手` | `じょうず` (skilled) vs `うわて` (upper part) | Both shown; reading used for disambiguation |

#### No match cases

| Input | Expected behavior |
|-------|------------------|
| Rare/archaic kanji not in JMdict | "No dictionary entry found" shown in expanded card; Kanji Study link still constructed using unicode code point fallback `kanjistudy://info?code=XXXXX` |
| Proper noun (person name) `田中` | May or may not be in JMdict; if not, show "No entry found" gracefully |
| Katakana loanword `コーヒー` | JMdict has many katakana entries; should match `珈琲` entry via reading `コーヒー` |
| Slang / neologism not in JMdict | Graceful "No entry found", no crash |
| Empty dictionary form from Sudachi | Defensive null check; skip lookup silently |

#### JLPT and common flags

- Verify that N5 words like `食べる`, `学校`, `行く` return `isCommon = true` and correct JLPT level.
- Verify that an obscure word returns `isCommon = false` and `jlptLevel = null`.
- Badge display: "N4" badge renders correctly on both light and dark backgrounds.
- No JLPT level: no badge shown, no empty badge placeholder shown.

#### Sense and gloss rendering

| Test | Expectation |
|------|-------------|
| Word with 5+ senses (e.g. `上がる`) | All senses rendered; UI scrollable within expanded card |
| Sense with `misc` = "usually written in kana" | Shown in italic below glosses |
| Sense with domain field = "mathematics" | Domain label shown |
| Sense with multiple glosses | All glosses shown comma-separated or line-separated |
| POS changes between senses | New POS label shown at the sense boundary |
| Entry with "Other forms" (e.g. `毀れる` as alternate for `壊れる`) | Other forms section renders with reading above each form |

---

## Stage 2 — Edge Cases

### Sudachi → JMdict mapping failures

- Sudachi may return a dictionary form that doesn't exactly match any JMdict headword (e.g. due to orthographic variation). Test that the lookup falls back to reading-based search before giving up.
- Sudachi may return an empty string as dictionary form for some function words. Confirm no lookup is attempted and no error shown.
- Compound words: `待ち合わせ` — Sudachi may split this or keep it together. Either way the lookup must work.

### Disambiguation UI stress tests

- A sentence with 5+ ambiguous words simultaneously: all disambiguation controls must render without overlap.
- Rapidly tapping between different chips while disambiguation sheet is open: no state corruption.
- Rotating device while disambiguation sheet is open: state preserved.

### Database

- First launch: JMdict asset copy from APK to storage — verify no truncation on large asset.
- Database query on main thread: must never happen — verify all Room calls go through Dispatchers.IO.
- Concurrent lookups (long sentence with many tokens): all lookups resolve correctly with no race condition in results list.
- Database version migration: if schema changes in a future version, old database must be dropped and re-copied cleanly.

---

## Stage 3 — Anki Card Creation Tests

### Unit: `CardDataBuilder`

#### Furigana field generation

| Input | Expected output |
|-------|----------------|
| `食べる`, reading `たべる` | `食[た]べる` |
| `膝立ち`, readings `ひざ`/`だ` | `膝[ひざ]立[だ]ち` |
| `行く`, reading `いく` | `行[い]く` |
| `コーヒー` (katakana, no kanji) | `コーヒー` (no ruby markup) |
| `きれい` (pure kana) | `きれい` (no markup) |
| `東京都`, readings `とうきょう`/`と` | `東京[とうきょう]都[と]` |
| Word with reading longer than kanji span | Verify character count alignment doesn't produce malformed markup |

#### Meaning HTML generation

- Verify the HTML matches Kanji Study's format exactly: `<small><font color='#78909C'>POS</font></small>` followed by numbered glosses.
- Verify HTML entities in gloss text are escaped (e.g. `<` → `&lt;`, `&` → `&amp;`).
- Multiple senses: each sense block separated by `<br>`.
- Sense with no glosses (malformed JMdict data): skip silently, do not produce empty numbered item.

#### Sentence HTML generation

| Case | Expected |
|------|---------|
| All tokens have JMdict IDs | Every content word wrapped in `<a href="kanjistudy://word?id=...">` |
| Token has no JMdict match | Rendered as plain text with furigana ruby only, no `<a>` tag |
| Target word in sentence | Wrapped in additional `<b>` tag or highlight span |
| Particle `は` | Plain text, no link (particles are de-emphasized) |
| Sentence ends with `。` | Punctuation rendered as plain text, no link |
| Sentence with katakana loanword | If JMdict ID found, linked; if not, plain |
| HTML special chars in sentence (hypothetical) | Properly escaped in output |

### Integration: AnkiDroid API

#### Prerequisite checks

| State | Expected behavior |
|-------|------------------|
| AnkiDroid not installed | Show dialog: "AnkiDroid is required. Install it?" with Play Store link |
| AnkiDroid installed, permission not granted | Request permission via AnkiDroid API; show rationale dialog if denied |
| AnkiDroid permission granted | Proceed directly to card creation |
| AnkiDroid installed but outdated (API version too old) | Show "Please update AnkiDroid" message, not a crash |

#### Note type creation

- On first card creation, "Tango Tori v1" note type is created with all 10 fields in the correct order.
- If the note type already exists (app reinstall or previous session), it is reused, not duplicated.
- Field order must be stable — reordering fields in a future app version is a breaking change; document this.

#### Deck selection

| State | Expected behavior |
|-------|------------------|
| No default deck set | "Choose Deck" is primary CTA; "Add to Default" button greyed out or hidden |
| Default deck set | "Add to [Deck Name]" is the primary CTA |
| Default deck deleted in AnkiDroid | Detect missing deck on next card creation; prompt to choose new default |
| User chooses deck, taps "Set as default" | Persisted to SharedPreferences; shown on button next launch |
| AnkiDroid has 0 decks | Should not crash; prompt user to create a deck in AnkiDroid first |

#### Card creation outcomes

| Scenario | Expected behavior |
|----------|------------------|
| Card added successfully | Snackbar: "Card added to [Deck] ✓", pop back to SentenceScreen |
| Duplicate card (same word already in deck) | AnkiDroid returns duplicate error; show "This word is already in [Deck]" — do not silently fail |
| AnkiDroid API call times out | Show error snackbar "Could not reach AnkiDroid", allow retry |
| Network not required | Confirm card creation works fully offline |
| User rotates device on CardCreationScreen | All edited fields preserved via ViewModel state |
| User presses back on CardCreationScreen | Returns to SentenceScreen with sentence intact and target word still selected |

---

## UI / Compose Tests

### SentenceScreen

- Input field accepts Japanese IME input without lag.
- Chip row scrolls horizontally when sentence overflows screen width.
- Tapping a chip scrolls the WordListView to the corresponding card smoothly.
- Expanding a word card in the list causes the chip for that word in the sentence view to update its visual state (selected highlight).
- Collapsing a card restores default chip state.
- Two words expanded simultaneously: only one should be expanded at a time (accordion behavior). Verify tapping a second word collapses the first.
- Settings icon navigates to SettingsScreen.

### FuriganaText composable

- Renders correctly at all system font size settings (small / default / large / largest).
- At font size "Largest", furigana does not overflow chip bounds or overlap with neighboring chips.
- Pure kana tokens render without any furigana container space above them (no empty gap).
- Kanji with 5+ character furigana (e.g. `薔薇` → `ばら`) renders without clipping.
- Dark mode: text and furigana readable against dark surface.
- Light mode: text and furigana readable against parchment surface.

### WordListView

- LazyColumn with 20+ items scrolls at 60fps (no frame drops visible in layout inspector).
- Expanding a card that is partially off-screen automatically scrolls to show it in full.
- Word cards for particles are visually distinct from content words (smaller, lower opacity).

### CardCreationScreen

- Word, Reading, and Source fields are all editable.
- Meaning field shows a read-only summary by default; "Edit" toggle reveals full text field.
- Sentence preview renders HTML ruby correctly in a `WebView` or equivalent.
- "Add to Default" button disabled (and tooltip shown) if AnkiDroid permission not granted.

### Theme / Dark mode

- Toggle device to dark mode mid-session: all screens re-render correctly with no hardcoded white or black backgrounds bleeding through.
- POS colors meet minimum contrast ratio on both light (`#F5EFE6`) and dark (`#1A1A1A`) surfaces. (Verify with Android accessibility checker — particles at `#888888` on `#1A1A1A` may be marginal.)
- Logo red `#C0392B` on dark surface: check contrast on interactive elements.

---

## First Launch Flow Tests

- Progress bar updates as download proceeds; does not freeze at 0% or 99%.
- If download interrupted (airplane mode mid-download): show retry button; partial file cleaned up.
- If app is killed during download: on next launch, resume prompt shown (or restart download cleanly).
- After successful download, Sudachi initializes correctly on first tokenization attempt.
- Sudachi init is async — input field should be disabled with a subtle "Initializing…" label until ready; typing before init complete must not crash.
- JMdict asset copy: verify checksum of copied DB matches the bundled asset (detect truncated copy).

---

## Regression / Stress Tests

### Sentence variety matrix

Run all of the following through the full Stage 1 + 2 pipeline and verify no crashes, no missing readings, no empty word list:

```
# N5 simple
今日は天気がいいです。

# N4 intermediate
この映画は去年見たことがある。

# N3 compound verbs
もっと早く起きればよかったのに。

# N2 passive/causative
先生に叱られてしまった。

# N1 literary
その知らせを聞いた瞬間、彼の顔色が変わった。

# Classical-ish
吾輩は猫である。

# Colloquial/casual
マジで？それやばくない？

# Katakana-heavy
スマートフォンのバッテリーが切れた。

# Numbers
三時間後に東京駅で待ってます。

# Onomatopoeia
雨がザーザー降っている。

# Quote embedded
「行かない」と彼女は静かに言った。

# Long sentence
彼女が昨日デパートで買ってきたという赤いコートはとても高そうだったが、実際には安売りで買ったものだったらしい。
```

### Memory / lifecycle

- Run the full flow 20 times in succession (paste sentence → select word → create card → back → repeat): no memory leak (verify with Android Studio Memory Profiler).
- Put app in background for 5 minutes, return: state fully restored.
- Background → foreground while AnkiDroid permission dialog is showing: no crash.
- Low memory kill (simulate with `adb shell am kill`): on relaunch, app starts cleanly from SentenceScreen.

---

## Known Hard Cases (Document, Don't Block)

These are cases where the system may produce imperfect results. They should be documented in a "Known Limitations" section in the app's about screen, not treated as blocking bugs.

| Case | Limitation |
|------|-----------|
| Homophone disambiguation (橋/端/箸) | Sudachi uses context but may misidentify in ambiguous sentences |
| Non-standard kanji readings (jukujikun) | `今日` → `きょう` is usually correct but may fail in literary edge cases |
| Slangy contractions (`じゃん`, `だろ`) | May tokenize oddly; JMdict may lack entry |
| Proper nouns (person names, place names) | Often not in JMdict; "No entry found" is correct behavior |
| Very new loanwords | Not in JMdict; handled gracefully |
| Code-switching (`それはcoolだよね`) | English fragment between Japanese; render as foreign token without crash |
