package com.tangotori.app.domain.util

/**
 * Splits a Chinese word into [KanjiTile]s — one per character — using
 * CC-CEDICT single-character entries as the meaning/reading source.
 *
 * Reuses [KanjiTile] (char + reading + meanings) so the existing
 * [ExpandedEntryCard] kanji section and Anki HTML builder work unchanged for
 * Chinese; the "reading" slot simply holds pinyin instead of hiragana.
 *
 * The pinyin syllables are taken from [wordPinyinMarks] (the word-level CC-
 * CEDICT reading), giving correct polyphone context.
 */
object HanziBreakdownBuilder {

    suspend fun build(
        surface: String,
        wordPinyinMarks: String,
        charLookup: suspend (String) -> List<String>,
        maxMeanings: Int = 3,
    ): List<KanjiTile> {
        val syllables = wordPinyinMarks.trim().split(' ').filter { it.isNotEmpty() }
        val hanziChars = surface.filter { isCjkChar(it) }
        if (hanziChars.isEmpty()) return emptyList()

        return hanziChars.mapIndexed { idx, c ->
            val pinyin = syllables.getOrElse(idx) { "" }
            val meanings = charLookup(c.toString()).take(maxMeanings)
            KanjiTile(char = c.toString(), reading = pinyin, meanings = meanings)
        }
    }

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
               code in 0x3400..0x4DBF ||
               code in 0xF900..0xFAFF
    }
}

/**
 * Renders hanzi breakdown tiles as `kanji-tile` HTML — identical markup to
 * [KanjiBreakdownHtmlBuilder] so the same Anki card stylesheet applies.
 */
object HanziBreakdownHtmlBuilder {

    fun build(tiles: List<KanjiTile>): String {
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
