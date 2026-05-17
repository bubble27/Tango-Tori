package com.tangotori.app.ui.sentence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.JmdictDialectLabels
import com.tangotori.app.domain.util.JmdictFieldLabels
import com.tangotori.app.domain.util.JmdictMiscLabels
import com.tangotori.app.domain.util.KanjiTile
import com.tangotori.app.domain.util.formatCodes
import com.tangotori.app.domain.util.formatPos
import com.tangotori.app.ui.theme.toColor
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

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
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
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
            lookup.entries.isNullOrEmpty() -> EmptyRow()
            else -> EntryBody(
                token = token,
                entries = lookup.entries,
                defaultDeckName = defaultDeckName,
                isSubmitting = isSubmitting,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
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
        "No JMdict entry for this word.",
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
    onAddToDefaultDeck: (Token, DictEntry) -> Unit,
    onChooseDeck: (Token, DictEntry) -> Unit,
    loadKanjiBreakdown: suspend (DictEntry) -> List<KanjiTile>,
) {
    // Multi-entry: tab strip. Preserve selection across recompositions but reset
    // when the entry list itself changes (different token, different entries).
    var selectedIdx by rememberSaveable(entries.map { it.id }) { mutableIntStateOf(0) }
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
    val isFunctionWord = token.partOfSpeech == PartOfSpeech.PARTICLE ||
            token.partOfSpeech == PartOfSpeech.AUXILIARY_VERB
    if (isFunctionWord) {
        // Particles / aux verbs don't get cards — and we don't show a button
        // saying so either, per user request. Just stop after the senses.
        return
    }

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
        Button(
            onClick = { onChooseDeck(token, entry) },
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
                contentDescription = "Choose specific deck",
            )
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
    val posColor = token.partOfSpeech.toColor()
    var previousPos: String? = null
    for ((idx, sense) in entry.senses.withIndex()) {
        val posLine = sense.partOfSpeech.joinToString(";")
        if (posLine != previousPos) {
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
            "Kanji",
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
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
            // No fixed width — the tile takes its natural intrinsic size, so a
            // long meaning like "training" doesn't wrap to two lines. The row
            // is in a horizontalScroll() (KanjiBreakdownRow above), so a wider
            // tile just makes the row scrollable rather than overflowing.
            Text(
                tile.meanings.joinToString(", "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                fontStyle = FontStyle.Italic,
                maxLines = 1,
            )
        }
    }
}

private val MutedNoteColor = Color(0xFF78909C)

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

