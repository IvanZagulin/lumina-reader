package com.lumina.reader.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape

/**
 * Chart palette and heatmap ramp of the statistics (spec §1.1), replacing the
 * old hard-coded blue/indigo/purple colours. Light and dark variants follow
 * the app's colour scheme.
 */
@Immutable
data class StatsPalette(
    val terracotta: Color,
    val emerald: Color,
    val brass: Color,
    val indigo: Color,
    val plum: Color,
    val ochre: Color,
    /** Five steps from "no reading" to "a lot of reading". */
    val heat: List<Color>
) {
    /** The six series colours in order. */
    val series: List<Color>
        get() = listOf(terracotta, emerald, brass, indigo, plum, ochre)

    /** Ramp colour for a 0..1 share of the busiest day: 0 → empty cell, then four steps. */
    fun heatColor(ratio: Float): Color {
        if (ratio <= 0f) return heat.first()
        val step = (ratio * (heat.size - 1)).toInt().coerceIn(0, heat.size - 2) + 1
        return heat[step]
    }

    companion object {
        val Light = StatsPalette(
            terracotta = Color(0xFFA64A23),
            emerald = Color(0xFF2F6B55),
            brass = Color(0xFF7D5F22),
            indigo = Color(0xFF3C5A80),
            plum = Color(0xFF7A4A6E),
            ochre = Color(0xFF9A6A1E),
            heat = listOf(
                Color(0xFFEDE3D4),
                Color(0xFFF3C9A6),
                Color(0xFFE59A6A),
                Color(0xFFC96B3A),
                Color(0xFF9A4419)
            )
        )
        val Dark = StatsPalette(
            terracotta = Color(0xFFF0A06B),
            emerald = Color(0xFF8CCFB3),
            brass = Color(0xFFE2C27A),
            indigo = Color(0xFF9DB4D6),
            plum = Color(0xFFD7A6C8),
            ochre = Color(0xFFE7B771),
            heat = listOf(
                Color(0xFF241C17),
                Color(0xFF5A3420),
                Color(0xFF8A4A28),
                Color(0xFFC26A38),
                Color(0xFFF0A06B)
            )
        )

        /** Dark icon on brass medallions. */
        val MedallionInk = Color(0xFF3A2A06)
    }
}

/** True when the app scheme is dark (works with or without the extended Lumina colours). */
@Composable
@ReadOnlyComposable
internal fun statsIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
@ReadOnlyComposable
internal fun statsPalette(): StatsPalette = if (statsIsDark()) StatsPalette.Dark else StatsPalette.Light

/** Lamp glow of the hero, or a faint primary wash when the extended palette is not provided. */
@Composable
@ReadOnlyComposable
internal fun statsLampGlow(): Color {
    val extended = Lumina.colors
    return if (extended.isDark == statsIsDark()) extended.lampGlow else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
}

/** Brass medallion gradient (foilLight → foilDark). */
internal val MedallionLight = Color(0xFFF0D69A)
internal val MedallionDark = Color(0xFFB98D3E)

/** Flat «LuminaCard» look: radius 20 (24 for big cards) and a 1dp outlineVariant hairline. */
internal val StatsCardShape = LuminaShape.Card
internal val StatsBigCardShape = RoundedCornerShape(24.dp)

@Composable
@ReadOnlyComposable
internal fun statsCardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** Big numbers: `LuminaType.numeralLarge` (Lora SemiBold 34/40, tabular) = displayMedium + "tnum". */
@Composable
@ReadOnlyComposable
internal fun statsNumeralStyle(): androidx.compose.ui.text.TextStyle =
    MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum")

/**
 * Achievement medallion (spec §7.11): a radial foilLight → foilDark disc with
 * a dark icon; locked ones are outlineVariant at 40%.
 */
@Composable
internal fun BrassMedallion(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    locked: Boolean = false
) {
    val lockedColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val lockedInk = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawWithCache {
                val foil = Brush.radialGradient(
                    colors = listOf(MedallionLight, MedallionDark),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.3f),
                    radius = this.size.maxDimension * 0.75f
                )
                onDrawBehind {
                    if (locked) drawRect(lockedColor) else drawRect(foil)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (locked) lockedInk else StatsPalette.MedallionInk,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
