package com.lumina.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.ui.unit.dp

/** Material shapes (spec §1.5): 6 / 12 / 16 / 24 / 32 dp. */
private val LuminaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * The app theme: warm «Дневная / Вечерняя библиотека» colour schemes, Onest and
 * Lora typography, and the extended Lumina colours through [LocalLuminaColors]
 * (read them with `Lumina.colors`). The app follows the system dark mode.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LuminaReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // `dynamicColor` stays in the API for callers that already pass it; the
    // Lumina palette is intentionally stable and cover-friendly.
    val colorScheme = if (darkTheme) LuminaDarkColorScheme else LuminaLightColorScheme
    val extended = if (darkTheme) LuminaExtendedColors.Dark else LuminaExtendedColors.Light

    SystemBarsAppearance(darkTheme)

    val fonts = rememberLuminaFonts()
    CompositionLocalProvider(
        LocalLuminaColors provides extended,
        LocalLuminaFonts provides fonts
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = fonts.typography,
            shapes = LuminaShapes,
            content = content
        )
    }
}

/**
 * Transparent system bars whose icons follow [darkTheme]. Android: the
 * activity window's status and navigation bars, re-applied on every
 * recomposition of the theme as when this code was inline (hence not
 * skippable); iOS: nothing yet (the status bar follows the system appearance,
 * which is what the theme follows too).
 */
@Composable
@NonSkippableComposable
internal expect fun SystemBarsAppearance(darkTheme: Boolean)
