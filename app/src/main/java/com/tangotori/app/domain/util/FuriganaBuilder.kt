package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Token.Companion.isHiragana
import com.tangotori.app.domain.models.Token.Companion.isKanji
import com.tangotori.app.domain.models.Token.Companion.isKatakana

/**
 * Generates AnkiDroid "kanji[reading]" furigana markup. The algorithm groups
 * each *contiguous run of kanji* under one bracket — it cannot split per kanji
 * without a per-character readings dictionary (kanjidic). Examples:
 *
 *   食べる + たべる  -> "食[た]べる"
 *   膝立ち  + ひざだち -> "膝立[ひざだ]ち"      (grouped — single bracket)
 *   東京都  + とうきょうと -> "東京都[とうきょうと]"
 *   コーヒー + コーヒー -> "コーヒー"           (no kanji, no markup)
 *   きれい  + きれい  -> "きれい"
 *
 * The spec's aspirational form `膝[ひざ]立[だ]ち` would require a per-kanji split
 * via kanjidic — left as a future enhancement (Stage 2+). The grouped output is
 * valid AnkiDroid markup and renders the same furigana on the card.
 *
 * If alignment fails (reading shorter than expected kana suffix), fall back to a
 * single bracketed group around the whole surface to avoid malformed markup.
 */
object FuriganaBuilder {

    fun build(surface: String, reading: String): String {
        if (surface.isBlank()) return surface
        if (reading.isBlank()) return surface
        if (!surface.any { it.isKanji() }) return surface

        val out = StringBuilder()
        var i = 0
        var r = 0

        while (i < surface.length) {
            val c = surface[i]
            if (c.isKanji()) {
                // Find end of kanji run
                var j = i
                while (j < surface.length && surface[j].isKanji()) j++
                val kanjiRun = surface.substring(i, j)

                // Look at what follows in the surface — the next kana run (if any)
                val nextKanaStart = j
                var nextKanaEnd = nextKanaStart
                while (nextKanaEnd < surface.length && !surface[nextKanaEnd].isKanji()) nextKanaEnd++
                val followingKana = surface.substring(nextKanaStart, nextKanaEnd)

                // Find that same kana run in the reading, starting at r
                val rEnd = if (followingKana.isEmpty()) reading.length
                else findKanaMatch(reading, r, followingKana)

                if (rEnd < 0) {
                    // Alignment failed — fall back: bracket the whole remaining surface
                    out.append(surface.substring(i)).append('[').append(reading.substring(r)).append(']')
                    return out.toString().sanitizeMarkup(surface)
                }

                val readingSlice = reading.substring(r, rEnd)
                out.append(kanjiRun).append('[').append(readingSlice).append(']')
                // Append the following kana run verbatim and advance both cursors
                out.append(followingKana)
                i = nextKanaEnd
                r = rEnd + followingKana.length
            } else {
                out.append(c)
                // Advance reading cursor in lockstep if it matches; if not, just leave it.
                if (r < reading.length && readingMatchesAt(reading, r, c)) r++
                i++
            }
        }
        return out.toString()
    }

    /** Try to find the substring [needle] in [reading] starting at [from]; returns
     *  the start index of the match, or -1 if not found. */
    private fun findKanaMatch(reading: String, from: Int, needle: String): Int {
        if (needle.isEmpty()) return from
        // Compare as hiragana on both sides (reading is hiragana already, but
        // surface katakana might leak through in mixed words).
        val rHira = reading
        val nHira = needle.katakanaToHiragana()
        var idx = from
        while (idx + nHira.length <= rHira.length) {
            if (rHira.regionMatches(idx, nHira, 0, nHira.length)) return idx
            idx++
        }
        return -1
    }

    private fun readingMatchesAt(reading: String, idx: Int, c: Char): Boolean {
        val r = reading[idx]
        return r == c || r == c.toHiragana()
    }

    private fun Char.toHiragana(): Char =
        if (this in 'ァ'..'ヶ') (this.code - 0x60).toChar() else this

    private fun String.katakanaToHiragana(): String = buildString(length) {
        for (c in this@katakanaToHiragana) {
            append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
        }
    }

    /** If the algorithm produced something obviously malformed (e.g. empty bracket
     *  contents), fall back to the surface. */
    private fun String.sanitizeMarkup(surface: String): String =
        if (contains("[]")) surface else this
}
