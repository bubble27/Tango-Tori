package com.tangotori.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.ui.theme.PosCompound
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
@Composable
fun WordChip(
    token: Token,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompound: Boolean = false,
    enterDelayMillis: Int = 0,
) {
    val posColor = if (isCompound) PosCompound else token.partOfSpeech.toColor()
    val deemphasized = token.partOfSpeech == PartOfSpeech.PARTICLE ||
            token.partOfSpeech == PartOfSpeech.AUXILIARY_VERB
    val alpha = if (deemphasized) 0.55f else 1f

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chipScale",
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
                // Faint tint on the selected chip so the active token is
                // still identifiable. POS color no longer appears as an
                // underline — it's the word color itself now.
                color = if (selected) posColor.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            // Plain clickable (no onDoubleClick) so single taps fire instantly.
            // `combinedClickable` defers onClick by ~300 ms waiting for a possible
            // second tap, which was making sentence-chip taps occasionally feel
            // like they "didn't register". Double-tap-to-edit is handled by the
            // parent Box's combinedClickable in ViewingLayout — double-taps in
            // empty chip-area space still enter edit mode; double-taps on a chip
            // just register as two selections of the same word (no-op).
            .clickable(onClick = onClick)
            // 6 dp horizontal padding: doubles as touch slop so taps near the
            // chip edge (or in the gap that used to exist between chips) hit
            // the chip's clickable instead of falling through to the parent
            // Box's noop onClick.
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FuriganaText(
            surface = token.surface,
            reading = if (token.isPureKana) null else token.reading,
            // POS color is now applied to the WORD glyph itself instead of
            // a 2 dp underline. Reads as a single colored token at a glance,
            // no extra ink underneath.
            textColor = posColor.copy(alpha = alpha),
            furiganaColor = MutedFuriganaColor.copy(alpha = alpha),
            // Don't bold on selection: the bolder glyph measures wider, which
            // can re-wrap the chip strip into an extra row when a long word
            // becomes active. That growth pushes the LazyColumn down mid-snap
            // and caused the "card title halfway covered by sentence" issue.
            // The 1.06× scale + tinted background still flag the selection.
            bold = false,
            furiganaAlpha = furiganaEnter.value,
        )
    }
}
