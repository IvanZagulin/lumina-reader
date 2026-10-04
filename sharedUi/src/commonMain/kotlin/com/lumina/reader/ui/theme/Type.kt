package com.lumina.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// App type scale (spec §1.4): Lora SemiBold/Medium for display and headline
// styles, Onest for everything else. The styles are built once per font load
// (LuminaFonts) and read through the composable accessors below.

private fun style(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    tracking: TextUnit = 0.sp
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = tracking
)

internal fun luminaTypography(onest: FontFamily, lora: FontFamily): Typography = Typography(
    displayLarge = style(lora, FontWeight.SemiBold, 40.sp, 48.sp, (-0.6).sp),
    displayMedium = style(lora, FontWeight.SemiBold, 34.sp, 40.sp, (-0.4).sp),
    displaySmall = style(lora, FontWeight.SemiBold, 28.sp, 34.sp, (-0.3).sp),
    headlineLarge = style(lora, FontWeight.SemiBold, 26.sp, 32.sp, (-0.2).sp),
    headlineMedium = style(lora, FontWeight.SemiBold, 22.sp, 28.sp, (-0.2).sp),
    headlineSmall = style(lora, FontWeight.Medium, 19.sp, 24.sp),
    titleLarge = style(onest, FontWeight.SemiBold, 18.sp, 24.sp),
    titleMedium = style(onest, FontWeight.SemiBold, 16.sp, 22.sp, 0.1.sp),
    titleSmall = style(onest, FontWeight.SemiBold, 14.sp, 20.sp, 0.1.sp),
    bodyLarge = style(onest, FontWeight.Normal, 16.sp, 24.sp),
    bodyMedium = style(onest, FontWeight.Normal, 14.sp, 20.sp),
    bodySmall = style(onest, FontWeight.Normal, 12.sp, 16.sp),
    labelLarge = style(onest, FontWeight.SemiBold, 14.sp, 20.sp, 0.1.sp),
    labelMedium = style(onest, FontWeight.Medium, 12.sp, 16.sp, 0.3.sp),
    labelSmall = style(onest, FontWeight.Medium, 11.sp, 14.sp, 0.4.sp),
)

internal fun eyebrowStyle(onest: FontFamily): TextStyle = style(onest, FontWeight.SemiBold, 11.sp, 14.sp, 1.2.sp)

internal fun numeralLargeStyle(lora: FontFamily): TextStyle =
    style(lora, FontWeight.SemiBold, 34.sp, 40.sp).copy(fontFeatureSettings = "tnum")

internal fun tabularStyle(typography: Typography): TextStyle =
    typography.labelMedium.copy(fontFeatureSettings = "tnum")

/** The app's Material type scale (the one [LuminaReaderTheme] installs). */
val LuminaTypography: Typography
    @Composable get() = currentLuminaFonts().typography

/** Extra LIBRARY styles beyond the Material scale. */
object LuminaType {
    /** Section eyebrows; use with `.uppercase()`. */
    val eyebrow: TextStyle
        @Composable get() = currentLuminaFonts().eyebrow

    /** Big statistics numbers with tabular figures. */
    val numeralLarge: TextStyle
        @Composable get() = currentLuminaFonts().numeralLarge

    /** labelMedium with tabular figures (percentages, counters). */
    val tabular: TextStyle
        @Composable get() = currentLuminaFonts().tabular
}
