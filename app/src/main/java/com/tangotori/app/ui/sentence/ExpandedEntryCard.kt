package com.tangotori.app.ui.sentence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.tangotori.app.domain.util.formatCodes
import com.tangotori.app.domain.util.formatPos
import com.tangotori.app.ui.theme.toColor

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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    ) {
        when {
            lookup == null || lookup.loading -> LoadingRow()
            lookup.error != null -> ErrorRow(lookup.error)
            lookup.entries.isNullOrEmpty() -> EmptyRow()
            else -> EntryBody(
                token = token,
                entries = lookup.entries,
                defaultDeckName = defaultDeckName,
                isSubmitting = isSubmitting,
                onAddToDefaultDeck = onAddToDefaultDeck,
                onChooseDeck = onChooseDeck,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Looking up…",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
        )
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
    EntryDetail(token = token, entry = entry)
    Spacer(Modifier.height(12.dp))
    val isFunctionWord = token.partOfSpeech == PartOfSpeech.PARTICLE ||
            token.partOfSpeech == PartOfSpeech.AUXILIARY_VERB
    if (isFunctionWord) {
        // No card creation for particles / aux verbs — same rule as before;
        // single disabled button explaining why.
        Button(
            onClick = { /* no-op */ },
            enabled = false,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Particles can't be made into cards", fontWeight = FontWeight.SemiBold)
        }
        return
    }

    // Two inline CTAs — no separate screen. Primary is the default-deck
    // shortcut when one is set; secondary always opens the picker.
    val primaryLabel = defaultDeckName?.let { "Add to $it" } ?: "Add to Anki deck"
    Button(
        onClick = { onAddToDefaultDeck(token, entry) },
        enabled = !isSubmitting,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(primaryLabel, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = { onChooseDeck(token, entry) },
        enabled = !isSubmitting,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Choose specific deck")
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
private fun EntryDetail(token: Token, entry: DictEntry) {
    // Header — dictionary form, primary reading, badges.
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = entry.headword.ifEmpty { entry.primaryReading },
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (entry.primaryReading.isNotBlank() && entry.primaryReading != entry.headword) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.primaryReading,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        BadgeRow(entry)
    }

    // Senses — POS subtitle per group, then any misc/field/dialect notes,
    // then numbered glosses. Order matters: notes belong BETWEEN the POS
    // label and the gloss (per Stage 2 spec / Kanji Study reference UI).
    Spacer(Modifier.height(8.dp))
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
private fun BadgeRow(entry: DictEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.jlptLevel?.let { JlptBadge(it) }
        if (entry.isCommon) CommonBadge()
    }
}

@Composable
private fun JlptBadge(level: String) {
    // Per Stage 2 feedback: outlined badge with primary border + text, to
    // visually distinguish from the filled green "Common" badge.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            level,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CommonBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF4CAF50).copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "Common",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2E7D32),
        )
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

