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
    /** Pre-rendered per-character breakdown HTML for the kanji in [word].
     *  Pass an empty string to omit the breakdown section from the card. */
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

/**
 * Card format for image-based study: the example sentence is replaced by an
 * image, and a [userSentence] field is left blank for the user to fill in
 * inside AnkiDroid after the card is created.
 *
 * Stored under note type [NOTE_TYPE_NAME] so it coexists with [CardData]
 * ("Tango Tori v5") without conflict.
 */
data class ImageCardData(
    val word: String,
    val reading: String,
    val furigana: String,
    val wordRuby: String,
    val meaningHtml: String,
    val partOfSpeech: String,
    val jlpt: String,
    val isCommon: Boolean,
    /** HTML for the image, e.g. `<img src="cat.jpg">`. Empty → no image shown. */
    val imageHtml: String = "",
    /** Plain-text sentence entered by the user inside AnkiDroid after the card
     *  is created. Always starts empty. */
    val userSentence: String = "",
    val source: String = "",
    val kanjiBreakdownHtml: String = "",
) {
    fun toFieldArray(): Array<String> = arrayOf(
        word, reading, furigana, wordRuby, meaningHtml, partOfSpeech, jlpt,
        if (isCommon) "1" else "0",
        imageHtml, userSentence, source, kanjiBreakdownHtml,
    )

    companion object {
        val FIELD_NAMES = arrayOf(
            "Word", "Reading", "Furigana", "WordRuby", "Meaning",
            "PartOfSpeech", "JLPT", "IsCommon",
            "Image", "UserSentence", "Source", "KanjiBreakdown",
        )

        const val NOTE_TYPE_NAME = "Tango Tori Image v1"
    }
}
