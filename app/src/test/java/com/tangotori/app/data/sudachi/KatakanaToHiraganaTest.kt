package com.tangotori.app.data.sudachi

import org.junit.Assert.assertEquals
import org.junit.Test

class KatakanaToHiraganaTest {
    @Test fun basic() {
        assertEquals("たべる", "タベル".katakanaToHiragana())
        assertEquals("がっこう", "ガッコウ".katakanaToHiragana())
    }
    @Test fun longVowel() {
        // ー is preserved (it's not in the Katakana block)
        assertEquals("こーひー", "コーヒー".katakanaToHiragana())
    }
    @Test fun mixedKeepsHiraganaAsIs() {
        assertEquals("たべます", "タベます".katakanaToHiragana())
    }
}
