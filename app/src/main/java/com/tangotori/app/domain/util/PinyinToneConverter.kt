package com.tangotori.app.domain.util

/**
 * Converts a single CC-CEDICT pinyin syllable in number-tone format
 * (e.g. "zhong1", "guo2", "ma5") to tone-mark form ("zhōng", "guó", "ma").
 *
 * Tone-mark placement follows standard Mandarin orthography rules:
 *   1. 'a' or 'e' always takes the mark.
 *   2. In "ou", 'o' takes the mark.
 *   3. Otherwise the last vowel in the rhyme takes the mark.
 *
 * Tone 5 (or 0) is the neutral/unstressed tone — returned without any mark.
 * CC-CEDICT uses 'u:' to represent ü (ü-umlaut); we normalize that first.
 */
object PinyinToneConverter {

    fun convertSyllable(pinyinNum: String): String {
        if (pinyinNum.isBlank()) return pinyinNum
        val tone = pinyinNum.last().digitToIntOrNull() ?: return pinyinNum
        // Strip the trailing digit and normalise ü representation.
        val base = pinyinNum.dropLast(1)
            .replace("u:", "ü", ignoreCase = true)
            .replace("v", "ü")          // alternate encoding sometimes seen

        if (tone == 5 || tone == 0) return base

        val idx = findTonePosition(base) ?: return base
        val vowel = base[idx].lowercaseChar()
        val marked = TONE_MARKS[vowel]?.getOrNull(tone - 1) ?: return base
        // Preserve original case of the vowel character.
        val finalMark = if (base[idx].isUpperCase()) marked.uppercase() else marked
        return base.substring(0, idx) + finalMark + base.substring(idx + 1)
    }

    /** Converts a full space-separated syllable string, e.g. "zhong1 guo2". */
    fun convertWord(pinyinNumbers: String): String =
        pinyinNumbers.trim().split(' ').joinToString(" ") { convertSyllable(it) }

    private fun findTonePosition(syllable: String): Int? {
        val s = syllable.lowercase()
        // Rule 1: 'a' or 'e' always takes the tone.
        s.indexOf('a').takeIf { it >= 0 }?.let { return it }
        s.indexOf('e').takeIf { it >= 0 }?.let { return it }
        // Rule 2: 'ou' → 'o' takes the tone.
        s.indexOf("ou").takeIf { it >= 0 }?.let { return it }
        // Rule 3: last vowel in the rhyme takes the tone.
        for (i in s.indices.reversed()) {
            if (s[i] in "iouü") return i
        }
        return null
    }

    private val TONE_MARKS: Map<Char, List<String>> = mapOf(
        'a' to listOf("ā", "á", "ǎ", "à"),
        'e' to listOf("ē", "é", "ě", "è"),
        'i' to listOf("ī", "í", "ǐ", "ì"),
        'o' to listOf("ō", "ó", "ǒ", "ò"),
        'u' to listOf("ū", "ú", "ǔ", "ù"),
        'ü' to listOf("ǖ", "ǘ", "ǚ", "ǜ"),
    )
}
