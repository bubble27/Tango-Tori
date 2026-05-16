package com.tangotori.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiBreakdownHtmlBuilderTest {

    private fun build(
        markup: String,
        readings: Map<String, Set<String>> = emptyMap(),
        meanings: Map<String, List<String>> = emptyMap(),
    ): String = KanjiBreakdownHtmlBuilder.build(
        furiganaMarkup = markup,
        kanjiMeanings = { c -> meanings[c] ?: emptyList() },
        kanjiReadings = { c -> readings[c] ?: emptySet() },
    )

    private fun readingOf(char: String, html: String): String? {
        // Extract the .kanji-reading content for a given char by walking tile
        // boundaries. Brittle but readable.
        val tilePattern = """<div class="kanji-tile">(.*?)</div>(?=<div class="kanji-tile"|$)""".toRegex()
        for (m in tilePattern.findAll(html)) {
            val tile = m.groupValues[1]
            if (tile.contains(">$char<")) {
                val readingMatch = """<div class="kanji-reading">(.*?)</div>""".toRegex()
                    .find(tile) ?: return null
                return readingMatch.groupValues[1]
            }
        }
        return null
    }

    @Test fun `pure kana surface emits empty section`() {
        val html = build("スタジオ")
        assertEquals("", html)
    }

    @Test fun `single kanji uses its bracketed reading`() {
        val html = build("食[た]べる")
        assertTrue(html.contains("<div class=\"kanji-reading\">た</div>"))
        assertTrue(html.contains("<div class=\"kanji-char\">食</div>"))
    }

    @Test fun `multi-kanji compound aligns plain readings`() {
        // 仲間 reads なかま — no phonetic changes, just contiguous concat.
        val html = build(
            "仲間[なかま]",
            readings = mapOf(
                "仲" to setOf("なか", "ちゅう"),
                "間" to setOf("あいだ", "ま", "けん", "かん"),
            ),
        )
        assertEquals("なか", readingOf("仲", html))
        assertEquals("ま", readingOf("間", html))
    }

    @Test fun `gemination - school displays base readings on tiles`() {
        // 学校 がっこう = 学 がく → がっ (sokuon) + 校 こう. The compound
        // *aligns* via がっ but the user wants the tile to show the BASE
        // reading がく — easier for a learner to recognize.
        val html = build(
            "学校[がっこう]",
            readings = mapOf(
                "学" to setOf("がく", "まな"),
                "校" to setOf("こう"),
            ),
        )
        assertEquals("がく", readingOf("学", html))
        assertEquals("こう", readingOf("校", html))
    }

    @Test fun `gemination - nation displays base readings on tiles`() {
        // 国家 こっか: こく + か aligned via こっ + か. Tiles show こく and か.
        val html = build(
            "国家[こっか]",
            readings = mapOf(
                "国" to setOf("こく", "くに"),
                "家" to setOf("か", "け", "いえ"),
            ),
        )
        assertEquals("こく", readingOf("国", html))
        assertEquals("か", readingOf("家", html))
    }

    @Test fun `rendaku - mouth displays base reading despite voicing`() {
        // 入口 いりぐち: 入 いり + 口 くち aligned via いり + ぐち. Tile shows くち.
        val html = build(
            "入口[いりぐち]",
            readings = mapOf(
                "入" to setOf("いり", "にゅう", "い"),
                "口" to setOf("くち", "こう"),
            ),
        )
        assertEquals("いり", readingOf("入", html))
        assertEquals("くち", readingOf("口", html))
    }

    @Test fun `rendaku - h-row aligned via p, tile shows base`() {
        // 鉛筆 えんぴつ: 鉛 えん + 筆 ひつ aligned via えん + ぴつ. Tile shows ひつ.
        val html = build(
            "鉛筆[えんぴつ]",
            readings = mapOf(
                "鉛" to setOf("えん", "なまり"),
                "筆" to setOf("ひつ", "ふで"),
            ),
        )
        assertEquals("えん", readingOf("鉛", html))
        assertEquals("ひつ", readingOf("筆", html))
    }

    @Test fun `unalignable reading falls back to whole on first kanji`() {
        // Synthetic: reading doesn't decompose into known readings → fallback.
        val html = build(
            "東京[とうきょう]",
            readings = mapOf(
                // Force the splitter to fail by giving wrong readings.
                "東" to setOf("ひがし"),
                "京" to setOf("みやこ"),
            ),
        )
        // Fallback: first kanji has the whole reading, second is empty.
        assertEquals("とうきょう", readingOf("東", html))
        assertEquals("", readingOf("京", html))
    }

    @Test fun `no meanings drops the meaning div`() {
        val html = build("食[た]べる")
        assertFalse(html.contains("kanji-meaning"))
    }

    @Test fun `meanings truncated to maxMeaningsPerChar default 3`() {
        val html = build(
            markup = "食[た]べる",
            meanings = mapOf("食" to listOf("eat", "food", "meal", "diet", "consume")),
        )
        assertTrue(html.contains("eat, food, meal"))
        assertFalse(html.contains("diet"))
    }
}
