package com.tangotori.app.ui.sentence

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.ui.components.TokenizedSentenceView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceScreen(
    viewModel: SentenceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Permission flow for AnkiDroid's READ_WRITE_DATABASE — needed both for
    // deck listing and for addNote. Resume the pending action on grant.
    var pendingCardAction by remember { mutableStateOf<PendingCardAction?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (val action = pendingCardAction) {
                is PendingCardAction.AddDefault ->
                    viewModel.addToDefaultDeck(action.token, action.entry)
                is PendingCardAction.OpenPicker ->
                    viewModel.openDeckPicker(action.token, action.entry)
                null -> {}
            }
        }
        pendingCardAction = null
    }
    fun ensurePermissionThen(action: PendingCardAction) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, ANKIDROID_PERMISSION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            when (action) {
                is PendingCardAction.AddDefault ->
                    viewModel.addToDefaultDeck(action.token, action.entry)
                is PendingCardAction.OpenPicker ->
                    viewModel.openDeckPicker(action.token, action.entry)
            }
        } else {
            pendingCardAction = action
            permissionLauncher.launch(ANKIDROID_PERMISSION)
        }
    }

    // Surface card-creation results as snackbars; pop the result once shown.
    LaunchedEffect(state.cardResult) {
        val r = state.cardResult ?: return@LaunchedEffect
        when (r) {
            is CardSubmitResult.Success -> snackbarHost.showSnackbar("Card added to ${r.deckName} ✓")
            CardSubmitResult.Duplicate -> snackbarHost.showSnackbar("Already in your deck (duplicate)")
            is CardSubmitResult.Failed -> snackbarHost.showSnackbar("Couldn't add card: ${r.reason}")
            CardSubmitResult.AnkiMissing -> { /* dialog renders separately */ }
        }
        if (r !is CardSubmitResult.AnkiMissing) viewModel.clearCardResult()
    }

    val listItems = remember(state.tokens) {
        state.tokens.withIndex().filter { it.value.partOfSpeech != PartOfSpeech.PUNCTUATION }
    }
    val absToFiltered = remember(listItems) {
        listItems.mapIndexed { filteredIdx, (absIdx, _) -> absIdx to filteredIdx }.toMap()
    }

    LaunchedEffect(state.selectedIndex) {
        val abs = state.selectedIndex ?: return@LaunchedEffect
        val filteredIdx = absToFiltered[abs] ?: return@LaunchedEffect
        if (filteredIdx < listState.layoutInfo.totalItemsCount) {
            listState.animateScrollToItem(filteredIdx)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tango Tori") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
        // Crossfade between the two modes. Edit → view is intentionally
        // snap-short — the chips' own staggered enter animation carries the
        // visual transition.
        AnimatedContent(
            targetState = state.isEditing,
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            transitionSpec = {
                if (targetState) {
                    (fadeIn(animationSpec = tween(180, delayMillis = 60)) togetherWith
                        fadeOut(animationSpec = tween(140)))
                } else {
                    (fadeIn(animationSpec = tween(40)) togetherWith
                        fadeOut(animationSpec = tween(110)))
                }
            },
            label = "sentenceMode",
        ) { editing ->
            if (editing) {
                EditingLayout(
                    input = state.input,
                    onInputChange = viewModel::onInputChange,
                    onFinishEditing = viewModel::finishEditing,
                )
            } else {
                ViewingLayout(
                    state = state,
                    listItems = listItems,
                    listState = listState,
                    onTokenSelected = viewModel::onTokenSelected,
                    onStartEditing = viewModel::startEditing,
                    onAddToDefaultDeck = { t, e ->
                        ensurePermissionThen(PendingCardAction.AddDefault(t, e))
                    },
                    onChooseDeck = { t, e ->
                        ensurePermissionThen(PendingCardAction.OpenPicker(t, e))
                    },
                )
            }
        }
    }

    state.deckPicker?.let { picker ->
        DeckPickerDialog(
            state = picker,
            currentDefaultId = state.defaultDeck?.id,
            onDismiss = viewModel::dismissDeckPicker,
            onSelect = viewModel::onDeckChosen,
        )
    }
    if (state.cardResult is CardSubmitResult.AnkiMissing) {
        AnkiNotInstalledDialog(
            onDismiss = viewModel::clearCardResult,
            onOpenPlayStore = {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(ANKIDROID_PLAY_STORE_URL),
                )
                context.startActivity(intent)
                viewModel.clearCardResult()
            },
        )
    }
}

