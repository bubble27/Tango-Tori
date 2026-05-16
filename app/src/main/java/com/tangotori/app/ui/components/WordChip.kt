package com.tangotori.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.ui.theme.toColor

/**
 * Renders a single content-word token as a tappable chip.
 *
 * Visual conventions per the Stage-1 feedback doc:
 * - Word text uses the surface color (`onSurface`) for legibility.
 * - The POS color appears as a 2dp bottom border under the chip, not on the text.
 * - Furigana is a muted neutral color, never the POS color.
 * - Particles and auxiliary verbs render at reduced alpha to signal "not card material".
 * - Every chip is exactly [TokenRowHeight] tall, so a row of chips shares a baseline.
 * - Punctuation never reaches this composable — the parent renders it as inline text.
 *
 * On first composition the chip runs a two-stage enter animation: the word +
 * underline fade and slide up first, then the furigana fades in shortly after —
 * giving a "writing on" feel when the sentence transitions out of edit mode.
 * [enterDelayMillis] staggers the animation across siblings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordChip(
    token: Token,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
    enterDelayMillis: Int = 0,
) {
    val posColor = token.partOfSpeech.toColor()
    val deemphasized = token.partOfSpeech == PartOfSpeech.PARTICLE ||
            token.partOfSpeech == PartOfSpeech.AUXILIARY_VERB
    val alpha = if (deemphasized) 0.55f else 1f

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chipScale",
    )
    val borderThickness by animateDpAsState(
        targetValue = if (selected) 3.dp else 2.dp,
        label = "chipBorder",
    )

    val chipEnter = remember { Animatable(0f) }
    val furiganaEnter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        chipEnter.animateTo(1f, tween(durationMillis = 240, delayMillis = enterDelayMillis))
    }
    LaunchedEffect(Unit) {
        furiganaEnter.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, delayMillis = enterDelayMillis + 140),
        )
    }

    Box(
        modifier = modifier
            .height(TokenRowHeight)
            .graphicsLayer {
                this.alpha = chipEnter.value
                this.translationY = (1f - chipEnter.value) * 6.dp.toPx()
            }
            .scale(scale)
            .background(
                color = if (selected) posColor.copy(alpha = 0.10f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .drawBehind {
                val y = size.height - borderThickness.toPx() / 2
                drawLine(
                    color = posColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = borderThickness.toPx(),
                )
            }
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FuriganaText(
            surface = token.surface,
            reading = if (token.isPureKana) null else token.reading,
            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            furiganaColor = MutedFuriganaColor.copy(alpha = alpha),
            bold = selected,
            furiganaAlpha = furiganaEnter.value,
        )
    }
}
