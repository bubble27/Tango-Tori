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
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.usecases.CreateCardUseCase
import com.tangotori.app.domain.usecases.LookupWordUseCase
import com.tangotori.app.domain.usecases.TokenizeSentenceUseCase
import com.tangotori.app.domain.util.FuriganaBuilder
import com.tangotori.app.domain.util.KanjiBreakdownBuilder
import com.tangotori.app.domain.util.KanjiTile
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
 * Per-token JMdict lookup state. Loading is non-null while a coroutine is
 * fetching; entries is non-null once the lookup completed (possibly empty
 * if JMdict had no match).
 */
data class EntryLookup(
    val loading: Boolean = false,
    val entries: List<DictEntry>? = null,
    val error: String? = null,
)

/**
 * Active deck-picker dialog state. Carries the token/entry that triggered the
 * picker so the chosen deck can immediately submit them.
 */
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
    /** AnkiDroid isn't installed — UI prompts the user to install it. */
    data object AnkiMissing : CardSubmitResult
}

data class SentenceUiState(
    val input: String = "",
    val tokens: List<Token> = emptyList(),
    val selectedIndex: Int? = null,
    val isTokenizing: Boolean = false,
    val tokenizerInitializing: Boolean = false,
    val error: String? = null,
    /** True while the user is editing the input; false when chips should be shown. */
    val isEditing: Boolean = true,
    /** Cached JMdict lookups keyed by the token's absolute index. */
    val entries: Map<Int, EntryLookup> = emptyMap(),
    /** Cached deck list for the picker (only fetched once per app session). */
    val defaultDeck: DefaultDeck? = null,
    val deckPicker: DeckPickerState? = null,
    val isSubmittingCard: Boolean = false,
    val cardResult: CardSubmitResult? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SentenceViewModel @Inject constructor(
    private val tokenize: TokenizeSentenceUseCase,
    private val lookup: LookupWordUseCase,
    private val anki: AnkiCardRepository,
    private val createCard: CreateCardUseCase,
    private val ankiPrefs: AnkiPreferences,
    private val incomingBus: IncomingSentenceBus,
    private val jmdictDao: JmdictDao,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Cache of computed kanji breakdowns keyed by JMdict entry id. The
    // breakdown is a synchronous projection of (headword, reading) + per-kanji
    // readings/meanings from KANJIDIC, but the KANJIDIC lookups are suspend.
    // Cache to avoid re-querying every recomposition.
    private val kanjiBreakdownCache = mutableMapOf<Long, List<KanjiTile>>()

    /**
     * Build a kanji breakdown for the given [entry]'s headword. Shared
     * splitter with the Anki card path so what the user sees in-app matches
     * what lands on the card. Returns empty for pure-kana words.
     */
    suspend fun loadKanjiBreakdown(entry: DictEntry): List<KanjiTile> {
        kanjiBreakdownCache[entry.id]?.let { return it }
        val word = entry.headword.ifEmpty { return emptyList() }
        val reading = entry.primaryReading
        val furigana = FuriganaBuilder.build(word, reading)
        val kanjiChars = word
            .filter { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }
            .map { it.toString() }
            .distinct()
        val rows = kanjiChars.associateWith { jmdictDao.getKanjiDic(it) }
        val meaningsMap = rows.mapValues { (_, row) ->
            row?.meanings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
        val readingsMap = rows.mapValues { (_, row) ->
            row?.readings?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
        }
        val tiles = KanjiBreakdownBuilder.build(
            furiganaMarkup = furigana,
            kanjiMeanings = { c -> meaningsMap[c] ?: emptyList() },
            kanjiReadings = { c -> readingsMap[c] ?: emptySet() },
        )
        kanjiBreakdownCache[entry.id] = tiles
        return tiles
    }

    private val _state = MutableStateFlow(SentenceUiState(input = savedState["input"] ?: ""))
    val state: StateFlow<SentenceUiState> = _state.asStateFlow()

    private val inputFlow = MutableStateFlow(_state.value.input)

    init {
        inputFlow
            .debounce(500)
            .distinctUntilChanged()
            .onEach { text ->
                if (text.isBlank()) {
                    _state.value = _state.value.copy(tokens = emptyList(), selectedIndex = null, isTokenizing = false)
                    return@onEach
                }
                _state.value = _state.value.copy(isTokenizing = true, error = null)
            }
            .mapLatest { text ->
                if (text.isBlank()) emptyList() else runCatching { tokenize(text) }.getOrElse {
                    _state.value = _state.value.copy(error = it.message, isTokenizing = false)
                    emptyList()
                }
            }
            .onEach { tokens ->
                _state.value = _state.value.copy(
                    tokens = tokens,
                    isTokenizing = false,
                    // New tokenization invalidates cached lookups (indices may
                    // refer to different tokens now).
                    entries = emptyMap(),
                )
            }
            .launchIn(viewModelScope)

        // Surface the persisted default deck for button-label rendering.
        ankiPrefs.defaultDeck
            .onEach { d -> _state.value = _state.value.copy(defaultDeck = d) }
            .launchIn(viewModelScope)

        // Sentences arriving from the Android share sheet — open them as the
        // active sentence and jump straight to chip view.
        incomingBus.events
            .onEach { acceptSharedSentence(it) }
            .launchIn(viewModelScope)
    }

    /** Public entry point — used by the in-screen Paste button. Forwards
     *  to the same code path the Android share intent takes. */
    fun loadSentence(sentence: String) = acceptSharedSentence(sentence)

    /**
     * Load a sentence shared into the app via Android's share intent OR
     * pasted from the clipboard. Skips the debounce flow so the user lands
     * in chip view immediately rather than seeing the input field for half
     * a second.
     */
    private fun acceptSharedSentence(sentence: String) {
        val text = sentence.trim()
        if (text.isBlank()) return
        savedState["input"] = text
        viewModelScope.launch {
            _state.value = _state.value.copy(
                input = text,
                isTokenizing = true,
                error = null,
                selectedIndex = null,
                entries = emptyMap(),
            )
            val tokens = runCatching { tokenize(text) }.getOrElse {
                _state.value = _state.value.copy(
                    error = it.message,
                    isTokenizing = false,
                )
                return@launch
            }
            _state.value = _state.value.copy(
                tokens = tokens,
                isTokenizing = false,
                isEditing = tokens.isEmpty(),
            )
            // Keep the debounce flow's view of input in sync, so a subsequent
            // edit doesn't trigger a redundant retokenize of the same text.
            inputFlow.value = text
        }
    }

    fun onInputChange(value: String) {
        savedState["input"] = value
        _state.value = _state.value.copy(input = value, isEditing = true)
        inputFlow.value = value
    }

    /** Set the active token. Called from chip taps, list-row taps, and the
     *  scroll-driven focus snapshot. The UI layer decides whether to
     *  actually render the body (it waits until the list isn't scrolling). */
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
            val result = runCatching { lookup(token) }
            val newLookup = result.fold(
                onSuccess = { EntryLookup(loading = false, entries = it) },
                onFailure = { EntryLookup(loading = false, error = it.message) },
            )
            _state.value = _state.value.copy(
                entries = _state.value.entries + (index to newLookup),
            )
        }
    }

    /** Re-enter edit mode, keeping the current input text intact. */
    fun startEditing() {
        _state.value = _state.value.copy(
            isEditing = true,
            selectedIndex = null,
        )
    }

    /** Leave edit mode. */
    fun finishEditing() {
        if (_state.value.input.isBlank()) return
        _state.value = _state.value.copy(isEditing = false)
    }

    // ---------------- Card creation (inline, no separate screen) ------------

    /**
     * Submit the given (token, entry) to the user's stored default deck. If
     * no default is set, opens the deck picker instead.
     */
    fun addToDefaultDeck(token: Token, entry: DictEntry) {
        val deck = _state.value.defaultDeck
        if (deck == null) {
            openDeckPicker(token, entry)
            return
        }
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
            // Build the sentence-token list lazily — we need entry ids for the
            // Kanji-Study deep links in the card's Sentence field.
            val sentenceTokens = _state.value.tokens.map { t ->
                val entryId = if (isLinkableToken(t)) {
                    runCatching { lookup(t).firstOrNull()?.id }.getOrNull()
                } else null
                SentenceHtmlBuilder.TokenWithEntry(t, entryId)
            }
            val card = createCard.buildCard(
                entry = entry,
                sentence = _state.value.input,
                sentenceTokens = sentenceTokens,
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

    private fun isLinkableToken(t: Token): Boolean = when (t.partOfSpeech) {
        PartOfSpeech.PUNCTUATION,
        PartOfSpeech.PARTICLE,
        PartOfSpeech.AUXILIARY_VERB -> false
        else -> true
    }
}
