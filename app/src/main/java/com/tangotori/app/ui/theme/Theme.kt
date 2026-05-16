package com.tangotori.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
        content = content,
    )
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
