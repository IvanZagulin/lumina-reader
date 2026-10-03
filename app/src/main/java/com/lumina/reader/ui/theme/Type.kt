package com.lumina.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// App type scale (spec §1.4): Lora SemiBold/Medium for display and headline
// styles, Onest for everything else.

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

val LuminaTypography: Typography = Typography(
    displayLarge = style(LoraFamily, FontWeight.SemiBold, 40.sp, 48.sp, (-0.6).sp),
    displayMedium = style(LoraFamily, FontWeight.SemiBold, 34.sp, 40.sp, (-0.4).sp),
    displaySmall = style(LoraFamily, FontWeight.SemiBold, 28.sp, 34.sp, (-0.3).sp),
    headlineLarge = style(LoraFamily, FontWeight.SemiBold, 26.sp, 32.sp, (-0.2).sp),
    headlineMedium = style(LoraFamily, FontWeight.SemiBold, 22.sp, 28.sp, (-0.2).sp),
    headlineSmall = style(LoraFamily, FontWeight.Medium, 19.sp, 24.sp),
    titleLarge = style(OnestFamily, FontWeight.SemiBold, 18.sp, 24.sp),
    titleMedium = style(OnestFamily, FontWeight.SemiBold, 16.sp, 22.sp, 0.1.sp),
    titleSmall = style(OnestFamily, FontWeight.SemiBold, 14.sp, 20.sp, 0.1.sp),
    bodyLarge = style(OnestFamily, FontWeight.Normal, 16.sp, 24.sp),
    bodyMedium = style(OnestFamily, FontWeight.Normal, 14.sp, 20.sp),
    bodySmall = style(OnestFamily, FontWeight.Normal, 12.sp, 16.sp),
    labelLarge = style(OnestFamily, FontWeight.SemiBold, 14.sp, 20.sp, 0.1.sp),
    labelMedium = style(OnestFamily, FontWeight.Medium, 12.sp, 16.sp, 0.3.sp),
    labelSmall = style(OnestFamily, FontWeight.Medium, 11.sp, 14.sp, 0.4.sp),
)

/** Extra LIBRARY styles beyond the Material scale. */
object LuminaType {
    /** Section eyebrows; use with `.uppercase()`. */
    val eyebrow: TextStyle = style(OnestFamily, FontWeight.SemiBold, 11.sp, 14.sp, 1.2.sp)

    /** Big statistics numbers with tabular figures. */
    val numeralLarge: TextStyle = style(LoraFamily, FontWeight.SemiBold, 34.sp, 40.sp)
        .copy(fontFeatureSettings = "tnum")

    /** labelMedium with tabular figures (percentages, counters). */
    val tabular: TextStyle = LuminaTypography.labelMedium.copy(fontFeatureSettings = "tnum")
}
