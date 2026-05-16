package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Token.Companion.isKanji

/**
 * Splits a token into a kanji prefix + kana suffix and aligns the reading to each part.
 *
 * Cases handled:
 *   食べる + たべる  → [Part("食", "た"), Part("べる")]
 *   入っ   + はいっ  → [Part("入", "はい"), Part("っ")]
 *   瞬間   + しゅんかん → [Part("瞬間", "しゅんかん")]
 *   スタジオ + すたじお → [Part("スタジオ")]            // no kanji
 *   に     + に     → [Part("に")]                     // pure kana
 *   お母さん + おかあさん → falls back to whole-token furigana over the first kanji run
 *
 * The current algorithm aligns the *trailing* kana suffix only. Tokens with kana in
 * the middle (e.g. お母さん) get one bracketed group covering all of their kanji,
 * matching FuriganaBuilder's behaviour and what AnkiDroid will display.
 */
object KanjiKanaSplit {

    data class Part(val text: String, val furigana: String? = null) {
        val hasFurigana: Boolean get() = !furigana.isNullOrEmpty()
    }

    fun split(surface: String, reading: String): List<Part> {
        if (surface.isEmpty()) return listOf(Part(""))
        if (!surface.any { it.isKanji() }) return listOf(Part(surface))
        if (reading.isBlank()) return listOf(Part(surface))

        // Find leading non-kanji prefix (rare for verbs but happens for お+kanji etc.)
        val leadingKanaEnd = surface.indexOfFirst { it.isKanji() }
        // Find trailing kana suffix
        val lastKanjiIdx = surface.indexOfLast { it.isKanji() }
        val trailingKanaStart = lastKanjiIdx + 1

        val leadingKana = surface.substring(0, leadingKanaEnd)
        val trailingKana = surface.substring(trailingKanaStart)
        val kanjiRun = surface.substring(leadingKanaEnd, trailingKanaStart)

        // Locate the reading slice that corresponds to the kanji run.
        // Strip leading kana from reading head and trailing kana from reading tail.
        val readingHiragana = reading.katakanaToHiragana()
        val leadingHira = leadingKana.katakanaToHiragana()
        val trailingHira = trailingKana.katakanaToHiragana()

        var readStart = 0
        if (leadingHira.isNotEmpty() && readingHiragana.startsWith(leadingHira)) {
            readStart = leadingHira.length
        }
        var readEnd = readingHiragana.length
        if (trailingHira.isNotEmpty() && readingHiragana.endsWith(trailingHira)) {
            readEnd = readingHiragana.length - trailingHira.length
        }
        if (readEnd <= readStart) {
            // Alignment failed — bail out to whole-token rendering
            return listOf(Part(surface, reading))
        }
        val kanjiReading = reading.substring(readStart, readEnd)

        val parts = mutableListOf<Part>()
        if (leadingKana.isNotEmpty()) parts.add(Part(leadingKana))
        parts.add(Part(kanjiRun, kanjiReading))
        if (trailingKana.isNotEmpty()) parts.add(Part(trailingKana))
        return parts
    }

    /**
     * Derives the reading of the *dictionary form* from the surface reading.
     * Sudachi's `Morpheme.readingForm()` returns the surface (conjugated) reading;
     * we need the lemma reading for the word-list label.
     *
     *   surface=入っ, surfaceReading=はいっ, dictForm=入る → はいる
     *   surface=変わっ, surfaceReading=かわっ, dictForm=変わる → かわる
     *   surface=瞬間, surfaceReading=しゅんかん, dictForm=瞬間 → しゅんかん
     *
     * Falls back to the surface reading when alignment fails.
     */
    fun deriveDictFormReading(surface: String, surfaceReading: String, dictForm: String): String {
        if (surface == dictForm) return surfaceReading
        if (surfaceReading.isBlank() || dictForm.isBlank()) return surfaceReading

        // Compute the kanji-run reading from the surface, then re-attach the dict form's
        // trailing kana suffix.
        val parts = split(surface, surfaceReading)
        val kanjiPart = parts.firstOrNull { it.hasFurigana } ?: return surfaceReading
        val kanjiRun = kanjiPart.text
        val kanjiReading = kanjiPart.furigana ?: return surfaceReading

        // Dict form should share the same kanji prefix; recover its kana suffix.
        if (!dictForm.startsWith(kanjiRun)) return surfaceReading
        val dictKanaSuffix = dictForm.substring(kanjiRun.length)

        // Recover any leading kana from the parts (e.g. お+kanji+kana).
        val leadingKana = parts.takeWhile { !it.hasFurigana }.joinToString("") { it.text }

        return leadingKana + kanjiReading + dictKanaSuffix
    }

    private fun String.katakanaToHiragana(): String = buildString(length) {
        for (c in this@katakanaToHiragana) {
            append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
        }
    }
}
