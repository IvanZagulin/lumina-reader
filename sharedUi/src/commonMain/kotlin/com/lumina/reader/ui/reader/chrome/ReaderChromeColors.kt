package com.lumina.reader.ui.reader.chrome

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.lumina.reader.core.model.ReadingTheme

/**
 * Colours of everything the reader draws around the text: capsules, sheets,
 * menus. They come from the reading theme only, never from the app colour
 * scheme, so the chrome always matches the page (design spec §4.2).
 */
@Immutable
data class ReaderChromeColors(
    val isDark: Boolean,
    /** The page colour. */
    val page: Color,
    /** Opaque theme surface: sheets and cards. */
    val surface: Color,
    /** Floating capsules: the surface at 94 %. */
    val bg: Color,
    /** 1dp hairline around capsules. */
    val border: Color,
    val content: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    /** Selected rows and chips: accent at 14 % on the surface. */
    val selectedBg: Color,
    /** Selection menu and snackbars. */
    val inverseBg: Color,
    val inverseContent: Color,
    val error: Color
) {
    companion object {
        private val InverseOnLight = Color(0xFF1F1A16)
        private val InverseOnLightContent = Color(0xFFF6F1EA)
        private val InverseOnDark = Color(0xFFF2EEE6)
        private val InverseOnDarkContent = Color(0xFF1A1612)

        fun of(theme: ReadingTheme): ReaderChromeColors {
            val surface = theme.surfaceComposeColor
            val text = theme.textComposeColor
            val accent = theme.accentComposeColor
            return ReaderChromeColors(
                isDark = theme.isDark,
                page = theme.bgComposeColor,
                surface = surface,
                bg = surface.copy(alpha = 0.94f),
                border = text.copy(alpha = if (theme.isDark) 0.12f else 0.08f),
                content = text,
                muted = theme.secondaryTextComposeColor,
                accent = accent,
                onAccent = theme.onAccentComposeColor,
                selectedBg = accent.copy(alpha = 0.14f).compositeOver(surface),
                inverseBg = if (theme.isDark) InverseOnDark else InverseOnLight,
                inverseContent = if (theme.isDark) InverseOnDarkContent else InverseOnLightContent,
                error = if (theme.isDark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
            )
        }
    }
}

@Composable
fun rememberReaderChromeColors(theme: ReadingTheme): ReaderChromeColors =
    remember(theme) { ReaderChromeColors.of(theme) }

/**
 * A Material colour scheme made of the reader colours, so stock components
 * (switches, sliders, text fields, menus) inside reader sheets look like the
 * page instead of the library.
 */
fun ReaderChromeColors.toColorScheme(): ColorScheme {
    val faint = content.copy(alpha = 0.06f).compositeOver(surface)
    val tint = content.copy(alpha = 0.10f).compositeOver(surface)
    val strong = content.copy(alpha = 0.14f).compositeOver(surface)
    val outline = content.copy(alpha = 0.38f).compositeOver(surface)
    val hairline = content.copy(alpha = 0.12f).compositeOver(surface)
    val errorContainer = error.copy(alpha = 0.16f).compositeOver(surface)
    return if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = selectedBg, onPrimaryContainer = content,
            inversePrimary = accent,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = selectedBg, onSecondaryContainer = content,
            tertiary = accent, onTertiary = onAccent,
            tertiaryContainer = selectedBg, onTertiaryContainer = content,
            background = page, onBackground = content,
            surface = surface, onSurface = content,
            surfaceVariant = faint, onSurfaceVariant = muted,
            surfaceTint = surface,
            inverseSurface = inverseBg, inverseOnSurface = inverseContent,
            error = error, onError = page,
            errorContainer = errorContainer, onErrorContainer = content,
            outline = outline, outlineVariant = hairline,
            scrim = Color.Black,
            surfaceBright = strong, surfaceDim = page,
            surfaceContainer = faint, surfaceContainerHigh = tint, surfaceContainerHighest = strong,
            surfaceContainerLow = surface, surfaceContainerLowest = page
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = selectedBg, onPrimaryContainer = content,
            inversePrimary = accent,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = selectedBg, onSecondaryContainer = content,
            tertiary = accent, onTertiary = onAccent,
            tertiaryContainer = selectedBg, onTertiaryContainer = content,
            background = page, onBackground = content,
            surface = surface, onSurface = content,
            surfaceVariant = faint, onSurfaceVariant = muted,
            surfaceTint = surface,
            inverseSurface = inverseBg, inverseOnSurface = inverseContent,
            error = error, onError = Color.White,
            errorContainer = errorContainer, onErrorContainer = content,
            outline = outline, outlineVariant = hairline,
            scrim = Color.Black,
            surfaceBright = page, surfaceDim = strong,
            surfaceContainer = faint, surfaceContainerHigh = tint, surfaceContainerHighest = strong,
            surfaceContainerLow = surface, surfaceContainerLowest = page
        )
    }
}

/**
 * Material theme for reader chrome and sheets: the reader colours with the
 * app's typography and shapes (Onest once the library theme provides it).
 */
@Composable
fun ReaderMaterialTheme(colors: ReaderChromeColors, content: @Composable () -> Unit) {
    val scheme = remember(colors) { colors.toColorScheme() }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
