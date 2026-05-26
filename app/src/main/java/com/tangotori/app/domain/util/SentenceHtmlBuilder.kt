package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.models.Token.Companion.isKanji

/**
 * Renders the original sentence as HTML for the Anki Sentence field.
 * Supports both Japanese (kana ruby) and Chinese (pinyin ruby) via [isChinese].
 *
 * Each content word becomes:
 *   <a href="..."><ruby>char<rt>reading</rt></ruby></a>
 * The target word is wrapped in <span class="target-word">.
 * Particles, punctuation, and tokens without a dictionary id render as plain
 * text with ruby annotation only.
 *
 * Link targets:
 *   Japanese, KS mode:  kanjistudy://word?id=ENTRY_ID
 *   Japanese, Jisho:    https://jisho.org/search/WORD
 *   Chinese, KS mode:   kanjistudy://word?id=ENTRY_ID
 *   Chinese, MDBG:      https://www.mdbg.net/chinese/dictionary?word=WORD
 */
object SentenceHtmlBuilder {

    data class TokenWithEntry(val token: Token, val entryId: Long?)

    fun build(
        tokens: List<TokenWithEntry>,
        targetEntryId: Long?,
        useKanjiStudyLinks: Boolean = false,
        isChinese: Boolean = false,
    ): String {
        val sb = StringBuilder()
        for ((token, entryId) in tokens) {
            val isTarget = entryId != null && entryId == targetEntryId
            val renderable = renderToken(token, entryId, useKanjiStudyLinks, isChinese)
            if (isTarget) {
                sb.append("<span class=\"target-word\">")
                    .append(renderable)
                    .append("</span>")
            } else {
                sb.append(renderable)
            }
        }
        return sb.toString()
    }

    private fun renderToken(
        token: Token,
        entryId: Long?,
        useKanjiStudyLinks: Boolean,
        isChinese: Boolean,
    ): String {
        val body = rubyOrPlain(token, isChinese)
        return when {
            token.partOfSpeech == PartOfSpeech.PARTICLE -> body
            token.partOfSpeech == PartOfSpeech.PUNCTUATION -> body
            entryId != null -> {
                val href = when {
                    useKanjiStudyLinks && !isChinese -> "kanjistudy://word?id=$entryId"
                    isChinese -> {
                        val encoded = java.net.URLEncoder.encode(token.dictionaryForm, "UTF-8")
                        "https://www.mdbg.net/chinese/dictionary?page=worddict&wdrst=0&wdqb=$encoded"
                    }
                    else -> {
                        val encoded = java.net.URLEncoder.encode(token.dictionaryForm, "UTF-8")
                        "https://jisho.org/search/$encoded"
                    }
                }
                "<a href=\"$href\">$body</a>"
            }
            else -> body
        }
    }

    private fun rubyOrPlain(token: Token, isChinese: Boolean): String {
        val surface = token.surface
        if (surface.isBlank()) return ""
        if (!surface.any { it.isKanji() }) return surface.htmlEscape()
        return if (isChinese) {
            buildPinyinRuby(surface, token.reading)
        } else {
            buildJapaneseRuby(surface, token.reading)
        }
    }

    // ── Chinese path ─────────────────────────────────────────────────────────

    /** Distributes space-separated pinyin syllables across CJK characters. */
    private fun buildPinyinRuby(surface: String, pinyinMarks: String): String {
        if (pinyinMarks.isBlank()) return surface.htmlEscape()
        val syllables = pinyinMarks.trim().split(' ').filter { it.isNotEmpty() }
        val out = StringBuilder()
        var syllableIdx = 0
        for (c in surface) {
            if (c.isKanji()) {
                val syllable = syllables.getOrElse(syllableIdx++) { "" }
                out.append("<ruby>").append(c.toString().htmlEscape())
                    .append("<rt>").append(syllable.htmlEscape()).append("</rt></ruby>")
            } else {
                out.append(c.toString().htmlEscape())
            }
        }
        return out.toString()
    }

    // ── Japanese path (unchanged) ─────────────────────────────────────────────

    private fun buildJapaneseRuby(surface: String, reading: String): String {
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
