package com.tangotori.app.ui.sentence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangotori.app.data.IncomingSentenceBus
import com.tangotori.app.data.anki.AnkiCardRepository
import com.tangotori.app.data.anki.AnkiPreferences
import com.tangotori.app.data.anki.DefaultDeck
import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Language
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.usecases.ChineseLookupUseCase
import com.tangotori.app.domain.usecases.ChineseTokenizerUseCase
import com.tangotori.app.domain.usecases.CreateCardUseCase
import com.tangotori.app.data.cedict.CedictRepository
import com.tangotori.app.domain.usecases.LookupWordUseCase
import com.tangotori.app.domain.usecases.TokenizeSentenceUseCase
import com.tangotori.app.domain.util.FuriganaBuilder
import com.tangotori.app.domain.util.HanziBreakdownBuilder
import com.tangotori.app.domain.util.KanjiBreakdownBuilder
import com.tangotori.app.domain.util.KanjiTile
import com.tangotori.app.domain.util.LanguageDetector
import com.tangotori.app.domain.util.SentenceHtmlBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-token JMdict/CC-CEDICT lookup state. Loading is non-null while a
 * coroutine is fetching; entries is non-null once the lookup completed.
 */
data class EntryLookup(
    val loading: Boolean = false,
    val entries: List<DictEntry>? = null,
    val error: String? = null,
)

data class DeckPickerState(
    val token: Token,
    val entry: DictEntry,
    val decks: List<DeckOption> = emptyList(),
    val loading: Boolean = false,
)

data class DeckOption(val id: Long, val name: String)

sealed interface CardSubmitResult {
    data class Success(val deckName: String) : CardSubmitResult
    data object Duplicate : CardSubmitResult
    data class Failed(val reason: String) : CardSubmitResult
    data object AnkiMissing : CardSubmitResult
}

