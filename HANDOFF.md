# Tango Tori — Handoff for Next Session

The chat is being cleared. This doc captures where the work stands. Read this first along with `tango-tori-spec.md`, `tango-tori-tests.md`, and `tango-tori-stage1-feedback.md` (the latter drives the current work).

The user's auto-memory at `C:\Users\cptns\.claude\projects\c--Users-cptns-OneDrive---University-of-Toronto-Documents-Self-Projects-Tango-Tori\memory\` is reliable. Toolchain + OneDrive + Sudachi gotchas are documented there.

---

## What the project is

Android app for Japanese learners. Paste a sentence → tokenize with Sudachi (with furigana + POS color coding) → look up in JMdict → create an Anki card via the AnkiDroid API. Three stages per spec:

1. **Stage 1** — tokenization + chip view + word list. ← **current focus**
2. Stage 2 — JMdict lookup, expanded word cards.
3. Stage 3 — Anki card creation.

Per user's standing feedback (`feedback_stage_one_first.md` in memory): Stage 1 fully buildable on device first. Stages 2 + 3 are scaffolded with TODOs and intentionally not wired to UI.

---

## Toolchain and build

Already installed and persisted at User scope:

- **JDK 17:** `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` (`JAVA_HOME`)
- **Android SDK:** `C:\Android\sdk` (`ANDROID_HOME`, `ANDROID_SDK_ROOT`) with `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- **Test device:** Pixel 9 Pro XL, serial `45191FDAS000N9`, Android 16. Authorized for adb. `adb devices` to confirm.
- **No Android Studio** — user opted SDK-only. Build via `./gradlew :app:installDebug`.

Build dir is redirected outside OneDrive (`~/.tangotori-build/`) via root `build.gradle.kts` — **do not undo this**. See `feedback_onedrive_build_dir.md`. If you see a `Cannot snapshot ... not a regular file` error, OneDrive dehydrated a file: `rm -rf` the affected build subdir and retry.

Standard invocation pattern (Git Bash):
```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "c:/Users/cptns/OneDrive - University of Toronto/Documents/Self Projects/Tango Tori"
./gradlew --no-daemon :app:installDebug
```

Use `run_in_background: true` on long builds and wait for the harness completion notification.

Launch on device:
```bash
/c/Android/sdk/platform-tools/adb.exe shell am force-stop com.tangotori.app
/c/Android/sdk/platform-tools/adb.exe shell am start -n com.tangotori.app/.MainActivity
```

---

## Stack and key code paths

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, Compose BOM 2024.12.01, Hilt 2.52, Room 2.6.1, Sudachi 0.7.5.
- KSP1 (not KSP2). `gradle.properties` has `ksp.useKSP2=false` — Hilt 2.52 breaks with KSP2.
- AnkiDroid API via JitPack (Stage 3, untested at runtime).

Project layout:
```
app/src/main/java/com/tangotori/app/
  TangoToriApp.kt              @HiltAndroidApp
  MainActivity.kt              NavHost: sentence/settings/card
  data/sudachi/
    SudachiAssetInstaller.kt   copies bundled dict → filesDir on first launch
    SudachiTokenizer.kt        wraps Sudachi; calls TokenMerger after raw tokenize
    TokenMerger.kt             merges aux verbs / te-particles into preceding verbs
    PosMapper.kt               Sudachi POS → 8 UI categories
    KatakanaToHiragana.kt
  data/db/                     Room entities/DAO/DB (Stage 2 stub)
  data/jmdict/                 JmdictRepository (Stage 2 stub)
  data/anki/                   AnkiCardRepository (Stage 3 stub)
  domain/models/               Token, DictEntry, Sense, CardData, PartOfSpeech
  domain/util/
    KanjiKanaSplit.kt          splits surface into kanji/kana parts; derives dict-form reading
    FuriganaBuilder.kt         Anki "kanji[reading]" markup builder (pure, tested)
    MeaningHtmlBuilder.kt      pure, tested
    SentenceHtmlBuilder.kt     pure, tested
    HtmlEscape.kt
  domain/usecases/             TokenizeSentenceUseCase, LookupWordUseCase, CreateCardUseCase
  ui/theme/                    Color, Theme, Type — parchment + ink palette per spec
  ui/components/
    FuriganaText.kt            two-band (furigana + word) per token
    WordChip.kt                tappable chip with POS bottom border
    TokenizedSentenceView.kt   FlowRow of chips + inline punctuation
  ui/sentence/
    SentenceScreen.kt          top-level, currently being reworked
    SentenceViewModel.kt       has isEditing flag now
  ui/card/                     CardCreationScreen (Stage 3 stub)
  ui/settings/                 SettingsScreen (stub)
  ui/onboarding/               FirstLaunchScreen (unused — dict is bundled)
  di/AppModule.kt
