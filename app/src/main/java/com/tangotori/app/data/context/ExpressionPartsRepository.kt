package com.tangotori.app.data.context

import com.tangotori.app.BuildConfig
import com.tangotori.app.data.budget.ApiKeyStore
import com.tangotori.app.data.budget.UsageBudgetManager
import com.tangotori.app.data.compound.DeviceIdProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-expression meaning of each component word of a grouped idiom / compound,
 * via the worker's `/expression_parts` endpoint. Cached per expression on the
 * backend (sentence-independent). Returns one gloss per part (parallel to the
 * input components), or null if unavailable (offline, over budget, AI off) — in
 * which case the parts simply show their plain dictionary entries.
 *
 * Mirrors [SenseDisambiguationRepository] for the budget / dev-mode / BYOK /
 * tier plumbing.
 */
/** One component to define: its word/reading plus the glosses (one per sense,
 *  in order) of the dictionary entry the app is displaying for it. */
data class PartInput(val word: String, val reading: String, val senses: List<String>)

/** Result for one part: its in-expression meaning, plus the 0-based index of the
 *  displayed entry's sense that matches (null = none → no red highlight). */
data class PartResult(val meaning: String, val senseIndex: Int?)

@Singleton
class ExpressionPartsRepository @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
    private val budgetManager: UsageBudgetManager,
    private val apiKeyStore: ApiKeyStore,
) {
    /**
     * @param expression the grouped word's dictionary form (e.g. 腹が立つ).
     * @param parts the component words, in order, with their displayed senses.
     * @return one [PartResult] per part, or null if none could be fetched.
     */
    suspend fun define(expression: String, parts: List<PartInput>): List<PartResult>? =
        withContext(Dispatchers.IO) {
            if (expression.isBlank() || parts.isEmpty()) return@withContext null

            // UNLIMITED: part meanings are sentence-independent and cached per
            // expression server-side, so they're never metered. We do NOT gate
            // on the daily limit (that applies only to in-context meaning) and
            // never mark the limit from here.
            val devActive = budgetManager.isDevActive()
            val userKey = apiKeyStore.getKey()

            val partsArray = JSONArray()
            for (p in parts) {
                partsArray.put(JSONObject().apply {
                    put("word", p.word)
                    put("reading", p.reading)
                    put("senses", JSONArray(p.senses))
                })
            }
            val body = JSONObject().apply {
                put("device_id", deviceIdProvider.deviceId)
                put("expression", expression)
                put("parts", partsArray)
                put("dev_mode", devActive)
                if (userKey != null) put("api_key", userKey)
                budgetManager.currentTier().let { if (it > 1) put("budget_tier", it) }
            }.toString()

            try {
                val url = URL("${BuildConfig.COMPOUND_API_URL}/expression_parts")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.outputStream.use { it.write(body.toByteArray()) }

                // Unmetered endpoint won't 429, but be defensive: never mark the
                // daily limit from here (it's for in-context meaning only).
                if (conn.responseCode != 200) return@withContext null

                val response = conn.inputStream.bufferedReader().readText()
                val arr = JSONObject(response).optJSONArray("parts") ?: return@withContext null
                if (arr.length() < parts.size) return@withContext null
                (0 until parts.size).map { i ->
                    val o = arr.getJSONObject(i)
                    val sense = o.optInt("sense", 0)
                    PartResult(
                        meaning = o.optString("meaning", "").trim(),
                        // worker is 1-based; 0 = no match → null (no highlight).
                        senseIndex = if (sense >= 1) sense - 1 else null,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
}
