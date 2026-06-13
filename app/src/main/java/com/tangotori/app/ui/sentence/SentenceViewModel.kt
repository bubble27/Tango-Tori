package com.tangotori.app.ui.sentence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangotori.app.data.IncomingSentenceBus
import com.tangotori.app.data.anki.AnkiCardRepository
import com.tangotori.app.data.anki.AnkiPreferences
import com.tangotori.app.data.compound.CompoundMeaningRepository
import com.tangotori.app.data.context.DisambiguationResult
import com.tangotori.app.data.context.ExpressionPartsRepository
import com.tangotori.app.data.context.SenseDisambiguationRepository
import com.tangotori.app.data.billing.BillingRepository
import com.tangotori.app.data.budget.ApiKeyStore
import com.tangotori.app.data.budget.UsageBudgetManager
import com.tangotori.app.data.history.SentenceHistoryRepository
import com.tangotori.app.data.settings.AppPreferences
import com.tangotori.app.data.compound.MeaningResult
import com.tangotori.app.data.anki.DefaultDeck
import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Language
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.usecases.ChineseLookupOutcome
import com.tangotori.app.domain.usecases.ChineseLookupUseCase
import com.tangotori.app.domain.usecases.LookupResult
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    val isFallbackSplit: Boolean = false,
    val subUnits: List<LookupResult> = emptyList(),
    /** null = fetch in flight; non-null = result received */
    val compoundMeaningResult: MeaningResult? = null,
    /** In-context disambiguation is running for this lookup. */
    val inContextLoading: Boolean = false,
    /** Resolved most-relevant sense for the sentence (null until/unless found). */
    val inContext: InContextSense? = null,
    /** Show the one-time "daily free limit reached" notice in the in-context
     *  meaning slot (true only for the first limit failure of the day). */
    val inContextLimitReached: Boolean = false,
    /** For a grouped idiom token: each component word with its own JMdict
     *  entries, rendered as sub-cards below the idiom (like Chinese compounds). */
    val componentEntries: List<ComponentEntries> = emptyList(),
    /** The AI "in-expression meaning" call for the components is in flight. */
    val inExpressionLoading: Boolean = false,
)

/** A component word of a grouped idiom/compound, with its own JMdict entries and
 *  its AI-generated meaning *as used within the expression* (null until/unless
 *  fetched). */
data class ComponentEntries(
    val token: Token,
    val entries: List<DictEntry>,
    val meaning: String? = null,
    /** 0-based sense index of [entries]`[0]` that matches the in-expression use,
     *  highlighted in red (null = none). */
    val senseIndex: Int? = null,
)

/** The dictionary sense the LLM judged most relevant to the current sentence,
 *  plus a short contextual gloss. [entryIndex]/[senseIndex] index into
 *  [EntryLookup.entries] and that entry's senses. */
