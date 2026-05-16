package com.tangotori.app.data.sudachi

import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenMergerTest {

    private fun verb(surface: String, dictForm: String, reading: String, dictReading: String = reading) =
        Token(surface, dictForm, reading, dictReading, PartOfSpeech.VERB, "動詞-一般")

    private fun aux(surface: String, reading: String) =
        Token(surface, surface, reading, reading, PartOfSpeech.AUXILIARY_VERB, "助動詞")

    private fun particle(surface: String, reading: String = surface, sub: String = "格助詞") =
        Token(surface, surface, reading, reading, PartOfSpeech.PARTICLE, "助詞-$sub")

    @Test fun `verb plus ta merges into past-tense surface and dict-form reading`() {
        val result = TokenMerger.merge(
            listOf(
                verb("入っ", dictForm = "入る", reading = "はいっ"),
                aux("た", "た"),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("入った", result[0].surface)
        assertEquals("はいった", result[0].reading)
        assertEquals("入る", result[0].dictionaryForm)
        assertEquals("はいる", result[0].dictionaryReading)
    }

    @Test fun `verb plus chain of auxiliaries collapses fully`() {
        // 食べ + させ + られ + た  →  食べさせられた  (causative-passive past)
        val result = TokenMerger.merge(
            listOf(
                verb("食べ", "食べる", "たべ"),
                aux("させ", "させ"),
                aux("られ", "られ"),
                aux("た", "た"),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("食べさせられた", result[0].surface)
        assertEquals("たべさせられた", result[0].reading)
    }

    @Test fun `case particle remains separate`() {
        val result = TokenMerger.merge(
            listOf(
                verb("学校", "学校", "がっこう").copy(partOfSpeech = PartOfSpeech.NOUN, rawPosTag = "名詞-普通名詞-一般"),
                particle("に"),
            ),
        )
        assertEquals(2, result.size)
        assertEquals("学校", result[0].surface)
        assertEquals("に", result[1].surface)
    }

    @Test fun `te-form connecting particle merges into verb`() {
        // 食べ + て  →  食べて
        val result = TokenMerger.merge(
            listOf(
                verb("食べ", "食べる", "たべ"),
                particle("て", "て", sub = "接続助詞"),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("食べて", result[0].surface)
        assertEquals("たべて", result[0].reading)
    }
}
