package com.tangotori.app.ui.sentence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangotori.app.data.IncomingSentenceBus
import com.tangotori.app.data.anki.AnkiCardRepository
import com.tangotori.app.data.anki.AnkiPreferences
import com.tangotori.app.data.anki.DefaultDeck
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.usecases.CreateCardUseCase
import com.tangotori.app.domain.usecases.LookupWordUseCase
import com.tangotori.app.domain.usecases.TokenizeSentenceUseCase
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
    private val savedState: SavedStateHandle,
) : ViewModel() {

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

    /**
     * Load a sentence shared into the app via Android's share intent. Skips
     * the debounce flow so the user lands in chip view immediately rather
     * than seeing the input field for half a second.
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

    fun onTokenSelected(index: Int) {
        val tokens = _state.value.tokens
        if (index !in tokens.indices) return
        if (tokens[index].partOfSpeech == PartOfSpeech.PUNCTUATION) return
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
        _state.value = _state.value.copy(isEditing = true, selectedIndex = null)
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
