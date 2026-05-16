package com.tangotori.app.domain.models

data class DictEntry(
    val id: Long,
    val isCommon: Boolean,
    val jlptLevel: String?,
    val kanjiForms: List<KanjiForm>,
    val readings: List<Reading>,
    val senses: List<Sense>,
) {
    val headword: String
        get() = kanjiForms.firstOrNull()?.text ?: readings.firstOrNull()?.text ?: ""

    val primaryReading: String
        get() = readings.firstOrNull()?.text ?: ""
}

data class KanjiForm(val text: String, val info: String? = null, val priority: String? = null)

data class Reading(
    val text: String,
    val info: String? = null,
    val priority: String? = null,
    val noKanji: Boolean = false,
)

data class Sense(
    val partOfSpeech: List<String>,
    val glosses: List<Gloss>,
    val misc: String? = null,
    val field: String? = null,
    val dialect: String? = null,
)

data class Gloss(val text: String, val language: String = "eng")
