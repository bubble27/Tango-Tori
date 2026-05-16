package com.tangotori.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaHtmlBuilderTest {

    @Test fun `per-kanji compound emits one ruby per group`() {
        // FuriganaBuilder can sometimes resolve per-kanji boundaries, e.g.
        // 仲[なか]間[ま] when both kanji have distinct kana neighbours upstream.
        val html = FuriganaHtmlBuilder.build("仲[なか]間[ま]")
        assertEquals(
            "<ruby>仲<rt>なか</rt></ruby><ruby>間<rt>ま</rt></ruby>",
            html,
        )
    }

    @Test fun `kanji run followed by trailing kana renders ruby plus kana`() {
        val html = FuriganaHtmlBuilder.build("食[た]べる")
        assertEquals("<ruby>食<rt>た</rt></ruby>べる", html)
    }

    @Test fun `whole-word reading on single ideograph run`() {
        val html = FuriganaHtmlBuilder.build("東京都[とうきょうと]")
        assertEquals("<ruby>東京都<rt>とうきょうと</rt></ruby>", html)
    }

    @Test fun `pure kana surface returns unchanged`() {
        assertEquals("スタジオ", FuriganaHtmlBuilder.build("スタジオ"))
    }

    @Test fun `empty input returns empty string`() {
        assertEquals("", FuriganaHtmlBuilder.build(""))
    }

    @Test fun `html-unsafe characters are escaped`() {
        // Constructed manually — FuriganaBuilder wouldn't emit `<` legitimately,
        // but if anything ever leaked through the markup we still want the
        // output to be safe HTML.
        val html = FuriganaHtmlBuilder.build("<")
        assertEquals("&lt;", html)
    }
}
