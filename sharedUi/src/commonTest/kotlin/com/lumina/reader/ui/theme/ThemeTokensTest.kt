package com.lumina.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemeTokensTest {

    @Test
    fun highlightSwatchesResolveByHexWithTheLegacyYellowFallback() {
        assertSame(HighlightPalette.Green, HighlightPalette.fromHex("#a8e6a1"))
        assertSame(HighlightPalette.Lilac, HighlightPalette.fromHex("#C9B6F2"))
        assertSame(HighlightPalette.Yellow, HighlightPalette.fromHex("#FFEB3B"))
        assertSame(HighlightPalette.Yellow, HighlightPalette.fromHex(null))
        assertEquals(5, HighlightPalette.all.size)
        assertEquals(0.28f, HighlightPalette.fillAlpha(isDarkTheme = true))
        assertEquals(0.38f, HighlightPalette.fillAlpha(isDarkTheme = false))
    }

    @Test
    fun lightAndDarkPalettesKeepTheirKeyColours() {
        assertFalse(LuminaExtendedColors.Light.isDark)
        assertTrue(LuminaExtendedColors.Dark.isDark)
        assertEquals(Color(0xFFF4ECE1), LuminaExtendedColors.Light.wall)
        assertEquals(Color(0xFF16110E), LuminaExtendedColors.Dark.wall)
        assertEquals(Color(0xFFA64A23), LuminaLightColorScheme.primary)
        assertEquals(Color(0xFFF0A06B), LuminaDarkColorScheme.primary)
        // Tonal elevation never tints: surfaceTint is the surface itself.
        assertEquals(LuminaLightColorScheme.surface, LuminaLightColorScheme.surfaceTint)
        assertEquals(LuminaDarkColorScheme.surface, LuminaDarkColorScheme.surfaceTint)
    }

    @Test
    fun typeScaleUsesLoraForHeadingsAndOnestForTheRest() {
        val onest = FontFamily.SansSerif
        val lora = FontFamily.Serif
        val type = luminaTypography(onest, lora)
        listOf(type.displayLarge, type.displayMedium, type.displaySmall, type.headlineLarge, type.headlineMedium, type.headlineSmall)
            .forEach { assertSame(lora, it.fontFamily) }
        listOf(
            type.titleLarge, type.titleMedium, type.titleSmall, type.bodyLarge, type.bodyMedium, type.bodySmall,
            type.labelLarge, type.labelMedium, type.labelSmall
        ).forEach { assertSame(onest, it.fontFamily) }
        assertEquals(19.sp, type.headlineSmall.fontSize)
        assertEquals(FontWeight.Medium, type.headlineSmall.fontWeight)
        assertEquals(16.sp, type.bodyLarge.fontSize)
        assertEquals(24.sp, type.bodyLarge.lineHeight)

        val fonts = LuminaFonts(onest, lora)
        assertEquals("tnum", fonts.tabular.fontFeatureSettings)
        assertEquals(type.labelMedium.fontSize, fonts.tabular.fontSize)
        assertEquals("tnum", fonts.numeralLarge.fontFeatureSettings)
        assertSame(lora, fonts.numeralLarge.fontFamily)
        assertEquals(1.2.sp, fonts.eyebrow.letterSpacing)
    }
}
