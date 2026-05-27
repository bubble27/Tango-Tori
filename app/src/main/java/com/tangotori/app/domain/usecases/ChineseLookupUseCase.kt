package com.tangotori.app.domain.usecases

import com.tangotori.app.data.cedict.CedictRepository
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

sealed class LookupResult {
    data class Match(val token: String, val entries: List<DictEntry>) : LookupResult()
    data class NoMatch(val token: String) : LookupResult()
}

sealed class ChineseLookupOutcome {
    data class Direct(val entries: List<DictEntry>) : ChineseLookupOutcome()
    data class FallbackSplit(val subUnits: List<LookupResult>) : ChineseLookupOutcome()
}

class ChineseLookupUseCase @Inject constructor(
    private val repo: CedictRepository,
) {
    // Raw CC-CEDICT lookup cache — avoids redundant DB queries during DP.
    private val lookupCache = HashMap<String, List<DictEntry>>()
    // Final segmentation cache keyed by compound string.
    private val segCache = HashMap<String, List<LookupResult>>()

    /** Simple lookup used by card-submission flow. */
    suspend operator fun invoke(token: Token): List<DictEntry> {
        val exact = cachedLookup(token.dictionaryForm)
        if (exact.isNotEmpty() || token.dictionaryForm.length <= 1) return exact
        return repo.lookupByChars(token.dictionaryForm)
    }

    /**
     * Full lookup for display. Returns [ChineseLookupOutcome.Direct] when the
     * token has a CC-CEDICT entry, or [ChineseLookupOutcome.FallbackSplit] with
     * the minimum-token DP segmentation when it doesn't.
     */
    suspend fun lookupWithFallback(token: Token): ChineseLookupOutcome {
        val results = segment(token.dictionaryForm)
        if (results.size == 1 && results[0] is LookupResult.Match) {
            val allEntries = cachedLookup(token.dictionaryForm)
            val fallback = (results[0] as LookupResult.Match).entries
            return ChineseLookupOutcome.Direct(allEntries.ifEmpty { fallback })
        }
        return ChineseLookupOutcome.FallbackSplit(results)
    }

    /**
     * Minimum-token DP segmentation.
     *
     * Finds the split of [word] into the fewest sub-units where each sub-unit
     * is either in CC-CEDICT or a single character. This correctly handles
     * ambiguous strings like "今天天气" → ["今天", "天气"] rather than the
     * greedy first-coverage result ["今天", "天", "气"].
     */
    private suspend fun segment(word: String): List<LookupResult> {
        segCache[word]?.let { return it }

        val exact = cachedLookup(word)
        if (exact.isNotEmpty()) {
            return listOf(LookupResult.Match(word, exact)).also { segCache[word] = it }
        }
        if (word.length == 1) {
            return listOf(LookupResult.NoMatch(word)).also { segCache[word] = it }
        }

        val n = word.length
        // dp[i] = minimum tokens to segment word[0..i].
        val dp = IntArray(n + 1) { Int.MAX_VALUE }
        // prev[i] = start index of the last segment in the optimal split ending at i.
        val prev = IntArray(n + 1) { -1 }
        dp[0] = 0

        for (i in 1..n) {
            // Max word length in CC-CEDICT is rarely above 6 chars.
            for (j in maxOf(0, i - 8) until i) {
                if (dp[j] == Int.MAX_VALUE) continue
                val sub = word.substring(j, i)
                // A substring is a valid segment if it's in CC-CEDICT or a lone char.
                val valid = (i - j) == 1 || cachedLookup(sub).isNotEmpty()
                if (valid && dp[j] + 1 < dp[i]) {
                    dp[i] = dp[j] + 1
                    prev[i] = j
                }
            }
        }

        // Backtrack through prev[] to recover the optimal segmentation.
        val segs = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            segs.add(0, word.substring(prev[pos], pos))
            pos = prev[pos]
        }

        val result = segs.map { seg ->
            val found = cachedLookup(seg)
            if (found.isNotEmpty()) LookupResult.Match(seg, found)
            else LookupResult.NoMatch(seg)
        }

        return result.also { segCache[word] = it }
    }

    private suspend fun cachedLookup(word: String): List<DictEntry> {
        lookupCache[word]?.let { return it }
        return repo.lookup(word).also { lookupCache[word] = it }
    }
}
