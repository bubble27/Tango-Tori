package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Token.Companion.isKanji

/**
 * Generates the kanji-breakdown section for the back of an Anki card.
 *
 * Input is the AnkiDroid furigana markup that [FuriganaBuilder] produces
 * (e.g. `仲間[なかま]` for a fused compound, or `食[た]べる` for kanji+okurigana).
 * Each ideograph yields a tile that renders in the card template as:
 *
 *   <div class="kanji-tile">
 *     <div class="kanji-reading">なか</div>   ← reading on top
 *     <div class="kanji-char">仲</div>        ← kanji below
 *     <div class="kanji-meaning">relation</div>
 *   </div>
 *
 * When the markup lumps multiple kanji under one bracketed reading (FuriganaBuilder
 * can't always split compounds), we use the per-kanji readings supplied by
 * [kanjiReadings] to align the compound reading against each kanji and emit
 * per-tile readings. If no alignment is possible (rendaku, gemination, etc.),
 * we fall back to attaching the whole reading to the first kanji.
 *
 * Pure-kana words produce an empty string — the template's `{{#KanjiBreakdown}}`
 * mustache guard then collapses the section.
 */
object KanjiBreakdownHtmlBuilder {

    fun build(
        furiganaMarkup: String,
        kanjiMeanings: (String) -> List<String>,
        kanjiReadings: (String) -> Set<String> = { emptySet() },
        maxMeaningsPerChar: Int = 3,
    ): String {
        if (furiganaMarkup.isEmpty()) return ""
        if (!furiganaMarkup.any { it.isKanji() }) return ""

        val tiles = mutableListOf<Tile>()
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
            tiles += explode(kanjiRun, reading, kanjiReadings)
            i = closeBracket + 1
        }
        if (tiles.isEmpty()) return ""

        val sb = StringBuilder()
        for (t in tiles) {
            val meanings = kanjiMeanings(t.char)
                .take(maxMeaningsPerChar)
                .joinToString(", ")
                .htmlEscape()
            sb.append("<div class=\"kanji-tile\">")
                .append("<div class=\"kanji-reading\">").append(t.reading.htmlEscape()).append("</div>")
                .append("<div class=\"kanji-char\">").append(t.char.htmlEscape()).append("</div>")
            if (meanings.isNotEmpty()) {
                sb.append("<div class=\"kanji-meaning\">").append(meanings).append("</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }

    private data class Tile(val char: String, val reading: String)

    private fun explode(
        kanjiRun: String,
        reading: String,
        kanjiReadings: (String) -> Set<String>,
    ): List<Tile> {
        if (kanjiRun.length == 1) return listOf(Tile(kanjiRun, reading))
        val split = splitReadingAcrossKanji(kanjiRun, reading, kanjiReadings)
        if (split != null) {
            return kanjiRun.mapIndexed { idx, c -> Tile(c.toString(), split[idx]) }
        }
        // Fallback: attach the whole reading to the first kanji, leave the
        // rest blank. Matches the v1 behavior; rare with KANJIDIC readings
        // available.
        return kanjiRun.mapIndexed { idx, c ->
            Tile(c.toString(), if (idx == 0) reading else "")
        }
    }

    /**
     * Try every combination of per-kanji readings that exactly tiles the
     * compound reading. Backtracking — exits on first match. The match pool
     * for each kanji is enriched with two compound-junction sound changes:
     *
     *   - **Rendaku** (sequential voicing): non-initial kanji's first mora's
     *     unvoiced consonant becomes voiced — か→が, さ→ざ, は→ば/ぱ, etc.
     *   - **Gemination** (sokuon): non-final kanji's last mora becomes っ —
     *     がく + こう → がっこう (学校).
     *
     * We pair each candidate `(matchForm, displayForm)`. The match form is
     * what we try to align against the compound reading; the display form is
     * the original/base kanjidic reading we want to show on the card. So for
     * 学校 we match `がっ` against the reading slice but output `がく`,
     * matching the user's intent that tiles show the regular per-kanji
     * reading rather than its phonetic compound variant.
     *
     * Returns null if no alignment consumes the entire compound reading —
     * caller falls back to whole-reading-on-first.
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
            // (matchForm, displayForm) pairs. Base readings have match = display;
            // rendaku/gemination variants pair the variant with the original
            // base form so the card shows the regular reading.
            val candidates = LinkedHashMap<String, String>() // match → display
            for (b in base) candidates.putIfAbsent(b, b)
            if (!isFirst) for (b in base) {
                for (v in rendakuVariants(b)) candidates.putIfAbsent(v, b)
            }
            if (!isLast) for (b in base) {
                geminationVariant(b)?.let { v -> candidates.putIfAbsent(v, b) }
            }
            // Prefer longer matches — disambiguates overlapping readings.
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

    /**
     * Returns the rendaku-voiced forms of [reading] (the first mora's
     * unvoiced consonant becomes voiced). Most consonants have one voiced
     * counterpart; the h-row has two (b- and p-).
     */
    private fun rendakuVariants(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val voiced = RendakuMap[reading[0]] ?: return emptyList()
        val tail = reading.substring(1)
        return voiced.map { v -> "$v$tail" }
    }

    /**
     * Returns the geminated form (last mora → っ) when the last mora is one
     * of the "sokuon-prone" morae — く, き, ち, つ, ふ. Null otherwise.
     */
    private fun geminationVariant(reading: String): String? {
        if (reading.length < 2) return null
        val last = reading.last()
        if (last !in GeminationLastMora) return null
        return reading.dropLast(1) + 'っ'
    }

    private val RendakuMap: Map<Char, List<Char>> = mapOf(
        // k → g
        'か' to listOf('が'), 'き' to listOf('ぎ'), 'く' to listOf('ぐ'),
        'け' to listOf('げ'), 'こ' to listOf('ご'),
        // s → z (し→じ uses Japanese phonology, written ぢ vs じ; we use じ).
        'さ' to listOf('ざ'), 'し' to listOf('じ'), 'す' to listOf('ず'),
        'せ' to listOf('ぜ'), 'そ' to listOf('ぞ'),
        // t → d (ち→ぢ, つ→づ are historical/yotsugana — produced occasionally).
        'た' to listOf('だ'), 'ち' to listOf('ぢ', 'じ'), 'つ' to listOf('づ', 'ず'),
        'て' to listOf('で'), 'と' to listOf('ど'),
        // h → b or p (both occur — はち+ひゃく → はっぴゃく has p-,
        // 山 + 花 → 山花 やまばな has b-).
        'は' to listOf('ば', 'ぱ'),
        'ひ' to listOf('び', 'ぴ'),
        'ふ' to listOf('ぶ', 'ぷ'),
        'へ' to listOf('べ', 'ぺ'),
        'ほ' to listOf('ぼ', 'ぽ'),
    )

    private val GeminationLastMora: Set<Char> = setOf('く', 'き', 'ち', 'つ', 'ふ')
}
