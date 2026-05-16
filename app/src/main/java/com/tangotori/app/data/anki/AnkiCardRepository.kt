package com.tangotori.app.data.anki

import android.content.Context
import android.content.pm.PackageManager
import com.ichi2.anki.api.AddContentApi
import com.tangotori.app.domain.models.CardData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AnkiDroid's [AddContentApi].
 *
 * Note type: "Tango Tori v2" (bumped from v1 after the Stage 3 feedback round
 * required adding a `WordRuby` field + new template/CSS — AnkiDroid won't
 * mutate an existing note type, so we let it create the v2 model alongside
 * any old v1 notes).
 */
@Singleton
class AnkiCardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val api by lazy { AddContentApi(context) }

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.ichi2.anki", 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    suspend fun getDecks(): Map<Long, String> = withContext(Dispatchers.IO) {
        api.deckList ?: emptyMap()
    }

    sealed interface AddResult {
        data class Success(val deckName: String) : AddResult
        data object Duplicate : AddResult
        data class Failed(val reason: String) : AddResult
    }

    suspend fun addCard(card: CardData, deckId: Long): AddResult = withContext(Dispatchers.IO) {
        runCatching {
            val modelId = findOrCreateNoteType()
            val deckName = api.getDeckName(deckId) ?: "Default"
            val fields = card.toFieldArray()
            val noteId = api.addNote(modelId, deckId, fields, tagsFor(card))
            if (noteId == null) AddResult.Duplicate else AddResult.Success(deckName)
        }.getOrElse { AddResult.Failed(it.message ?: "Unknown error") }
    }

    /**
     * Tags surfaced on every note for filtering inside AnkiDroid:
     *   - `tango-tori` always (identifies cards this app created)
     *   - `common` when JMdict marks the entry as a common word
     *   - the JLPT level lowercased (`n5` .. `n1`) when known
     *   - the raw JMdict part-of-speech codes from the entry's first sense
     *     (`n`, `v5r`, `vi`, `adj-i`, …). Codes stay raw because they're short,
     *     unambiguous, and already AnkiDroid-tag-safe. Spaces are stripped
     *     defensively.
     */
    private fun tagsFor(card: CardData): Set<String> {
        val tags = LinkedHashSet<String>()
        tags += "tango-tori"
        if (card.isCommon) tags += "common"
        if (card.jlpt.isNotBlank()) tags += card.jlpt.lowercase()
        // CardData.partOfSpeech is the first sense's POS list joined with ", ".
        card.partOfSpeech.split(',', ';').forEach { raw ->
            val code = raw.trim().replace(' ', '-').lowercase()
            if (code.isNotEmpty()) tags += code
        }
        return tags
    }

    private fun findOrCreateNoteType(): Long {
        val existing = api.modelList?.entries?.firstOrNull { it.value == CardData.NOTE_TYPE_NAME }
        if (existing != null) return existing.key
        return api.addNewCustomModel(
            CardData.NOTE_TYPE_NAME,
            CardData.FIELD_NAMES,
            arrayOf("Card 1"),
            arrayOf(FRONT_TEMPLATE),
            arrayOf(BACK_TEMPLATE),
            CARD_CSS,
            null,
            null,
        )
    }

    private companion object {
        // Layout (Stage 3 feedback target state):
        //   FRONT — centered headword with per-kanji ruby furigana, plus the
        //   context sentence below (so the user is tested with the word in
        //   context, not isolation).
        //   BACK  — full POS label + ordered list of senses + sentence with
        //   the target word colored in primary red. The JLPT badge from v1 is
        //   intentionally removed: JLPT is metadata, not testable content.
        //
        // Front: plain headword (no ruby). The reading is part of what the
        // user is being tested on, so it stays hidden until the card flips.
        // Back uses {{WordRuby}} which adds the per-kanji <ruby>/<rt>.
        private const val FRONT_TEMPLATE = """
            <div class="card-body">
              <div class="word-block">{{Word}}</div>
            </div>
        """

        // Back layout: word, then kanji breakdown (so the reading of each
        // kanji sits close to the headword), then meanings, then the context
        // sentence at the bottom. Three explicit <hr>s separate them.
        private const val BACK_TEMPLATE = """
            <div class="card-body">
              <div class="word-block">{{WordRuby}}</div>
              <hr>
              {{#KanjiBreakdown}}<div class="kanji-section">{{KanjiBreakdown}}</div>
              <hr>{{/KanjiBreakdown}}
              <div class="meaning">{{Meaning}}</div>
              <hr>
              {{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
            </div>
        """

        // CSS notes (Stage 3 feedback round 2):
        //   - No card background-color: AnkiDroid's default (white in light
        //     mode, dark grey in night mode) is what the user wants.
        //   - Furigana raised a bit higher and rendered in a slightly darker
        //     muted color — ample line-height on the word-block gives the rt
        //     room above the kanji.
        //   - Sentence + senses sit at bigger sizes.
        //   - The `<ol>` markers (sense numbers) are explicitly greyed.
        //   - Dark-mode overrides handle the muted/red colors but never set
        //     a background — let Anki pick.
        private const val CARD_CSS = """
            .card {
              font-family: -apple-system, "Hiragino Sans", "Yu Gothic UI", "Noto Sans CJK JP", sans-serif;
              padding: 16px;
            }
            .card-body { text-align: center; }
            .word-block {
              text-align: center;
              font-size: 56px;
              margin: 28px 0 10px 0;
              line-height: 1.9;
            }
            .word-block ruby rt {
              font-size: 0.36em;
              color: #5A6B75;
              line-height: 1.0;
              padding-bottom: 4px;
            }
            .sentence {
              margin: 18px auto 10px auto;
              font-size: 22px;
              line-height: 2.1;
              max-width: 92%;
              text-align: center;
            }
            .sentence a {
              color: inherit;
              text-decoration: none;
            }
            .sentence rt { color: #5A6B75; font-size: 0.55em; }
            .sentence .target-word { color: #C0392B; font-weight: normal; }
            .sentence .target-word a { color: #C0392B; }
            hr {
              border: none;
              border-top: 1px solid #DDD;
              margin: 18px 0;
            }
            .meaning { text-align: left; }
            .pos-label {
              font-size: 14px;
              color: #78909C;
              font-style: italic;
              margin: 10px 0 6px 0;
            }
            .senses {
              padding-left: 24px;
              margin: 0 0 10px 0;
            }
            .senses li {
              font-size: 18px;
              line-height: 1.55;
              margin-bottom: 6px;
            }
            .senses li::marker {
              color: #999;
            }
            .kanji-section {
              display: flex;
              flex-direction: row;
              justify-content: center;
              flex-wrap: wrap;
              gap: 16px;
              margin: 12px 0;
            }
            .kanji-tile {
              display: flex;
              flex-direction: column;
              align-items: center;
              min-width: 80px;
            }
            .kanji-reading {
              font-size: 15px;
              color: #5A6B75;
              line-height: 1.0;
              margin-bottom: 2px;
              min-height: 18px;
            }
            .kanji-char {
              font-size: 40px;
              line-height: 1.0;
            }
            .kanji-meaning {
              font-size: 13px;
              color: #999;
              text-align: center;
              margin-top: 4px;
            }

            /* AnkiDroid dark mode — let Anki pick the background; we only
               soften the accent / hr / muted-text colors so they read on a
               dark card. */
            .night_mode hr { border-top-color: #444; }
            .night_mode .pos-label,
            .night_mode .word-block ruby rt,
            .night_mode .sentence rt,
            .night_mode .kanji-reading { color: #90A4AE; }
            .night_mode .senses li::marker { color: #BBB; }
            .night_mode .sentence .target-word,
            .night_mode .sentence .target-word a { color: #E07B6A; }
        """
    }
}
