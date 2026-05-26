package com.tangotori.app.data.cedict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Gloss
import com.tangotori.app.domain.models.KanjiForm
import com.tangotori.app.domain.models.Reading
import com.tangotori.app.domain.models.Sense
import com.tangotori.app.domain.util.PinyinToneConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CedictRepository(private val db: SQLiteDatabase?) {

    suspend fun lookup(word: String): List<DictEntry> {
        if (word.isBlank() || db == null) return emptyList()
        return withContext(Dispatchers.IO) {
            db.rawQuery(
                """
                SELECT * FROM cedict_entry
                WHERE simplified = ? OR traditional = ?
                ORDER BY
                    CASE hsk_level
                        WHEN 'HSK 1' THEN 0 WHEN 'HSK 2' THEN 1 WHEN 'HSK 3' THEN 2
                        WHEN 'HSK 4' THEN 3 WHEN 'HSK 5' THEN 4 WHEN 'HSK 6' THEN 5
                        ELSE 6
                    END ASC,
                    is_common DESC
                """,
                arrayOf(word, word),
            ).use { cur -> cur.toList { hydrateFromCursor(it) } }
        }
    }

    suspend fun lookupChar(char: String): CedictCharInfo? {
        if (db == null) return null
        return withContext(Dispatchers.IO) {
            db.rawQuery(
                """
                SELECT * FROM cedict_entry
                WHERE simplified = ? AND length(simplified) = 1
                ORDER BY
                    CASE hsk_level
                        WHEN 'HSK 1' THEN 0 WHEN 'HSK 2' THEN 1 WHEN 'HSK 3' THEN 2
                        WHEN 'HSK 4' THEN 3 WHEN 'HSK 5' THEN 4 WHEN 'HSK 6' THEN 5
                        ELSE 6
                    END ASC,
                    is_common DESC
                LIMIT 1
                """,
                arrayOf(char),
            ).use { cur ->
                if (!cur.moveToFirst()) return@withContext null
                val id = cur.getLong(cur.getColumnIndexOrThrow("id"))
                val rawGlosses = fetchRawGlosses(id)
                CedictCharInfo(
                    char = cur.getString(cur.getColumnIndexOrThrow("simplified")),
                    pinyinMarks = cur.getString(cur.getColumnIndexOrThrow("pinyin_marks")),
                    meanings = rawGlosses
                        .map { extractBriefMeaning(it) }
                        .filter { it.isNotBlank() }
                        .take(2),
                    allGlosses = rawGlosses.map { formatGloss(it) },
                )
            }
        }
    }

    // Strips leading CC-CEDICT parenthetical usage notes before truncating so
    // tile labels show the actual meaning rather than "(after a suppositional…".
    // e.g. "(after a suppositional phrase) in that case/then" → "in that case"
    private fun extractBriefMeaning(gloss: String): String {
        var s = gloss.trim()
        while (s.startsWith("(")) {
            val close = s.indexOf(')')
            if (close < 0) break
            s = s.substring(close + 1).trimStart()
        }
        s = s.split('/').first().trim()
        return if (s.length > 20) s.take(20) + "…" else s
    }

    private fun hydrateFromCursor(cur: Cursor): DictEntry {
        val id          = cur.getLong(cur.getColumnIndexOrThrow("id"))
        val simplified  = cur.getString(cur.getColumnIndexOrThrow("simplified"))
        val traditional = cur.getString(cur.getColumnIndexOrThrow("traditional"))
        val pinyinMarks = cur.getString(cur.getColumnIndexOrThrow("pinyin_marks"))
        val hskLevel    = cur.getString(cur.getColumnIndexOrThrow("hsk_level"))
        val isCommon    = cur.getInt(cur.getColumnIndexOrThrow("is_common")) != 0

        // One Sense per raw gloss so EntryDetail numbers them individually
        // rather than joining everything into one giant paragraph.
        val senses = fetchRawGlosses(id).map { rawGloss ->
            Sense(
                partOfSpeech = emptyList(),
                glosses = listOf(Gloss(formatGloss(rawGloss))),
            )
        }

        val kanjiForms = if (simplified == traditional) {
            listOf(KanjiForm(simplified))
        } else {
            listOf(KanjiForm(simplified), KanjiForm(traditional, info = "trad."))
        }

        return DictEntry(
            id = id,
            isCommon = isCommon,
            jlptLevel = hskLevel,
            kanjiForms = kanjiForms,
            readings = listOf(Reading(text = pinyinMarks)),
            senses = senses,
        )
    }

    private fun fetchRawGlosses(entryId: Long): List<String> {
        db ?: return emptyList()
        return db.rawQuery(
            "SELECT gloss FROM cedict_sense WHERE entry_id = ?",
            arrayOf(entryId.toString()),
        ).use { cur ->
            cur.toList { it.getString(0) }.filter { !it.startsWith("CL:") }
        }
    }

    // Converts number-tone pinyin inside square brackets to tone-marked form and
    // rewraps with parentheses so cross-references read as natural prose.
    // e.g. "variant of 皮草[pi2 cao3]" → "variant of 皮草 (pí cǎo)"
    // Only replaces bracketed content that contains at least one digit so that
    // regular English abbreviations like "[fig.]" are left untouched.
    private val PINYIN_BRACKET_RE = Regex("""\[([a-z][a-z0-9: ]*[0-9][^\]]*)\]""", RegexOption.IGNORE_CASE)

    private fun formatGloss(raw: String): String =
        PINYIN_BRACKET_RE.replace(raw) { m ->
            val marked = m.groupValues[1].trim().split(" ").joinToString(" ") { syl ->
                PinyinToneConverter.convertSyllable(syl)
            }
            " ($marked)"
        }

    // When a compound word has no entry, look up each character individually
    // and return the top result per character. Handles Classical Chinese and
    // any compound that jieba produces but CC-CEDICT doesn't list as a word.
    suspend fun lookupByChars(word: String): List<DictEntry> {
        if (db == null || word.length <= 1) return emptyList()
        return withContext(Dispatchers.IO) {
            word.mapNotNull { char ->
                db.rawQuery(
                    """
                    SELECT * FROM cedict_entry
                    WHERE simplified = ?
                    ORDER BY
                        CASE hsk_level
                            WHEN 'HSK 1' THEN 0 WHEN 'HSK 2' THEN 1 WHEN 'HSK 3' THEN 2
                            WHEN 'HSK 4' THEN 3 WHEN 'HSK 5' THEN 4 WHEN 'HSK 6' THEN 5
                            ELSE 6
                        END ASC,
                        is_common DESC
                    LIMIT 1
                    """,
                    arrayOf(char.toString()),
                ).use { cur ->
                    if (cur.moveToFirst()) hydrateFromCursor(cur) else null
                }
            }
        }
    }

    private fun <T> Cursor.toList(transform: (Cursor) -> T): List<T> {
        val result = mutableListOf<T>()
        while (moveToNext()) result += transform(this)
        return result
    }
}

data class CedictCharInfo(
    val char: String,
    val pinyinMarks: String,
    val meanings: List<String>,
    val allGlosses: List<String> = emptyList(),
)
