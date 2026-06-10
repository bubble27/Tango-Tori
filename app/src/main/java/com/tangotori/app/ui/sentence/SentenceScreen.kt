package com.tangotori.app.ui.sentence

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlinx.coroutines.yield
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Language
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.KanjiTile
import com.tangotori.app.ui.components.TokenizedSentenceView
import androidx.compose.foundation.isSystemInDarkTheme
import com.tangotori.app.ui.theme.BodyTintDark
import com.tangotori.app.ui.theme.BodyTintLight
import com.tangotori.app.ui.theme.HeaderTintLight
import com.tangotori.app.ui.theme.BodyTint
import com.tangotori.app.ui.theme.PosAdverb
import com.tangotori.app.ui.theme.PosAuxiliaryVerb
import com.tangotori.app.ui.theme.PosCompound
import com.tangotori.app.ui.theme.PosIAdjective
import com.tangotori.app.ui.theme.PosNaAdjective
import com.tangotori.app.ui.theme.PosNoun
import com.tangotori.app.ui.theme.PosVerb
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.tangotori.app.R
import com.tangotori.app.ui.theme.LogoRed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceScreen(
    viewModel: SentenceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentSentences by viewModel.recentSentences.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Back button returns to edit mode instead of exiting the app.
    BackHandler(enabled = !state.isEditing) {
        viewModel.startEditing()
    }

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

    // Success and duplicate are now communicated inline by the add button's
    // animation ("Successfully added" → "Already added to <deck>"), so only
    // surface real failures as snackbars.
    LaunchedEffect(state.cardResult) {
        val r = state.cardResult ?: return@LaunchedEffect
        when (r) {
            is CardSubmitResult.Failed -> snackbarHost.showSnackbar("Couldn't add card: ${r.reason}")
            else -> { /* Success/Duplicate: inline button; AnkiMissing: dialog */ }
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
        // Only consume systemBars (status + nav) here; the IME inset is left
        // unconsumed so EditingLayout can apply imePadding() itself and keep
        // the action buttons above the keyboard.
        contentWindowInsets = WindowInsets.systemBars,
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
                    languageOverride = state.languageOverride,
                    recentSentences = recentSentences,
                    onInputChange = viewModel::onInputChange,
                    onFinishEditing = viewModel::finishEditing,
                    onPasteSentence = viewModel::loadSentence,
                    onSetLanguage = viewModel::setLanguageOverride,
                    onHistoryTap = viewModel::loadSentence,
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
                    onToggleLinkStyle = viewModel::toggleLinkStyle,
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
 * Edit mode: inline logo header, plain text field, scrollable colored history
 * cards (hide when keyboard is up), sliding language tab bar. No buttons —
 * the keyboard's Send action submits; tapping a history card reloads it.
 */
@Composable
private fun EditingLayout(
    input: String,
    languageOverride: Language?,
    recentSentences: List<String>,
    onInputChange: (String) -> Unit,
    onFinishEditing: () -> Unit,
    onPasteSentence: (String) -> Unit,
    onSetLanguage: (Language?) -> Unit,
    onHistoryTap: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var isTyping by remember { mutableStateOf(false) }

    val clipboardText = LocalClipboardManager.current.getText()?.text?.trim().orEmpty()
    val showPasteHint = isTyping && input.isEmpty() && clipboardText.isNotEmpty()

    // Measured height of the header (logo + type area) so the card stack can be
    // inset to start just below it — yet still scroll up and tuck cleanly behind
    // the header's opaque, round-bottomed overlay.
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableStateOf(0) }
    val headerHeightDp = with(density) { headerHeightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ── BackHandler: back key clears focus (dismisses keyboard + typing mode)
        BackHandler(enabled = isTyping) {
            focusManager.clearFocus()
        }

        // ── History cards: full-height scrollable layer behind the header ────
        AnimatedContent(
            targetState = !isTyping && recentSentences.isNotEmpty(),
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(150))
            },
            label = "editMiddle",
        ) { showHistory ->
            if (showHistory) {
                HistoryCardList(
                    sentences = recentSentences,
                    topInset = headerHeightDp + 16.dp,
                    onTap = onHistoryTap,
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }

        // ── Header overlay: logo + type area. Opaque page-colored background
        //    with rounded bottom corners so cards slide cleanly behind it. ────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                ),
        ) {
            // ── Logo row: bird on left, title inline ─────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
            ) {
                // ic_bird_logo has a drawable-night-xxxhdpi variant (inverted
                // bird) that Android auto-selects in dark mode.
                Image(
                    painter = painterResource(R.drawable.ic_bird_logo),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Tango Tori",
                    color = LogoRed,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Plain text input — no box, tapping anywhere focuses ──────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusRequester.requestFocus() }
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            ) {
                // Placeholder layers (rendered behind the text field in z-order).
                // Text/Row don't consume tap events so touches fall through to
                // BasicTextField, which gains focus and raises the keyboard.
                if (input.isEmpty()) {
                    if (showPasteHint) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    focusManager.clearFocus()
                                    onPasteSentence(clipboardText)
                                },
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "paste from clipboard",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            )
                        }
                    } else if (!isTyping) {
                        Text(
                            "Tap to start typing…",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                        )
                    }
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        focusManager.clearFocus()
                        if (input.isNotBlank()) onFinishEditing()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { isTyping = it.isFocused },
                )
            }
        }

        // ── Language tab bar — overlaid over the cards, anchored to the bottom.
        LanguageTabBar(
            selected = languageOverride,
            onSelect = onSetLanguage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}

