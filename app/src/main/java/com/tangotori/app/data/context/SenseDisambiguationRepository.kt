package com.tangotori.app.data.context

import com.tangotori.app.BuildConfig
import com.tangotori.app.data.budget.ApiKeyStore
import com.tangotori.app.data.budget.UsageBudgetManager
import com.tangotori.app.data.compound.DeviceIdProvider
import com.tangotori.app.domain.models.DictEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of asking the backend which dictionary sense fits the sentence context.
 * [entryIndex]/[senseIndex] point into the [DictEntry] list passed to
 * [SenseDisambiguationRepository.disambiguate]; [meaning] is a short contextual
 * gloss generated for the word as used in the sentence.
 */
sealed class DisambiguationResult {
    data class Success(
        val entryIndex: Int,
        val senseIndex: Int,
        val meaning: String,
    ) : DisambiguationResult()
    data object NoInternet : DisambiguationResult()
    data object Failed : DisambiguationResult()
    /** The device spent its daily free token budget (worker replied 429, or the
     *  request was skipped because the limit is already known to be hit).
     *  [showMessage] is true only for the first notice of the day — the UI
     *  shows the limit message once, then later failures stay silent. */
    data class LimitReached(val showMessage: Boolean) : DisambiguationResult()
}

/**
 * Picks the most relevant meaning of a word given the sentence it appears in
 * (word-sense disambiguation), via the Cloudflare Worker's `/disambiguate`
 * endpoint. Mirrors [com.tangotori.app.data.compound.CompoundMeaningRepository].
 */
@Singleton
class SenseDisambiguationRepository @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
    private val budgetManager: UsageBudgetManager,
    private val apiKeyStore: ApiKeyStore,
) {
    /**
     * @param language "ja" or "zh".
     * @param entries the candidate entries (homographs) in the order they're
     *   displayed; the returned indices reference this list.
     */
    suspend fun disambiguate(
        language: String,
        word: String,
        sentence: String,
        entries: List<DictEntry>,
    ): DisambiguationResult = withContext(Dispatchers.IO) {
        // Flatten (entry, sense) → numbered candidates, keeping a map back to the
        // entry/sense indices so we can translate the chosen id.
        data class Cand(val id: Int, val entryIndex: Int, val senseIndex: Int)
        val candidates = JSONArray()
        val map = mutableListOf<Cand>()
        var id = 1
        entries.forEachIndexed { ei, entry ->
            entry.senses.forEachIndexed { si, sense ->
                val gloss = sense.glosses.joinToString("; ") { it.text }
                if (gloss.isBlank()) return@forEachIndexed
                candidates.put(JSONObject().apply {
                    put("id", id)
                    put("reading", entry.primaryReading)
                    put("pos", sense.partOfSpeech.joinToString(","))
                    put("gloss", gloss)
                })
                map.add(Cand(id, ei, si))
                id++
            }
        }
        // Need at least one glossed candidate. A single candidate still gets a
        // context-aware gloss (id is trivially that one); 0 means nothing to do.
        if (map.isEmpty()) return@withContext DisambiguationResult.Failed

        // Bring-your-own-key: billed to the user's key, no budget applies.
        val userKey = apiKeyStore.getKey()

        // Once the daily free limit is known to be hit, don't send disambiguation
        // requests at all until it lifts (UTC day rollover, Dev Mode re-enabled,
        // a boost purchase, or the user adding their own key).
        val devActive = budgetManager.isDevActive()
        if (userKey == null && !devActive && budgetManager.isLimitBlocked()) {
            return@withContext DisambiguationResult.LimitReached(
                showMessage = budgetManager.consumeLimitNotice(),
            )
        }

        val body = JSONObject().apply {
            put("device_id", deviceIdProvider.deviceId)
            put("language", language)
            put("word", word)
            put("sentence", sentence)
            put("candidates", candidates)
            put("dev_mode", devActive)
            if (userKey != null) put("api_key", userKey)
            budgetManager.currentTier().let { if (it > 1) put("budget_tier", it) }
        }.toString()

        try {
            val url = URL("${BuildConfig.COMPOUND_API_URL}/disambiguate")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode == 429) {
                budgetManager.markLimitReached()
                return@withContext DisambiguationResult.LimitReached(
                    showMessage = budgetManager.consumeLimitNotice(),
                )
            }
            if (conn.responseCode != 200) return@withContext DisambiguationResult.Failed

            val response = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(response)
            val chosenId = obj.optInt("id", -1)
            val meaning = obj.optString("meaning", "").trim()
            val cand = map.firstOrNull { it.id == chosenId }
            if (cand == null || meaning.isEmpty()) return@withContext DisambiguationResult.Failed
            DisambiguationResult.Success(cand.entryIndex, cand.senseIndex, meaning)
        } catch (_: UnknownHostException) {
            DisambiguationResult.NoInternet
        } catch (_: Exception) {
            DisambiguationResult.Failed
        }
    }
}
