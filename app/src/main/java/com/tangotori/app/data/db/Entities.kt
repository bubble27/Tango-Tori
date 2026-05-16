package com.tangotori.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "entries")
data class EntryRow(
    @PrimaryKey val id: Long,
    val isCommon: Boolean,
    val jlptLevel: String?,
)

@Entity(
    tableName = "kanji",
    foreignKeys = [ForeignKey(
        entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("entryId"), Index("text")],
)
data class KanjiRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val entryId: Long,
    val text: String,
    val info: String?,
    val priority: String?,
)

@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(
        entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("entryId"), Index("text")],
)
data class ReadingRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val entryId: Long,
    val text: String,
    val info: String?,
    val priority: String?,
    val noKanji: Boolean,
)

@Entity(
    tableName = "senses",
    foreignKeys = [ForeignKey(
        entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("entryId")],
)
data class SenseRow(
    @PrimaryKey(autoGenerate = true) val senseId: Long = 0,
    val entryId: Long,
    val orderIndex: Int,
    val partOfSpeech: String,
    val misc: String?,
    val field: String?,
    val dialect: String?,
)

@Entity(
    tableName = "glosses",
    foreignKeys = [ForeignKey(
        entity = SenseRow::class, parentColumns = ["senseId"], childColumns = ["senseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("senseId")],
)
data class GlossRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val senseId: Long,
    val text: String,
    val language: String,
)

/**
 * KANJIDIC2 per-character entry. Populated by the jmdict-builder tool from
 * the `kanjidic2-en` JSON release (scriptin/jmdict-simplified). `meanings` is
 * a semicolon-separated list of English meanings, ordered as the kanji
 * dictionary itself orders them (most-essential first).
 */
@Entity(tableName = "kanji_dic")
data class KanjiDicRow(
    @PrimaryKey val character: String,
    val meanings: String,
    /** Semicolon-separated kun/on readings, normalized to hiragana and with
     *  okurigana-separator dots stripped. Used by KanjiBreakdownHtmlBuilder to
     *  split a compound word's reading across individual kanji tiles. */
    val readings: String,
)
