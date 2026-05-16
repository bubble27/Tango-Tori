package com.tangotori.app.data.sudachi

import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.KanjiKanaSplit

/**
 * Sudachi splits conjugated forms into a content-word stem plus one or more
 * auxiliary-verb / particle morphemes. For display purposes we want the
 * conjugated form to read as a single word:
 *
 *   行っ + た       → 行った
 *   食べ + させ + られ + た → 食べさせられた
 *   美しかっ + た  → 美しかった
 *   行か + なかっ + た → 行かなかった
 *   見 + て + み + て → 見てみて (te-form chain — also fused)
 *
 * Merging policy: a "head" token (VERB / I_ADJECTIVE / NA_ADJECTIVE / AUXILIARY_VERB)
 * absorbs the immediately-following AUXILIARY_VERB or 接続助詞 (te-form particle).
 * AUXILIARY_VERB can be a head too, so chains like `さ` + `せ` + `られ` + `た`
 * collapse into one.
 *
 * Pure 格助詞/係助詞 particles (は, が, に, を, etc.) are *not* swallowed — they're
 * legitimately separate tokens the user might want to inspect.
 *
 * The merged token keeps the *head*'s dictionary form and POS; surface and surface
 * reading are concatenated; dictionaryReading is re-derived. Punctuation passes
 * through unchanged.
 */
object TokenMerger {

    fun merge(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val out = ArrayList<Token>(tokens.size)
        for (token in tokens) {
            val last = out.lastOrNull()
            if (last != null && shouldAbsorb(last, token)) {
                out[out.lastIndex] = combine(last, token)
            } else {
                out.add(token)
            }
        }
        return out
    }

    private fun shouldAbsorb(head: Token, next: Token): Boolean {
        if (!isHeadCandidate(head)) return false
        if (next.partOfSpeech == PartOfSpeech.AUXILIARY_VERB) return true
        // Te-form / connection particle: rawPosTag carries the Sudachi subtype.
        if (next.partOfSpeech == PartOfSpeech.PARTICLE && next.rawPosTag.contains("接続助詞")) return true
        return false
    }

    private fun isHeadCandidate(t: Token): Boolean = when (t.partOfSpeech) {
        PartOfSpeech.VERB,
        PartOfSpeech.I_ADJECTIVE,
        PartOfSpeech.NA_ADJECTIVE,
        PartOfSpeech.AUXILIARY_VERB -> true
        else -> false
    }

    private fun combine(head: Token, tail: Token): Token {
        val mergedSurface = head.surface + tail.surface
        val mergedReading = head.reading + tail.reading
        val dictForm = head.dictionaryForm
        return head.copy(
            surface = mergedSurface,
            reading = mergedReading,
            dictionaryReading = KanjiKanaSplit.deriveDictFormReading(
                surface = mergedSurface,
                surfaceReading = mergedReading,
                dictForm = dictForm,
            ),
        )
    }
}