data class InContextSense(
    val entryIndex: Int,
    val senseIndex: Int,
    val meaning: String,
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
    /** Entry id whose card is currently being submitted (drives the per-card
     *  "creating" ripple animation). null = no submission in flight. */
    val submittingEntryId: Long? = null,
    /** entryId -> deck name for cards added (or found duplicate) during this
     *  sentence view. Drives the disabled "Already added to <deck>" button. */
    val addedDecks: Map<Long, String> = emptyMap(),
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
    private val historyRepo: SentenceHistoryRepository,
    private val appPrefs: AppPreferences,
    private val incomingBus: IncomingSentenceBus,
    private val jmdictDao: JmdictDao,
    private val cedictRepo: CedictRepository,
    private val compoundMeaningRepo: CompoundMeaningRepository,
    private val senseDisambiguation: SenseDisambiguationRepository,
    private val expressionParts: ExpressionPartsRepository,
    private val budgetManager: UsageBudgetManager,
    private val apiKeyStore: ApiKeyStore,
    private val billing: BillingRepository,
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

    val recentSentences: StateFlow<List<String>> = historyRepo.recentSentences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // "About" dialog visibility. Auto-shown once on the very first app launch
    // (see init), and openable any time from the home-screen info button.
    private val _showInfo = MutableStateFlow(false)
    val showInfo: StateFlow<Boolean> = _showInfo.asStateFlow()

    // ── Dev Mode ─────────────────────────────────────────────────────────────
    // The Dev Mode toggle appears in the About dialog only on a dev device
    // (one on the worker's bypass list — checked once per launch in init).
    // Toggle ON → unmetered; OFF → metered like a free user.
    val isDevDevice: StateFlow<Boolean> = budgetManager.isDevDevice
    val devModeEnabled: StateFlow<Boolean> = appPrefs.devModeEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setDevMode(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setDevModeEnabled(enabled)
            // Re-enabling Dev Mode lifts the local daily-limit block immediately.
            if (enabled) budgetManager.clearLimit()
        }
    }

    // ── AI features opt-out ──────────────────────────────────────────────────
    // OFF = privacy mode: no lookup content, device id, or purchase token is
    // ever sent anywhere; everything stays on-device (see PRIVACY_POLICY.md).
    val aiEnabled: StateFlow<Boolean> = appPrefs.aiFeaturesEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setAiFeaturesEnabled(enabled)
            // The launch-time dev-status check is skipped while opted out;
            // run it now that backend communication is allowed again.
            if (enabled) budgetManager.refreshDevStatus()
        }
    }

    // ── Usage limits / purchases screen ──────────────────────────────────────
    private val _showUsageInfo = MutableStateFlow(false)
    val showUsageInfo: StateFlow<Boolean> = _showUsageInfo.asStateFlow()

    fun openUsageInfo() {
        _showUsageInfo.value = true
        billing.refresh() // load live prices / entitlements for the screen
    }

    fun dismissUsageInfo() {
        _showUsageInfo.value = false
        _keySaveState.value = KeySaveState.IDLE
    }

    /** Bring-your-own-key save flow state for the usage screen. */
    enum class KeySaveState { IDLE, CHECKING, SAVED, INVALID, NETWORK_ERROR }

    private val _keySaveState = MutableStateFlow(KeySaveState.IDLE)
    val keySaveState: StateFlow<KeySaveState> = _keySaveState.asStateFlow()

    val hasUserApiKey: StateFlow<Boolean> = apiKeyStore.hasKey

    /** Validates the key against Anthropic, then stores it encrypted. */
    fun saveUserApiKey(key: String) {
        if (key.isBlank() || _keySaveState.value == KeySaveState.CHECKING) return
        _keySaveState.value = KeySaveState.CHECKING
        viewModelScope.launch {
            when (apiKeyStore.validate(key)) {
                ApiKeyStore.Validation.VALID -> {
                    apiKeyStore.saveKey(key)
                    budgetManager.clearLimit() // user is now self-funded
                    _keySaveState.value = KeySaveState.SAVED
                }
                ApiKeyStore.Validation.INVALID ->
                    _keySaveState.value = KeySaveState.INVALID
                ApiKeyStore.Validation.NETWORK_ERROR ->
                    _keySaveState.value = KeySaveState.NETWORK_ERROR
            }
        }
    }

    fun removeUserApiKey() {
        apiKeyStore.clearKey()
        _keySaveState.value = KeySaveState.IDLE
    }

    // Billing state for the usage screen.
    val usageTier: StateFlow<Int> = billing.tier
    val ownsBase8x: StateFlow<Boolean> = billing.ownsBase8x
    val billingProducts = billing.products
    val billingReady: StateFlow<Boolean> = billing.billingReady
    val billingMessage: StateFlow<String?> = billing.message

    fun purchaseBoost(activity: android.app.Activity, productId: String) =
        billing.launchPurchase(activity, productId)

    fun restorePurchases() = billing.refresh()
    fun consumeBillingMessage() = billing.consumeMessage()

    fun openInfo() { _showInfo.value = true }

    // True while viewing a sentence that was opened from the Anki "Open in
    // Tango Tori" deep link. Drives back: such a sentence returns to AnkiDroid
    // on back rather than the home screen. Cleared once the user navigates
    // within the app (edit, type, or load a different sentence normally).
    private val _cameFromDeepLink = MutableStateFlow(false)
    val cameFromDeepLink: StateFlow<Boolean> = _cameFromDeepLink.asStateFlow()

    fun dismissInfo() {
        _showInfo.value = false
        viewModelScope.launch { appPrefs.markInfoSeen() }
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

    /** The (text, language) the current [SentenceUiState.tokens] were built from.
     *  Lets the debounced pipeline skip a redundant re-tokenize when
     *  [acceptSharedSentence] has already tokenized the text it pushes into
     *  [inputFlow] — that re-run flashed the spinner between the sentence and
     *  the word list ~500ms after opening a sentence from history, a visible
     *  one-frame resize of the dictionary screen's top area. */
    private var lastTokenized: Pair<String, Language>? = null

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
            // Skip text that is already tokenized. acceptSharedSentence tokenizes
            // before flipping to the viewing screen and then syncs inputFlow;
            // without this filter the pipeline fired ~500ms after landing and
            // redundantly re-tokenized the same text (spinner flash + token list
            // replacement = the "top area resizes for a frame" glitch — which only
            // happened when the sentence changed, because MutableStateFlow drops
            // equal values).
            .filter { text ->
                text.isBlank() || lastTokenized != (text to _state.value.language)
            }
            .onEach { text ->
                if (text.isBlank()) {
                    lastTokenized = null
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
                }.onSuccess {
                    lastTokenized = text to lang
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
                    // New sentence → cards from the old one are no longer "added"
                    // (a different sentence makes a different Anki card).
                    addedDecks = emptyMap(),
                )
            }
            .launchIn(viewModelScope)

        ankiPrefs.defaultDeck
            .onEach { d -> _state.value = _state.value.copy(defaultDeck = d) }
            .launchIn(viewModelScope)

        incomingBus.events
            .onEach { acceptSharedSentence(it.text, fromDeepLink = it.fromDeepLink) }
            .launchIn(viewModelScope)

        // Restore the persisted language override on a fresh launch. A value
        // already in savedState (process recreated mid-session) takes priority.
        if (savedState.get<String>("languageOverride") == null) {
            viewModelScope.launch {
                appPrefs.languageOverride.first()?.let { setLanguageOverride(it) }
            }
        }

        // Restore the persisted kanji-study-links toggle on a fresh launch
        // (savedState wins on in-session process recreation).
        if (savedState.get<Boolean>("linkToKanjiStudy") == null) {
            viewModelScope.launch {
                if (appPrefs.linkToKanjiStudy.first()) {
                    _state.value = _state.value.copy(linkToKanjiStudy = true)
                }
            }
        }

        // Learn whether this install is a dev (bypass-listed) device.
        viewModelScope.launch { budgetManager.refreshDevStatus() }

        // Refresh purchase entitlements; a granted/restored boost lifts the
        // local daily-limit block immediately.
        billing.onEntitlementGranted = { budgetManager.clearLimit() }
        billing.refresh()

        // Show the "About" dialog the first time the app is ever launched.
        if (savedState.get<Boolean>("infoShownThisProcess") != true) {
            savedState["infoShownThisProcess"] = true
            viewModelScope.launch {
                if (!appPrefs.hasSeenInfo.first()) _showInfo.value = true
            }
        }
    }

    fun loadSentence(sentence: String) = acceptSharedSentence(sentence)

    private fun acceptSharedSentence(sentence: String, fromDeepLink: Boolean = false) {
        val text = sentence.trim()
        if (text.isBlank()) return
        _cameFromDeepLink.value = fromDeepLink
        savedState["input"] = text
        val detected = LanguageDetector.detect(text)
        val effective = _state.value.languageOverride ?: detected
        viewModelScope.launch {
            // Tokenize BEFORE switching to the viewing screen. If we flipped to
            // viewing first and tokenized after, the top sentence area would start
            // empty and visibly resize as the tokens/chips arrived. Staying on the
            // (frozen) editing screen for the brief tokenize keeps the handoff clean.
            val tokens = runCatching {
                when (effective) {
                    Language.JAPANESE -> tokenize(text)
                    Language.CHINESE_SIMPLIFIED,
                    Language.CHINESE_TRADITIONAL -> chineseTokenize(text)
                }
            }.getOrElse {
                lastTokenized = null
                _state.value = _state.value.copy(
                    input = text,
                    language = effective,
                    isEditing = false,
                    isTokenizing = false,
                    tokens = emptyList(),
                    selectedIndex = null,
                    entries = emptyMap(),
                    addedDecks = emptyMap(),
                    error = it.message,
                )
                inputFlow.value = text
                return@launch
            }
            lastTokenized = text to effective
            _state.value = _state.value.copy(
                input = text,
                language = effective,
                isEditing = tokens.isEmpty(),
                isTokenizing = false,
                tokens = tokens,
                selectedIndex = null,
                entries = emptyMap(),
                addedDecks = emptyMap(),
                error = null,
            )
            inputFlow.value = text
            historyRepo.addSentence(text)
        }
    }

    fun onInputChange(value: String) {
        savedState["input"] = value
        _cameFromDeepLink.value = false
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
            val newLookup = runCatching {
                when (lang) {
                    Language.JAPANESE -> {
                        val entries = lookup(token)
                        EntryLookup(loading = false, entries = entries)
                    }
                    Language.CHINESE_SIMPLIFIED,
                    Language.CHINESE_TRADITIONAL -> {
                        when (val outcome = chineseLookup.lookupWithFallback(token)) {
                            is ChineseLookupOutcome.Direct ->
                                EntryLookup(loading = false, entries = outcome.entries)
                            is ChineseLookupOutcome.FallbackSplit ->
                                EntryLookup(
                                    loading = false,
                                    isFallbackSplit = true,
                                    subUnits = outcome.subUnits,
                                )
                        }
                    }
                }
            }.getOrElse { EntryLookup(loading = false, error = it.message) }

            // Grouped idiom: look up each component word so the card can show
            // them as sub-cards below the idiom (腹 / が / 立つ under 腹が立つ).
            val componentEntries =
                if (lang == Language.JAPANESE && token.isIdiomGroup) {
                    token.components.map { comp ->
                        val raw = runCatching { lookup(comp) }.getOrDefault(emptyList())
                        // Prefer the homograph entry whose reading matches the
                        // component's contextual reading (生 in 高校生 is せい,
                        // not the more-common なま), so the right entry shows first.
                        val ordered = raw.sortedByDescending {
                            it.primaryReading == comp.dictionaryReading ||
                                it.primaryReading == comp.reading
                        }
                        ComponentEntries(comp, ordered)
                    }
                } else emptyList()

            // Run for any entry with at least one sense: even single-meaning
            // words get a context-aware gloss (conjugation/grammar matters), the
            // difference being there's no sense to "select". Both AI calls are
            // gated on the privacy opt-out — when off, nothing leaves the device.
            val aiOn = aiEnabled.value
            val ctxEntries = newLookup.entries
            val ctxEligible =
                aiOn && ctxEntries != null && ctxEntries.sumOf { it.senses.size } >= 1

            // In-expression meanings for the parts (idiom or decomposed
            // compound), fetched once per expression — gated on the AI opt-out.
            val partsEligible = aiOn && componentEntries.isNotEmpty()

            _state.value = _state.value.copy(
                entries = _state.value.entries + (index to newLookup.copy(
                    inContextLoading = ctxEligible,
                    componentEntries = componentEntries,
                    inExpressionLoading = partsEligible,
                    compoundMeaningResult = if (newLookup.isFallbackSplit && !aiOn) {
                        MeaningResult.AiDisabled
                    } else {
                        newLookup.compoundMeaningResult
                    },
                )),
            )

            // Fetch the per-part in-expression meanings + matched sense (separate
            // Haiku call, cached per expression). Send each part's displayed
            // entry's senses so the model can pick which one to highlight.
            if (partsEligible) {
                launch {
                    val partInputs = componentEntries.map { comp ->
                        val senses = comp.entries.firstOrNull()?.senses?.map { s ->
                            s.glosses.joinToString("; ") { it.text }
                        } ?: emptyList()
                        com.tangotori.app.data.context.PartInput(
                            word = comp.token.dictionaryForm,
                            reading = comp.token.dictionaryReading,
                            senses = senses,
                        )
                    }
                    val results = expressionParts.define(token.dictionaryForm, partInputs)
                    val current = _state.value.entries[index] ?: return@launch
                    val updated = if (results != null) {
                        current.componentEntries.mapIndexed { ci, comp ->
                            val r = results.getOrNull(ci)
                            comp.copy(
                                meaning = r?.meaning?.takeIf { it.isNotBlank() },
                                senseIndex = r?.senseIndex,
                            )
                        }
                    } else current.componentEntries
                    _state.value = _state.value.copy(
                        entries = _state.value.entries + (index to current.copy(
                            componentEntries = updated,
                            inExpressionLoading = false,
                        )),
                    )
                }
            }
            // Fetch compound meaning asynchronously after the card is visible.
            if (newLookup.isFallbackSplit && aiOn) {
                launch {
                    val result = compoundMeaningRepo.fetchMeaning(token.dictionaryForm, newLookup.subUnits)
                    val current = _state.value.entries[index] ?: return@launch
                    _state.value = _state.value.copy(
                        entries = _state.value.entries + (index to current.copy(
                            compoundMeaningResult = result,
                        )),
                    )
                }
            }
            // Pick the most relevant sense for the sentence (separate Haiku call).
            if (ctxEligible && ctxEntries != null) {
                val sentence = _state.value.input
                val langCode = if (lang == Language.JAPANESE) "ja" else "zh"
                launch {
                    val result = senseDisambiguation.disambiguate(
                        language = langCode,
                        word = token.surface,
                        sentence = sentence,
                        entries = ctxEntries,
                    )
                    val current = _state.value.entries[index] ?: return@launch
                    val inCtx = (result as? DisambiguationResult.Success)?.let {
                        InContextSense(it.entryIndex, it.senseIndex, it.meaning)
                    }
                    _state.value = _state.value.copy(
                        entries = _state.value.entries + (index to current.copy(
                            inContextLoading = false,
                            inContext = inCtx,
                            inContextLimitReached =
                                (result as? DisambiguationResult.LimitReached)
                                    ?.showMessage == true,
                        )),
                    )
                }
            }
        }
    }

    fun startEditing() {
        _cameFromDeepLink.value = false
        _state.value = _state.value.copy(isEditing = true, selectedIndex = null)
    }

    fun finishEditing() {
        if (_state.value.input.isBlank()) return
        val text = _state.value.input
        _state.value = _state.value.copy(isEditing = false)
        viewModelScope.launch { historyRepo.addSentence(text) }
    }

    /**
     * Sets the manual language override (null = auto-detect) and immediately
     * re-tokenizes. Uses a direct coroutine launch rather than poking inputFlow
     * because MutableStateFlow deduplicates equal values and would silently
     * drop the re-tokenization request when the input text hasn't changed.
     */
    fun setLanguageOverride(override: Language?) {
        savedState["languageOverride"] = override?.name
        viewModelScope.launch { appPrefs.setLanguageOverride(override) }
        val detected = LanguageDetector.detect(_state.value.input)
        val effective = override ?: detected
        val text = _state.value.input
        lastTokenized = null
        _state.value = _state.value.copy(
            languageOverride = override,
            language = effective,
            tokens = emptyList(),
            selectedIndex = null,
            entries = emptyMap(),
            addedDecks = emptyMap(),
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
            lastTokenized = text to effective
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
            _state.value = _state.value.copy(submittingEntryId = entry.id)
            val lang = _state.value.language
            val isChinese = lang != Language.JAPANESE
            val sentenceTokens = _state.value.tokens.map { t ->
                val entryId = when {
                    !isLinkableToken(t) -> null
                    // Use the card's own entry.id for the target token so that
                    // compound words (whose synthetic id has no DB row) are still
                    // highlighted correctly in the sentence.
                    t.dictionaryForm == entry.headword -> entry.id
                    else -> runCatching {
                        when (lang) {
                            Language.JAPANESE -> lookup(t).firstOrNull()?.id
                            else -> chineseLookup(t).firstOrNull()?.id
                        }
                    }.getOrNull()
                }
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
            // Both a fresh add and a duplicate mean the card is now in the deck,
            // so either way mark it added (button → "Already added to <deck>").
            val addedDeckName = when (result) {
                is AnkiCardRepository.AddResult.Success -> result.deckName
                AnkiCardRepository.AddResult.Duplicate -> deckName
                is AnkiCardRepository.AddResult.Failed -> null
            }
            _state.value = _state.value.copy(
                submittingEntryId = null,
                addedDecks = if (addedDeckName != null) {
                    _state.value.addedDecks + (entry.id to addedDeckName)
                } else {
                    _state.value.addedDecks
                },
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
        viewModelScope.launch { appPrefs.setLinkToKanjiStudy(next) }
    }

    private fun isLinkableToken(t: Token): Boolean =
        t.partOfSpeech != PartOfSpeech.PUNCTUATION
}
