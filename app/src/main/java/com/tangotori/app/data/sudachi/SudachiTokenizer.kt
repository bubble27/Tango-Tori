package com.tangotori.app.data.sudachi

import android.content.Context
import android.util.Log
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.KanjiKanaSplit
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps Sudachi. The dictionary file ships in the APK at `assets/sudachi/system_core.dic`
 * and is copied to <filesDir>/sudachi/system_core.dic on first use by
 * [SudachiAssetInstaller]. Tokenizer construction is heavy; we lazy-init under a
 * mutex and keep the instance for the process lifetime.
 *
 * Settings JSON includes at least one OOV provider plugin —
 * [com.worksap.nlp.sudachi.SimpleOovProviderPlugin] — because Sudachi refuses to
 * construct a dictionary otherwise. We skip MeCabOovProviderPlugin (which needs
 * `char.def` and `unk.def` files that don't ship with the SudachiDict release zip).
 */
class SudachiTokenizer(
    private val appContext: Context,
    private val installer: SudachiAssetInstaller,
) {
    private val initMutex = Mutex()
    // Sudachi's Tokenizer instances are NOT thread-safe: the internal lattice
    // is per-tokenizer state, and concurrent tokenize() calls corrupt it
    // (manifests as "EOS isn't connected to BOS" and NPEs inside LatticeNode).
    // We serialize all tokenize calls through this mutex. Cost: shared-input
    // throughput drops to one-at-a-time, which is fine — a single sentence
    // takes ~10ms once warmed up.
    private val tokenizeMutex = Mutex()
    private var tokenizer: Tokenizer? = null

    val isReady: Boolean get() = tokenizer != null

    suspend fun ensureReady() {
        if (tokenizer != null) return
        initMutex.withLock {
            if (tokenizer != null) return
            installer.installIfNeeded()
            tokenizer = withContext(Dispatchers.IO) { buildTokenizer() }
        }
    }

    private fun buildTokenizer(): Tokenizer {
        val dictDir = File(appContext.filesDir, "sudachi")
        val systemDict = File(dictDir, "system_core.dic")
        Log.i(TAG, "Building Sudachi with systemDict=${systemDict.absolutePath} (size=${systemDict.length()})")

        val settings = """
            {
              "systemDict": "${systemDict.absolutePath.replace("\\", "\\\\")}",
              "inputTextPlugin": [
                { "class": "com.worksap.nlp.sudachi.DefaultInputTextPlugin" }
              ],
              "oovProviderPlugin": [
                {
                  "class": "com.worksap.nlp.sudachi.SimpleOovProviderPlugin",
                  "oovPOS": ["補助記号", "一般", "*", "*", "*", "*"],
                  "leftId": 5968,
                  "rightId": 5968,
                  "cost": 3857
                }
              ]
            }
        """.trimIndent()

        @Suppress("DEPRECATION")
        val dictionary = DictionaryFactory().create(dictDir.absolutePath, settings)
        return dictionary.create()
    }

    suspend fun tokenize(sentence: String): List<Token> {
        if (sentence.isBlank()) return emptyList()
        ensureReady()
        val tok = tokenizer ?: return emptyList()
        return tokenizeMutex.withLock {
            withContext(Dispatchers.Default) {
                val raw = tok.tokenize(Tokenizer.SplitMode.C, sentence).map { m ->
                    val posList = m.partOfSpeech().toList()
                    val surface = m.surface()
                    val dictForm = m.dictionaryForm().ifEmpty { surface }
                    val surfaceReading = m.readingForm().katakanaToHiragana()
                    Token(
                        surface = surface,
                        dictionaryForm = dictForm,
                        reading = surfaceReading,
                        dictionaryReading = KanjiKanaSplit.deriveDictFormReading(
                            surface = surface,
                            surfaceReading = surfaceReading,
                            dictForm = dictForm,
                        ),
                        partOfSpeech = PosMapper.classify(surface, posList),
                        rawPosTag = posList.joinToString(","),
                    )
                }
                TokenMerger.merge(raw)
            }
        }
    }

    private companion object { const val TAG = "SudachiTokenizer" }
}
