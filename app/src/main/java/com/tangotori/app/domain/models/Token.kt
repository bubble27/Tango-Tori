package com.tangotori.app.domain.models

data class Token(
    val surface: String,
    val dictionaryForm: String,
    /** Reading of the surface (conjugated) form — used for furigana in the sentence view. */
    val reading: String,
    /** Reading of the dictionary/base form — used for the word list label. */
    val dictionaryReading: String,
    val partOfSpeech: PartOfSpeech,
    val rawPosTag: String,
    /** When non-empty, this token is a grouped token (idiom or compound): these
     *  are the individual word tokens it was built from. The group is looked up
     *  in JMdict by [dictionaryForm] like any word; the components drive the
     *  in-card parts breakdown. */
    val components: List<Token> = emptyList(),
    /** True when this group is an idiomatic EXPRESSION (merged from over-split
     *  tokens by the idiom grouper, e.g. 腹が立つ). False for a single-token
     *  COMPOUND that was decomposed into morphemes (e.g. 高校生). Drives the
     *  parts-section labels. */
    val isExpression: Boolean = false,
) {
    /** True for a grouped token — an idiom (腹が立つ) or a decomposed compound
     *  (高校生) — i.e. anything with a [components] breakdown. */
    val isIdiomGroup: Boolean get() = components.isNotEmpty()

    /** Bound suffix morpheme (Sudachi 接尾辞), e.g. 生 in 高校生 → shown as ~生. */
    val isSuffix: Boolean get() = rawPosTag.contains("接尾辞")

    /** Bound prefix morpheme (Sudachi 接頭辞), e.g. お in お茶 → shown as お~. */
    val isPrefix: Boolean get() = rawPosTag.contains("接頭辞")

    /** A bound affix (suffix or prefix) — needs a composed "morphemic meaning"
     *  gloss (生→~生); free-standing parts (高校) just need a sense highlight. */
    val isBoundAffix: Boolean get() = isSuffix || isPrefix

    /** Headword as shown in a compound breakdown: bound affixes get a tilde. */
    val partLabel: String get() = when {
        isSuffix -> "~$dictionaryForm"
        isPrefix -> "$dictionaryForm~"
        else -> dictionaryForm
    }

    val isContentWord: Boolean
        get() = when (partOfSpeech) {
            PartOfSpeech.NOUN,
            PartOfSpeech.VERB,
            PartOfSpeech.I_ADJECTIVE,
            PartOfSpeech.NA_ADJECTIVE,
            PartOfSpeech.ADVERB -> true
            else -> false
        }

    val isPureKana: Boolean
        get() = surface.all { it.isHiragana() || it.isKatakana() }

    companion object {
        fun Char.isHiragana() = this in '぀'..'ゟ'
        fun Char.isKatakana() = this in '゠'..'ヿ' || this == 'ー'
        fun Char.isKanji() = this in '一'..'鿿' || this in '㐀'..'䶿'
    }
}
