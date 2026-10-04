package com.lumina.reader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// «Живая полка 2.0» app palette (spec §1.1). Every Material role is set
// explicitly, including all surfaceContainer roles, and surfaceTint equals
// surface so tonal elevation never adds a cool tint. Extended colours (wall,
// planks, foil…) live in the frozen LuminaTokens.kt.

/** «Дневная библиотека». */
internal val LuminaLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFA64A23),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DCCB),
    onPrimaryContainer = Color(0xFF4A1A06),
    inversePrimary = Color(0xFFF0A06B),
    secondary = Color(0xFF2F6B55),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE8DC),
    onSecondaryContainer = Color(0xFF0B2A1F),
    tertiary = Color(0xFF7D5F22),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1E2BF),
    onTertiaryContainer = Color(0xFF2B1E05),
    background = Color(0xFFF4ECE1),
    onBackground = Color(0xFF2A211B),
    surface = Color(0xFFFFFAF3),
    onSurface = Color(0xFF2A211B),
    surfaceVariant = Color(0xFFEFE5D7),
    onSurfaceVariant = Color(0xFF6F6256),
    surfaceTint = Color(0xFFFFFAF3),
    inverseSurface = Color(0xFF2A211B),
    inverseOnSurface = Color(0xFFF6EFE6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8F7F70),
    outlineVariant = Color(0xFFE2D5C3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFAF3),
    surfaceContainer = Color(0xFFF7EFE4),
    surfaceContainerHigh = Color(0xFFEFE5D7),
    surfaceContainerHighest = Color(0xFFE8DCCB),
    surfaceContainerLow = Color(0xFFFFFAF3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9DFD1),
)

/** «Вечерняя библиотека». */
internal val LuminaDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFF0A06B),
    onPrimary = Color(0xFF4A1F05),
    primaryContainer = Color(0xFF6A3517),
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = Color(0xFFA64A23),
    secondary = Color(0xFF8CCFB3),
    onSecondary = Color(0xFF0B2A1F),
    secondaryContainer = Color(0xFF1F4D3D),
    onSecondaryContainer = Color(0xFFCFE8DC),
    tertiary = Color(0xFFE2C27A),
    onTertiary = Color(0xFF3A2A06),
    tertiaryContainer = Color(0xFF5A4514),
    onTertiaryContainer = Color(0xFFF6E3B8),
    background = Color(0xFF16110E),
    onBackground = Color(0xFFF1E6D8),
    surface = Color(0xFF1E1813),
    onSurface = Color(0xFFF1E6D8),
    surfaceVariant = Color(0xFF30261F),
    onSurfaceVariant = Color(0xFFB3A493),
    surfaceTint = Color(0xFF1E1813),
    inverseSurface = Color(0xFFF1E6D8),
    inverseOnSurface = Color(0xFF2A211B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A7A6B),
    outlineVariant = Color(0xFF3A2F27),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A2E26),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF30261F),
    surfaceContainerHighest = Color(0xFF3A2E26),
    surfaceContainerLow = Color(0xFF1E1813),
    surfaceContainerLowest = Color(0xFF110D0A),
    surfaceDim = Color(0xFF16110E),
)

/**
 * Chart palette (terracotta, emerald, brass, indigo, plum, ochre) and the
 * heatmap ramp from spec §1.1, for statistics screens.
 */
object LuminaChartPalette {
    val Light: List<Color> = listOf(
        Color(0xFFA64A23), Color(0xFF2F6B55), Color(0xFF7D5F22),
        Color(0xFF3C5A80), Color(0xFF7A4A6E), Color(0xFF9A6A1E),
    )
    val Dark: List<Color> = listOf(
        Color(0xFFF0A06B), Color(0xFF8CCFB3), Color(0xFFE2C27A),
        Color(0xFF9DB4D6), Color(0xFFD7A6C8), Color(0xFFE7B771),
    )
    val HeatmapLight: List<Color> = listOf(
        Color(0xFFEDE3D4), Color(0xFFF3C9A6), Color(0xFFE59A6A), Color(0xFFC96B3A), Color(0xFF9A4419),
    )
    val HeatmapDark: List<Color> = listOf(
        Color(0xFF241C17), Color(0xFF5A3420), Color(0xFF8A4A28), Color(0xFFC26A38), Color(0xFFF0A06B),
    )

    fun series(isDark: Boolean): List<Color> = if (isDark) Dark else Light
    fun heatmap(isDark: Boolean): List<Color> = if (isDark) HeatmapDark else HeatmapLight
}
