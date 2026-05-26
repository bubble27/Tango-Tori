package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Language

/**
 * Detects whether a text snippet is Japanese, Simplified Chinese, or
 * Traditional Chinese using Unicode codepoint heuristics.
 *
 * Japanese almost always contains hiragana or katakana alongside kanji —
 * those scripts are the strongest signal. For pure-CJK input (no kana), we
 * scan for characters whose Traditional form has a different codepoint from
 * the Simplified form; finding any such character means the text is
 * Traditional. Otherwise we default to Simplified Chinese.
 *
 * This is good enough for sentence-level input: a user pasting a Japanese
 * sentence will virtually always include at least one hiragana character.
 */
object LanguageDetector {

    fun detect(text: String): Language {
        var hasKana = false
        var hasCjk = false
        var hasTraditionalOnly = false

        for (c in text) {
            val code = c.code
            when {
                code in 0x3040..0x309F -> { hasKana = true; break }   // hiragana
                code in 0x30A0..0x30FF && c != 'ー' -> { hasKana = true; break } // katakana (ー is also used in Chinese loanword rendering, ignore)
                isCjk(code) -> {
                    hasCjk = true
                    if (c in TRADITIONAL_ONLY_CHARS) hasTraditionalOnly = true
                }
            }
        }

        return when {
            hasKana -> Language.JAPANESE
            !hasCjk -> Language.JAPANESE
            hasTraditionalOnly -> Language.CHINESE_TRADITIONAL
            else -> Language.CHINESE_SIMPLIFIED
        }
    }

    private fun isCjk(code: Int): Boolean =
        code in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
        code in 0x3400..0x4DBF ||   // CJK Extension A
        code in 0x20000..0x2A6DF    // CJK Extension B (supplementary, rare)

    /**
     * Characters whose Traditional Chinese codepoints are distinct from both
     * their Simplified Chinese equivalents AND from everyday Japanese kanji.
     * Selected from the top-200 most frequent Chinese characters.
     *
     * Traditional → Simplified mapping: e.g. 們→们, 這→这, 國→国, etc.
     * We check the TRADITIONAL form here because a text typed in traditional
     * Chinese will use these codepoints; the simplified equivalents (们/这/国)
     * are valid in both Simplified Chinese AND Japanese so they give no signal.
     */
    private val TRADITIONAL_ONLY_CHARS: Set<Char> = setOf(
        '們', '這', '時', '來', '說', '對', '從', '發', '為', '會',
        '傳', '當', '頭', '進', '問', '關', '開', '電', '車', '麼',
        '動', '過', '長', '現', '萬', '種', '體', '機', '與', '學',
        '點', '歡', '讓', '還', '後', '裡', '邊', '誰', '啊', '嗎',
        '呢', '吧', '嘛', '麻', '險', '廣', '愛', '愛', '書', '語',
        '語', '話', '讀', '聽', '寫', '詞', '難', '簡', '繁', '體',
        '舊', '舊', '幫', '實', '際', '際', '響', '聲', '聲', '請',
        '讀', '認', '識', '識', '辦', '辦', '員', '樣', '樣', '條',
    )
}
