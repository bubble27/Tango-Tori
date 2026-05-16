package com.tangotori.app.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface JmdictDao {
    @Query("SELECT * FROM entries WHERE id IN (SELECT entryId FROM kanji WHERE text = :form) " +
            "OR id IN (SELECT entryId FROM readings WHERE text = :form) " +
            "ORDER BY isCommon DESC, CASE jlptLevel " +
            "WHEN 'N5' THEN 0 WHEN 'N4' THEN 1 WHEN 'N3' THEN 2 " +
            "WHEN 'N2' THEN 3 WHEN 'N1' THEN 4 ELSE 5 END ASC")
    suspend fun findByForm(form: String): List<EntryRow>

    @Query("SELECT * FROM entries WHERE id IN (SELECT entryId FROM readings WHERE text = :reading) " +
            "ORDER BY isCommon DESC, CASE jlptLevel " +
            "WHEN 'N5' THEN 0 WHEN 'N4' THEN 1 WHEN 'N3' THEN 2 " +
            "WHEN 'N2' THEN 3 WHEN 'N1' THEN 4 ELSE 5 END ASC")
    suspend fun findByReading(reading: String): List<EntryRow>

    @Query("SELECT * FROM kanji WHERE entryId = :entryId")
    suspend fun getKanji(entryId: Long): List<KanjiRow>

    @Query("SELECT * FROM readings WHERE entryId = :entryId")
    suspend fun getReadings(entryId: Long): List<ReadingRow>

    @Query("SELECT * FROM senses WHERE entryId = :entryId ORDER BY orderIndex ASC")
    suspend fun getSenses(entryId: Long): List<SenseRow>

    @Query("SELECT * FROM glosses WHERE senseId = :senseId")
    suspend fun getGlosses(senseId: Long): List<GlossRow>

    @Query("SELECT * FROM kanji_dic WHERE character = :c")
    suspend fun getKanjiDic(c: String): KanjiDicRow?
}
