package com.tangotori.app.data.settings

import android.content.Context
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

    private object Keys {
        val LanguageOverride = stringPreferencesKey("language_override")
    }
}
