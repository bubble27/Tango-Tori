package com.tangotori.app.ui.sentence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
@Composable
fun ExpandedEntryCard(
    token: Token,
    lookup: EntryLookup?,
    defaultDeckName: String?,
    isSubmitting: Boolean,
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
    // Top padding gives the senses block air below the red header bar; the
    // bar's bottom edge would otherwise sit flush against the first POS line.
    Column(
        modifier = modifier
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
                isSubmitting = isSubmitting,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                loadKanjiBreakdown = loadKanjiBreakdown,
            )
            lookup.entries.isNullOrEmpty() -> EmptyRow()
            else -> EntryBody(
                token = token,
                entries = lookup.entries,
                defaultDeckName = defaultDeckName,
                isSubmitting = isSubmitting,
                linkToKanjiStudy = linkToKanjiStudy,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
                onToggleLinkStyle = onToggleLinkStyle,
                loadKanjiBreakdown = loadKanjiBreakdown,
            )
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

@Composable
private fun EntryBody(
    token: Token,
    entries: List<DictEntry>,
    defaultDeckName: String?,
    isSubmitting: Boolean,
    linkToKanjiStudy: Boolean,
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
    if (entries.size > 1) {
        EntryTabs(
            entries = entries,
            selectedIdx = selectedIdx,
            onSelect = { selectedIdx = it },
        )
        Spacer(Modifier.height(8.dp))
    }

    val entry = entries[selectedIdx.coerceIn(0, entries.lastIndex)]
    EntryDetail(token = token, entry = entry, loadKanjiBreakdown = loadKanjiBreakdown)
    Spacer(Modifier.height(12.dp))

    // Inline action row: a wide primary "Add to <default deck>" button paired
    // with a small icon-only chooser to the LEFT. The icon button lets the
    // user override the default for this one card without burying it under a
    // second large button.
    // Split-button row: wide "Add to <Deck>" on the left, square overflow on
    // the right. They share the same height and sit flush against each other
    // (a 1 dp hairline gap reads as a divider rather than two separate
    // buttons). Less-rounded corners (8 dp) so the pair looks like one unit.
    val primaryLabel = defaultDeckName?.let { "Add to $it" } ?: "Add to Anki deck"
    val buttonHeight = 48.dp
    val cornerR = 8.dp
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(buttonHeight),
    ) {
        Button(
            onClick = { onAddToDefaultDeck(token, entry) },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(
                topStart = cornerR,
                bottomStart = cornerR,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
            ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Text(primaryLabel, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(
                onClick = { menuExpanded = true },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    bottomStart = 0.dp,
                    topEnd = cornerR,
                    bottomEnd = cornerR,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Choose deck") },
                    onClick = {
                        menuExpanded = false
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
                        menuExpanded = false
                        onToggleLinkStyle()
                    },
                )
            }
        }
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
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // Header (word + reading + badges) is now rendered up in WordListItem as
    // an animated colored card. This composable only handles the senses +
    // other-forms + kanji-breakdown block.

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
        SenseLine(index = idx + 1, sense = sense)
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
    isSubmitting: Boolean,
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

    // Add compound to Anki — split button, enabled once meaning is known.
    val primaryLabel = defaultDeckName?.let { "Add to $it" } ?: "Add to Anki deck"
    val buttonHeight = 48.dp
    val cornerR = 8.dp
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(buttonHeight),
    ) {
        Button(
            onClick = { compoundEntry?.let { onAddToDefaultDeck(token, it) } },
            enabled = !isSubmitting && compoundEntry != null,
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
            Text(primaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(
                onClick = { menuExpanded = true },
                enabled = !isSubmitting && compoundEntry != null,
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
                DropdownMenuItem(
                    text = { Text("Choose deck") },
                    onClick = {
                        menuExpanded = false
                        compoundEntry?.let { onChooseDeck(token, it) }
                    },
                )
            }
        }
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
                isSubmitting = isSubmitting,
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
    isSubmitting: Boolean,
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

    // Add to Anki — split button (same pattern as main cards)
    val primaryLabel = defaultDeckName?.let { "Add to $it" } ?: "Add to Anki deck"
    val cornerR = 8.dp
    val buttonHeight = 44.dp
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(buttonHeight),
    ) {
        Button(
            onClick = { onAddToDefaultDeck(subToken, entry) },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(
                topStart = cornerR, bottomStart = cornerR,
                topEnd = 0.dp, bottomEnd = 0.dp,
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(primaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(1.dp))
        Box {
            Button(
                onClick = { menuExpanded = true },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(
                    topStart = 0.dp, bottomStart = 0.dp,
                    topEnd = cornerR, bottomEnd = cornerR,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Choose deck") },
                    onClick = {
                        menuExpanded = false
                        onChooseDeck(subToken, entry)
                    },
                )
            }
        }
    }
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
private fun SenseLine(index: Int, sense: com.tangotori.app.domain.models.Sense) {
    // Misc/field/dialect notes have been hoisted out of SenseLine and into
    // [SenseNotes] (rendered ABOVE the gloss per spec). SenseLine itself is
    // now just the numbered gloss text.
    val glossText = sense.glosses.joinToString("; ") { it.text }
    Row {
        Text(
            text = "$index. ",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            fontSize = 14.sp,
        )
        Text(
            text = glossText,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

