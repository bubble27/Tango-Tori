package com.tangotori.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaBuilderTest {

    @Test fun `kanji with kana tail`() {
        assertEquals("食[た]べる", FuriganaBuilder.build("食べる", "たべる"))
    }

    @Test fun `verb with single kanji kana suffix`() {
        assertEquals("行[い]く", FuriganaBuilder.build("行く", "いく"))
    }

    @Test fun `compound with two kanji groups separated by kana`() {
        // Grouped form — the algorithm cannot split per-kanji without kanjidic.
        // See FuriganaBuilder class docstring for the limitation.
        assertEquals("膝立[ひざだ]ち", FuriganaBuilder.build("膝立ち", "ひざだち"))
    }

    @Test fun `pure katakana returns as-is`() {
        assertEquals("コーヒー", FuriganaBuilder.build("コーヒー", "こーひー"))
    }

    @Test fun `pure hiragana returns as-is`() {
        assertEquals("きれい", FuriganaBuilder.build("きれい", "きれい"))
    }

    @Test fun `multi-kanji compound`() {
        // All three kanji are contiguous → one bracket (grouped form).
        assertEquals("東京都[とうきょうと]", FuriganaBuilder.build("東京都", "とうきょうと"))
    }

    @Test fun `empty inputs return surface`() {
        assertEquals("", FuriganaBuilder.build("", "abc"))
        assertEquals("食べる", FuriganaBuilder.build("食べる", ""))
    }
}
