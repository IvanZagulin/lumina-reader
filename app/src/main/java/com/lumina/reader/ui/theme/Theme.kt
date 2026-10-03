package com.lumina.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalLuminaColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LuminaTypography,
            shapes = LuminaShapes,
            content = content
        )
    }
}
