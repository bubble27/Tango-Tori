package com.tangotori.jmdictbuilder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- jmdict-simplified JSON shape -------------------------------------------
// Spec: https://github.com/scriptin/jmdict-simplified

@Serializable
data class JmdictSimplified(
    val version: String,
    val languages: List<String> = emptyList(),
    val dictDate: String? = null,
    val commonOnly: Boolean = false,
    val words: List<SimpleWord>,
)

@Serializable
data class SimpleWord(
    val id: String,
    val kanji: List<KanjiVariant> = emptyList(),
    val kana: List<KanaVariant> = emptyList(),
    val sense: List<SenseEntry> = emptyList(),
)

@Serializable
data class KanjiVariant(
    val common: Boolean = false,
    val text: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class KanaVariant(
    val common: Boolean = false,
    val text: String,
    val tags: List<String> = emptyList(),
    val appliesToKanji: List<String> = emptyList(),
)

@Serializable
data class SenseEntry(
    val partOfSpeech: List<String> = emptyList(),
    val appliesToKanji: List<String> = emptyList(),
    val appliesToKana: List<String> = emptyList(),
    val field: List<String> = emptyList(),
    val dialect: List<String> = emptyList(),
    val misc: List<String> = emptyList(),
    val info: List<String> = emptyList(),
    val gloss: List<Gloss> = emptyList(),
)

@Serializable
data class Gloss(
    val lang: String = "eng",
    val text: String,
    val type: String? = null,
    val gender: String? = null,
)

// --- kanjidic2-simplified JSON shape ----------------------------------------
// Same scriptin/jmdict-simplified repo; the English release ships as
// `kanjidic2-en-X.Y.Z.json.zip`.

@Serializable
data class Kanjidic2Root(
    val version: String,
    val characters: List<Kanjidic2Character>,
)

@Serializable
data class Kanjidic2Character(
    val literal: String,
    val readingMeaning: Kanjidic2ReadingMeaning? = null,
)

@Serializable
data class Kanjidic2ReadingMeaning(
    val groups: List<Kanjidic2Group> = emptyList(),
)

@Serializable
data class Kanjidic2Group(
    val readings: List<Kanjidic2Reading> = emptyList(),
    val meanings: List<Kanjidic2Meaning> = emptyList(),
)

@Serializable
data class Kanjidic2Reading(
    val type: String,
    val value: String,
)

@Serializable
data class Kanjidic2Meaning(
    val lang: String,
    val value: String,
)

// --- GitHub release shape (subset) ------------------------------------------

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GithubAsset>,
)

@Serializable
data class GithubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)
