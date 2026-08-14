package com.lumina.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = LuminaPrimaryDark,
    onPrimary = ColorTokens.DarkOnPrimary,
    primaryContainer = ColorTokens.DarkPrimaryContainer,
    onPrimaryContainer = ColorTokens.DarkOnPrimaryContainer,
    secondary = LuminaAccent,
    onSecondary = ColorTokens.DarkOnSecondary,
    secondaryContainer = ColorTokens.DarkSecondaryContainer,
    onSecondaryContainer = ColorTokens.DarkOnSecondaryContainer,
    tertiary = LuminaWarm,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkSubtext,
    outline = ColorTokens.DarkOutline,
    outlineVariant = ColorTokens.DarkOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = LuminaPrimary,
    onPrimary = LightSurface,
    primaryContainer = ColorTokens.LightPrimaryContainer,
    onPrimaryContainer = ColorTokens.LightOnPrimaryContainer,
    secondary = LuminaAccent,
    onSecondary = LightSurface,
    secondaryContainer = ColorTokens.LightSecondaryContainer,
    onSecondaryContainer = ColorTokens.LightOnSecondaryContainer,
    tertiary = ColorTokens.LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = LightSubtext,
    outline = ColorTokens.LightOutline,
    outlineVariant = ColorTokens.LightOutlineVariant
)

private object ColorTokens {
    val DarkOnPrimary = Color(0xFF211A68)
    val DarkPrimaryContainer = Color(0xFF30277C)
    val DarkOnPrimaryContainer = Color(0xFFE4E1FF)
    val DarkOnSecondary = Color(0xFF003731)
    val DarkSecondaryContainer = Color(0xFF004F48)
    val DarkOnSecondaryContainer = Color(0xFF7CF8E8)
    val DarkOutline = Color(0xFF767D8D)
    val DarkOutlineVariant = Color(0xFF343B49)

    val LightPrimaryContainer = Color(0xFFE5E1FF)
    val LightOnPrimaryContainer = Color(0xFF251E69)
    val LightSecondaryContainer = Color(0xFF9EF2E7)
    val LightOnSecondaryContainer = Color(0xFF003731)
    val LightTertiary = Color(0xFF9A5700)
    val LightOutline = Color(0xFF777886)
    val LightOutlineVariant = Color(0xFFC8C8D2)
}

private val LuminaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun LuminaReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // `dynamicColor` stays in the API for callers that already pass it, but the
    // Lumina palette is intentionally stable and cover-friendly.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = LuminaShapes,
        content = content
    )
}
