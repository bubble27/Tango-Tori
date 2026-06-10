package com.tangotori.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tangotori.app.domain.models.PartOfSpeech
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = LogoRed,
    onPrimary = OnPrimary,
    surface = SurfaceLight,
    background = SurfaceLight,
    onSurface = OnSurfaceLight,
    onBackground = OnSurfaceLight,
    secondary = LogoRed,
    onSecondary = OnPrimary,
    // Surface container hierarchy — warm parchment tones so dialogs, menus,
    // segmented toggles, and dropdowns all stay on-palette instead of using
    // Material3's default grey.
    surfaceVariant = HeaderTintLight,            // language toggle bg, chips
    onSurfaceVariant = OnSurfaceLight,
    surfaceContainer = BodyTintLight,            // dropdown menu bg
    surfaceContainerLow = SurfaceLight,
    surfaceContainerHigh = HeaderTintLight,      // AlertDialog bg
    surfaceContainerHighest = HeaderTintLight,
)

private val DarkColors = darkColorScheme(
    primary = LogoRed,
    onPrimary = OnPrimary,
    surface = SurfaceDark,
    background = SurfaceDark,
    onSurface = OnSurfaceDark,
    onBackground = OnSurfaceDark,
    secondary = LogoRed,
    onSecondary = OnPrimary,
    surfaceVariant = HeaderTintDark,
    onSurfaceVariant = OnSurfaceDark,
    surfaceContainer = BodyTintDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerHigh = HeaderTintDark,
    surfaceContainerHighest = HeaderTintDark,
)

@Composable
fun TangoToriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TangoTypography,
    ) {
        // Many composables call Text(...) with only fontSize / fontWeight set
        // (no fontFamily) — without this override they'd fall through to
        // FontFamily.Default (system sans). Pin the serif family for every
        // bare Text via the LocalTextStyle composition local.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = SerifMixed),
            content = content,
        )
    }
}

/** Human-readable POS label for Chinese tokens (from Jieba tags via [PartOfSpeech]). */
fun PartOfSpeech.toChinesePosLabel(): String? = when (this) {
    PartOfSpeech.NOUN            -> "Noun"
    PartOfSpeech.VERB            -> "Verb"
    PartOfSpeech.I_ADJECTIVE     -> "Adjective"
    PartOfSpeech.NA_ADJECTIVE    -> "Adjective"
    PartOfSpeech.ADVERB          -> "Adverb"
    PartOfSpeech.PARTICLE        -> "Particle"
    PartOfSpeech.AUXILIARY_VERB  -> "Auxiliary verb"
    PartOfSpeech.CONJUNCTION_OTHER -> "Conjunction / Preposition"
    PartOfSpeech.PUNCTUATION     -> null
}

fun PartOfSpeech.toColor(): Color = when (this) {
    PartOfSpeech.NOUN -> PosNoun
    PartOfSpeech.VERB -> PosVerb
    PartOfSpeech.I_ADJECTIVE -> PosIAdjective
    PartOfSpeech.NA_ADJECTIVE -> PosNaAdjective
    PartOfSpeech.PARTICLE -> PosParticle
    PartOfSpeech.ADVERB -> PosAdverb
    PartOfSpeech.AUXILIARY_VERB -> PosAuxiliaryVerb
    PartOfSpeech.CONJUNCTION_OTHER -> PosConjunctionOther
    PartOfSpeech.PUNCTUATION -> PosConjunctionOther
}
