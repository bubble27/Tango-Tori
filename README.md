# Tango Tori

Android app for Japanese and Chinese learners. Paste or share a sentence; Tango Tori tokenizes it, looks each word up in a bundled dictionary, and lets you turn any word into an AnkiDroid card with one tap.

- **Japanese** — Sudachi tokenizer · JMdict + KANJIDIC · furigana · kanji breakdown · Jisho / KanjiStudy links
- **Chinese** — jieba tokenizer · CC-CEDICT · tone-marked pinyin · hanzi breakdown · MDBG links · AI meanings for compound words

Language is detected automatically on paste or share.

Stack: Kotlin 2.0 · Jetpack Compose · Hilt · Room · Sudachi 0.7.5 · jieba-analysis · AnkiDroid public API · Cloudflare Workers.

## Download

Pre-built APKs are on the [Releases page](https://github.com/bubble27/Tango-Tori/releases). Download the latest `.apk`, open it on your Android device, and tap **Install** (you may need to enable *Install unknown apps* in Settings → Security).

---

## Project layout

```
app/
  src/main/java/com/tangotori/app/
    data/sudachi/         Sudachi wrapper + tokenizer (Japanese)
    data/chinese/         jieba tokenizer wrapper (Chinese)
    data/db/              Room entities + DAOs (JMdict, KANJIDIC, CC-CEDICT)
    data/jmdict/          JMdict lookup repository
    data/cedict/          CC-CEDICT lookup repository
    data/compound/        AI compound-meaning API client (Cloudflare Worker)
    data/anki/            AnkiDroid integration + card target repo
    domain/models/        Language enum (JAPANESE / CHINESE)
    domain/util/          HTML builders — furigana, kanji/hanzi breakdown, pinyin, sentence
    domain/usecases/      TokenizeSentence, LookupWord, ChineseLookup, CreateCard
    ui/sentence/          Sentence screen, ViewModel, expanded entry card
    ui/components/        FuriganaText, WordChip, TokenizedSentenceView
scripts/                  Python tools — CC-CEDICT DB builder, tango_tori API
tools/jmdict-builder/     JVM Gradle subproject — builds the bundled JMdict DB
backend/                  Cloudflare Worker — compound-meaning AI endpoint (Claude Haiku)
```

## Build

Standard Gradle Android setup. Requires JDK 17 and Android SDK with `platforms;android-35` + `build-tools;35.0.0`.

```bash
# Debug build — fast iteration. JIT-only so scroll/animations look slower.
./gradlew :app:installDebug

# Release build — R8-minified, AOT-compiled. Use for perf-sensitive testing.
./gradlew :app:installRelease
```

## Regenerating bundled data assets

Three large files are gitignored and must be regenerated on a fresh clone.

### 1. SudachiDict (`app/src/main/assets/sudachi/system_core.dic`, ~216 MB)

```bash
curl -L -o /tmp/sudachi.zip \
  https://github.com/WorksApplications/SudachiDict/releases/download/v20230927/sudachi-dictionary-20230927-core.zip
unzip /tmp/sudachi.zip -d /tmp/
mv /tmp/sudachi-dictionary-20230927/system_core.dic app/src/main/assets/sudachi/
```

On Windows under OneDrive, pin the file so it isn't dehydrated mid-build:
```
attrib +P app/src/main/assets/sudachi/system_core.dic
```

### 2. JMdict + KANJIDIC SQLite (`app/src/main/assets/jmdict.db`, ~61 MB)

```bash
./gradlew :app:kspDebugKotlin               # exports Room schema JSON first
./gradlew :tools:jmdict-builder:run -Dorg.gradle.jvmargs="-Xmx2g"
```

Downloads `jmdict-eng-*.json.zip` and `kanjidic2-en-*.json.zip` from the latest scriptin/jmdict-simplified release automatically.

### 3. CC-CEDICT SQLite (`app/src/main/assets/cedict.db`, ~30 MB)

Download the latest CC-CEDICT export from [MDBG](https://www.mdbg.net/chinese/dictionary?page=cc-cedict), place `cedict_ts.u8` in the project root, then run:

```bash
python scripts/build_cedict.py
```

This creates `app/src/main/assets/cedict.db`.

---

## Feature flow

1. **Paste / share a sentence** — accepts text via the input field or the Android share sheet. Language is auto-detected; toggle manually with the flag button.
2. **Tokenization** — Sudachi (Japanese) or jieba (Chinese) splits the sentence. Chips render inline with furigana / pinyin above each character group and a POS-color underline.
3. **POS coloring** — each word chip is color-coded by part of speech (verb, noun, adjective, particle, etc.). Chinese POS is resolved from Jieba's own ~120k-entry dictionary. Compound words (multi-character words not in CC-CEDICT, split for lookup) get a distinct steel-blue color.
4. **Dictionary lookup** — tap a chip or word-list row to expand: numbered senses, POS label, JLPT badge (Japanese), readings, alternate forms, multi-entry tabs for disambiguation.
5. **AI meanings for compound words** — Chinese words not found in CC-CEDICT fetch a concise AI-generated meaning from the backend Cloudflare Worker (Claude Haiku with prompt caching and per-device rate limiting).
6. **Character breakdown** — the expanded card shows a hanzi/kanji tile row. Tap any tile for a full popup with all meanings and the character's reading.
7. **Anki card creation** — *Add to default deck* or *Choose specific deck*. Cards use the **Tango Tori v5** note type with fields for word, ruby, meaning HTML, sentence HTML with highlight, character breakdown, and tags. Japanese cards include Jisho/KanjiStudy links; Chinese cards include an MDBG link.
8. **Back button** — pressing the Android back button while viewing results returns to the text-edit screen instead of exiting the app.

---

## Backend (Cloudflare Worker)

`backend/` contains the Cloudflare Worker that serves AI-generated meanings for Chinese compound words. It uses Claude Haiku with prompt caching, a D1 SQLite cache to avoid repeat API calls, and per-device rate limiting.

Deploy with [Wrangler](https://developers.cloudflare.com/workers/wrangler/):

```bash
cd backend
npm install
npx wrangler deploy
```

The worker URL goes into `local.properties` as `COMPOUND_API_URL=https://your-worker.workers.dev/meaning` — the app reads it at build time via `BuildConfig.COMPOUND_API_URL`.

---

## Notes

- The repo lives under a OneDrive-synced path. `build.gradle.kts` redirects the Gradle build directory to `~/.tangotori-build/` to prevent OneDrive from dehydrating intermediate artifacts. Override with `TANGOTORI_BUILD_DIR`.
- Sudachi's `Tokenizer` is not thread-safe — `SudachiTokenizer.tokenize` serializes through a mutex.
- Debug-signed APKs run JIT-only on Android; always check perf with `:app:installRelease`.
- The Anki note type version is bumped when fields or templates change. Old cards stay under their original model name.

## License

JMdict / KANJIDIC content is the property of EDRDG, bundled under their licence. SudachiDict is bundled under the Apache 2.0 licence. CC-CEDICT is distributed under a Creative Commons Attribution-ShareAlike 4.0 licence.
