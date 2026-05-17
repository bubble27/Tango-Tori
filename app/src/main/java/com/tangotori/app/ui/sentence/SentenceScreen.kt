package com.tangotori.app.ui.sentence

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.KanjiTile
import com.tangotori.app.ui.components.TokenizedSentenceView
import androidx.compose.foundation.isSystemInDarkTheme
import com.tangotori.app.ui.theme.BodyTintDark
import com.tangotori.app.ui.theme.BodyTintLight
import com.tangotori.app.ui.theme.HeaderTintLight

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

    // Scroll-to-item only fires from explicit chip taps (see onChipTap below).
    // We deliberately do NOT auto-scroll on every selectedIndex change — that
    // caused the list to "snap back" to the previously focused word whenever
    // scroll-flow re-emitted after settling.
    // Chip tap is now a pure "set selection" — the auto-scroll-on-selection
    // effect inside WordList (keyed on state.selectedIndex) does the gliding,
    // so chip taps, row taps, and post-release picks all share the same
    // landing animation.
    fun onChipTap(absIdx: Int) {
        viewModel.onTokenSelected(absIdx)
    }

    Scaffold(
        // No top bar — the parchment surface runs edge to edge for a quieter
        // dictionary feel. Status bar is restyled in themes.xml to match.
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
                    onPasteSentence = viewModel::loadSentence,
                )
            } else {
                ViewingLayout(
                    state = state,
                    listItems = listItems,
                    listState = listState,
                    onTokenSelected = viewModel::onTokenSelected,
                    onChipTapped = ::onChipTap,
                    onStartEditing = viewModel::startEditing,
                    onAddToDefaultDeck = { t, e ->
                        ensurePermissionThen(PendingCardAction.AddDefault(t, e))
                    },
                    onChooseDeck = { t, e ->
                        ensurePermissionThen(PendingCardAction.OpenPicker(t, e))
                    },
                    loadKanjiTiles = viewModel::loadKanjiBreakdown,
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
    onPasteSentence: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
        // Paste button — reads the most recent clipboard item and routes it
        // through the same flow as the share intent (overwrites the field +
        // jumps straight to chip view).
        Button(
            onClick = {
                val text = clipboard.getText()?.text?.trim().orEmpty()
                if (text.isNotEmpty()) onPasteSentence(text)
            },
        ) {
            Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Paste from clipboard")
        }
        Spacer(Modifier.height(10.dp))
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
    /** Called when a row in the WORD LIST is tapped — selects only, no scroll. */
    onTokenSelected: (Int) -> Unit,
    /** Called when a CHIP at the top is tapped — selects AND scrolls the list. */
    onChipTapped: (Int) -> Unit,
    onStartEditing: () -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiTiles: suspend (DictEntry) -> List<KanjiTile>,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Double-tap-only detector. combinedClickable here intercepted
                // EVERY pointer down (it has to, in case the second tap arrives
                // within the double-click window) and intermittently won the
                // race against the inner WordChip's combinedClickable, eating
                // single taps. detectTapGestures with only onDoubleTap doesn't
                // claim single taps, so chip clicks pass through reliably.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onStartEditing() },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            TokenizedSentenceView(
                tokens = state.tokens,
                selectedIndex = state.selectedIndex,
                onTokenClick = onChipTapped,
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
                loadKanjiTiles = loadKanjiTiles,
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
    loadKanjiTiles: suspend (DictEntry) -> List<KanjiTile>,
    listState: LazyListState,
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    // Finger-on-screen flag. Used to gate scroll-mode entry so that the
    // post-release snap animation (programmatic scroll, no finger) can't
    // re-arm scroll mode and shuffle the selection mid-animation.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val currentSelected = rememberUpdatedState(selectedAbsIndex)

    // ──────────────────────── Scroll-mode state machine ────────────────────────
    // Three interaction phases:
    //  1. **Settled / unfurled**:    user not scrolling. The selected card is
    //     fully visible and unfurled. `scrollMode = false`.
    //  2. **Browsing**:               user has scrolled the selected card off
    //     either viewport edge. We furl it and switch into wheel-picker mode:
    //     all rows render compact; focus snaps to whichever row the anchor
    //     line is over; haptic ticks on every change. `scrollMode = true`.
    //  3. **Release**:                user lifts finger and fling settles.
    //     `scrollMode` flips back off; the now-focused card slides into
    //     position and unfurls.
    var scrollMode by remember { mutableStateOf(false) }

    // Tracks the most recent scroll delta sign so we can pick the right
    // "adjacent" word when the user first enters scroll mode (next vs. prev).
    var lastScrollDelta by remember { mutableStateOf(0f) }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, off) ->
            // Approximate signed pixel delta. Positive = content moved up
            // (i.e. user scrolled down through the sentence).
            val delta = when {
                idx > prevIndex -> 1f
                idx < prevIndex -> -1f
                else -> (off - prevOffset).toFloat()
            }
            if (delta != 0f) lastScrollDelta = delta
            prevIndex = idx
            prevOffset = off
        }
    }

    // Enter scroll mode only once the selected card has been **fully scrolled
    // past** one of the viewport edges. The earlier rule ("any edge crossed")
    // misfired on cards taller than the viewport — those always extend past
    // both edges, so they'd flip to scroll mode the instant the user started
    // dragging and never let the user reach their own bottom buttons.
    //
    // Past-edge means: card's BOTTOM is above viewport top (scrolled fully
    // up past) OR card's TOP is below viewport bottom (scrolled fully down
    // past). Anything in between is "still partially visible" and the card
    // stays unfurled so the user can pan around inside it.
    //
    // Still gated on isDragged so the programmatic post-release snap can't
    // re-arm scroll mode mid-animation.
    LaunchedEffect(listState, items) {
        snapshotFlow {
            if (!isDragged) return@snapshotFlow false
            val info = listState.layoutInfo
            val sel = currentSelected.value ?: return@snapshotFlow false
            val item = info.visibleItemsInfo.firstOrNull { row ->
                items.getOrNull(row.index)?.index == sel
            } ?: return@snapshotFlow true   // not in visible list → already past
            // Past-up: bottom edge above viewport top.
            // Past-down: top edge below viewport bottom.
            item.offset + item.size <= info.viewportStartOffset ||
                item.offset >= info.viewportEndOffset
        }.collect { crossed ->
            if (crossed && !scrollMode) {
                scrollMode = true
                // On entry, jump focus to the IMMEDIATELY-NEXT word in the
                // scroll direction (not whichever happens to be at the anchor
                // line) — matches the user's "start at the card directly
                // after the active one" requirement.
                val sel = currentSelected.value ?: return@collect
                val selFiltered = items.indexOfFirst { it.index == sel }
                if (selFiltered < 0) return@collect
                val step = if (lastScrollDelta >= 0f) 1 else -1
                val nextFiltered = (selFiltered + step)
                    .coerceIn(0, items.lastIndex)
                items.getOrNull(nextFiltered)?.let { next ->
                    if (next.index != sel) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTokenSelected(next.index)
                    }
                }
            }
        }
    }

    // While in scroll mode AND the finger is still on screen, focus follows
    // whichever row sits nearest the anchor line. Gated on isDragged (rather
    // than isScrolling) so the fling that follows finger lift doesn't continue
    // to reshuffle the selection — once the user releases, the focus locks to
    // whichever row was under the anchor at that exact moment. Haptic tick per
    // change.
    LaunchedEffect(listState, items) {
        snapshotFlow {
            if (!scrollMode || !isDragged) return@snapshotFlow null
            val info = listState.layoutInfo
            val anchor = info.viewportStartOffset +
                // Anchor sits a small inset below viewport top — keeps a sliver of the
            // previous item visible (so the user has scroll context) while
            // putting the active card's title close to the sentence header.
            12
            info.visibleItemsInfo.minByOrNull { row ->
                kotlin.math.abs(row.offset - anchor)
            }?.let { items.getOrNull(it.index)?.index }
        }
            .distinctUntilChanged()
            .collect { absIdx ->
                if (absIdx == null) return@collect
                if (absIdx != currentSelected.value) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTokenSelected(absIdx)
                }
            }
    }

    // Release / settle: flip scroll mode off, then slide the focused card so
    // its TOP sits at the anchor line. `snapped` is a per-scroll-cycle guard
    // so the programmatic snap (which itself toggles isScrollInProgress) can
    // never trigger a second snap.
    //
    // Important: the snap only fires when this gesture actually entered
    // scroll-mode. If the user just panned an open card around inside the
    // viewport (e.g. scrolling within a tall card's body to reach the
    // Add-to-deck button), releasing should NOT yank the card back to the
    // anchor — that would feel like the card is fighting the user.
    LaunchedEffect(listState, items) {
        var snapped = false
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) {
                    // A new scroll started — could be a user drag/fling OR the
                    // snap we kicked off. Either way, reset the guard so the
                    // *next* genuine settle can snap again.
                    snapped = false
                    return@collect
                }
                if (snapped) return@collect
                snapped = true
                // Capture BEFORE flipping — the snap decision is based on
                // whether we were in scroll-mode at the moment of release.
                val wasInScrollMode = scrollMode
                if (scrollMode) scrollMode = false
                if (!wasInScrollMode) return@collect
                val sel = currentSelected.value ?: return@collect
                val info = listState.layoutInfo
                if (info.visibleItemsInfo.isEmpty()) return@collect
                val anchor = info.viewportStartOffset +
                    // Anchor sits a small inset below viewport top — keeps a sliver of the
            // previous item visible (so the user has scroll context) while
            // putting the active card's title close to the sentence header.
            12
                val item = info.visibleItemsInfo.firstOrNull { row ->
                    items.getOrNull(row.index)?.index == sel
                }
                if (item != null) {
                    val delta = (item.offset - anchor).toFloat()
                    if (kotlin.math.abs(delta) > 2f) {
                        coroutineScope.launch {
                            listState.animateScrollBy(
                                value = delta,
                                animationSpec = tween(
                                    durationMillis = 280,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        }
                    }
                } else {
                    // Selected scrolled fully off — bring it back into view at
                    // the anchor line.
                    val filteredIdx = items.indexOfFirst { it.index == sel }
                    if (filteredIdx >= 0) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(
                                index = filteredIdx,
                                scrollOffset = -(anchor - info.viewportStartOffset),
                            )
                        }
                    }
                }
            }
    }

    // Auto-scroll & scroll-mode reset on manual selection.
    //
    //   - When `selectedAbsIndex` changes AND it didn't come from a drag
    //     (i.e. the user tapped a chip or a row), force-exit scroll mode so
    //     the body actually unfurls — without this, a chip tap that lands
    //     during a fling-settle window leaves `scrollMode = true` and the
    //     card never opens (the "tapping a word does nothing" bug).
    //   - Then glide the selected card so its top sits just below the
    //     sentence header, body maximally visible.
    LaunchedEffect(selectedAbsIndex) {
        if (selectedAbsIndex == null) return@LaunchedEffect
        if (isDragged) return@LaunchedEffect
        if (scrollMode) scrollMode = false
        val filteredIdx = items.indexOfFirst { it.index == selectedAbsIndex }
        if (filteredIdx < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        if (info.viewportSize.height == 0) {
            listState.scrollToItem(filteredIdx)
            return@LaunchedEffect
        }
        val item = info.visibleItemsInfo.firstOrNull { row ->
            items.getOrNull(row.index)?.index == selectedAbsIndex
        }
        if (item != null) {
            val delta = (item.offset - info.viewportStartOffset - 12).toFloat()
            if (kotlin.math.abs(delta) > 2f) {
                listState.animateScrollBy(
                    value = delta,
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            listState.animateScrollToItem(filteredIdx, scrollOffset = -12)
        }
    }

    // Body unfurls only when we're NOT in scroll mode and the JMdict lookup
    // has come back. Loading is signalled by the in-header spinner.
    val expandedAbsIndex = selectedAbsIndex
        ?.takeIf { !scrollMode }
        ?.takeIf { entries[it]?.entries != null }
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The activated card docks just below the sentence header (anchor =
        // viewport top + tiny inset), with the body filling the rest of the
        // screen. Bottom padding is most of the viewport so the LAST item can
        // also scroll up to the anchor — without that, words near the end of
        // a sentence can't reach the top of the list.
        val topPad = 8.dp
        val bottomPad = maxHeight - 96.dp
        LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
    ) {
        itemsIndexed(
            items = items,
            key = { _, indexed -> indexed.index },
        ) { listIndex, indexed ->
            val absIdx = indexed.index
            val token = indexed.value
            val isFocused = absIdx == selectedAbsIndex
            val isExpanded = absIdx == expandedAbsIndex
            WordListItem(
                token = token,
                listIndex = listIndex,
                enterProgress = enterProgressState,
                animationFinished = animationFinished,
                focused = isFocused,
                expanded = isExpanded,
                // Lookup is needed for badges (when focused, in the colored
                // header) AND for the body (when expanded).
                lookup = if (isFocused || isExpanded) entries[absIdx] else null,
                defaultDeckName = defaultDeckName,
                isSubmittingCard = isSubmittingCard,
                onClick = { onTokenSelected(absIdx) },
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                loadKanjiTiles = loadKanjiTiles,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
    }
    } // end BoxWithConstraints
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
    /** True when this row is the active token — drives the red header card. */
    focused: Boolean,
    /** True when this row's body (definition + buttons) is currently unfurled.
     *  Always implies [focused]; can also lag behind [focused] briefly during
     *  scroll so the body only renders after scrolling settles. */
    expanded: Boolean,
    lookup: EntryLookup?,
    defaultDeckName: String?,
    isSubmittingCard: Boolean,
    onClick: () -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiTiles: suspend (DictEntry) -> List<KanjiTile>,
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

    // Suppress the kana reading when the word has no kanji at all — pure
    // katakana (スタジオ → すたじお), pure hiragana (に → に), and the long-
    // vowel mark all count as "their own reading". Nothing to add by
    // reprinting the kana form on a separate line.
    val hasKanji = token.dictionaryForm.any { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }
    val readingText = token.dictionaryReading.takeIf {
        it.isNotBlank() && it != token.dictionaryForm && hasKanji
    }

    // Header card visuals key off [focused] so the red card follows the
    // user's scroll instantly. Font size also bumps when focused so the
    // active word reads as the page header. The body underneath is gated
    // separately by [expanded] (commits after scrolling settles).
    val animatedTitleSize by animateFloatAsState(
        targetValue = if (focused) 30f else titleSize.value,
        label = "wordListTitleSize",
    )
    val animatedReadingSize by animateFloatAsState(
        targetValue = if (focused) 16f else readingSize.value,
        label = "wordListReadingSize",
    )
    val headerBgColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "wordListHeaderBg",
    )
    val headerTextColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        label = "wordListHeaderText",
    )
    val headerEntry = if (focused) lookup?.entries?.firstOrNull() else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(entranceModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = if (isFunctionWord) 4.dp else 6.dp),
    ) {
        // Header shape: when the body is unfurled, drop the bottom corners
        // so the red bar reads as a continuous header attached to the beige
        // body below. When collapsed, it's a free-floating pill.
        val headerShape = if (focused && expanded) {
            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        } else {
            RoundedCornerShape(12.dp)
        }
        Surface(
            color = headerBgColor,
            shape = headerShape,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
        ) {
            // Outer row layout:
            //   [donut (fixed slot, only on focus+loading)]
            //   [word + reading — weight(1f), fills all remaining space]
            //   [badge stack — natural width, vertically centered]
            //
            // The donut is a fixed-size leading slot rather than inline before
            // the headword so it doesn't push the text right when it appears /
            // disappears mid-lookup. Badges are stacked vertically in a
            // narrow column (see BadgeRow → Column) so longer words like
            // トレーニング no longer wrap.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = if (focused) 14.dp else 8.dp,
                    vertical = if (focused) 12.dp else 4.dp,
                ),
            ) {
                if (focused && lookup?.loading == true) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = headerTextColor.copy(alpha = 0.78f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = token.dictionaryForm,
                        color = headerTextColor.copy(alpha = alpha),
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = animatedTitleSize.sp,
                    )
                    if (readingText != null) {
                        Spacer(Modifier.width(if (focused) 10.dp else 6.dp))
                        Text(
                            text = readingText,
                            color = headerTextColor.copy(alpha = alpha * 0.78f),
                            fontSize = animatedReadingSize.sp,
                            modifier = Modifier.padding(bottom = if (focused) 6.dp else 0.dp),
                        )
                    }
                }
                if (headerEntry != null) {
                    Spacer(Modifier.width(10.dp))
                    BadgeRow(headerEntry)
                }
            }
        }
        // Body sits in a beige Surface attached to the red header — flat top
        // corners (continuous with the header) + rounded bottom. Slightly
        // beiger than the page bg so the dictionary card reads as a distinct
        // panel without breaking the warm palette.
        val bodyShape = RoundedCornerShape(
            topStart = 0.dp, topEnd = 0.dp,
            bottomStart = 12.dp, bottomEnd = 12.dp,
        )
        val bodyReady = lookup != null && !lookup.loading
        // Exit is intentionally [ExitTransition.None]: when scroll-mode
        // furls the active card, the body must vanish on the *same* frame
        // so the wheel-picker layout settles instantly. An animated shrink
        // here would race the user's drag and make the list jump under
        // their finger. Enter is still animated for the smooth unfurl on
        // release.
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded && bodyReady,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = tween(durationMillis = 260),
                expandFrom = Alignment.Top,
            ) + androidx.compose.animation.fadeIn(animationSpec = tween(220)),
            exit = androidx.compose.animation.ExitTransition.None,
        ) {
            Surface(
                // Dark-mode parity: the body tint is parchment-deeper in light
                // mode and a near-black in dark mode (BodyTintDark). Reading
                // isSystemInDarkTheme directly here keeps the tint pinned to
                // the system setting without threading it through the theme.
                color = if (isSystemInDarkTheme()) BodyTintDark else BodyTintLight,
                shape = bodyShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ExpandedEntryCard(
                    token = token,
                    lookup = lookup,
                    defaultDeckName = defaultDeckName,
                    isSubmitting = isSubmittingCard,
                    onAddToDefaultDeck = onAddToDefaultDeck,
                    onChooseDeck = onChooseDeck,
                    loadKanjiBreakdown = loadKanjiTiles,
                )
            }
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
