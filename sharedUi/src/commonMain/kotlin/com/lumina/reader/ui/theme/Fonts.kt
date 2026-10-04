package com.lumina.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lumina.reader.sharedui.resources.Res
import com.lumina.reader.sharedui.resources.lora_variable
import com.lumina.reader.sharedui.resources.onest_variable
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

// UI typefaces (spec §1.4). Both files are subset variable fonts (wght axis)
// shipped as Compose Multiplatform resources (composeResources/font), so
// Android and iOS load the same files. Font(resource, weight) sets the `wght`
// variation for each declared weight, exactly like Font(R.font.x, weight) did,
// so one file serves every weight.
//
// Loading a resource font is a composable call, so the families are created
// once by LuminaReaderTheme and handed down through LocalLuminaFonts; the
// accessors below read them from there.

/** Onest: the clean Cyrillic grotesque used for all UI text. */
val OnestFamily: FontFamily
    @Composable get() = currentLuminaFonts().onest

/** Lora: display headings only (screen titles, shelf names, generated covers). */
val LoraFamily: FontFamily
    @Composable get() = currentLuminaFonts().lora

/** The UI font families and the text styles built from them. */
@Immutable
internal class LuminaFonts(val onest: FontFamily, val lora: FontFamily) {
    /** The Material type scale (see Type.kt). */
    val typography: Typography = luminaTypography(onest, lora)

    internal val eyebrow: TextStyle = eyebrowStyle(onest)
    internal val numeralLarge: TextStyle = numeralLargeStyle(lora)
    internal val tabular: TextStyle = tabularStyle(typography)
}

/** Provided by [LuminaReaderTheme]; null outside it (previews, tests). */
internal val LocalLuminaFonts = staticCompositionLocalOf<LuminaFonts?> { null }

/** The fonts of the enclosing theme, or freshly loaded ones outside a theme. */
@Composable
internal fun currentLuminaFonts(): LuminaFonts = LocalLuminaFonts.current ?: rememberLuminaFonts()

/**
 * Loads the UI fonts. The resource fonts are equal from one composition to
 * the next, so the remembered families (and every style built from them) keep
 * their identity and never invalidate text below the theme.
 */
@Composable
internal fun rememberLuminaFonts(): LuminaFonts {
    val onest = rememberVariableFamily(Res.font.onest_variable)
    val lora = rememberVariableFamily(Res.font.lora_variable)
    return remember(onest, lora) { LuminaFonts(onest, lora) }
}

@Composable
private fun rememberVariableFamily(resource: FontResource): FontFamily {
    val normal = Font(resource, FontWeight.Normal)
    val medium = Font(resource, FontWeight.Medium)
    val semiBold = Font(resource, FontWeight.SemiBold)
    val bold = Font(resource, FontWeight.Bold)
    return remember(normal, medium, semiBold, bold) { FontFamily(normal, medium, semiBold, bold) }
}
