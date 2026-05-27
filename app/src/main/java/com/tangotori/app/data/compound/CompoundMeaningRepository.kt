package com.tangotori.app.data.compound

import com.tangotori.app.BuildConfig
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
}

@Singleton
class CompoundMeaningRepository @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
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

        val body = JSONObject().apply {
            put("device_id", deviceIdProvider.deviceId)
            put("compound", compound)
            put("parts", partsArray)
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
