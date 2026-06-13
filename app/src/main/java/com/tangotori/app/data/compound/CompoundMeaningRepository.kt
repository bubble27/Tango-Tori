package com.tangotori.app.data.compound

import com.tangotori.app.BuildConfig
import com.tangotori.app.data.budget.ApiKeyStore
import com.tangotori.app.data.budget.UsageBudgetManager
import com.tangotori.app.domain.usecases.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

sealed class MeaningResult {
    data class Success(val meanings: List<String>) : MeaningResult()
    data object NoInternet : MeaningResult()
    data object Failed : MeaningResult()
    /** The device spent its daily free token budget (worker replied 429).
     *  [showMessage] is true only for the first notice of the day — the UI
     *  shows the limit message once, then later misses stay silent. */
    data class LimitReached(val showMessage: Boolean) : MeaningResult()
    /** The user turned the AI features off — no request was made. */
    data object AiDisabled : MeaningResult()
}

@Singleton
class CompoundMeaningRepository @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
    private val budgetManager: UsageBudgetManager,
    private val apiKeyStore: ApiKeyStore,
) {
    suspend fun fetchMeaning(
        compound: String,
        subUnits: List<LookupResult>,
    ): MeaningResult = withContext(Dispatchers.IO) {
        val partsArray = org.json.JSONArray()
        for (unit in subUnits) {
            if (unit is LookupResult.Match) {
                val entry = unit.entries.firstOrNull {
                    it.primaryReading.firstOrNull()?.isLowerCase() == true
                } ?: unit.entries.firstOrNull() ?: continue
                val meaning = entry.senses.firstOrNull()?.glosses?.firstOrNull()?.text ?: continue
                partsArray.put(JSONObject().apply {
                    put("text", unit.token)
                    put("pinyin", entry.primaryReading)
                    put("meaning", meaning)
                })
            }
        }
        if (partsArray.length() == 0) return@withContext MeaningResult.Failed

        // Compound requests are sent even when the daily free limit is hit:
        // the worker serves D1 cache hits for free regardless of budget, and
        // only rejects misses (429) — which we then surface/ignore below.
        val devActive = budgetManager.isDevActive()
        // Bring-your-own-key: billed to the user's key, no budget applies.
        val userKey = apiKeyStore.getKey()

        val body = JSONObject().apply {
            put("device_id", deviceIdProvider.deviceId)
            put("compound", compound)
            put("parts", partsArray)
            put("dev_mode", devActive)
            if (userKey != null) put("api_key", userKey)
            budgetManager.currentTier().let { if (it > 1) put("budget_tier", it) }
        }.toString()

        try {
            val url = URL("${BuildConfig.COMPOUND_API_URL}/compound")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode == 429) {
                budgetManager.markLimitReached()
                return@withContext MeaningResult.LimitReached(
                    showMessage = budgetManager.consumeLimitNotice(),
                )
            }
            if (conn.responseCode != 200) return@withContext MeaningResult.Failed

            val response = conn.inputStream.bufferedReader().readText()
            val arr = JSONObject(response).optJSONArray("meanings")
            if (arr == null || arr.length() == 0) return@withContext MeaningResult.Failed
            val list = (0 until arr.length()).map { arr.getString(it) }
            MeaningResult.Success(list)
        } catch (_: UnknownHostException) {
            MeaningResult.NoInternet
        } catch (_: Exception) {
            MeaningResult.Failed
        }
    }
}
