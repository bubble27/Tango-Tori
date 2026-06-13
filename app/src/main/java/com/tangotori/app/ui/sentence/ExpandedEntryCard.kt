package com.tangotori.app.ui.sentence

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tangotori.app.data.compound.MeaningResult
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Gloss
import com.tangotori.app.domain.models.KanjiForm
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Reading
import com.tangotori.app.domain.models.Sense
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.JmdictDialectLabels
import com.tangotori.app.domain.util.JmdictFieldLabels
import com.tangotori.app.domain.util.JmdictMiscLabels
import com.tangotori.app.domain.usecases.LookupResult
import com.tangotori.app.domain.util.KanjiTile
import com.tangotori.app.domain.util.formatCodes
import com.tangotori.app.domain.util.formatPos
import com.tangotori.app.ui.theme.LogoRed
import com.tangotori.app.ui.theme.toChinesePosLabel
import com.tangotori.app.ui.theme.toColor
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

/**
 * Rich expanded entry view shown beneath a tapped word in the list.
 *
 * Handles three states: loading, empty (no JMdict match), and one-or-more
 * matched entries. If multiple entries match, a horizontal tab strip lets the
 * user disambiguate before tapping "Select this word".
 */
/**
 * Per-entry add-to-deck state, threaded down from the ViewModel. Drives the
 * "creating" ripple animation and the disabled "Already added to <deck>" button.
 */
data class CardAddState(
    val submittingEntryId: Long? = null,
    val addedDecks: Map<Long, String> = emptyMap(),
) {
    fun isSubmitting(entryId: Long): Boolean = submittingEntryId == entryId
    fun addedDeck(entryId: Long): String? = addedDecks[entryId]
    val isAnySubmitting: Boolean get() = submittingEntryId != null
}

@Composable
fun ExpandedEntryCard(
    token: Token,
    lookup: EntryLookup?,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    linkToKanjiStudy: Boolean,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    onToggleLinkStyle: () -> Unit,
    /** Fetcher for per-kanji tiles (char + reading + meanings) shown in the
     *  in-app dictionary's kanji section. Called from a [produceState] keyed
     *  by entry id so flipping tabs re-fetches. */
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
    modifier: Modifier = Modifier,
) {
    // Box wrapper so a downward "creation ripple" can sweep behind the whole
    // card body and into the add button while a card is being submitted. Drawn
    // first so it sits BEHIND the content (and disappears under the opaque
    // button rather than painting over it).
    Box(modifier = modifier.fillMaxWidth()) {
        // Only the expanded card shows add buttons, so a global "any submitting"
        // flag is enough to gate the card-wide ripple here.
        CardCreationRipple(active = cardAddState.isAnySubmitting)

        // Top padding gives the senses block air below the red header bar; the
        // bar's bottom edge would otherwise sit flush against the first POS line.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 14.dp),
        ) {
            when {
                lookup == null -> { /* should not be reached when caller gates */ }
                lookup.error != null -> ErrorRow(lookup.error)
                lookup.isFallbackSplit -> CompoundEntryCard(
                    token = token,
                    subUnits = lookup.subUnits,
                    compoundMeaningResult = lookup.compoundMeaningResult,
                    defaultDeckName = defaultDeckName,
                    cardAddState = cardAddState,
                    onAddToDefaultDeck = onAddToDefaultDeck,
                    onChooseDeck = onChooseDeck,
                    loadKanjiBreakdown = loadKanjiBreakdown,
                )
                lookup.entries.isNullOrEmpty() -> EmptyRow()
                // Grouped idiom (腹が立つ): the idiom entry at top, then each
                // component word (腹 / が / 立つ) as its own sub-card below.
                token.isIdiomGroup -> IdiomGroupBody(
                    token = token,
                    entries = lookup.entries,
                    componentEntries = lookup.componentEntries,
                    inExpressionLoading = lookup.inExpressionLoading,
                    defaultDeckName = defaultDeckName,
                    cardAddState = cardAddState,
                    linkToKanjiStudy = linkToKanjiStudy,
                    inContext = lookup.inContext,
                    inContextLoading = lookup.inContextLoading,
                    inContextLimitReached = lookup.inContextLimitReached,
                    onAddToDefaultDeck = onAddToDefaultDeck,
                    onChooseDeck = onChooseDeck,
                    onToggleLinkStyle = onToggleLinkStyle,
                    loadKanjiBreakdown = loadKanjiBreakdown,
                )
                else -> EntryBody(
                    token = token,
                    entries = lookup.entries,
                    defaultDeckName = defaultDeckName,
                    cardAddState = cardAddState,
                    linkToKanjiStudy = linkToKanjiStudy,
                    inContext = lookup.inContext,
                    inContextLoading = lookup.inContextLoading,
                    inContextLimitReached = lookup.inContextLimitReached,
                    onAddToDefaultDeck = onAddToDefaultDeck,
                    onChooseDeck = onChooseDeck,
                    onToggleLinkStyle = onToggleLinkStyle,
                    loadKanjiBreakdown = loadKanjiBreakdown,
                )
            }
        }
    }
}

