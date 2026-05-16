package com.tangotori.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tokens.forEachIndexed { idx, token ->
            val delay = idx * PerTokenStaggerMs
            if (token.partOfSpeech == PartOfSpeech.PUNCTUATION) {
                StaggeredPunctuation(text = token.surface, enterDelayMillis = delay)
            } else {
                WordChip(
                    token = token,
                    selected = idx == selectedIndex,
                    onClick = { onTokenClick(idx) },
                    onDoubleClick = onTokenDoubleClick,
                    enterDelayMillis = delay,
                )
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
