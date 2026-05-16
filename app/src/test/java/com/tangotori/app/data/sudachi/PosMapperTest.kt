package com.tangotori.app.data.sudachi

import com.tangotori.app.domain.models.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

class PosMapperTest {
    @Test fun nouns() {
        assertEquals(PartOfSpeech.NOUN, PosMapper.map(listOf("名詞", "普通名詞", "一般")))
        assertEquals(PartOfSpeech.NOUN, PosMapper.map(listOf("名詞", "固有名詞", "人名")))
        assertEquals(PartOfSpeech.NOUN, PosMapper.map(listOf("名詞", "普通名詞", "サ変可能")))
    }

    @Test fun verbs() {
        assertEquals(PartOfSpeech.VERB, PosMapper.map(listOf("動詞", "一般")))
        assertEquals(PartOfSpeech.VERB, PosMapper.map(listOf("動詞", "非自立可能")))
    }

    @Test fun adjectives() {
        assertEquals(PartOfSpeech.I_ADJECTIVE, PosMapper.map(listOf("形容詞", "一般")))
        // Sudachi 0.7 uses 形状詞 in place of the spec's 形容動詞語幹 — both map to NA.
        assertEquals(PartOfSpeech.NA_ADJECTIVE, PosMapper.map(listOf("形状詞", "一般")))
    }

    @Test fun particles() {
        assertEquals(PartOfSpeech.PARTICLE, PosMapper.map(listOf("助詞", "格助詞")))
        assertEquals(PartOfSpeech.PARTICLE, PosMapper.map(listOf("助詞", "係助詞")))
        assertEquals(PartOfSpeech.PARTICLE, PosMapper.map(listOf("助詞", "副助詞")))
    }

    @Test fun others() {
        assertEquals(PartOfSpeech.ADVERB, PosMapper.map(listOf("副詞")))
        assertEquals(PartOfSpeech.AUXILIARY_VERB, PosMapper.map(listOf("助動詞")))
        assertEquals(PartOfSpeech.CONJUNCTION_OTHER, PosMapper.map(listOf("接続詞")))
        assertEquals(PartOfSpeech.CONJUNCTION_OTHER, PosMapper.map(listOf("感動詞")))
        assertEquals(PartOfSpeech.PUNCTUATION, PosMapper.map(listOf("補助記号", "句点")))
    }
}
