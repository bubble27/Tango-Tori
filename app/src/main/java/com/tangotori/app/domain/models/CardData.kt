package com.tangotori.app.domain.models

data class CardData(
    val word: String,
    val reading: String,
    /** AnkiDroid "kanji[reading]" markup, e.g. `仲[なか]間[ま]`. Kept for the
     *  Furigana field that's surfaced in some external Anki templates. */
    val furigana: String,
    /** Pre-rendered `<ruby>` HTML for the headword, used by the front template
     *  so each kanji shows its own reading above it. */
    val wordRuby: String,
    val meaningHtml: String,
    val partOfSpeech: String,
    val jlpt: String,
    val isCommon: Boolean,
    val sentenceHtml: String,
    val sentenceRaw: String,
    val source: String = "",
    /** Pre-rendered per-character breakdown HTML for the kanji in [word]. */
    val kanjiBreakdownHtml: String = "",
) {
    fun toFieldArray(): Array<String> = arrayOf(
        word,
        reading,
        furigana,
        wordRuby,
        meaningHtml,
        partOfSpeech,
        jlpt,
        if (isCommon) "1" else "0",
        sentenceHtml,
        sentenceRaw,
        source,
        kanjiBreakdownHtml,
    )

    companion object {
        val FIELD_NAMES = arrayOf(
            "Word",
            "Reading",
            "Furigana",
            "WordRuby",
            "Meaning",
            "PartOfSpeech",
            "JLPT",
            "IsCommon",
            "Sentence",
            "SentenceRaw",
            "Source",
            "KanjiBreakdown",
        )

        // Bumped on field-set or template change so AnkiDroid creates a fresh
        // model instead of trying to reuse an incompatible older one.
        const val NOTE_TYPE_NAME = "Tango Tori v5"
    }
}
