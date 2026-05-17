package com.tangotori.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App typeface — Android's bundled system serif (Noto Serif for Latin, with
 * the system's CJK serif fallback handling kanji + kana). We use this rather
 * than a downloadable Google Font because the Google Fonts provider depends
 * on Play Services fetching the font on first use and silently falls back to
 * sans-serif on any failure (which is what was happening on-device).
 *
 * FontFamily.Serif is part of the platform font stack on every Android device
 * Tango Tori targets (API 26+), so the serif look is guaranteed.
 */
val SerifMixed: FontFamily = FontFamily.Serif

val TangoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SerifMixed,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)
