package com.tangotori.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tangotori.app.domain.models.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed persistence for general app/UI settings that should survive
 * across launches (as opposed to SavedStateHandle, which only survives process
 * recreation within a session).
 */
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Manual language override (null = auto-detect), persisted across launches. */
    val languageOverride: Flow<Language?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[Keys.LanguageOverride]?.let { runCatching { Language.valueOf(it) }.getOrNull() }
    }

    suspend fun setLanguageOverride(override: Language?) {
        context.appSettingsDataStore.edit { prefs ->
            if (override == null) prefs.remove(Keys.LanguageOverride)
            else prefs[Keys.LanguageOverride] = override.name
        }
    }

    /** Whether the user has already seen the first-launch "About" dialog. */
    val hasSeenInfo: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[Keys.HasSeenInfo] ?: false
    }

    suspend fun markInfoSeen() {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.HasSeenInfo] = true }
    }

    /**
     * Dev Mode toggle (only meaningful on a dev device, i.e. one in the worker's
     * BYPASS_DEVICE_IDS list). Default ON: a dev device is unmetered. Turning it
     * OFF makes the worker meter this device like a free user. Has no effect on
     * non-dev devices, which are always metered.
     */
    val devModeEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[Keys.DevModeEnabled] ?: true
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.DevModeEnabled] = enabled }
    }

    /**
     * Master switch for the AI features (in-context meaning, compound-word
     * interpretation). Default ON. When OFF the app makes NO requests to the
     * backend at all — lookups, dev-status checks, and purchase verification
     * are all skipped — so nothing the user types leaves the device (the
     * privacy opt-out promised in PRIVACY_POLICY.md).
     */
    val aiFeaturesEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[Keys.AiFeaturesEnabled] ?: true
    }

    suspend fun setAiFeaturesEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.AiFeaturesEnabled] = enabled }
    }

    /** Whether Anki cards link kanji to the Kanji Study app. Persists across
     *  launches; default off. */
    val linkToKanjiStudy: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[Keys.LinkToKanjiStudy] ?: false
    }

    suspend fun setLinkToKanjiStudy(enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.LinkToKanjiStudy] = enabled }
    }

    private object Keys {
        val LanguageOverride = stringPreferencesKey("language_override")
        val HasSeenInfo = booleanPreferencesKey("has_seen_info")
        val DevModeEnabled = booleanPreferencesKey("dev_mode_enabled")
        val AiFeaturesEnabled = booleanPreferencesKey("ai_features_enabled")
        val LinkToKanjiStudy = booleanPreferencesKey("link_to_kanji_study")
    }
}