// Subtle accent tints — each card lerps 10% from the neutral card color toward
// one of the app's own part-of-speech colors, so the stack has gentle variation
// without straying from the palette.
private val historyCardAccents = listOf(
    PosNaAdjective,
    PosAdverb,
    PosCompound,
    PosNoun,
    PosIAdjective,
    PosAuxiliaryVerb,
    PosVerb,
)

@Composable
private fun HistoryCardList(
    sentences: List<String>,
    topInset: Dp,
    onTap: (String) -> Unit,
) {
    var exitingIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    // Scrollable column with negative spacing so each card overlaps the one
    // above it, creating a "deck of cards" stack effect. Each card is drawn on
    // top of the previous one (natural declaration order), so each card's title
    // (top-aligned) stays visible while its lower body is tucked under the next.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy((-(CARD_HEIGHT - CARD_PEEK)).dp),
    ) {
        // Inset so the first card starts just below the header overlay, yet can
        // still scroll up and tuck behind it. We add one card-overlap of extra
        // height to cancel the negative arrangement spacing that the Column also
        // applies between this spacer and the first card (otherwise the first
        // card gets pulled up behind the header).
        Spacer(Modifier.height(topInset + (CARD_HEIGHT - CARD_PEEK).dp))
        sentences.forEachIndexed { index, sentence ->
            // Deterministic per-card jitter (derived from the text) so each card
            // keeps a consistent, subtle tilt + scale — like a hand-stacked pile.
            val seed = sentence.hashCode()
            val baseRotation = (((seed % 5) + 5) % 5 - 2) * 0.8f      // -1.6°..1.6°
            val baseScale = 1f - (((seed / 5 % 3) + 3) % 3) * 0.012f  // 1.0 / .988 / .976

            val isTapped = exitingIndex == index
            val isOther = exitingIndex != null && !isTapped

            // Tapped card straightens to the default (no tilt, full scale); the
            // others slide down and fade out before we leave the screen.
            val rotation by animateFloatAsState(
                targetValue = if (isTapped) 0f else baseRotation,
                animationSpec = tween(260),
                label = "cardRotation",
            )
            val scale by animateFloatAsState(
                targetValue = if (isTapped) 1f else baseScale,
                animationSpec = tween(260),
                label = "cardScale",
            )
            val cardAlpha by animateFloatAsState(
                targetValue = if (isOther) 0f else 1f,
                animationSpec = tween(220),
                label = "cardAlpha",
            )
            val translateY by animateFloatAsState(
                targetValue = if (isOther) 56f else 0f,
                animationSpec = tween(260),
                label = "cardTranslateY",
            )
            HistoryCard(
                sentence = sentence,
                color = lerp(
                    BodyTint,
                    historyCardAccents[index % historyCardAccents.size],
                    0.2f,
                ),
                alpha = cardAlpha,
                rotation = rotation,
                scale = scale,
                translateYDp = translateY,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = {
                    if (exitingIndex == null) {
                        exitingIndex = index
                        scope.launch {
                            delay(280)
                            onTap(sentence)
                        }
                    }
                },
            )
        }
        // Tall trailing spacer: clears the floating language bar and guarantees
        // the stack overflows the viewport so it's always scrollable (with some
        // blank space below when there are only a few cards).
        Spacer(Modifier.height(300.dp))
    }
}