app/src/main/assets/sudachi/system_core.dic   216 MB, attrib +P pinned
app/src/test/java/...          unit tests for the pure builders + KanjiKanaSplit + TokenMerger + PosMapper
```

The SudachiDict file is bundled as an asset (216 MB, ~230 MB APK). `attrib +P` pins it so OneDrive can't dehydrate it. **Don't delete it** — re-downloading takes ~5 min and extracts from `https://github.com/WorksApplications/SudachiDict/releases/download/v20230927/sudachi-dictionary-20230927-core.zip`.

Sudachi config quirk (`feedback_sudachi_config.md`): the settings JSON in [SudachiTokenizer.kt](app/src/main/java/com/tangotori/app/data/sudachi/SudachiTokenizer.kt) **must** include `SimpleOovProviderPlugin` or Sudachi throws "there must be at least one oov provider plugin." Don't simplify it away.

---

## Stage 1 feedback status (from `tango-tori-stage1-feedback.md`)

| # | Issue | Status |
|---|---|---|
| 1 | Punctuation as chips/list items | **Fixed** — punctuation renders inline; word list filters it out |
| 2 | Sentence view layout (broken inline flow) | **Fixed** — uniform `TokenRowHeight` (currently 16dp + 28dp = 44dp) |
| 3 | Furigana over non-kanji tokens | **Fixed** — `KanjiKanaSplit` only emits furigana for parts containing kanji |
| 4 | Whole-token reading above conjugated verbs | **Fixed** — `KanjiKanaSplit.split()` strips trailing okurigana; furigana sits only above the kanji prefix |
| 5 | POS color on furigana, not on word | **Fixed** — `WordChip` draws 2dp POS-color line via `drawBehind`; furigana is muted `#78909C`; word text is `onSurface` |
| 6 | Word list shows surface reading | **Fixed** — added `Token.dictionaryReading`, derived by `KanjiKanaSplit.deriveDictFormReading(surface, surfaceReading, dictForm)` |
| 7 | Particles/aux verbs at full weight | **Fixed** — list items detect PARTICLE/AUXILIARY_VERB → 14sp / 55% alpha, smaller padding |
| 8 | Too much whitespace in word list | **Fixed** — 8dp/4dp vertical padding, thin 8%-alpha dividers |
| 9 | Sentence chip spacing too large | **Fixed** — FlowRow spacing 4dp |
| 10 | `た` appearing twice | **Fixed by side-effect** — `TokenMerger` collapses aux verbs into their parent verb, so `た`s no longer appear standalone |

Also fixed in this round but not on the original list: **`た` was Sudachi splitting `入っ` + `た`**. `TokenMerger` (new) absorbs `AUXILIARY_VERB` and `接続助詞` (te-form) into the immediately-preceding verb/i-adj/na-adj/aux-verb. Tests: `app/src/test/.../TokenMergerTest.kt`. Spec test table in `tango-tori-tests.md` actually expects the split form — the merger overrides that for better UX. If asked, defend with: spec describes Sudachi output, UI wants merged display.

---

## Unverified / in-flight work

### The latest screenshot bug (still unfixed at handoff)

User screenshotted after the most recent install. Two complaints:

**(a) Furigana still clipped at the top.** `はい` above `入`, `しゅんかん` above `瞬間`, `くうき` above `空気`, `か` above `変わった` — all show ascenders cut by the upper chip edge.

**My latest edit** to [`FuriganaText.kt`](app/src/main/java/com/tangotori/app/ui/components/FuriganaText.kt) (not yet built or installed at handoff):
- `FuriganaBandHeight = 16.dp`, `WordBandHeight = 28.dp`, total `TokenRowHeight = 44.dp`
- Furigana band now `Alignment.BottomCenter` (was `TopCenter`) so the glyph hugs the kanji below and any slack sits at the *top* of the band, not the bottom
- Both Texts use `NoPaddingTextStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false), lineHeightStyle = LineHeightStyle(alignment = Center, trim = Both))` to strip the platform's ascender-padding that was pushing the glyph above the band

**Need to rebuild + reinstall and check.** If still clipping after this, the next thing to try is bumping `FuriganaBandHeight` to 20.dp.

**(b) User wants the input box merged with the sentence view, with animated transition.**

> "make it so that the paste box is not separate from the sentence. Once the text is pasted in, it is dynamically changed into the parsed version with spaces, colored underlines and furigana. There should be a smooth transition as the words move away from each other to make way for the spaces and underlines appear. Furigana should also be drawn on with an animation."

**Status:** partially done at handoff. Changes already on disk:

