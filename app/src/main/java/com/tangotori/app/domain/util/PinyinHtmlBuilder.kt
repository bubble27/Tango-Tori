package com.tangotori.app.domain.util

/**
 * Converts [PinyinBuilder]'s bracket markup into per-character `<ruby>` HTML,
 * mirroring [FuriganaHtmlBuilder]:
 *
 *   "中[zhōng]国[guó]"  →  "<ruby>中<rt>zhōng</rt></ruby><ruby>国<rt>guó</rt></ruby>"
 *   "的[de]"            →  "<ruby>的<rt>de</rt></ruby>"
 *   "hello"             →  "hello"
 */
object PinyinHtmlBuilder {

    fun build(pinyinMarkup: String): String {
        if (pinyinMarkup.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < pinyinMarkup.length) {
            val openBracket = pinyinMarkup.indexOf('[', startIndex = i)
            if (openBracket < 0) {
                out.append(pinyinMarkup.substring(i).htmlEscape())
                break
            }
            // The character immediately before '[' is the hanzi.
            val charStart = openBracket - 1
            if (charStart < i) {
                // Safety: no char before bracket
                out.append(pinyinMarkup.substring(i, openBracket).htmlEscape())
                i = openBracket
                continue
            }
            // Flush any plain text before the hanzi character.
            if (charStart > i) {
                out.append(pinyinMarkup.substring(i, charStart).htmlEscape())
            }
            val hanziChar = pinyinMarkup.substring(charStart, openBracket)
            val closeBracket = pinyinMarkup.indexOf(']', startIndex = openBracket + 1)
            if (closeBracket < 0) {
                out.append(pinyinMarkup.substring(charStart).htmlEscape())
                break
            }
            val pinyin = pinyinMarkup.substring(openBracket + 1, closeBracket)
            out.append("<ruby>")
                .append(hanziChar.htmlEscape())
                .append("<rt>")
                .append(pinyin.htmlEscape())
                .append("</rt></ruby>")
            i = closeBracket + 1
        }
        return out.toString()
    }
}
