package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceHtmlBuilderTest {

    private fun tok(
        surface: String,
        reading: String = surface,
        pos: PartOfSpeech = PartOfSpeech.NOUN,
    ) = SentenceHtmlBuilder.TokenWithEntry(
        Token(surface, surface, reading, reading, pos, ""),
        if (pos == PartOfSpeech.PARTICLE || pos == PartOfSpeech.PUNCTUATION) null else 1L,
    )

    @Test fun `noun with kanji gets ruby and jisho link by default`() {
        val html = SentenceHtmlBuilder.build(
            listOf(SentenceHtmlBuilder.TokenWithEntry(
                Token("学校", "学校", "がっこう", "がっこう", PartOfSpeech.NOUN, ""), 100L)),
            targetEntryId = null,
        )
        assertEquals("<a href=\"https://jisho.org/search/%E5%AD%A6%E6%A0%A1\"><ruby>学校<rt>がっこう</rt></ruby></a>", html)
    }

    @Test fun `noun with kanji gets kanjistudy link in KS mode`() {
        val html = SentenceHtmlBuilder.build(
            listOf(SentenceHtmlBuilder.TokenWithEntry(
                Token("学校", "学校", "がっこう", "がっこう", PartOfSpeech.NOUN, ""), 100L)),
            targetEntryId = null,
            useKanjiStudyLinks = true,
        )
        assertEquals("<a href=\"kanjistudy://word?id=100\"><ruby>学校<rt>がっこう</rt></ruby></a>", html)
    }

    @Test fun `particle is plain text no link`() {
        val html = SentenceHtmlBuilder.build(
            listOf(SentenceHtmlBuilder.TokenWithEntry(
                Token("は", "は", "は", "は", PartOfSpeech.PARTICLE, ""), null)),
            targetEntryId = null,
        )
        assertEquals("は", html)
        assertFalse(html.contains("<a"))
    }

    @Test fun `target word wrapped in target-word span`() {
        val tokens = listOf(
            SentenceHtmlBuilder.TokenWithEntry(
                Token("食べる", "食べる", "たべる", "たべる", PartOfSpeech.VERB, ""), 42L),
        )
        val html = SentenceHtmlBuilder.build(tokens, targetEntryId = 42L, useKanjiStudyLinks = true)
        assertTrue(html.startsWith("<span class=\"target-word\">"))
        assertTrue(html.endsWith("</span>"))
        assertTrue(html.contains("kanjistudy://word?id=42"))
    }

    @Test fun `punctuation rendered plain`() {
        val html = SentenceHtmlBuilder.build(
            listOf(SentenceHtmlBuilder.TokenWithEntry(
                Token("。", "。", "", "", PartOfSpeech.PUNCTUATION, ""), null)),
            null,
        )
        assertEquals("。", html)
    }

    @Test fun `token without entry id is plain but may keep ruby`() {
        val html = SentenceHtmlBuilder.build(
            listOf(SentenceHtmlBuilder.TokenWithEntry(
                Token("壊", "壊", "こわ", "こわ", PartOfSpeech.NOUN, ""), null)),
            null,
        )
        assertFalse(html.contains("<a "))
        assertTrue(html.contains("<ruby>壊<rt>こわ</rt></ruby>"))
    }
}
