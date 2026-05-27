package com.tangotori.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Tango Tori palette from spec
val LogoRed = Color(0xFFAB2F1F)
val OnPrimary = Color(0xFFFFFFFF)
// Parchment — sampled from the launcher icon so app body, splash, and
// adaptive-icon background are exactly the same tone.
val SurfaceLight = Color(0xFFF7ECE0)
val SurfaceDark = Color(0xFF1A1A1A)
val OnSurfaceLight = Color(0xFF1A1A1A)
val OnSurfaceDark = Color(0xFFF7ECE0)
// Slightly darker parchment — used for the expanded-word header card so
// the headword pops against the body without breaking the warm palette.
val HeaderTintLight = Color(0xFFE8D8C5)
val HeaderTintDark = Color(0xFF2A2620)
// Even slightly more beige than the page surface — used for the body of the
// unfurled dictionary card so it reads as visually attached to the red
// header above it (but distinct from the rest of the list).
val BodyTintLight = Color(0xFFEFE0CD)
val BodyTintDark = Color(0xFF221F1A)

/** Theme-aware accessor for the unfurled-card background. Reads the system
 *  dark-mode flag so the card stays distinct from the page surface in both
 *  modes (the hardcoded light tint was bleaching out in dark mode). */
val BodyTint: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) BodyTintDark else BodyTintLight

// Part-of-speech colors (used as text / underline color, not background)
val PosNoun = Color(0xFFC8A882)
val PosVerb = Color(0xFFC0392B)
val PosIAdjective = Color(0xFFE07B54)
val PosNaAdjective = Color(0xFFD4956A)
val PosParticle = Color(0xFF888888)
val PosAdverb = Color(0xFF8B9E6E)
val PosAuxiliaryVerb = Color(0xFFA05050)
val PosConjunctionOther = Color(0xFFAAAAAA)
// Compound words (FallbackSplit — multiple CC-CEDICT sub-units)
val PosCompound = Color(0xFF6B85A0)