// How tall each card is, and how much of it "peeks" out above the next card
// in the stack (i.e. the visible strip that shows the title).
private const val CARD_HEIGHT = 132
private const val CARD_PEEK = 62

@Composable
private fun HistoryCard(
    sentence: String,
    color: Color,
    alpha: Float,
    rotation: Float,
    scale: Float,
    translateYDp: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT.dp)
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = rotation
                scaleX = scale
                scaleY = scale
                translationY = translateYDp.dp.toPx()
            }
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(18.dp),
                clip = false,
            )
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = sentence,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
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
    onToggleLinkStyle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        val compoundIndices = remember(state.entries) {
            state.entries.entries.filter { it.value.isFallbackSplit }.map { it.key }.toSet()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onStartEditing() })
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            TokenizedSentenceView(
                tokens = state.tokens,
                selectedIndex = state.selectedIndex,
                onTokenClick = onChipTapped,
                onTokenDoubleClick = onStartEditing,
                compoundIndices = compoundIndices,
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
                cardAddState = CardAddState(state.submittingEntryId, state.addedDecks),
                linkToKanjiStudy = state.linkToKanjiStudy,
                onTokenSelected = onTokenSelected,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                onToggleLinkStyle = onToggleLinkStyle,
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
    cardAddState: CardAddState,
    linkToKanjiStudy: Boolean,
    onTokenSelected: (Int) -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    onToggleLinkStyle: () -> Unit,
    loadKanjiTiles: suspend (DictEntry) -> List<KanjiTile>,
    listState: LazyListState,
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    // ~4 collapsed card heights of buffer before scroll mode arms on the
    // bottom-visible trigger. Captured here so it's available in snapshotFlow.
    val scrollTriggerBufferPx = with(LocalDensity.current) { 192.dp.toPx() }
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
    // True while the chip-tap scroll animation is running. Body expansion is
    // suppressed during this window so the item's layout height stays constant
    // throughout animateScrollBy — a growing item during animation makes the
    // pre-computed delta stale, landing the card in the wrong position.
    var isScrollingToItem by remember { mutableStateOf(false) }

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

    // Scroll mode arms when either:
    //   top  > beforeContentPadding  →  card pushed DOWN from its dock
    //                                   position (navigating to earlier words)
    //   bottom < viewportEnd - buffer  →  card's bottom has risen into the
    //                                   viewport by at least ~4 card heights
    //                                   (navigating to later words / finished
    //                                   reading a tall entry)
    // For a SHORT card the second condition is already true when docked, so
    // any drag arms scroll mode. For a TALL card neither fires at rest.
    //
    // Gated on isDragged so the programmatic post-release snap (no finger on
    // screen) can never re-arm scroll mode mid-animation.
    LaunchedEffect(listState, items) {
        snapshotFlow {
            if (!isDragged) return@snapshotFlow false
            val info = listState.layoutInfo
            val sel = currentSelected.value ?: return@snapshotFlow false
            val item = info.visibleItemsInfo.firstOrNull { row ->
                items.getOrNull(row.index)?.index == sel
            } ?: return@snapshotFlow true   // fully off-screen
            item.offset > info.beforeContentPadding ||
                item.offset + item.size < info.viewportEndOffset - scrollTriggerBufferPx
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
                // Yield before reading scrollMode so that any
                // LaunchedEffect(selectedAbsIndex) dispatched in the same
                // frame (e.g. a chip tap that coincided with the scroll
                // settling) runs first and sets scrollMode = false. Without
                // this, the snap reads scrollMode=true, starts an animation,
                // and LaunchedEffect immediately cancels it — the brief
                // competing animation appears as "word slightly too high."
                yield()
                // Capture BEFORE flipping — the snap decision is based on
                // whether we were in scroll-mode at the moment of release.
                val wasInScrollMode = scrollMode
                if (scrollMode) scrollMode = false
                if (!wasInScrollMode) return@collect
                val sel = currentSelected.value ?: return@collect
                val info = listState.layoutInfo
                if (info.visibleItemsInfo.isEmpty()) return@collect
                // Snap by INDEX rather than computed pixel delta.
                //
                // The old animateScrollBy-with-delta approach read item.offset
                // at the moment of release and animated by that delta. If the
                // layout shifted DURING the animation (the body unfurling
                // re-runs measure on the row, the chip strip above can
                // rewrap when the selected token bolds, etc.) the final
                // position drifted — that was the "sometimes covered by the
                // sentence" inconsistency.
                //
                // animateScrollToItem(index, scrollOffset = 0) asks the lazy
                // list to land item `index` at the viewport's start, regardless
                // of intermediate layout shifts. Final position is canonical.
                val filteredIdx = items.indexOfFirst { it.index == sel }
                if (filteredIdx >= 0) {
                    coroutineScope.launch {
                        listState.animateScrollToItem(
                            index = filteredIdx,
                            scrollOffset = 0,
                        )
                    }
                }
            }
    }

    // Chip-tap scroll: dock the focused card flush with the viewport top, then unfurl.
    //
    // Uses animateScrollToItem rather than a pre-computed animateScrollBy delta.
    // The delta approach failed because two layout changes happen during the animation:
    //   1. The newly-focused header grows (200 ms tween, 16 sp → 30 sp + padding).
    //   2. The previously-focused header collapses (snap), shifting everything below it.
    // A delta sampled at t=0 is stale by t=300 ms, so the card consistently landed
    // in the wrong place. animateScrollToItem re-evaluates the target offset every
    // frame, absorbing both changes automatically.
    //
    // isScrollingToItem is set before the first suspension point so the body
    // is suppressed from frame 1 — not after a withFrameNanos wait. This eliminates
    // the one-frame window where a cached lookup could briefly flash the body open.
    LaunchedEffect(selectedAbsIndex) {
        if (selectedAbsIndex == null) return@LaunchedEffect
        if (isDragged) return@LaunchedEffect
        if (scrollMode) scrollMode = false
        val filteredIdx = items.indexOfFirst { it.index == selectedAbsIndex }
        if (filteredIdx < 0) return@LaunchedEffect
        isScrollingToItem = true
        // Wait one frame for the body-collapse (ExitTransition.None, triggered by
        // isScrollingToItem = true above) to complete its layout pass and be
        // reflected in listState.layoutInfo. Without this, animateScrollToItem reads
        // stale item offsets that still include the old body height, targets the
        // wrong scroll position, and overshoots — visually appearing as the card
        // landing slightly too high until the new entry's body loads.
        withFrameNanos {}
        try {
            listState.animateScrollToItem(index = filteredIdx, scrollOffset = 0)
        } finally {
            isScrollingToItem = false
        }
    }

    // Body unfurls once the chip-tap scroll settles AND a lookup has been
    // initiated (entries[it] != null). The body immediately shows a skeleton
    // while loading; Crossfade inside the body Surface transitions to the real
    // entry once the lookup completes (bodyReady = true in WordListItem).
    val expandedAbsIndex = selectedAbsIndex
        ?.takeIf { !scrollMode }
        ?.takeIf { !isScrollingToItem }
        ?.takeIf { entries[it] != null }
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
                // Lookup is always passed so compound badges can show in the
                // unfocused header (body rendering is still gated by isExpanded).
                lookup = entries[absIdx],
                defaultDeckName = defaultDeckName,
                cardAddState = cardAddState,
                linkToKanjiStudy = linkToKanjiStudy,
                onClick = { onTokenSelected(absIdx) },
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                onToggleLinkStyle = onToggleLinkStyle,
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
    cardAddState: CardAddState,
    linkToKanjiStudy: Boolean,
    onClick: () -> Unit,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    onToggleLinkStyle: () -> Unit,
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
    // Expand with a short tween when gaining focus; collapse instantly (snap)
    // when losing focus so the previously-focused card gets out of the way
    // without animating its layout height.
    val animatedTitleSize by animateFloatAsState(
        targetValue = if (focused) 30f else titleSize.value,
        animationSpec = if (focused) tween(durationMillis = 200) else snap(),
        label = "wordListTitleSize",
    )
    val animatedReadingSize by animateFloatAsState(
        targetValue = if (focused) 16f else readingSize.value,
        animationSpec = if (focused) tween(durationMillis = 200) else snap(),
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
            modifier = Modifier.fillMaxWidth(),
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
                } else if (lookup?.isFallbackSplit == true && lookup.subUnits.size > 1) {
                    Spacer(Modifier.width(10.dp))
                    CompoundBadge(count = lookup.subUnits.size, focused = focused)
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
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = tween(durationMillis = 260),
                expandFrom = Alignment.Bottom,
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
                // Crossfade between the shimmer skeleton (while lookup is in
                // flight) and the real entry (once bodyReady flips true).
                // The 200 ms fade is short enough to feel instant on fast
                // devices while still masking the height change as the real
                // content (which may be taller than the skeleton) unfurls.
                Crossfade(
                    targetState = bodyReady,
                    animationSpec = tween(durationMillis = 200),
                    label = "entryLoad",
                ) { ready ->
                    if (ready) {
                        ExpandedEntryCard(
                            token = token,
                            lookup = lookup,
                            defaultDeckName = defaultDeckName,
                            cardAddState = cardAddState,
                            linkToKanjiStudy = linkToKanjiStudy,
                            onAddToDefaultDeck = onAddToDefaultDeck,
                            onChooseDeck = onChooseDeck,
                            onToggleLinkStyle = onToggleLinkStyle,
                            loadKanjiBreakdown = loadKanjiTiles,
                        )
                    } else {
                        EntrySkeletonBody()
                    }
                }
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

// ─────────────────────────────── Skeleton loading ───────────────────────────────

/**
 * Placeholder body shown while the JMdict lookup is in flight.
 * Matches the approximate vertical size of a 2–3 sense entry so the body
 * expands to roughly its final height on the first frame, and the subsequent
 * Crossfade to real content feels like a reveal rather than a resize.
 */
@Composable
private fun EntrySkeletonBody() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 14.dp),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val brush = shimmerBrush(widthPx)
        Column {
            // POS label
            SkeletonBar(brush, widthFraction = 0.32f, height = 11.dp)
            Spacer(Modifier.height(6.dp))
            // Sense lines
            SkeletonBar(brush, widthFraction = 0.92f, height = 14.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonBar(brush, widthFraction = 0.76f, height = 14.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonBar(brush, widthFraction = 0.58f, height = 14.dp)
            Spacer(Modifier.height(16.dp))
            // Action button
            SkeletonBar(brush, widthFraction = 1f, height = 48.dp, cornerRadius = 8.dp)
        }
    }
}

@Composable
internal fun SkeletonBar(
    brush: Brush,
    widthFraction: Float,
    height: Dp,
    cornerRadius: Dp = 5.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    )
}

