package com.tangotori.app.domain.usecases

import com.tangotori.app.data.anki.AnkiCardRepository
import com.tangotori.app.domain.models.CardData
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Token
import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.util.FuriganaBuilder
import com.tangotori.app.domain.util.FuriganaHtmlBuilder
import com.tangotori.app.domain.util.KanjiBreakdownHtmlBuilder
import com.tangotori.app.domain.util.MeaningHtmlBuilder
import com.tangotori.app.domain.util.SentenceHtmlBuilder
import javax.inject.Inject

class CreateCardUseCase @Inject constructor(
    private val anki: AnkiCardRepository,
    private val dao: JmdictDao,
) {
    suspend fun buildCard(
        entry: DictEntry,
        sentence: String,
        sentenceTokens: List<SentenceHtmlBuilder.TokenWithEntry>,
        source: String = "",
    ): CardData {
        val word = entry.headword
        val reading = entry.primaryReading
        val furigana = FuriganaBuilder.build(word, reading)
        // Pre-fetch meanings AND per-kanji readings for every ideograph in the
        // word. The breakdown builder uses the readings to split a compound
        // word reading (なかま) across individual kanji tiles (仲=なか, 間=ま).
        val kanjiChars = word.filter { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }
            .map { it.toString() }
            .distinct()
        val rows = kanjiChars.associateWith { dao.getKanjiDic(it) }
        val meaningsMap = rows.mapValues { (_, row) ->
            row?.meanings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
        val readingsMap = rows.mapValues { (_, row) ->
            row?.readings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
        }
        val breakdown = KanjiBreakdownHtmlBuilder.build(
            furiganaMarkup = furigana,
            kanjiMeanings = { c -> meaningsMap[c] ?: emptyList() },
            kanjiReadings = { c -> readingsMap[c] ?: emptySet() },
        )
        return CardData(
            word = word,
            reading = reading,
            furigana = furigana,
            wordRuby = FuriganaHtmlBuilder.build(furigana),
            meaningHtml = MeaningHtmlBuilder.build(entry.senses),
            partOfSpeech = entry.senses.firstOrNull()?.partOfSpeech?.joinToString(", ").orEmpty(),
            jlpt = entry.jlptLevel.orEmpty(),
            isCommon = entry.isCommon,
            sentenceHtml = SentenceHtmlBuilder.build(sentenceTokens, entry.id),
            sentenceRaw = sentence,
            source = source,
            kanjiBreakdownHtml = breakdown,
        )
    }

    suspend fun submit(card: CardData, deckId: Long) = anki.addCard(card, deckId)
}
