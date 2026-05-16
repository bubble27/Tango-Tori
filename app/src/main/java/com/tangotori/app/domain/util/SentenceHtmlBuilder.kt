package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.models.Token.Companion.isKanji

/**
 * Renders the original sentence as HTML for the Anki Sentence field.
 * Each content word becomes:
 *   <a href="kanjistudy://word?id=ENTRY_ID"><ruby>kanji<rt>reading</rt></ruby>suffix</a>
 * The target word also wraps its anchor in <b>...</b>.
 * Particles, punctuation, and tokens without a JMdict id render as plain
 * text (with ruby only if they contain kanji).
 */
object SentenceHtmlBuilder {

    data class TokenWithEntry(val token: Token, val entryId: Long?)

    fun build(tokens: List<TokenWithEntry>, targetEntryId: Long?): String {
        val sb = StringBuilder()
        for ((token, entryId) in tokens) {
            val isTarget = entryId != null && entryId == targetEntryId
            val renderable = renderToken(token, entryId)
            if (isTarget) {
                // CSS-class wrapper instead of <b>: the card stylesheet renders
                // the target word in primary red (matching the in-app color)
                // and the rest of the sentence in default body color, so the
                // target stands out without bolding.
                sb.append("<span class=\"target-word\">")
                    .append(renderable)
                    .append("</span>")
            } else {
                sb.append(renderable)
            }
        }
        return sb.toString()
    }

    private fun renderToken(token: Token, entryId: Long?): String {
        val body = rubyOrPlain(token)
        return when {
            token.partOfSpeech == PartOfSpeech.PARTICLE -> body
            token.partOfSpeech == PartOfSpeech.PUNCTUATION -> body
            entryId != null -> "<a href=\"kanjistudy://word?id=$entryId\">$body</a>"
            else -> body
        }
    }

    private fun rubyOrPlain(token: Token): String {
        val surface = token.surface
        if (surface.isBlank()) return ""
        if (!surface.any { it.isKanji() }) return surface.htmlEscape()
        // Build ruby per kanji run, mirroring FuriganaBuilder's logic but with
        // <ruby>/<rt> markup instead of bracket notation.
        return buildRuby(surface, token.reading)
    }

    private fun buildRuby(surface: String, reading: String): String {
        if (reading.isBlank()) return surface.htmlEscape()
        val out = StringBuilder()
        var i = 0
        var r = 0
        while (i < surface.length) {
            val c = surface[i]
            if (c.isKanji()) {
                var j = i
                while (j < surface.length && surface[j].isKanji()) j++
                val kanjiRun = surface.substring(i, j)

                val nextKanaStart = j
                var nextKanaEnd = nextKanaStart
                while (nextKanaEnd < surface.length && !surface[nextKanaEnd].isKanji()) nextKanaEnd++
                val followingKana = surface.substring(nextKanaStart, nextKanaEnd)

                val rEnd = if (followingKana.isEmpty()) reading.length
                else findKanaMatch(reading, r, followingKana)

                if (rEnd < 0) {
                    out.append("<ruby>").append(surface.substring(i).htmlEscape())
                        .append("<rt>").append(reading.substring(r).htmlEscape()).append("</rt></ruby>")
                    return out.toString()
                }
                out.append("<ruby>").append(kanjiRun.htmlEscape())
                    .append("<rt>").append(reading.substring(r, rEnd).htmlEscape())
                    .append("</rt></ruby>")
                out.append(followingKana.htmlEscape())
                i = nextKanaEnd
                r = rEnd + followingKana.length
            } else {
                out.append(c.toString().htmlEscape())
                if (r < reading.length) r++
                i++
            }
        }
        return out.toString()
    }

    private fun findKanaMatch(reading: String, from: Int, needle: String): Int {
        if (needle.isEmpty()) return from
        var idx = from
        while (idx + needle.length <= reading.length) {
            if (reading.regionMatches(idx, needle, 0, needle.length)) return idx
            idx++
        }
        return -1
    }
}
