package com.tangotori.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KanjiKanaSplitTest {

    @Test fun `verb stem and okurigana`() {
        val parts = KanjiKanaSplit.split("食べる", "たべる")
        assertEquals(2, parts.size)
        assertEquals("食", parts[0].text); assertEquals("た", parts[0].furigana)
        assertEquals("べる", parts[1].text); assertEquals(null, parts[1].furigana)
    }

    @Test fun `conjugated verb stem`() {
        val parts = KanjiKanaSplit.split("入っ", "はいっ")
        assertEquals(2, parts.size)
        assertEquals("入", parts[0].text); assertEquals("はい", parts[0].furigana)
        assertEquals("っ", parts[1].text); assertEquals(null, parts[1].furigana)
    }

    @Test fun `noun with no kana`() {
        val parts = KanjiKanaSplit.split("瞬間", "しゅんかん")
        assertEquals(1, parts.size)
        assertEquals("瞬間", parts[0].text); assertEquals("しゅんかん", parts[0].furigana)
    }

    @Test fun `pure kana token`() {
        val parts = KanjiKanaSplit.split("スタジオ", "すたじお")
        assertEquals(1, parts.size)
        assertEquals("スタジオ", parts[0].text); assertEquals(null, parts[0].furigana)
    }

    @Test fun `derive dict form reading for godan verb`() {
        assertEquals(
            "はいる",
            KanjiKanaSplit.deriveDictFormReading("入っ", "はいっ", "入る"),
        )
    }

    @Test fun `derive dict form reading for kawaru`() {
        assertEquals(
            "かわる",
            KanjiKanaSplit.deriveDictFormReading("変わっ", "かわっ", "変わる"),
        )
    }

    @Test fun `dict form same as surface returns surface reading`() {
        assertEquals(
            "しゅんかん",
            KanjiKanaSplit.deriveDictFormReading("瞬間", "しゅんかん", "瞬間"),
        )
    }
}
