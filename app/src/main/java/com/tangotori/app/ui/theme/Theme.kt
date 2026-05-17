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
