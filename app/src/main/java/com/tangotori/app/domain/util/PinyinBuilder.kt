package com.tangotori.app.domain.util

/**
 * Builds AnkiDroid "character[pinyin]" bracket markup for Chinese text,
 * mirroring [FuriganaBuilder]'s output format so the same card template works
 * for both languages. Each hanzi character gets its own bracket group:
 *
 *   "中国" + "zhōng guó"  →  "中[zhōng]国[guó]"
 *   "的"   + "de5"        →  "的[de]"
 *   "hello"               →  "hello"   (no CJK, returned as-is)
 *
 * [pinyinMarks] must be a space-separated tone-marked syllable string with
 * exactly as many syllables as there are Chinese characters in [surface]. If
 * the counts don't match, we fall back to bracketing the whole surface under
 * the full pinyin string — the same safety net FuriganaBuilder uses.
 */
object PinyinBuilder {

    fun build(surface: String, pinyinMarks: String): String {
        if (surface.isBlank()) return surface
        if (!surface.any { isCjkChar(it) }) return surface

        val syllables = pinyinMarks.trim().split(' ').filter { it.isNotEmpty() }
        val cjkCount = surface.count { isCjkChar(it) }

        if (syllables.size != cjkCount) {
            // Mismatch — fall back to one bracket around the whole word.
            return "$surface[$pinyinMarks]"
        }

        val out = StringBuilder()
        var syllableIdx = 0
        for (c in surface) {
            if (isCjkChar(c)) {
                out.append(c).append('[').append(syllables[syllableIdx++]).append(']')
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
               code in 0x3400..0x4DBF ||
               code in 0xF900..0xFAFF
    }
}
