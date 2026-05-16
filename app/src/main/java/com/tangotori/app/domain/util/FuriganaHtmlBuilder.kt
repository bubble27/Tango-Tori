package com.tangotori.app.domain.util

/**
 * Converts the AnkiDroid `kanji[reading]` furigana markup (the format
 * [FuriganaBuilder] emits) into per-kanji-run `<ruby>` HTML for the front of
 * the card. The runs are already grouped to whatever granularity
 * [FuriganaBuilder] could resolve — usually per-kanji for compounds like
 * `仲[なか]間[ま]`, occasionally per-multi-kanji-group for ambiguous compounds
 * like `膝立[ひざだ]ち`. We just emit one `<ruby>` per group.
 *
 *   "仲[なか]間[ま]"     →  "<ruby>仲<rt>なか</rt></ruby><ruby>間<rt>ま</rt></ruby>"
 *   "食[た]べる"         →  "<ruby>食<rt>た</rt></ruby>べる"
 *   "スタジオ"            →  "スタジオ"  (no kanji, no ruby)
 *   "東京都[とうきょうと]" →  "<ruby>東京都<rt>とうきょうと</rt></ruby>"
 */
object FuriganaHtmlBuilder {

    fun build(furiganaMarkup: String): String {
        if (furiganaMarkup.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < furiganaMarkup.length) {
            val openBracket = furiganaMarkup.indexOf('[', startIndex = i)
            if (openBracket < 0) {
                // No more reading groups — flush the remainder as plain text.
                out.append(furiganaMarkup.substring(i).htmlEscape())
                break
            }
            // The kanji run is everything from `i` up to (but not including)
            // the `[` *minus* any plain-kana already consumed by the previous
            // group. We need to find where the kanji run actually starts —
            // it's the contiguous run of characters immediately preceding the
            // `[`, of length 1+ ideographs (CJK Unified). Anything before
            // that is plain kana that the previous group already passed by.
            var kanjiStart = openBracket
            while (kanjiStart > i && furiganaMarkup[kanjiStart - 1].isIdeograph()) {
                kanjiStart--
            }
            // Kana between `i` and kanjiStart, if any, is plain text.
            if (kanjiStart > i) {
                out.append(furiganaMarkup.substring(i, kanjiStart).htmlEscape())
            }
            val kanji = furiganaMarkup.substring(kanjiStart, openBracket)
            val closeBracket = furiganaMarkup.indexOf(']', startIndex = openBracket + 1)
            if (closeBracket < 0) {
                // Malformed markup — emit the rest verbatim and bail.
                out.append(furiganaMarkup.substring(kanjiStart).htmlEscape())
                break
            }
            val reading = furiganaMarkup.substring(openBracket + 1, closeBracket)
            out.append("<ruby>")
                .append(kanji.htmlEscape())
                .append("<rt>")
                .append(reading.htmlEscape())
                .append("</rt></ruby>")
            i = closeBracket + 1
        }
        return out.toString()
    }

    private fun Char.isIdeograph(): Boolean {
        val code = code
        return code in 0x3400..0x4DBF ||  // CJK Unified Ideographs Extension A
                code in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
                code in 0xF900..0xFAFF     // CJK Compatibility Ideographs
    }
}
