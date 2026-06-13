package com.tangotori.app.data.budget

import android.content.Context
import com.tangotori.app.BuildConfig
import com.tangotori.app.data.compound.DeviceIdProvider
import com.tangotori.app.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side view of the worker's daily token budget plus the Dev Mode state.
 *
 * - Dev device detection: the worker keeps a bypass list (BYPASS_DEVICE_IDS);
 *   [refreshDevStatus] asks `GET /status` whether this install's device_id is
 *   on it. The result is cached in SharedPreferences so the Dev Mode toggle
 *   still shows when offline.
 * - Limit blocking: when a request comes back 429 (daily budget spent), we
 *   remember the UTC date — the worker's budget is keyed on the UTC day.
 *   While blocked, disambiguation requests are not sent at all; compound
 *   requests ARE still sent because the worker serves D1 cache hits for free
 *   even when over budget (only misses get rejected). The "limit reached"
 *   message is surfaced exactly once per blocked day (see
 *   [consumeLimitNotice]); later failures stay silent. Re-enabling Dev Mode
 *   clears the block immediately.
 */
@Singleton
class UsageBudgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdProvider: DeviceIdProvider,
    private val appPrefs: AppPreferences,
) {
    private val prefs by lazy {
        context.getSharedPreferences("tango_tori", Context.MODE_PRIVATE)
    }

    private val _isDevDevice = MutableStateFlow(false)
    /** Whether this install's device_id is on the worker's bypass list. */
    val isDevDevice: StateFlow<Boolean> = _isDevDevice.asStateFlow()

    init {
        _isDevDevice.value = prefs.getBoolean(KEY_IS_DEV, false)
    }

    /** Re-checks dev status with the worker; call once at app start. */
    suspend fun refreshDevStatus() = withContext(Dispatchers.IO) {
        // AI features off = the user opted out of ALL backend communication.
        if (!appPrefs.aiFeaturesEnabled.first()) return@withContext
        try {
            val url = URL(
                "${BuildConfig.COMPOUND_API_URL}/status?device_id=${deviceIdProvider.deviceId}"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                val dev = obj.optBoolean("dev", false)
                _isDevDevice.value = dev
                prefs.edit().putBoolean(KEY_IS_DEV, dev).apply()
            }
        } catch (_: Exception) {
            // Offline / worker unreachable — keep the cached value.
        }
    }

    /** True when this is a dev device AND the Dev Mode toggle is on → unmetered. */
    suspend fun isDevActive(): Boolean =
        _isDevDevice.value && appPrefs.devModeEnabled.first()

    /** Purchased usage tier (1 = free, 8 or 20 = boost). Written by
     *  BillingRepository into the same SharedPreferences file. */
    fun currentTier(): Int = prefs.getInt("usage_tier", 1)

    /** True while the daily free limit is known to be spent for the current UTC day. */
    fun isLimitBlocked(): Boolean {
        val blockedDate = prefs.getString(KEY_BLOCKED_DATE, null) ?: return false
        if (blockedDate == todayUtc()) return true
        prefs.edit().remove(KEY_BLOCKED_DATE).apply() // day rolled over → lifted
        return false
    }

    /** Records that the worker rejected a request with 429 (budget spent). */
    fun markLimitReached() {
        prefs.edit().putString(KEY_BLOCKED_DATE, todayUtc()).apply()
    }

    /**
     * The "daily free limit reached" message is shown exactly once per blocked
     * day. Returns true the first time it's called after the limit is hit on a
     * given UTC day; every later call (until the day rolls over or the block is
     * cleared) returns false and the caller should fail silently.
     */
    @Synchronized
    fun consumeLimitNotice(): Boolean {
        val today = todayUtc()
        if (prefs.getString(KEY_NOTICE_DATE, null) == today) return false
        prefs.edit().putString(KEY_NOTICE_DATE, today).apply()
        return true
    }

    /** Lifts the local block (e.g. when Dev Mode is re-enabled). */
    fun clearLimit() {
        prefs.edit().remove(KEY_BLOCKED_DATE).remove(KEY_NOTICE_DATE).apply()
    }

    private fun todayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private companion object {
        const val KEY_IS_DEV = "is_dev_device"
        const val KEY_BLOCKED_DATE = "budget_blocked_date"
        const val KEY_NOTICE_DATE = "budget_notice_date"
    }
}
