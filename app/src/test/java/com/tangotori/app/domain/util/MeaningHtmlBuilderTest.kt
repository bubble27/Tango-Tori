package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Gloss
import com.tangotori.app.domain.models.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningHtmlBuilderTest {

    @Test fun `single sense renders POS label then OL with one LI`() {
        val html = MeaningHtmlBuilder.build(
            listOf(Sense(listOf("v1", "vi"), listOf(Gloss("to break"), Gloss("to fall apart")))),
        )
        // Codes translate to human-readable labels and glosses pack into one LI.
        assertEquals(
            "<div class=\"pos-label\">Ichidan verb, Intransitive verb</div>" +
                    "<ol class=\"senses\"><li>to break, to fall apart</li></ol>",
            html,
        )
    }

    @Test fun `consecutive senses with same POS share one OL`() {
        val html = MeaningHtmlBuilder.build(
            listOf(
                Sense(listOf("n"), listOf(Gloss("first"))),
                Sense(listOf("n"), listOf(Gloss("second"))),
            ),
        )
        // One pos-label, one ol, two li.
        assertEquals(1, "pos-label".toRegex().findAll(html).count())
        assertEquals(1, "<ol".toRegex().findAll(html).count())
        assertTrue(html.contains("<li>first</li>"))
        assertTrue(html.contains("<li>second</li>"))
    }

    @Test fun `pos change starts a fresh group`() {
        val html = MeaningHtmlBuilder.build(
            listOf(
                Sense(listOf("n"), listOf(Gloss("noun sense"))),
                Sense(listOf("vs"), listOf(Gloss("verb sense"))),
            ),
        )
        assertEquals(2, "pos-label".toRegex().findAll(html).count())
        assertEquals(2, "<ol".toRegex().findAll(html).count())
        assertTrue(html.contains("<div class=\"pos-label\">Noun</div>"))
        assertTrue(html.contains("<div class=\"pos-label\">Suru verb</div>"))
    }

    @Test fun `senses with no glosses are skipped`() {
        val html = MeaningHtmlBuilder.build(
            listOf(
                Sense(listOf("n"), emptyList()),
                Sense(listOf("n"), listOf(Gloss("real"))),
            ),
        )
        assertTrue(html.contains("<li>real</li>"))
        // Empty sense's LI never appears.
        assertEquals(1, "<li>".toRegex().findAll(html).count())
    }

    @Test fun `html in gloss is escaped`() {
        val html = MeaningHtmlBuilder.build(
            listOf(Sense(listOf("n"), listOf(Gloss("<b>x</b>&y")))),
        )
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;&amp;y"))
    }

    @Test fun `misc code rendered in italic muted color`() {
        val html = MeaningHtmlBuilder.build(
            listOf(Sense(listOf("n"), listOf(Gloss("water")), misc = "uk")),
        )
        // "uk" → "Usually written in kana alone" via JmdictMiscLabels.
        assertTrue(html.contains("Usually written in kana alone"))
        assertTrue(html.contains("<small style=\"color:#78909C;\"><i>"))
    }

    @Test fun `unknown POS code falls through unchanged`() {
        val html = MeaningHtmlBuilder.build(
            listOf(Sense(listOf("zzz-made-up"), listOf(Gloss("x")))),
        )
        assertTrue(html.contains("<div class=\"pos-label\">zzz-made-up</div>"))
    }
}
