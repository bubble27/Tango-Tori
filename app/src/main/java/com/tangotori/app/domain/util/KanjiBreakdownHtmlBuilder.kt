package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Token.Companion.isKanji

/**
 * Per-kanji tile data: ideograph + the slice of the compound reading that maps
 * to it + up to N English meanings. Consumed by the Anki HTML builder and by
 * the in-app dictionary's kanji section, so both views share the exact same
 * splitting/alignment logic.
 */
data class KanjiTile(
    val char: String,
    val reading: String,
    val meanings: List<String>,
)

/**
 * Splits a furigana-markup string into structured [KanjiTile]s — one per
 * ideograph. The compound reading is split across kanji via the same
 * backtracking + rendaku/gemination logic that the HTML builder used to do
 * inline; both views call this so they stay in sync.
 */
object KanjiBreakdownBuilder {

    fun build(
        furiganaMarkup: String,
        kanjiMeanings: (String) -> List<String>,
        kanjiReadings: (String) -> Set<String> = { emptySet() },
        maxMeaningsPerChar: Int = 3,
    ): List<KanjiTile> {
        if (furiganaMarkup.isEmpty()) return emptyList()
        if (!furiganaMarkup.any { it.isKanji() }) return emptyList()

        data class RawTile(val char: String, val reading: String)
        val raw = mutableListOf<RawTile>()
        var i = 0
        while (i < furiganaMarkup.length) {
            val openBracket = furiganaMarkup.indexOf('[', startIndex = i)
            val groupKanjiStart = if (openBracket < 0) {
                furiganaMarkup.length
            } else {
                var s = openBracket
                while (s > i && furiganaMarkup[s - 1].isKanji()) s--
                s
            }
            i = groupKanjiStart
            if (openBracket < 0) break
            val kanjiRun = furiganaMarkup.substring(i, openBracket)
            val closeBracket = furiganaMarkup.indexOf(']', startIndex = openBracket + 1)
            if (closeBracket < 0) break
            val reading = furiganaMarkup.substring(openBracket + 1, closeBracket)
            raw += explode(kanjiRun, reading, kanjiReadings).map { RawTile(it.char, it.reading) }
            i = closeBracket + 1
        }

        return raw.map { t ->
            KanjiTile(
                char = t.char,
                reading = t.reading,
                meanings = kanjiMeanings(t.char).take(maxMeaningsPerChar),
            )
        }
    }

    private data class Pair(val char: String, val reading: String)

    private fun explode(
        kanjiRun: String,
        reading: String,
        kanjiReadings: (String) -> Set<String>,
    ): List<Pair> {
        if (kanjiRun.length == 1) return listOf(Pair(kanjiRun, reading))
        val split = splitReadingAcrossKanji(kanjiRun, reading, kanjiReadings)
        if (split != null) {
            return kanjiRun.mapIndexed { idx, c -> Pair(c.toString(), split[idx]) }
        }
        return kanjiRun.mapIndexed { idx, c ->
            Pair(c.toString(), if (idx == 0) reading else "")
        }
    }

    /**
     * Try every combination of per-kanji readings that exactly tiles the
     * compound reading. Backtracking — exits on first match. Pool for each
     * kanji includes rendaku/gemination variants, paired with the BASE
     * display form so the tile shows the regular reading.
     */
    private fun splitReadingAcrossKanji(
        kanjiRun: String,
        reading: String,
        kanjiReadings: (String) -> Set<String>,
    ): List<String>? {
        fun recurse(kIdx: Int, rIdx: Int, acc: MutableList<String>): List<String>? {
            if (kIdx == kanjiRun.length) {
                return if (rIdx == reading.length) acc.toList() else null
            }
            val char = kanjiRun[kIdx].toString()
            val base = kanjiReadings(char).filter { it.isNotEmpty() }
            val isFirst = kIdx == 0
            val isLast = kIdx == kanjiRun.length - 1
            val candidates = LinkedHashMap<String, String>()
            for (b in base) candidates.putIfAbsent(b, b)
            if (!isFirst) for (b in base) {
                for (v in rendakuVariants(b)) candidates.putIfAbsent(v, b)
            }
            if (!isLast) for (b in base) {
                geminationVariant(b)?.let { v -> candidates.putIfAbsent(v, b) }
            }
            val sorted = candidates.entries.sortedByDescending { it.key.length }
            for ((matchForm, displayForm) in sorted) {
                if (matchForm.isEmpty()) continue
                if (rIdx + matchForm.length > reading.length) continue
                if (!reading.regionMatches(rIdx, matchForm, 0, matchForm.length)) continue
                acc.add(displayForm)
                val result = recurse(kIdx + 1, rIdx + matchForm.length, acc)
                if (result != null) return result
                acc.removeAt(acc.size - 1)
            }
            return null
        }
        return recurse(0, 0, mutableListOf())
    }

    private fun rendakuVariants(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val voiced = RendakuMap[reading[0]] ?: return emptyList()
        val tail = reading.substring(1)
        return voiced.map { v -> "$v$tail" }
    }

    private fun geminationVariant(reading: String): String? {
        if (reading.length < 2) return null
        val last = reading.last()
        if (last !in GeminationLastMora) return null
        return reading.dropLast(1) + 'っ'
    }

    private val RendakuMap: Map<Char, List<Char>> = mapOf(
        'か' to listOf('が'), 'き' to listOf('ぎ'), 'く' to listOf('ぐ'),
        'け' to listOf('げ'), 'こ' to listOf('ご'),
        'さ' to listOf('ざ'), 'し' to listOf('じ'), 'す' to listOf('ず'),
        'せ' to listOf('ぜ'), 'そ' to listOf('ぞ'),
        'た' to listOf('だ'), 'ち' to listOf('ぢ', 'じ'), 'つ' to listOf('づ', 'ず'),
        'て' to listOf('で'), 'と' to listOf('ど'),
        'は' to listOf('ば', 'ぱ'),
        'ひ' to listOf('び', 'ぴ'),
        'ふ' to listOf('ぶ', 'ぷ'),
        'へ' to listOf('べ', 'ぺ'),
        'ほ' to listOf('ぼ', 'ぽ'),
    )

    private val GeminationLastMora: Set<Char> = setOf('く', 'き', 'ち', 'つ', 'ふ')
}

/**
 * Generates the kanji-breakdown HTML for the back of an Anki card. Delegates
 * tile construction to [KanjiBreakdownBuilder] so the dictionary view (Compose)
 * and the Anki card (HTML) always render identical splits.
 */
object KanjiBreakdownHtmlBuilder {

    fun build(
        furiganaMarkup: String,
        kanjiMeanings: (String) -> List<String>,
        kanjiReadings: (String) -> Set<String> = { emptySet() },
        maxMeaningsPerChar: Int = 3,
    ): String {
        val tiles = KanjiBreakdownBuilder.build(
            furiganaMarkup = furiganaMarkup,
            kanjiMeanings = kanjiMeanings,
            kanjiReadings = kanjiReadings,
            maxMeaningsPerChar = maxMeaningsPerChar,
        )
        if (tiles.isEmpty()) return ""
        val sb = StringBuilder()
        for (t in tiles) {
            val meaningText = t.meanings.joinToString(", ").htmlEscape()
            sb.append("<div class=\"kanji-tile\">")
                .append("<div class=\"kanji-reading\">").append(t.reading.htmlEscape()).append("</div>")
                .append("<div class=\"kanji-char\">").append(t.char.htmlEscape()).append("</div>")
            if (meaningText.isNotEmpty()) {
                sb.append("<div class=\"kanji-meaning\">").append(meaningText).append("</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }
}