data class SentenceUiState(
    val input: String = "",
    val tokens: List<Token> = emptyList(),
    val selectedIndex: Int? = null,
    val isTokenizing: Boolean = false,
    val tokenizerInitializing: Boolean = false,
    val error: String? = null,
    val isEditing: Boolean = true,
    val entries: Map<Int, EntryLookup> = emptyMap(),
    val defaultDeck: DefaultDeck? = null,
    val deckPicker: DeckPickerState? = null,
    val isSubmittingCard: Boolean = false,
    val cardResult: CardSubmitResult? = null,
    val linkToKanjiStudy: Boolean = false,
    /** Language currently active (auto-detected or manually overridden). */
    val language: Language = Language.JAPANESE,
    /**
     * When non-null, overrides auto-detection. Cycles:
     *   null (auto) → JAPANESE → CHINESE_SIMPLIFIED → null
     */
    val languageOverride: Language? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SentenceViewModel @Inject constructor(
    private val tokenize: TokenizeSentenceUseCase,
    private val chineseTokenize: ChineseTokenizerUseCase,
    private val lookup: LookupWordUseCase,
    private val chineseLookup: ChineseLookupUseCase,
    private val anki: AnkiCardRepository,
    private val createCard: CreateCardUseCase,
    private val ankiPrefs: AnkiPreferences,
    private val incomingBus: IncomingSentenceBus,
    private val jmdictDao: JmdictDao,
    private val cedictRepo: CedictRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val charBreakdownCache = mutableMapOf<Long, List<KanjiTile>>()

    /**
     * Loads per-character breakdown tiles for [entry].
     * Japanese → KANJIDIC kanji tiles (reading = hiragana).
     * Chinese  → CC-CEDICT character tiles (reading = pinyin).
     * Returns empty for pure-kana / pure-latin words.
     */
    suspend fun loadKanjiBreakdown(entry: DictEntry): List<KanjiTile> {
        charBreakdownCache[entry.id]?.let { return it }
        val tiles = when (_state.value.language) {
            Language.JAPANESE -> loadJapaneseTiles(entry)
            Language.CHINESE_SIMPLIFIED,
            Language.CHINESE_TRADITIONAL -> loadChineseTiles(entry)
        }
        charBreakdownCache[entry.id] = tiles
        return tiles
    }

    private suspend fun loadJapaneseTiles(entry: DictEntry): List<KanjiTile> {
        val word = entry.headword.ifEmpty { return emptyList() }
        val furigana = FuriganaBuilder.build(word, entry.primaryReading)
        val kanjiChars = word
            .filter { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }
            .map { it.toString() }.distinct()
        val rows = kanjiChars.associateWith { jmdictDao.getKanjiDic(it) }
        val meaningsMap = rows.mapValues { (_, row) ->
            row?.meanings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        val readingsMap = rows.mapValues { (_, row) ->
            row?.readings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }
        return KanjiBreakdownBuilder.build(
            furiganaMarkup = furigana,
            kanjiMeanings = { c -> meaningsMap[c] ?: emptyList() },
            kanjiReadings = { c -> readingsMap[c] ?: emptySet() },
        )
    }

    private suspend fun loadChineseTiles(entry: DictEntry): List<KanjiTile> {
        // Pre-fetch each unique character once so charLookup and allMeanings
        // augmentation share the same DB round-trips.
        val chars = entry.headword.filter { c ->
            c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF
        }.map { it.toString() }.distinct()
        val charInfoMap = chars.associateWith { cedictRepo.lookupChar(it) }

        return HanziBreakdownBuilder.build(
            surface = entry.headword,
            wordPinyinMarks = entry.primaryReading,
            charLookup = { char -> charInfoMap[char]?.meanings ?: emptyList() },
        ).map { tile ->
            val glosses = charInfoMap[tile.char]?.allGlosses ?: emptyList()
            if (glosses.isNotEmpty()) tile.copy(allMeanings = glosses) else tile
        }
    }

    private val _state = MutableStateFlow(SentenceUiState(
        input = savedState["input"] ?: "",
        linkToKanjiStudy = savedState["linkToKanjiStudy"] ?: false,
        languageOverride = savedState.get<String>("languageOverride")?.let {
            runCatching { Language.valueOf(it) }.getOrNull()
        },
    ))
    val state: StateFlow<SentenceUiState> = _state.asStateFlow()

    private val inputFlow = MutableStateFlow(_state.value.input)

    init {
        // Immediate language detection (no debounce) so the pill updates as
        // the user types, before the tokenizer fires.
        inputFlow
            .onEach { text ->
                val detected = if (text.isBlank()) Language.JAPANESE
                               else LanguageDetector.detect(text)
                val effective = _state.value.languageOverride ?: detected
                _state.value = _state.value.copy(language = effective)
            }
            .launchIn(viewModelScope)

        // Debounced tokenization routed by active language.
        inputFlow
            .debounce(500)
            .onEach { text ->
                if (text.isBlank()) {
                    _state.value = _state.value.copy(tokens = emptyList(), selectedIndex = null, isTokenizing = false)
                    return@onEach
                }
                _state.value = _state.value.copy(isTokenizing = true, error = null)
            }
            .mapLatest { text ->
                if (text.isBlank()) return@mapLatest emptyList()
                val lang = _state.value.language
                runCatching {
                    when (lang) {
                        Language.JAPANESE -> tokenize(text)
                        Language.CHINESE_SIMPLIFIED,
                        Language.CHINESE_TRADITIONAL -> chineseTokenize(text)
                    }
                }.getOrElse {
                    _state.value = _state.value.copy(error = it.message, isTokenizing = false)
                    emptyList()
                }
            }
            .onEach { tokens ->
                _state.value = _state.value.copy(
                    tokens = tokens,
                    isTokenizing = false,
                    entries = emptyMap(),
                )
            }
            .launchIn(viewModelScope)

        ankiPrefs.defaultDeck
            .onEach { d -> _state.value = _state.value.copy(defaultDeck = d) }
            .launchIn(viewModelScope)

        incomingBus.events
            .onEach { acceptSharedSentence(it) }
            .launchIn(viewModelScope)
    }

    fun loadSentence(sentence: String) = acceptSharedSentence(sentence)

    private fun acceptSharedSentence(sentence: String) {
        val text = sentence.trim()
        if (text.isBlank()) return
        savedState["input"] = text
        val detected = LanguageDetector.detect(text)
        val effective = _state.value.languageOverride ?: detected
        viewModelScope.launch {
            _state.value = _state.value.copy(
                input = text,
                language = effective,
                isEditing = false,
                isTokenizing = true,
                error = null,
                selectedIndex = null,
                entries = emptyMap(),
            )
            val tokens = runCatching {
                when (effective) {
                    Language.JAPANESE -> tokenize(text)
                    Language.CHINESE_SIMPLIFIED,
                    Language.CHINESE_TRADITIONAL -> chineseTokenize(text)
                }
            }.getOrElse {
                _state.value = _state.value.copy(error = it.message, isTokenizing = false)
                return@launch
            }
            _state.value = _state.value.copy(
                tokens = tokens,
                isTokenizing = false,
                isEditing = tokens.isEmpty(),
            )
            inputFlow.value = text
        }
    }

    fun onInputChange(value: String) {
        savedState["input"] = value
        _state.value = _state.value.copy(input = value, isEditing = true)
        inputFlow.value = value
    }

    fun onTokenSelected(index: Int) {
        val tokens = _state.value.tokens
        if (index !in tokens.indices) return
        if (tokens[index].partOfSpeech == PartOfSpeech.PUNCTUATION) return
        if (index == _state.value.selectedIndex) return
        _state.value = _state.value.copy(selectedIndex = index)
        if (_state.value.entries[index] == null) {
            fetchEntries(index, tokens[index])
        }
    }

    private fun fetchEntries(index: Int, token: Token) {
        _state.value = _state.value.copy(
            entries = _state.value.entries + (index to EntryLookup(loading = true)),
        )
        viewModelScope.launch {
            val lang = _state.value.language
            val result = runCatching {
                when (lang) {
                    Language.JAPANESE -> lookup(token)
                    Language.CHINESE_SIMPLIFIED,
                    Language.CHINESE_TRADITIONAL -> chineseLookup(token)
                }
            }
            val newLookup = result.fold(
                onSuccess = { EntryLookup(loading = false, entries = it) },
                onFailure = { EntryLookup(loading = false, error = it.message) },
            )
            _state.value = _state.value.copy(
                entries = _state.value.entries + (index to newLookup),
            )
        }
    }

    fun startEditing() {
        _state.value = _state.value.copy(isEditing = true, selectedIndex = null)
    }

    fun finishEditing() {
        if (_state.value.input.isBlank()) return
        _state.value = _state.value.copy(isEditing = false)
    }

    /**
     * Sets the manual language override (null = auto-detect) and immediately
     * re-tokenizes. Uses a direct coroutine launch rather than poking inputFlow
     * because MutableStateFlow deduplicates equal values and would silently
     * drop the re-tokenization request when the input text hasn't changed.
     */
    fun setLanguageOverride(override: Language?) {
        savedState["languageOverride"] = override?.name
        val detected = LanguageDetector.detect(_state.value.input)
        val effective = override ?: detected
        val text = _state.value.input
        _state.value = _state.value.copy(
            languageOverride = override,
            language = effective,
            tokens = emptyList(),
            selectedIndex = null,
            entries = emptyMap(),
        )
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isTokenizing = true, error = null)
            val tokens = runCatching {
                when (effective) {
                    Language.JAPANESE -> tokenize(text)
                    Language.CHINESE_SIMPLIFIED,
                    Language.CHINESE_TRADITIONAL -> chineseTokenize(text)
                }
            }.getOrElse {
                _state.value = _state.value.copy(error = it.message, isTokenizing = false)
                return@launch
            }
            _state.value = _state.value.copy(tokens = tokens, isTokenizing = false)
        }
    }

    // ── Card creation ────────────────────────────────────────────────────────

    fun addToDefaultDeck(token: Token, entry: DictEntry) {
        val deck = _state.value.defaultDeck
        if (deck == null) { openDeckPicker(token, entry); return }
        submitCard(token, entry, deck.id, deck.name, setAsDefault = false)
    }

    fun openDeckPicker(token: Token, entry: DictEntry) {
        if (!anki.isAnkiDroidInstalled()) {
            _state.value = _state.value.copy(cardResult = CardSubmitResult.AnkiMissing)
            return
        }
        _state.value = _state.value.copy(
            deckPicker = DeckPickerState(token = token, entry = entry, loading = true),
        )
        viewModelScope.launch {
            val decks = runCatching { anki.getDecks() }.getOrDefault(emptyMap())
            _state.value = _state.value.copy(
                deckPicker = _state.value.deckPicker?.copy(
                    loading = false,
                    decks = decks.map { (id, name) -> DeckOption(id, name) }
                        .sortedBy { it.name.lowercase() },
                ),
            )
        }
    }

    fun dismissDeckPicker() {
        _state.value = _state.value.copy(deckPicker = null)
    }

    fun onDeckChosen(deckId: Long, deckName: String, setAsDefault: Boolean) {
        val picker = _state.value.deckPicker ?: return
        _state.value = _state.value.copy(deckPicker = null)
        viewModelScope.launch {
            if (setAsDefault) ankiPrefs.setDefaultDeck(deckId, deckName)
        }
        submitCard(picker.token, picker.entry, deckId, deckName, setAsDefault)
    }

    private fun submitCard(
        token: Token,
        entry: DictEntry,
        deckId: Long,
        deckName: String,
        setAsDefault: Boolean,
    ) {
        if (!anki.isAnkiDroidInstalled()) {
            _state.value = _state.value.copy(cardResult = CardSubmitResult.AnkiMissing)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmittingCard = true)
            val lang = _state.value.language
            val isChinese = lang != Language.JAPANESE
            val sentenceTokens = _state.value.tokens.map { t ->
                val entryId = if (isLinkableToken(t)) {
                    runCatching {
                        when (lang) {
                            Language.JAPANESE -> lookup(t).firstOrNull()?.id
                            else -> chineseLookup(t).firstOrNull()?.id
                        }
                    }.getOrNull()
                } else null
                SentenceHtmlBuilder.TokenWithEntry(t, entryId)
            }
            val card = createCard.buildCard(
                entry = entry,
                sentence = _state.value.input,
                sentenceTokens = sentenceTokens,
                useKanjiStudyLinks = _state.value.linkToKanjiStudy,
                language = lang,
            )
            val result = anki.addCard(card, deckId)
            _state.value = _state.value.copy(
                isSubmittingCard = false,
                cardResult = when (result) {
                    is AnkiCardRepository.AddResult.Success ->
                        CardSubmitResult.Success(result.deckName)
                    AnkiCardRepository.AddResult.Duplicate -> CardSubmitResult.Duplicate
                    is AnkiCardRepository.AddResult.Failed ->
                        CardSubmitResult.Failed(result.reason)
                },
            )
        }
    }

    fun clearCardResult() {
        _state.value = _state.value.copy(cardResult = null)
    }

    fun toggleLinkStyle() {
        val next = !_state.value.linkToKanjiStudy
        savedState["linkToKanjiStudy"] = next
        _state.value = _state.value.copy(linkToKanjiStudy = next)
    }

    private fun isLinkableToken(t: Token): Boolean = when (t.partOfSpeech) {
        PartOfSpeech.PUNCTUATION,
        PartOfSpeech.PARTICLE,
        PartOfSpeech.AUXILIARY_VERB -> false
        else -> true
    }
}
