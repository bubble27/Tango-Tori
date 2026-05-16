package com.tangotori.app.data.sudachi

import com.tangotori.app.domain.models.PartOfSpeech

/**
 * Maps Sudachi's detailed Japanese POS tags to the 8 UI categories from the spec.
 * Sudachi's POS list comes as up to 6 hierarchical fields. We match on the first
 * field with refinement on the second where useful.
 */
object PosMapper {
    /**
     * Maps Sudachi's POS tags PLUS a surface-string sanity check: any token
     * whose surface is entirely CJK / ASCII punctuation gets PUNCTUATION
     * regardless of what Sudachi tagged it. Defensive against dictionary
     * mistagging — `、` and `。` must never be rendered as a word chip.
     */
    fun classify(surface: String, posList: List<String>): PartOfSpeech {
        if (surface.isNotEmpty() && surface.all { isPunctuationChar(it) }) {
            return PartOfSpeech.PUNCTUATION
        }
        return map(posList)
    }

    private fun isPunctuationChar(c: Char): Boolean {
        val code = c.code
        return when {
            // CJK Symbols and Punctuation: 、 。 ・ 「 」 etc.
            code in 0x3000..0x303F -> true
            // General Punctuation (en/em dashes, ellipses, quotes).
            code in 0x2000..0x206F -> true
            // ASCII punctuation.
            else -> when (c) {
                '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',',
                '-', '.', '/', ':', ';', '<', '=', '>', '?', '@', '[', '\\',
                ']', '^', '_', '`', '{', '|', '}', '~' -> true
                else -> false
            }
        }
    }

    fun map(posList: List<String>): PartOfSpeech {
        if (posList.isEmpty()) return PartOfSpeech.CONJUNCTION_OTHER
        val top = posList[0]
        val sub = posList.getOrNull(1).orEmpty()
        return when (top) {
            "名詞" -> PartOfSpeech.NOUN
            "代名詞" -> PartOfSpeech.NOUN
            "動詞" -> PartOfSpeech.VERB
            "形容詞" -> PartOfSpeech.I_ADJECTIVE
            "形状詞" -> PartOfSpeech.NA_ADJECTIVE // Sudachi's term for keiyoudoushi stem
            "助詞" -> PartOfSpeech.PARTICLE
            "副詞" -> PartOfSpeech.ADVERB
            "助動詞" -> PartOfSpeech.AUXILIARY_VERB
            "接続詞" -> PartOfSpeech.CONJUNCTION_OTHER
            "感動詞" -> PartOfSpeech.CONJUNCTION_OTHER
            "連体詞" -> PartOfSpeech.CONJUNCTION_OTHER
            "接頭辞" -> PartOfSpeech.CONJUNCTION_OTHER
            "接尾辞" -> when (sub) {
                "名詞的" -> PartOfSpeech.NOUN
                "動詞的" -> PartOfSpeech.VERB
                "形容詞的" -> PartOfSpeech.I_ADJECTIVE
                else -> PartOfSpeech.CONJUNCTION_OTHER
            }
            "補助記号", "記号" -> PartOfSpeech.PUNCTUATION
            "空白" -> PartOfSpeech.PUNCTUATION
            else -> PartOfSpeech.CONJUNCTION_OTHER
        }
    }

    /** Spec compatibility: spec uses 形容動詞語幹 as the example tag for na-adjective.
     * Sudachi 0.7 outputs 形状詞 for these; both should map to NA_ADJECTIVE. */
    fun mapRawString(raw: String): PartOfSpeech =
        map(raw.split(",", "-", "／").map { it.trim() }.filter { it.isNotEmpty() })
}