/** A gradient sweep that moves left-to-right on an infinite loop. */
@Composable
internal fun shimmerBrush(widthPx: Float): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -widthPx,
        targetValue = widthPx * 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "shimmerX",
    )
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + widthPx, 0f),
    )
}

/**
 * Three-option pill tab bar. A sliding LogoRed indicator moves between Auto /
 * Japanese / Chinese with a spring animation. Each slot shows only an icon —
 * a refresh icon for auto, kanji characters for the languages.
 */
@Composable
private fun LanguageTabBar(
    selected: Language?,
    onSelect: (Language?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf<Language?>(null, Language.JAPANESE, Language.CHINESE_SIMPLIFIED)
    val selectedIdx = options.indexOfFirst { lang ->
        lang == selected || (lang == Language.CHINESE_SIMPLIFIED && selected == Language.CHINESE_TRADITIONAL)
    }.coerceAtLeast(0)

    val indicatorFraction by animateFloatAsState(
        targetValue = selectedIdx / options.size.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "langTabPill",
    )

    BoxWithConstraints(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Sliding pill
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(maxWidth / options.size)
                .graphicsLayer { translationX = maxWidth.toPx() * indicatorFraction }
                .padding(6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LogoRed),
        )
        // Hit areas + icons
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { i, lang ->
                val isSelected = i == selectedIdx
                val tint by animateColorAsState(
                    targetValue = if (isSelected) Color.White
                                  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    label = "tabTint",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(lang) },
                    contentAlignment = Alignment.Center,
                ) {
                    when (i) {
                        0 -> Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = tint,
                        )
                        1 -> Text("日", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tint)
                        else -> Text("中", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompoundBadge(count: Int, focused: Boolean = true) {
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
             else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fg.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("×$count", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
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