private const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
private const val ANKIDROID_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.ichi2.anki"

/** Card-creation action queued while we wait for the AnkiDroid permission. */
private sealed interface PendingCardAction {
    val token: Token
    val entry: DictEntry
    data class AddDefault(override val token: Token, override val entry: DictEntry) : PendingCardAction
    data class OpenPicker(override val token: Token, override val entry: DictEntry) : PendingCardAction
}

/**
 * Edit mode layout: the input field is vertically centered on screen, with a
 * "Finish editing" button below it. Word list and chip view are not shown
 * — this state is pure text entry.
 */
@Composable
private fun EditingLayout(
    input: String,
    onInputChange: (String) -> Unit,
    onFinishEditing: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (input.isEmpty()) {
                    Text(
                        "Paste a Japanese sentence",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 20.sp,
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onFinishEditing,
            enabled = input.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Finish editing")
        }
    }
}

/**
 * Viewing-mode layout: tokenized chip view at the top, word list below.
 * Double-tap anywhere on the chip area (chips or empty space) returns to
 * edit mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewingLayout(
    state: SentenceUiState,
    listItems: List<IndexedValue<Token>>,
    listState: LazyListState,
    onTokenSelected: (Int) -> Unit,
    onStartEditing: () -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onDoubleClick = onStartEditing,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            TokenizedSentenceView(
                tokens = state.tokens,
                selectedIndex = state.selectedIndex,
                onTokenClick = onTokenSelected,
                onTokenDoubleClick = onStartEditing,
            )
        }
        if (state.isTokenizing) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
        if (state.tokens.isNotEmpty()) {
            HorizontalDivider()
            WordList(
                items = listItems,
                selectedAbsIndex = state.selectedIndex,
                entries = state.entries,
                defaultDeckName = state.defaultDeck?.name,
                isSubmittingCard = state.isSubmittingCard,
                onTokenSelected = onTokenSelected,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                listState = listState,
            )
        } else if (state.input.isNotBlank() && !state.isTokenizing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    state.error ?: "Waiting for tokenizer…",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun WordList(
    items: List<IndexedValue<Token>>,
    selectedAbsIndex: Int?,
    entries: Map<Int, EntryLookup>,
    defaultDeckName: String?,
    isSubmittingCard: Boolean,
    onTokenSelected: (Int) -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    listState: LazyListState,
) {
    // Top-down staggered fade-in. Driven by a single Animatable in the parent;
    // each row reads State<Float> inside a graphicsLayer block (re-evaluates
    // on draw without recomposing). Once the entrance completes we flip
    // animationFinished, and rows drop the graphicsLayer modifier entirely so
    // scrolling has no per-row hardware-layer cost.
    val enterProgress = remember { Animatable(0f) }
    val enterProgressState = enterProgress.asState()
    var animationFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 520),
        )
        animationFinished = true
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, indexed -> indexed.index },
        ) { listIndex, indexed ->
            val absIdx = indexed.index
            val token = indexed.value
            val isExpanded = absIdx == selectedAbsIndex
            WordListItem(
                token = token,
                listIndex = listIndex,
                enterProgress = enterProgressState,
                animationFinished = animationFinished,
                expanded = isExpanded,
                lookup = if (isExpanded) entries[absIdx] else null,
                defaultDeckName = defaultDeckName,
                isSubmittingCard = isSubmittingCard,
                onClick = { onTokenSelected(absIdx) },
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
    }
}

/**
 * Per-item stagger: row N starts fading [PerRowStaggerFraction] of the total
 * after row N-1, and fades over [PerRowFadeFraction] of the total. Rows beyond
 * [MaxStaggeredRows] share the same delay cap so the entrance still finishes
 * inside ~520 ms even on long sentences; off-screen rows are at alpha=1 by the
 * time the user scrolls to them.
 */
