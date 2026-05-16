package com.tangotori.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangotori.app.domain.util.KanjiKanaSplit
import com.tangotori.app.ui.theme.TangoToriTheme

/**
 * Renders a Japanese token with furigana above each contiguous kanji run.
 * Every token reserves the same vertical footprint — a fixed-height furigana band
 * on top, then the word band — so a row of these composables shares a uniform
 * baseline regardless of which tokens have kanji.
 *
 * Within a token, kanji and kana segments render as side-by-side mini-columns,
 * each contributing its own furigana label (or empty spacer). Furigana sits in
 * the upper band; word text sits at the bottom of the lower band so all
 * baselines line up.
 *
 *   入った  → [Column("入" + "はい")] [Column("っ" + spacer)] [Column("た" + spacer)]
 *   瞬間    → [Column("瞬間" + "しゅんかん")]
 *   スタジオ → [Column("スタジオ" + spacer)]
 */
@Composable
fun FuriganaText(
    surface: String,
    reading: String?,
    modifier: Modifier = Modifier,
    textColor: Color = LocalContentColor.current,
    furiganaColor: Color = MutedFuriganaColor,
    bold: Boolean = false,
    fontSize: TextUnit = 18.sp,
    furiganaSize: TextUnit = 10.sp,
    furiganaAlpha: Float = 1f,
) {
    val parts = if (reading.isNullOrBlank()) listOf(KanjiKanaSplit.Part(surface))
    else KanjiKanaSplit.split(surface, reading)

    Row(modifier = modifier) {
        for (part in parts) {
            FuriganaPart(
                text = part.text,
                furigana = part.furigana,
                textColor = textColor,
                furiganaColor = furiganaColor,
                furiganaAlpha = furiganaAlpha,
                bold = bold,
                fontSize = fontSize,
                furiganaSize = furiganaSize,
            )
        }
    }
}

/**
 * One kanji-or-kana segment. Uses a custom [Layout] so the segment's reported
 * width equals the *word* (lower) glyph's width, while the furigana glyph can
 * overhang past the segment edges. Consequence: a row of these segments packs
 * by kanji width — okurigana sit immediately to the right of the kanji and
 * don't get pushed apart by wide readings (per Stage 2 spec).
 *
 * Y-positions are fixed: furigana sits bottom-aligned in [FuriganaBandHeight]
 * (so the glyph snugs against the kanji and clipping slack is at the top of
 * the row); word sits centered in [WordBandHeight] (leaving a few dp gap above
 * the POS underline drawn at the chip's outer bottom edge).
 */
@Composable
private fun FuriganaPart(
    text: String,
    furigana: String?,
    textColor: Color,
    furiganaColor: Color,
    furiganaAlpha: Float,
    bold: Boolean,
    fontSize: TextUnit,
    furiganaSize: TextUnit,
) {
    Layout(
        content = {
            // Slot 0: furigana (always emitted so slot indexing is stable; the
            // Text is empty when no furigana — costs nothing to measure).
            Text(
                text = furigana.orEmpty(),
                color = furiganaColor.copy(alpha = furiganaColor.alpha * furiganaAlpha),
                fontSize = furiganaSize,
                maxLines = 1,
                style = NoPaddingTextStyle,
            )
            // Slot 1: word.
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                style = NoPaddingTextStyle,
            )
        },
    ) { measurables, constraints ->
        // Measure each child without forcing it to fill parent width.
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val furiganaPlaceable = measurables[0].measure(loose)
        val wordPlaceable = measurables[1].measure(loose)

        val width = wordPlaceable.width
        val rowHeight = (FuriganaBandHeight + WordBandHeight).roundToPx()

        layout(width, rowHeight) {
            // Furigana: bottom-aligned inside the upper band, centered above
            // the word's horizontal midpoint. May extend past 0..width.
            val furiganaY = FuriganaBandHeight.roundToPx() - furiganaPlaceable.height
            val furiganaX = (wordPlaceable.width - furiganaPlaceable.width) / 2
            furiganaPlaceable.place(furiganaX, furiganaY)

            // Word: vertically centered in the lower band.
            val wordY = FuriganaBandHeight.roundToPx() +
                    (WordBandHeight.roundToPx() - wordPlaceable.height) / 2
            wordPlaceable.place(0, wordY)
        }
    }
}

// Fixed bands so every chip — and the inline-punctuation Text in the sentence view —
// occupies the same vertical footprint and aligns naturally inside a FlowRow.
// Sized for 10sp furigana / 18sp word with [NoPaddingTextStyle] (which strips the
// platform's default font-padding so the actual rendered glyph fills the band).
// FuriganaBandHeight has extra room at the top so the visible glyph is never the
// uppermost pixel of the chip; WordBandHeight reserves a few dp of slack below
// the word so the kanji doesn't touch the POS underline.
internal val FuriganaBandHeight = 16.dp
internal val WordBandHeight = 28.dp
/** Total height a chip / inline punctuation should occupy in the sentence view. */
val TokenRowHeight = FuriganaBandHeight + WordBandHeight

internal val MutedFuriganaColor = Color(0xFF78909C)

/**
 * Text style with platform font padding disabled and a centered line-height policy.
 * Compose's default [PlatformTextStyle] reserves extra space above/below each glyph
 * for ascenders/descenders — that padding was pushing the furigana up over the chip's
 * top edge and getting clipped. Removing it makes the rendered text height match the
 * containing Box height predictably.
 */
internal val NoPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

@Preview(showBackground = true)
@Composable
private fun PreviewFurigana() {
    TangoToriTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuriganaText("入った", "はいった")
            FuriganaText("瞬間", "しゅんかん")
            FuriganaText("スタジオ", "すたじお")
            FuriganaText("に", null)
        }
    }
}
