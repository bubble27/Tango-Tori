package com.tangotori.app.domain.usecases

import com.tangotori.app.data.anki.AnkiCardRepository
import com.tangotori.app.data.cedict.CedictRepository
import com.tangotori.app.domain.models.CardData
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Language
import com.tangotori.app.domain.models.Token
import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.util.FuriganaBuilder
import com.tangotori.app.domain.util.FuriganaHtmlBuilder
import com.tangotori.app.domain.util.HanziBreakdownBuilder
import com.tangotori.app.domain.util.HanziBreakdownHtmlBuilder
import com.tangotori.app.domain.util.KanjiBreakdownHtmlBuilder
import com.tangotori.app.domain.util.MeaningHtmlBuilder
import com.tangotori.app.domain.util.PinyinBuilder
import com.tangotori.app.domain.util.PinyinHtmlBuilder
import com.tangotori.app.domain.util.SentenceHtmlBuilder
import java.net.URLEncoder
import javax.inject.Inject

class CreateCardUseCase @Inject constructor(
    private val anki: AnkiCardRepository,
    private val dao: JmdictDao,
    private val cedictRepo: CedictRepository,
) {
    suspend fun buildCard(
        entry: DictEntry,
        sentence: String,
        sentenceTokens: List<SentenceHtmlBuilder.TokenWithEntry>,
        useKanjiStudyLinks: Boolean = false,
        source: String = "",
        language: Language = Language.JAPANESE,
    ): CardData = when (language) {
        Language.JAPANESE -> buildJapaneseCard(entry, sentence, sentenceTokens, useKanjiStudyLinks, source)
        Language.CHINESE_SIMPLIFIED,
        Language.CHINESE_TRADITIONAL -> buildChineseCard(entry, sentence, sentenceTokens, useKanjiStudyLinks, source)
    }

    // ── Japanese ────────────────────────────────────────────────────────────

    private suspend fun buildJapaneseCard(
        entry: DictEntry,
        sentence: String,
        sentenceTokens: List<SentenceHtmlBuilder.TokenWithEntry>,
        useKanjiStudyLinks: Boolean,
        source: String,
    ): CardData {
        val word = entry.headword
        val reading = entry.primaryReading
        val furigana = FuriganaBuilder.build(word, reading)
        val kanjiChars = word.filter { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }
            .map { it.toString() }.distinct()
        val rows = kanjiChars.associateWith { dao.getKanjiDic(it) }
        val meaningsMap = rows.mapValues { (_, row) ->
            row?.meanings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        val readingsMap = rows.mapValues { (_, row) ->
            row?.readings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
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
            sentenceHtml = SentenceHtmlBuilder.build(sentenceTokens, entry.id, useKanjiStudyLinks, isChinese = false),
            sentenceRaw = sentence,
            source = source,
            kanjiBreakdownHtml = breakdown,
            openUrl = openUrlFor(sentence),
        )
    }

    // ── Chinese ─────────────────────────────────────────────────────────────

    private suspend fun buildChineseCard(
        entry: DictEntry,
        sentence: String,
        sentenceTokens: List<SentenceHtmlBuilder.TokenWithEntry>,
        useKanjiStudyLinks: Boolean,
        source: String,
    ): CardData {
        val word = entry.headword           // simplified form
        val pinyinMarks = entry.primaryReading  // e.g. "zhōng guó"

        val pinyinMarkup = PinyinBuilder.build(word, pinyinMarks)
        val wordRuby = PinyinHtmlBuilder.build(pinyinMarkup)

        val hanziTiles = HanziBreakdownBuilder.build(
            surface = word,
            wordPinyinMarks = pinyinMarks,
            charLookup = { char ->
                cedictRepo.lookupChar(char)?.meanings ?: emptyList()
            },
        )
        val breakdownHtml = HanziBreakdownHtmlBuilder.build(hanziTiles)

        return CardData(
            word = word,
            reading = pinyinMarks,
            furigana = pinyinMarkup,     // reuse the furigana field for pinyin bracket notation
            wordRuby = wordRuby,
            meaningHtml = MeaningHtmlBuilder.build(entry.senses),
            partOfSpeech = entry.senses.firstOrNull()?.partOfSpeech?.joinToString(", ").orEmpty(),
            jlpt = entry.jlptLevel.orEmpty(),  // contains "HSK 3" etc. for Chinese entries
            isCommon = entry.isCommon,
            sentenceHtml = SentenceHtmlBuilder.build(sentenceTokens, entry.id, useKanjiStudyLinks, isChinese = true),
            sentenceRaw = sentence,
            source = source,
            kanjiBreakdownHtml = breakdownHtml,
            openUrl = openUrlFor(sentence),
        )
    }

    suspend fun submit(card: CardData, deckId: Long) = anki.addCard(card, deckId)

    /** Builds the `tangotori://sentence?text=…` deep link the Anki card's
     *  "Open in Tango Tori" button uses. Pre-encoded here because Anki
     *  templates have no URL-encode filter. `%20` (not `+`) for spaces so
     *  Uri.getQueryParameter decodes it back correctly on the receiving side. */
    private fun openUrlFor(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return ""
        val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        return "tangotori://sentence?text=$encoded"
    }
}
