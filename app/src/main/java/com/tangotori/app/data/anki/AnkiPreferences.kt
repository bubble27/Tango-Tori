package com.tangotori.app.data.anki

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed persistence for Stage 3 settings. Currently just the user's
 * default Anki deck choice; later we'll add furigana display mode + POS
 * color toggle.
 */
private val Context.ankiDataStore by preferencesDataStore(name = "anki_prefs")

@Singleton
class AnkiPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.ankiDataStore

    val defaultDeck: Flow<DefaultDeck?> = store.data.map { prefs ->
        val id = prefs[Keys.DefaultDeckId] ?: return@map null
        val name = prefs[Keys.DefaultDeckName] ?: return@map null
        DefaultDeck(id, name)
    }

    suspend fun currentDefaultDeck(): DefaultDeck? = defaultDeck.first()

    suspend fun setDefaultDeck(id: Long, name: String) {
        store.edit { prefs ->
            prefs[Keys.DefaultDeckId] = id
            prefs[Keys.DefaultDeckName] = name
        }
    }

    suspend fun clearDefaultDeck() {
        store.edit { prefs ->
            prefs.remove(Keys.DefaultDeckId)
            prefs.remove(Keys.DefaultDeckName)
        }
    }

    private object Keys {
        val DefaultDeckId: Preferences.Key<Long> = longPreferencesKey("default_deck_id")
        val DefaultDeckName: Preferences.Key<String> = stringPreferencesKey("default_deck_name")
    }
}

data class DefaultDeck(val id: Long, val name: String)