@Composable
private fun ErrorRow(message: String) {
    Text(
        "Lookup failed: $message",
        color = MaterialTheme.colorScheme.error,
        fontSize = 13.sp,
    )
}

@Composable
private fun EmptyRow() {
    Text(
        "No dictionary entry found.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        fontSize = 13.sp,
        fontStyle = FontStyle.Italic,
    )
}

/**
 * Grouped-idiom body, mirroring the Chinese [CompoundEntryCard]: the idiom's own
 * JMdict entry (senses + in-context meaning + add button) at the top, then each
 * component word as its own sub-card below, separated by dividers.
 */
@Composable
private fun IdiomGroupBody(
    token: Token,
    entries: List<DictEntry>,
    componentEntries: List<ComponentEntries>,
    inExpressionLoading: Boolean,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    linkToKanjiStudy: Boolean,
    inContext: InContextSense?,
    inContextLoading: Boolean,
    inContextLimitReached: Boolean,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    onToggleLinkStyle: () -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // The idiom itself as a normal word entry (headword is the colored
    // WordListItem card above; this is senses + in-context + add button).
    EntryBody(
        token = token,
        entries = entries,
        defaultDeckName = defaultDeckName,
        cardAddState = cardAddState,
        linkToKanjiStudy = linkToKanjiStudy,
        inContext = inContext,
        inContextLoading = inContextLoading,
        inContextLimitReached = inContextLimitReached,
        onAddToDefaultDeck = onAddToDefaultDeck,
        onChooseDeck = onChooseDeck,
        onToggleLinkStyle = onToggleLinkStyle,
        loadKanjiBreakdown = loadKanjiBreakdown,
    )

    if (componentEntries.isNotEmpty()) {
        // Idioms read as an "expression"; decomposed compounds read as a
        // "compound" with "morphemic" part meanings.
        val sectionHeader = if (token.isExpression) "WORDS IN THIS EXPRESSION" else "COMPOUND PARTS"
        val partMeaningLabel = if (token.isExpression) "In-expression meaning" else "Morphemic meaning"
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        Spacer(Modifier.height(12.dp))
        Text(
            sectionHeader,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MutedNoteColor,
        )
        componentEntries.forEachIndexed { i, comp ->
            Spacer(Modifier.height(14.dp))
            // In a compound, an UNchanging part (高校, 経済) doesn't need a
            // morphemic gloss — only the red sense highlight. Bound affixes
            // (生, 的, 化) do. Idiom parts always show their in-expression meaning.
            val showMeaning = token.isExpression || comp.token.isBoundAffix
            IdiomPartCard(
                token = comp.token,
                entries = comp.entries,
                meaning = if (showMeaning) comp.meaning else null,
                meaningLoading = showMeaning && inExpressionLoading && comp.meaning == null,
                meaningLabel = partMeaningLabel,
                senseIndex = comp.senseIndex,
                defaultDeckName = defaultDeckName,
                cardAddState = cardAddState,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                loadKanjiBreakdown = loadKanjiBreakdown,
            )
            if (i < componentEntries.lastIndex) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

/** One component word inside a grouped idiom: headword + reading + senses +
 *  add-to-deck. No in-context meaning (only the group gets that). */
@Composable
private fun IdiomPartCard(
    token: Token,
    entries: List<DictEntry>,
    meaning: String?,
    meaningLoading: Boolean,
    meaningLabel: String,
    senseIndex: Int?,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // Bound suffix/prefix parts (生 in 高校生) display with a tilde: ~生.
    val headword = token.partLabel
    if (entries.isEmpty()) {
        Text(headword, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (token.dictionaryReading.isNotBlank() && token.dictionaryReading != token.dictionaryForm) {
            Text(
                token.dictionaryReading,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(4.dp))
        InExpressionSection(label = meaningLabel, loading = meaningLoading, meaning = meaning)
        if (meaning == null && !meaningLoading) {
            Text(
                "No dictionary entry found.",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        return
    }

    var selectedIdx by rememberSaveable(entries.map { it.id }) { mutableIntStateOf(0) }
    if (entries.size > 1) {
        EntryTabs(entries = entries, selectedIdx = selectedIdx, onSelect = { selectedIdx = it })
        Spacer(Modifier.height(8.dp))
    }
    val entry = entries[selectedIdx.coerceIn(0, entries.lastIndex)]

    Text(headword, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val reading = entry.primaryReading
    if (reading.isNotBlank() && reading != token.dictionaryForm) {
        Text(
            reading,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
    Spacer(Modifier.height(6.dp))

    // The AI "as a part" meaning sits above the literal dictionary senses.
    InExpressionSection(label = meaningLabel, loading = meaningLoading, meaning = meaning)

    EntryDetail(
        token = token,
        entry = entry,
        inContextLoading = false,
        inContextMeaning = null,
        inContextLimitReached = false,
        // The matched sense applies to the displayed (first) entry only.
        contextSenseIndex = if (selectedIdx == 0) senseIndex else null,
        loadKanjiBreakdown = loadKanjiBreakdown,
    )

    Spacer(Modifier.height(10.dp))
    AddToDeckButton(
        deckName = defaultDeckName,
        entryId = entry.id,
        cardAddState = cardAddState,
        onAdd = { onAddToDefaultDeck(token, entry) },
        height = 44.dp,
        fontSize = 13.sp,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Choose deck") },
            onClick = {
                dismiss()
                onChooseDeck(token, entry)
            },
        )
    }
}

@Composable
private fun EntryBody(
    token: Token,
    entries: List<DictEntry>,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    linkToKanjiStudy: Boolean,
    inContext: InContextSense?,
    inContextLoading: Boolean,
    inContextLimitReached: Boolean,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    onToggleLinkStyle: () -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // Multi-entry: tab strip. Preserve selection across recompositions but reset
    // when the entry list itself changes (different token, different entries).
    // CEDICT capitalizes the first pinyin syllable of proper nouns and surnames
    // (坐（Zuò） = surname Zuo vs 坐（zuò） = to sit). Default to the first
    // entry whose reading starts lowercase so common words win over names.
    val defaultIdx = remember(entries) {
        entries.indexOfFirst { it.primaryReading.firstOrNull()?.isLowerCase() == true }
            .takeIf { it >= 0 } ?: 0
    }
    var selectedIdx by rememberSaveable(entries.map { it.id }) { mutableIntStateOf(defaultIdx) }

    // When the in-context result lands, jump to the homograph entry it chose.
    LaunchedEffect(inContext?.entryIndex) {
        val ei = inContext?.entryIndex
        if (ei != null && ei in entries.indices) selectedIdx = ei
    }

    if (entries.size > 1) {
        EntryTabs(
            entries = entries,
            selectedIdx = selectedIdx,
            onSelect = { selectedIdx = it },
        )
        Spacer(Modifier.height(8.dp))
    }

    val activeIdx = selectedIdx.coerceIn(0, entries.lastIndex)
    val entry = entries[activeIdx]
    // The contextual meaning + highlighted sense apply only to the entry the LLM
    // actually chose; on any other homograph tab we show nothing extra.
    val isChosenEntry = inContext != null && inContext.entryIndex == activeIdx
    EntryDetail(
        token = token,
        entry = entry,
        inContextLoading = inContextLoading && inContext == null,
        inContextMeaning = if (isChosenEntry) inContext!!.meaning else null,
        inContextLimitReached = inContextLimitReached,
        contextSenseIndex = if (isChosenEntry) inContext!!.senseIndex else null,
        loadKanjiBreakdown = loadKanjiBreakdown,
    )
    Spacer(Modifier.height(12.dp))

    // Inline add-to-deck control: a split "Add to <Deck>" button (wide primary
    // + overflow chooser) that morphs into a creation ripple, then a
    // "Successfully added" flash, then a disabled "Already added to <deck>".
    AddToDeckButton(
        deckName = defaultDeckName,
        entryId = entry.id,
        cardAddState = cardAddState,
        onAdd = { onAddToDefaultDeck(token, entry) },
        height = 48.dp,
        fontSize = 14.sp,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Choose deck") },
            onClick = {
                dismiss()
                onChooseDeck(token, entry)
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Kanji Study links") },
            trailingIcon = if (linkToKanjiStudy) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null,
            onClick = {
                dismiss()
                onToggleLinkStyle()
            },
        )
    }
}

@Composable
private fun EntryTabs(
    entries: List<DictEntry>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
) {
    // Disambiguator: if two entries share the same headword, the readings
    // differentiate them ("入る（いる）" vs "入る（はいる）"). If readings also
    // collide, fall back to the first gloss text.
    val labels = remember(entries) { buildTabLabels(entries) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(entries, key = { _, e -> e.id }) { idx, _ ->
            val selected = idx == selectedIdx
            // Selected tab: filled primary tint, strong primary border, bold
            // text. Unselected: muted background, subtle border, regular text.
            // The visual contrast between states must be unambiguous.
            val containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            }
            val borderColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            }
            val textColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = labels[idx],
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                )
            }
        }
    }
}

/** Build tab labels that disambiguate entries with the same headword. */
private fun buildTabLabels(entries: List<DictEntry>): List<String> {
    val headwordCounts = entries.groupingBy { it.headword.ifEmpty { it.primaryReading } }
        .eachCount()
    return entries.map { entry ->
        val head = entry.headword.ifEmpty { entry.primaryReading }
        val needsDisambiguation = (headwordCounts[head] ?: 0) > 1
        if (!needsDisambiguation) {
            head
        } else if (entry.primaryReading.isNotBlank() && entry.primaryReading != head) {
            "$head（${entry.primaryReading}）"
        } else {
            // Same head + same reading is rare; fall back to first gloss.
            val gloss = entry.senses.firstOrNull()?.glosses?.firstOrNull()?.text
                ?.take(20)
                ?: ""
            if (gloss.isNotEmpty()) "$head — $gloss" else head
        }
    }
}

@Composable
private fun EntryDetail(
    token: Token,
    entry: DictEntry,
    inContextLoading: Boolean,
    inContextMeaning: String?,
    inContextLimitReached: Boolean,
    contextSenseIndex: Int?,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // Header (word + reading + badges) is now rendered up in WordListItem as
    // an animated colored card. This composable only handles the senses +
    // other-forms + kanji-breakdown block.

    // In-context meaning section (the LLM's read of how this word is used in the
    // current sentence), shown at the very top like the compound "Tango Tori
    // Meaning" block.
    InContextSection(
        loading = inContextLoading,
        meaning = inContextMeaning,
        limitReached = inContextLimitReached,
    )

    // Senses — POS subtitle per group, then any misc/field/dialect notes,
    // then numbered glosses. Order matters: notes belong BETWEEN the POS
    // label and the gloss (per Stage 2 spec / Kanji Study reference UI).
    val hasHiragana = entry.readings.any { r -> r.text.any { c -> c.code in 0x3040..0x309F } }
    val posColor = token.partOfSpeech.toColor()

    // Japanese (JMdict): POS codes live on each Sense and may change between senses.
    // Chinese (CC-CEDICT): senses never carry POS codes, so fall back to the token-level
    // Jieba tag shown once above all glosses.
    val hasJmdictPos = entry.senses.any { it.partOfSpeech.isNotEmpty() }
    if (!hasJmdictPos) {
        token.partOfSpeech.toChinesePosLabel()?.let { label ->
            Text(
                text = label,
                color = posColor,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }

    var previousPos: String? = null
    for ((idx, sense) in entry.senses.withIndex()) {
        val posLine = sense.partOfSpeech.joinToString(";")
        if (posLine != previousPos && posLine.isNotBlank()) {
            Text(
                text = formatPos(posLine),
                color = posColor,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            previousPos = posLine
        }
        SenseNotes(sense)
        // Highlight the contextually-relevant sense in Tango Tori red — only
        // meaningful when there's more than one sense to choose between.
        val highlighted = entry.senses.size > 1 && idx == contextSenseIndex
        SenseLine(index = idx + 1, sense = sense, highlighted = highlighted)
    }

    // Other forms — alternate kanji writings beyond the headword. Render one
    // per line so each is clearly distinct. The header reading is already
    // displayed above, so we don't repeat it per form.
    if (entry.kanjiForms.size > 1) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Other forms",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedNoteColor,
        )
        Spacer(Modifier.height(2.dp))
        for (form in entry.kanjiForms.drop(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = form.text,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
                if (!form.info.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = form.info,
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        color = MutedNoteColor,
                    )
                }
            }
        }
    }

    // Kanji breakdown — same per-character tiles the Anki card shows, but
    // inline in the dictionary so the user can browse the constituent kanji
    // without going to Kanji-Study. Loader is suspend (hits KANJIDIC Room
    // table), so we fetch via produceState keyed by entry id.
    val tilesState = produceState<List<KanjiTile>?>(initialValue = null, key1 = entry.id) {
        value = runCatching { loadKanjiBreakdown(entry) }.getOrNull()
    }
    val tiles = tilesState.value
    if (!tiles.isNullOrEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            if (hasHiragana) "Kanji" else "Hanzi",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedNoteColor,
        )
        Spacer(Modifier.height(6.dp))
        KanjiBreakdownRow(tiles)
    }
}

@Composable
private fun KanjiBreakdownRow(tiles: List<KanjiTile>) {
    // Horizontally scrollable in case a long compound overflows the screen.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (tile in tiles) {
            KanjiTileBox(tile)
        }
    }
}

@Composable
private fun KanjiTileBox(tile: KanjiTile) {
    var showDetail by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable { showDetail = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (tile.reading.isNotBlank()) {
            Text(
                tile.reading,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            tile.char,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (tile.meanings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                tile.meanings.joinToString(", "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showDetail) {
        CharDetailDialog(tile = tile, onDismiss = { showDetail = false })
    }
}

@Composable
private fun CharDetailDialog(tile: KanjiTile, onDismiss: () -> Unit) {
    val defs = tile.allMeanings.ifEmpty { tile.meanings }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tile.char,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (tile.reading.isNotBlank()) {
                    Text(
                        tile.reading,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                defs.forEachIndexed { i, def ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "${i + 1}. ",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                        Text(def, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

// ── Compound / fallback-split card ──────────────────────────────────────────

@Composable
private fun CompoundEntryCard(
    token: Token,
    subUnits: List<LookupResult>,
    compoundMeaningResult: MeaningResult?,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    val combinedPinyin = subUnits.joinToString(" ") { r ->
        when (r) {
            is LookupResult.Match -> preferredEntry(r.entries).primaryReading
            is LookupResult.NoMatch -> r.token
        }
    }
    if (combinedPinyin.isNotBlank()) {
        Text(
            combinedPinyin,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(6.dp))
    }

    // "Tango Tori Meaning" section — shimmer while loading, styled like a
    // regular definition once received, or an error note on failure.
    when (compoundMeaningResult) {
        null -> {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val brush = shimmerBrush(widthPx)
                Column {
                    SkeletonBar(brush, widthFraction = 0.38f, height = 11.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBar(brush, widthFraction = 0.90f, height = 14.dp)
                    Spacer(Modifier.height(5.dp))
                    SkeletonBar(brush, widthFraction = 0.68f, height = 14.dp)
                }
            }
        }
        is MeaningResult.Success -> {
            Text(
                "Tango Tori Meaning",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MutedNoteColor,
            )
            Spacer(Modifier.height(4.dp))
            compoundMeaningResult.meanings.forEachIndexed { i, meaning ->
                Row {
                    if (compoundMeaningResult.meanings.size > 1) {
                        Text(
                            "${i + 1}. ",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Text(meaning, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        is MeaningResult.NoInternet -> Text(
            "No internet connection",
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        is MeaningResult.Failed -> Text(
            "Meaning unavailable",
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        // Daily free limit: the notice is shown once per day; later misses
        // render nothing (cache hits still come back as Success).
        is MeaningResult.LimitReached -> if (compoundMeaningResult.showMessage) {
            DailyLimitNotice()
        }
        // Privacy opt-out: no request was made.
        is MeaningResult.AiDisabled -> Text(
            "AI features are turned off",
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
    Spacer(Modifier.height(10.dp))

    // Synthetic DictEntry for the whole compound — built once the meaning lands.
    val compoundEntry = remember(token.dictionaryForm, combinedPinyin, compoundMeaningResult) {
        if (compoundMeaningResult is MeaningResult.Success) {
            DictEntry(
                id = token.dictionaryForm.hashCode().toLong(),
                isCommon = false,
                jlptLevel = null,
                kanjiForms = listOf(KanjiForm(token.dictionaryForm)),
                readings = listOf(Reading(combinedPinyin)),
                senses = listOf(
                    Sense(
                        partOfSpeech = emptyList(),
                        glosses = compoundMeaningResult.meanings.map { Gloss(it) },
                    )
                ),
            )
        } else null
    }

    // Add compound to Anki — split button, enabled once meaning is known. Its
    // synthetic entry id is derived from the dictionary form (matches the id the
    // ViewModel records on submit) so the "added" state lines up.
    val compoundEntryId = remember(token.dictionaryForm) {
        token.dictionaryForm.hashCode().toLong()
    }
    AddToDeckButton(
        deckName = defaultDeckName,
        entryId = compoundEntryId,
        cardAddState = cardAddState,
        enabled = compoundEntry != null,
        onAdd = { compoundEntry?.let { onAddToDefaultDeck(token, it) } },
        height = 48.dp,
        fontSize = 13.sp,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Choose deck") },
            onClick = {
                dismiss()
                compoundEntry?.let { onChooseDeck(token, it) }
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

    subUnits.forEachIndexed { index, result ->
        Spacer(Modifier.height(14.dp))
        when (result) {
            is LookupResult.Match -> SubUnitFullCard(
                surface = result.token,
                entries = result.entries,
                defaultDeckName = defaultDeckName,
                cardAddState = cardAddState,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                loadKanjiBreakdown = loadKanjiBreakdown,
            )
            is LookupResult.NoMatch -> UnknownSubUnit(result.token)
        }
        if (index < subUnits.lastIndex) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun SubUnitFullCard(
    surface: String,
    entries: List<DictEntry>,
    defaultDeckName: String?,
    cardAddState: CardAddState,
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    val defaultIdx = remember(entries) {
        entries.indexOfFirst { it.primaryReading.firstOrNull()?.isLowerCase() == true }
            .takeIf { it >= 0 } ?: 0
    }
    var selectedIdx by rememberSaveable(entries.map { it.id }) { mutableIntStateOf(defaultIdx) }
    val entry = entries[selectedIdx.coerceIn(0, entries.lastIndex)]

    if (entries.size > 1) {
        EntryTabs(entries = entries, selectedIdx = selectedIdx, onSelect = { selectedIdx = it })
        Spacer(Modifier.height(8.dp))
    }

    val subToken = remember(surface, entry.id) {
        Token(
            surface = surface,
            dictionaryForm = surface,
            reading = entry.primaryReading,
            dictionaryReading = entry.primaryReading,
            partOfSpeech = PartOfSpeech.NOUN,
            rawPosTag = "",
        )
    }

    // Headword row with HSK / Common badges
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(surface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (entry.primaryReading.isNotBlank()) {
                Text(
                    entry.primaryReading,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
        SubUnitBadges(entry)
    }
    Spacer(Modifier.height(6.dp))

    // POS label derived from gloss text — CEDICT has no POS field, but verb
    // entries reliably start their first gloss with "to ".
    cedictPosLabel(entry)?.let { label ->
        val posColor = cedictPosColor(entry)
        Text(
            text = label,
            color = posColor,
            fontStyle = FontStyle.Italic,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }

    // Senses
    entry.senses.forEachIndexed { i, sense ->
        Row {
            Text(
                "${i + 1}. ",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(
                sense.glosses.joinToString("; ") { it.text },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    // Hanzi breakdown tiles
    val tilesState = produceState<List<KanjiTile>?>(initialValue = null, key1 = entry.id) {
        value = runCatching { loadKanjiBreakdown(entry) }.getOrNull()
    }
    val tiles = tilesState.value
    if (!tiles.isNullOrEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Hanzi", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedNoteColor)
        Spacer(Modifier.height(6.dp))
        KanjiBreakdownRow(tiles)
    }

    Spacer(Modifier.height(10.dp))

    // Add to Anki — same animated split button as the main cards.
    AddToDeckButton(
        deckName = defaultDeckName,
        entryId = entry.id,
        cardAddState = cardAddState,
        onAdd = { onAddToDefaultDeck(subToken, entry) },
        height = 44.dp,
        fontSize = 13.sp,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Choose deck") },
            onClick = {
                dismiss()
                onChooseDeck(subToken, entry)
            },
        )
    }
}

// ── Animated add-to-deck button ─────────────────────────────────────────────

private enum class AddPhase { Idle, Submitting, Success, Added }

/**
 * The add-to-deck control. Crossfades through four phases:
 *   Idle → split "Add to <Deck>" button (wide primary + overflow chooser)
 *   Submitting → a primary "Adding…" pill with a downward ripple sweep
 *   Success → a brief "Successfully added ✓" flash
 *   Added → a disabled "Already added to <deck> ✓" pill
 * Once a card is added it cannot be re-added (the button never returns to Idle
 * for that entry).
 */
@Composable
private fun AddToDeckButton(
    deckName: String?,
    entryId: Long,
    cardAddState: CardAddState,
    onAdd: () -> Unit,
    height: Dp,
    fontSize: TextUnit,
    enabled: Boolean = true,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val isSubmitting = cardAddState.isSubmitting(entryId)
    val addedDeck = cardAddState.addedDeck(entryId)

    // Brief "Successfully added" flash once a submit completes, before settling
    // into the persistent "Already added" state.
    var justSucceeded by remember { mutableStateOf(false) }
    var wasSubmitting by remember { mutableStateOf(false) }
    LaunchedEffect(isSubmitting, addedDeck) {
        if (wasSubmitting && !isSubmitting && addedDeck != null) {
            justSucceeded = true
            delay(1300)
            justSucceeded = false
        }
        wasSubmitting = isSubmitting
    }

    val phase = when {
        isSubmitting -> AddPhase.Submitting
        justSucceeded -> AddPhase.Success
        addedDeck != null -> AddPhase.Added
        else -> AddPhase.Idle
    }

    val cornerR = 8.dp
    Crossfade(
        targetState = phase,
        animationSpec = tween(300),
        modifier = Modifier.fillMaxWidth().height(height),
        label = "addPhase",
    ) { p ->
        when (p) {
            AddPhase.Idle -> SplitAddButton(
                label = deckName?.let { "Add to $it" } ?: "Add to Anki deck",
                enabled = enabled,
                cornerR = cornerR,
                fontSize = fontSize,
                onAdd = onAdd,
                menuItems = menuItems,
            )
            AddPhase.Submitting -> CreatingPill(cornerR = cornerR, fontSize = fontSize)
            AddPhase.Success -> StatusPill(
                text = "Successfully added",
                cornerR = cornerR,
                fontSize = fontSize,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
            )
            AddPhase.Added -> AddedButton(
                deckName = addedDeck ?: deckName ?: "deck",
                cornerR = cornerR,
                fontSize = fontSize,
                menuItems = menuItems,
            )
        }
    }
}

@Composable
private fun SplitAddButton(
    label: String,
    enabled: Boolean,
    cornerR: Dp,
    fontSize: TextUnit,
    onAdd: () -> Unit,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize(),
    ) {
        Button(
            onClick = onAdd,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(
                topStart = cornerR, bottomStart = cornerR,
                topEnd = 0.dp, bottomEnd = 0.dp,
            ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
        }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(
                onClick = { menuExpanded = true },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(
                    topStart = 0.dp, bottomStart = 0.dp,
                    topEnd = cornerR, bottomEnd = cornerR,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                menuItems { menuExpanded = false }
            }
        }
    }
}

/** Submitting state — a plain primary "Adding…" pill. The motion comes from the
 *  card-wide ripple sweeping down into it, not from the button itself. */
@Composable
private fun CreatingPill(cornerR: Dp, fontSize: TextUnit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerR))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Adding…",
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
        )
    }
}

/** Persistent "added" state — a muted "Already added to <deck>" pill on the
 *  left, but the overflow chooser stays live on the right so the user can still
 *  add this word to a *different* deck. */
@Composable
private fun AddedButton(
    deckName: String,
    cornerR: Dp,
    fontSize: TextUnit,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val accent = MaterialTheme.colorScheme.primary
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = cornerR, bottomStart = cornerR,
                        topEnd = 0.dp, bottomEnd = 0.dp,
                    ),
                )
                .background(muted),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Already added to $deckName",
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(
                onClick = { menuExpanded = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = muted,
                    contentColor = accent,
                ),
                shape = RoundedCornerShape(
                    topStart = 0.dp, bottomStart = 0.dp,
                    topEnd = cornerR, bottomEnd = cornerR,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Add to another deck")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                menuItems { menuExpanded = false }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    cornerR: Dp,
    fontSize: TextUnit,
    container: Color,
    content: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerR))
            .background(container),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
    }
}

/** A translucent, rounded primary band that descends the card body and into
 *  (behind) the add button while a card is being created. Single pass: it eases
 *  down over ~1s and stops at the button — and always completes that ~1s descent
 *  even if the add finishes sooner — then fades out in place. */
@Composable
private fun BoxScope.CardCreationRipple(active: Boolean) {
    val pos = remember { Animatable(0f) }   // 0 = top, 1 = at the button
    val alpha = remember { Animatable(0f) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            running = true
            pos.snapTo(0f)
            alpha.snapTo(0f)
            launch { alpha.animateTo(1f, animationSpec = tween(180)) }
            // Full ~1s descent into the button, then hold there.
            pos.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
        } else if (running) {
            // Let the band finish arriving at the button (so the motion always
            // reads as ~1s), then fade it out in place.
            if (pos.value < 1f) {
                val remainingMs = ((1f - pos.value) * 1000f).toInt().coerceAtLeast(140)
                pos.animateTo(1f, animationSpec = tween(remainingMs, easing = FastOutSlowInEasing))
            }
            alpha.animateTo(0f, animationSpec = tween(260))
            running = false
            pos.snapTo(0f)
        }
    }
    if (!active && !running) return

    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawBehind {
                val p = pos.value
                val a = alpha.value
                if (a <= 0f) return@drawBehind
                val w = size.width
                // Fixed (dp-based) sizing so the wave looks the same regardless
                // of how tall the card is.
                val band = 96.dp.toPx()
                val startCenter = 24.dp.toPx()
                // Stop in line with the button center: the button sits 14.dp from
                // the card bottom and is 48.dp tall → center is 38.dp up.
                val endCenter = size.height - 38.dp.toPx()
                val center = startCenter + (endCenter - startCenter) * p
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to color.copy(alpha = 0.28f),
                        0.7f to color.copy(alpha = 0.28f),
                        1f to Color.Transparent,
                        startY = center - band / 2f,
                        endY = center + band / 2f,
                    ),
                    topLeft = Offset(0f, center - band / 2f),
                    size = Size(w, band),
                    alpha = a,
                )
            },
    )
}

private fun preferredEntry(entries: List<DictEntry>): DictEntry =
    entries.firstOrNull { it.primaryReading.firstOrNull()?.isLowerCase() == true }
        ?: entries.first()

@Composable
private fun SubUnitBadges(entry: DictEntry) {
    val color = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        entry.jlptLevel?.let { level ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(level, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
        if (entry.isCommon) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("Common", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

@Composable
private fun UnknownSubUnit(char: String) {
    Text(
        char,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
    )
    Text(
        "(no entry found)",
        fontSize = 12.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
    )
}

private val MutedNoteColor = Color(0xFF78909C)

/**
 * Derives a POS label from a CC-CEDICT entry's gloss text.
 * CEDICT has no POS field; verb entries reliably open with "to ", and measure
 * words / proper nouns are flagged parenthetically. Everything else is treated
 * as a noun (the vast majority) and returns null so the label is omitted rather
 * than stating the obvious.
 */
private fun cedictPosLabel(entry: DictEntry): String? {
    val first = entry.senses.firstOrNull()?.glosses?.firstOrNull()?.text ?: return null
    return when {
        first.startsWith("to ") -> "Verb"
        first.contains("measure word", ignoreCase = true) -> "Measure word"
        first.contains("surname") || first.contains("given name") ||
            first.contains("place name") -> "Proper noun"
        else -> null
    }
}

@Composable
private fun cedictPosColor(entry: DictEntry): androidx.compose.ui.graphics.Color {
    val label = cedictPosLabel(entry)
    val posEnum = when (label) {
        "Verb" -> PartOfSpeech.VERB
        "Measure word" -> PartOfSpeech.CONJUNCTION_OTHER
        "Proper noun" -> PartOfSpeech.NOUN
        else -> PartOfSpeech.NOUN
    }
    return posEnum.toColor()
}

@Composable
private fun SenseNotes(sense: com.tangotori.app.domain.models.Sense) {
    val misc = formatCodes(sense.misc, JmdictMiscLabels)
    val field = formatCodes(sense.field, JmdictFieldLabels)
    val dialect = formatCodes(sense.dialect, JmdictDialectLabels)
    val notes = listOfNotNull(misc, field, dialect)
    if (notes.isEmpty()) return
    Text(
        text = notes.joinToString(" · "),
        fontSize = 11.sp,
        fontStyle = FontStyle.Italic,
        color = MutedNoteColor,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
internal fun BadgeRow(entry: DictEntry) {
    // Inline: JLPT and Common side-by-side. The header row already gives the
    // word + reading group `weight(1f)` so it pushes the badges right; long
    // headwords like トレーニング wrap to a second baseline line within the
    // word/reading group rather than fighting horizontal space with the badges.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.jlptLevel?.let { JlptBadge(it) }
        if (entry.isCommon) CommonBadge()
    }
}

// Badges live inside the WordListItem's red header card now. Use the
// same on-primary tone as the kana reading next to them so all three
// pieces of text (word / reading / badges) read as one band.
private val BadgeForeground: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)

@Composable
private fun JlptBadge(level: String) {
    val fg = BadgeForeground
    // Solid filled chip — matches CommonBadge so the two read as a paired set
    // rather than "one outlined + one filled" mismatch.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fg.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(level, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun CommonBadge() {
    val fg = BadgeForeground
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fg.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("Common", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun SenseLine(
    index: Int,
    sense: com.tangotori.app.domain.models.Sense,
    highlighted: Boolean = false,
) {
    // Misc/field/dialect notes have been hoisted out of SenseLine and into
    // [SenseNotes] (rendered ABOVE the gloss per spec). SenseLine itself is
    // now just the numbered gloss text.
    val glossText = sense.glosses.joinToString("; ") { it.text }
    val numberColor = if (highlighted) LogoRed.copy(alpha = 0.8f)
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val glossColor = if (highlighted) LogoRed else MaterialTheme.colorScheme.onSurface
    Row {
        Text(
            text = "$index. ",
            color = numberColor,
            fontSize = 14.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = glossText,
            fontSize = 14.sp,
            color = glossColor,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Top-of-card "In-context meaning" block: shimmer while the disambiguation
 *  call is in flight, then the LLM's contextual gloss. Styled like the compound
 *  "Tango Tori Meaning" section. */
@Composable
private fun InContextSection(loading: Boolean, meaning: String?, limitReached: Boolean = false) {
    if (!loading && meaning == null && !limitReached) return
    if (limitReached) {
        // One-time daily-limit notice shown in place of the contextual gloss;
        // subsequent lookups while over the limit render nothing here.
        DailyLimitNotice()
    } else if (meaning != null) {
        Text(
            "In-context meaning",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedNoteColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(meaning, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val brush = shimmerBrush(widthPx)
            Column {
                SkeletonBar(brush, widthFraction = 0.42f, height = 11.dp)
                Spacer(Modifier.height(6.dp))
                SkeletonBar(brush, widthFraction = 0.82f, height = 14.dp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    Spacer(Modifier.height(10.dp))
}

/** A component part's "in-expression meaning" (the AI's read of what the part
 *  contributes inside the idiom/compound): shimmer while loading, then the
 *  gloss. Styled like [InContextSection] but without the trailing divider. */
@Composable
private fun InExpressionSection(label: String, loading: Boolean, meaning: String?) {
    if (!loading && meaning == null) return
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MutedNoteColor,
    )
    Spacer(Modifier.height(4.dp))
    if (meaning != null) {
        Text(meaning, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val brush = shimmerBrush(widthPx)
            SkeletonBar(brush, widthFraction = 0.7f, height = 14.dp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

