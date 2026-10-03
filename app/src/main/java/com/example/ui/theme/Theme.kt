package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = OlloPrimaryButtonFill,
    onPrimary = OlloPrimaryButtonText,
    primaryContainer = OlloSurfaceElevated,
    onPrimaryContainer = OlloOnBackground,
    secondary = OlloAccent,
    onSecondary = OlloBackground,
    secondaryContainer = OlloAccentMuted,
    onSecondaryContainer = OlloAccent,
    tertiary = OlloAccent,
    onTertiary = OlloBackground,
    background = OlloBackground,
    onBackground = OlloOnBackground,
    surface = OlloSurface,
    onSurface = OlloOnBackground,
    surfaceVariant = OlloSurfaceElevated,
    onSurfaceVariant = OlloTextMuted,
    outline = OlloOutline,
    outlineVariant = OlloOutline,
    error = OlloError,
    onError = OlloBackground
)

object OlloTheme {
    val colors: OlloExtendedColors
        @Composable
        get() = LocalOlloColors.current
}

@Composable
fun OllOTheme(
    content: @Composable () -> Unit
) {
    val extendedColors = OlloExtendedColors()

    CompositionLocalProvider(
        LocalOlloColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = OlloTypography,
            shapes = OlloShapes,
            content = content
        )
    }
}