private const val PerRowStaggerFraction = 0.06f
private const val PerRowFadeFraction = 0.4f
private const val MaxStaggeredRows = 12

@Composable
private fun WordListItem(
    token: Token,
    listIndex: Int,
    enterProgress: State<Float>,
    animationFinished: Boolean,
    expanded: Boolean,
    lookup: EntryLookup?,
    defaultDeckName: String?,
    isSubmittingCard: Boolean,
    onClick: () -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
) {
    val isFunctionWord = token.partOfSpeech == PartOfSpeech.PARTICLE ||
            token.partOfSpeech == PartOfSpeech.AUXILIARY_VERB
    val alpha = if (isFunctionWord) 0.55f else 1f
    val titleSize = if (isFunctionWord) 14.sp else 16.sp
    val readingSize = if (isFunctionWord) 11.sp else 12.sp

    val staggerStart = remember(listIndex) {
        (minOf(listIndex, MaxStaggeredRows) * PerRowStaggerFraction)
            .coerceAtMost(1f - PerRowFadeFraction)
    }

    // Drop the graphicsLayer entirely post-animation so scroll is layer-free.
    val entranceModifier = if (animationFinished) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            val p = enterProgress.value
            val local = ((p - staggerStart) / PerRowFadeFraction).coerceIn(0f, 1f)
            this.alpha = local
            this.translationY = (1f - local) * 6.dp.toPx()
        }
    }

    // Ripple-less clickable so each row doesn't carry an indication layer.
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(entranceModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = if (isFunctionWord) 4.dp else 8.dp),
    ) {
        Text(
            text = token.dictionaryForm,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = titleSize,
        )
        // Suppress the kana reading when the surface IS its own kana already
        // (pure katakana loanwords like スタジオ → no point showing すたじお).
        val isPureKatakana = token.dictionaryForm.isNotEmpty() &&
                token.dictionaryForm.all { it in '゠'..'ヿ' || it == 'ー' }
        val readingText = token.dictionaryReading.takeIf {
            it.isNotBlank() && it != token.dictionaryForm && !isPureKatakana
        }
        if (readingText != null) {
            Text(
                text = readingText,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.7f),
                fontSize = readingSize,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (expanded) {
            ExpandedEntryCard(
                token = token,
                lookup = lookup,
                defaultDeckName = defaultDeckName,
                isSubmitting = isSubmittingCard,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
            )
        }
    }
}

@Composable
private fun DeckPickerDialog(
    state: DeckPickerState,
    currentDefaultId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long, String, Boolean) -> Unit,
) {
    var setAsDefault by androidx.compose.runtime.saveable.rememberSaveable {
        // Default-checked when no default deck exists yet — most users will
        // want to lock in their first choice. Disabled-but-checked otherwise.
        mutableStateOf(currentDefaultId == null)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose deck") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.loading -> {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Loading decks…", fontSize = 13.sp)
                        }
                    }
                    state.decks.isEmpty() -> Text(
                        "AnkiDroid returned no decks. Make sure AnkiDroid is installed and the permission was granted.",
                        fontSize = 13.sp,
                    )
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            itemsIndexed(state.decks, key = { _, d -> d.id }) { _, deck ->
                                DeckRow(
                                    deck = deck,
                                    isCurrentDefault = deck.id == currentDefaultId,
                                    onClick = { onSelect(deck.id, deck.name, setAsDefault) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = setAsDefault,
                                onCheckedChange = { setAsDefault = it },
                            )
                            Text("Set as default deck", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeckRow(
    deck: DeckOption,
    isCurrentDefault: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = deck.name,
                fontSize = 15.sp,
                fontWeight = if (isCurrentDefault) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (isCurrentDefault) {
                Text(
                    text = "default",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AnkiNotInstalledDialog(onDismiss: () -> Unit, onOpenPlayStore: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AnkiDroid is required") },
        text = {
            Text(
                "Card creation uses AnkiDroid. Install it from the Play Store to continue.",
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            Button(onClick = onOpenPlayStore) { Text("Open Play Store") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
