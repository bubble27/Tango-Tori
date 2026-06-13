package com.tangotori.app.domain.usecases

import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

/**
 * Merges runs of consecutive tokens that form a single JMdict idiomatic
 * expression (e.g. 腹が立つ, 気の利いた) into one grouped [Token]. Sudachi
 * tokenizes these word-by-word (腹 / が / 立った), which is misleading because
 * the expression means something the parts don't.
 *
 * Detection (deliberately conservative):
 *  - A span of 2+ tokens whose combined form is a JMdict entry tagged `exp`
 *    (expression). Ordinary noun compounds (高校生 etc.) are tagged `n`, not
 *    `exp`, so they're never grouped.
 *  - The span must contain at least one content word (noun/verb/adj/adverb),
 *    which excludes purely grammatical `exp` entries like ので / のに.
 *
 * Two candidate forms are tried per span so both the conjugated headword case
 * (腹が立った → dict form 腹が立つ) and the literal headword case (気の利いた,
 * whose JMdict headword includes the た) are caught. The longest matching span
 * starting at each position wins; matched tokens are consumed.
 */
class IdiomGrouper @Inject constructor(
    private val dao: JmdictDao,
) {
    suspend fun group(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val out = ArrayList<Token>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val match = findIdiomAt(tokens, i)
            if (match != null) {
                out.add(match.token)
                i = match.endExclusive
            } else {
                out.add(tokens[i])
                i++
            }
        }
        return out
    }

    private data class Match(val token: Token, val endExclusive: Int)

    private suspend fun findIdiomAt(tokens: List<Token>, start: Int): Match? {
        val maxEnd = minOf(tokens.size, start + MAX_SPAN_TOKENS)
        var best: Match? = null
        for (end in (start + 2)..maxEnd) {
            val span = tokens.subList(start, end)
            // Don't cross punctuation, and skip purely grammatical spans.
            if (span.any { it.partOfSpeech == PartOfSpeech.PUNCTUATION }) break
            if (span.none { it.isContentWord }) continue

            val surfaceForm = span.joinToString("") { it.surface }
            val dictForm = span.dropLast(1).joinToString("") { it.surface } + span.last().dictionaryForm
            val matchedForm = when {
                dao.existsExpressionForm(surfaceForm) -> surfaceForm
                dictForm != surfaceForm && dao.existsExpressionForm(dictForm) -> dictForm
                else -> null
            } ?: continue

            // Longest matching span wins (loop ascends, so keep overwriting).
            best = Match(buildIdiomToken(span, matchedForm), end)
        }
        return best
    }

    private fun buildIdiomToken(span: List<Token>, matchedForm: String): Token {
        val surface = span.joinToString("") { it.surface }
        val reading = span.joinToString("") { it.reading }
        // POS/colour follow the last content word in the span (the verb/adj an
        // idiom conjugates on), falling back to the last token.
        val head = span.lastOrNull { it.isContentWord } ?: span.last()
        return Token(
            surface = surface,
            dictionaryForm = matchedForm,
            reading = reading,
            dictionaryReading = reading,
            partOfSpeech = head.partOfSpeech,
            rawPosTag = head.rawPosTag,
            components = span.toList(),
            isExpression = true,
        )
    }

    private companion object {
        // JMdict expression headwords are short; cap the scan window.
        const val MAX_SPAN_TOKENS = 6
    }
}
