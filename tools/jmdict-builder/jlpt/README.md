# JLPT Vocab Source

JMdict does not carry JLPT levels, so the builder joins externally-sourced vocab lists against the JMdict entries to populate the `jlptLevel` column.

## Resolution order

The builder tries each source in turn and stops at the first one that yields data per level:

1. **Local CSVs** at `tools/jmdict-builder/jlpt/{n5,n4,n3,n2,n1}.csv`
   - First column: kanji form. Second column: kana reading. Extra columns ignored.
   - Blank lines and lines starting with `#` are skipped.
2. **Cached downloads** at `.cache/jlpt_{n5..n1}.csv` (rebuilt automatically on first run).
3. **Fresh download** from the URL pattern in `JLPT_URL_PATTERN`, defaulting to
   `https://raw.githubusercontent.com/jonsafari/jlpt-resources/master/data/jlpt_voc_{level}.csv`.

If all three fail for a level, the builder warns and leaves `jlptLevel = null` for entries that would have matched.

## Source recommendations

- **`jonsafari/jlpt-resources`** — public CSVs per level, simple `kanji,reading,…` format.
- **`elzup/jlpt-word-list`** — JSON arrays per level (`{ "word": "...", "furigana": "..." }`).
- **`tanos.co.uk/jlpt/`** — the original community lists.

To pin a specific source, drop CSVs into this directory; they take priority over downloads.

## Re-running the builder

After dropping/updating CSVs:

```bash
./gradlew :tools:jmdict-builder:run
```

This regenerates `app/src/main/assets/jmdict.db` with the new JLPT mapping.
