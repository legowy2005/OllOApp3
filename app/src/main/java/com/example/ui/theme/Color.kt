package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// OllO Palette Tokens (Dark Only)
val OlloBackground = Color(0xFF282828)
val OlloOnBackground = Color(0xFFD9D9D9)
val OlloSurface = Color(0xFF2F2F2F)
val OlloSurfaceElevated = Color(0xFF383838)
val OlloOutline = Color(0xFF4A4A4A)
val OlloTextMuted = Color(0xFFA8A8A8)
val OlloTextFaint = Color(0xFF7D7D7D)

val OlloPrimaryButtonFill = Color(0xFFD9D9D9)
val OlloPrimaryButtonText = Color(0xFF282828)

// Accent: Soft Sage
val OlloAccent = Color(0xFF7FB5A5)
val OlloAccentMuted = Color(0xFF2E403B)

// Status colors
val OlloError = Color(0xFFE57373)
val OlloWarning = Color(0xFFFFB74D)
val OlloSuccess = Color(0xFF81C784)

@Immutable
data class OlloExtendedColors(
    val background: Color = OlloBackground,
    val onBackground: Color = OlloOnBackground,
    val surface: Color = OlloSurface,
    val surfaceElevated: Color = OlloSurfaceElevated,
    val outline: Color = OlloOutline,
    val textMuted: Color = OlloTextMuted,
    val textFaint: Color = OlloTextFaint,
    val primaryButtonFill: Color = OlloPrimaryButtonFill,
    val primaryButtonText: Color = OlloPrimaryButtonText,
    val accent: Color = OlloAccent,
    val accentMuted: Color = OlloAccentMuted,
    val error: Color = OlloError,
    val warning: Color = OlloWarning,
    val success: Color = OlloSuccess
)

val LocalOlloColors = staticCompositionLocalOf { OlloExtendedColors() }
