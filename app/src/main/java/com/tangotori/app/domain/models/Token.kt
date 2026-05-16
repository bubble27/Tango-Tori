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
) {
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
