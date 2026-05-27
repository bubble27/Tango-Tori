package com.tangotori.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token

/**
 * Sentence view. Renders the tokens as inline-flowing content:
 * - Content tokens are [WordChip]s, all of [TokenRowHeight] height.
 * - Punctuation is flat inline text inside a matching-height Box so it
 *   bottom-aligns with adjacent chips — no chip styling, not tappable,
 *   not in the word list.
 *
 * Each child fades in with a left-to-right stagger so the parse result
 * appears to "write itself in" after the user submits a sentence.
 */
private const val PerTokenStaggerMs = 32

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenizedSentenceView(
    tokens: List<Token>,
    selectedIndex: Int?,
    onTokenClick: (Int) -> Unit,
    onTokenDoubleClick: () -> Unit,
    compoundIndices: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        // No horizontal gap between chips — each chip already has its own
        // horizontal padding, so the touch area for adjacent tokens is flush.
        // Gaps were creating ~4 dp dead zones where taps fell through to the
        // parent's empty onClick and did nothing.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Group each word chip with its immediately trailing punctuation into a
        // single Row so FlowRow never breaks a punctuation mark onto its own line.
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.partOfSpeech == PartOfSpeech.PUNCTUATION) {
                // Leading punctuation (e.g. opening bracket) — emit standalone.
                StaggeredPunctuation(text = token.surface, enterDelayMillis = i * PerTokenStaggerMs)
                i++
            } else {
                // Collect any punctuation that immediately follows this word.
                var j = i + 1
                while (j < tokens.size && tokens[j].partOfSpeech == PartOfSpeech.PUNCTUATION) j++
                val wordIdx = i
                if (j == i + 1) {
                    // No trailing punctuation — emit chip directly (no extra Row wrapper).
                    WordChip(
                        token = token,
                        selected = wordIdx == selectedIndex,
                        isCompound = wordIdx in compoundIndices,
                        onClick = { onTokenClick(wordIdx) },
                        enterDelayMillis = wordIdx * PerTokenStaggerMs,
                    )
                } else {
                    // Word + trailing punctuation wrapped together so FlowRow keeps them on the same line.
                    Row {
                        WordChip(
                            token = token,
                            selected = wordIdx == selectedIndex,
                            isCompound = wordIdx in compoundIndices,
                            onClick = { onTokenClick(wordIdx) },
                            enterDelayMillis = wordIdx * PerTokenStaggerMs,
                        )
                        for (punctIdx in i + 1 until j) {
                            StaggeredPunctuation(
                                text = tokens[punctIdx].surface,
                                enterDelayMillis = punctIdx * PerTokenStaggerMs,
                            )
                        }
                    }
                }
                i = j
            }
        }
    }
}

@Composable
private fun StaggeredPunctuation(text: String, enterDelayMillis: Int) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(durationMillis = 240, delayMillis = enterDelayMillis))
    }
    Column(
        modifier = Modifier.graphicsLayer {
            this.alpha = enter.value
            this.translationY = (1f - enter.value) * 6.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(FuriganaBandHeight))
        Box(
            modifier = Modifier.height(WordBandHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 18.sp,
            )
        }
    }
}