- [`SentenceViewModel.kt`](app/src/main/java/com/tangotori/app/ui/sentence/SentenceViewModel.kt) has `isEditing: Boolean` in `SentenceUiState`; default true; flipped to false on successful tokenize; `startEditing()` method to re-enter edit mode. `onInputChange()` sets `isEditing = true`.
- [`SentenceScreen.kt`](app/src/main/java/com/tangotori/app/ui/sentence/SentenceScreen.kt) replaced with a new layout:
  - A `SentenceCard` composable wraps a Box with a primary-color border and the parchment background — one container for both states.
  - Inside it, `AnimatedContent` crossfades between a `BasicTextField` (edit mode) and the `TokenizedSentenceView` (view mode).
  - `Modifier.animateContentSize(tween(250))` on the outer Box smooths the layout shift.
  - In view mode, the chip area is clickable (no ripple) and calls `viewModel.startEditing()`; a small `Icons.Default.Edit` icon sits in the top-right of the chip area as an affordance.
  - The tokenizing indicator + word list + "Waiting for tokenizer…" empty state only show in view mode now (gated on `!state.isEditing`).

**Not yet done:**
- The user wanted per-chip animation: "words move away from each other to make way for the spaces" and "Furigana should also be drawn on with an animation." The current implementation only does an outer crossfade — chips just appear in their final positions, no per-chip stagger or furigana fade-in. To do this properly: have `WordChip` run a one-shot fade/scale animation on first composition (via `LaunchedEffect(Unit)` + `animateFloatAsState`), and animate the furigana column's alpha from 0 → 1 with a small delay after the underline. Not implemented yet.
- The card hasn't been built/installed/visually verified.

### Verify after next build

1. Furigana is no longer clipping.
2. The input field and chip area share one bordered container.
3. Crossfade between text input and chip view feels smooth (~250 ms).
4. Tapping the chip area (or the edit-pencil icon) returns to edit mode with the same text preserved.
5. Editing the text re-tokenizes and slides back to chip view.
6. Then iterate on the per-chip stagger / furigana fade if needed.

---

## Immediate next steps (in order)

1. **Build + install:**
   ```bash
   export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
   export ANDROID_HOME="/c/Android/sdk"
   export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
   cd "c:/Users/cptns/OneDrive - University of Toronto/Documents/Self Projects/Tango Tori"
   ./gradlew --no-daemon :app:installDebug
   ```
2. **Launch + ask for a screenshot.** Verify the furigana clipping is gone and the merged card looks right.
3. **If clipping persists:** bump `FuriganaBandHeight` to 20 or 22 in `FuriganaText.kt`.
4. **Once the visual layout is approved:** add per-chip animation:
   - In `WordChip`: `LaunchedEffect(Unit)` triggers `Animatable(0f).animateTo(1f, tween(300))` for an alpha + scale enter animation.
   - Stagger via the chip's index passed in by parent. Optional but the user asked for it.
5. **Once Stage 1 is signed off:** move to Stage 2 (JMdict lookup wiring + expanded word card UI). User said Stages 2+3 are scaffold-only until Stage 1 is solid.

---

## Things to avoid doing

- Don't unwire `TokenMerger` — its tests pass and it fixes complaint #10 from the feedback doc.
- Don't try `Config.fromClasspath()` / `Config.defaultConfig()` / `Dictionary.from(config)` for Sudachi — none of those exist with no-arg signatures in 0.7.5. The current `DictionaryFactory().create(path, settings)` legacy API works; keep it.
- Don't enable `ksp.useKSP2=true` — Hilt 2.52 breaks.
- Don't move the project out of OneDrive without checking with the user — they chose that location.
- Don't delete `app/src/main/assets/sudachi/system_core.dic`.
- Don't unilaterally re-arrange the Stage 2 / Stage 3 scaffolding unless asked. User wants Stage 1 polish first.
- Don't add per-kanji furigana splitting to `FuriganaBuilder` (the Anki markup builder) — it groups contiguous kanji runs and that's intentional; the spec's per-kanji split form requires kanjidic readings we don't have.

---

## Useful one-liners

```bash
# Logcat for the app
/c/Android/sdk/platform-tools/adb.exe logcat -d --pid=$(/c/Android/sdk/platform-tools/adb.exe shell pidof com.tangotori.app | tr -d '\r') | grep -iE "sudachi|tangotori|fatal|exception"

# Wipe app data (useful when testing the asset installer path)
/c/Android/sdk/platform-tools/adb.exe shell pm clear com.tangotori.app

# Run only unit tests
./gradlew --no-daemon :app:testDebugUnitTest

# Pull a screenshot from device for inspection
/c/Android/sdk/platform-tools/adb.exe shell screencap -p /sdcard/screen.png
/c/Android/sdk/platform-tools/adb.exe pull /sdcard/screen.png .
```
