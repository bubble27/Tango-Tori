package com.tangotori.app.data.jmdict

import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Gloss
import com.tangotori.app.domain.models.KanjiForm
import com.tangotori.app.domain.models.Reading
import com.tangotori.app.domain.models.Sense
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JmdictRepository @Inject constructor(private val dao: JmdictDao) {

    /**
     * Spec lookup strategy:
     * 1. exact match on kanji OR reading = dictionaryForm
     * 2. if no match, fall back to reading-only
     * 3. rank: isCommon desc, jlptLevel ascending (N5 < N4 < N3 < N2 < N1)
     */
    suspend fun lookup(dictionaryForm: String, reading: String?): List<DictEntry> {
        if (dictionaryForm.isBlank()) return emptyList()
        val primary = dao.findByForm(dictionaryForm)
        val rows = primary.ifEmpty {
            if (!reading.isNullOrBlank()) dao.findByReading(reading) else emptyList()
        }
        return rows.map { hydrate(it) }
    }

    private suspend fun hydrate(entry: com.tangotori.app.data.db.EntryRow): DictEntry {
        val kanji = dao.getKanji(entry.id).map { KanjiForm(it.text, it.info, it.priority) }
        val readings = dao.getReadings(entry.id).map {
            Reading(it.text, it.info, it.priority, it.noKanji)
        }
        val senses = dao.getSenses(entry.id).map { senseRow ->
            val glosses = dao.getGlosses(senseRow.senseId).map { Gloss(it.text, it.language) }
            Sense(
                partOfSpeech = senseRow.partOfSpeech.split(';').filter { it.isNotBlank() },
                glosses = glosses,
                misc = senseRow.misc,
                field = senseRow.field,
                dialect = senseRow.dialect,
            )
        }
        return DictEntry(
            id = entry.id,
            isCommon = entry.isCommon,
            jlptLevel = entry.jlptLevel,
            kanjiForms = kanji,
            readings = readings,
            senses = senses,
        )
    }
}
