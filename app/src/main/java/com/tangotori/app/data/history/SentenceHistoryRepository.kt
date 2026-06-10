package com.tangotori.app.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.historyDataStore by preferencesDataStore(name = "sentence_history")

@Singleton
class SentenceHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val recentSentences: Flow<List<String>> = context.historyDataStore.data
        .map { prefs ->
            prefs[Keys.Recent]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    suspend fun addSentence(text: String) {
        if (text.isBlank()) return
        context.historyDataStore.edit { prefs ->
            val current = prefs[Keys.Recent]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(text) + current.filter { it != text }).take(MAX_HISTORY)
            prefs[Keys.Recent] = updated.joinToString(SEPARATOR)
        }
    }

    private object Keys {
        val Recent = stringPreferencesKey("recent")
    }

    companion object {
        private const val SEPARATOR = "|||"
        private const val MAX_HISTORY = 50
    }
}
