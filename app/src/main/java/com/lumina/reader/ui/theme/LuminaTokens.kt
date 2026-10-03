package com.lumina.reader.ui.theme

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Frozen design contract shared by LIBRARY and READER. Do not edit existing declarations. */

@Immutable
data class LuminaExtendedColors(
    val isDark: Boolean,
    val wall: Color,
    val wallShade: Color,
    val surfaceSunken: Color,
    val lampGlow: Color,
    val plankTop: Color,
    val plankFront: Color,
    val plankHighlight: Color,
    val plankShadow: Color,
    val pageEdge: Color,
    val pageEdgeLine: Color,
    val bookShadow: Color,
    val foilLight: Color,
    val foilDark: Color,
    val brass: Color,
    val success: Color,
    val onSuccess: Color,
    val favorite: Color,
    val ribbon: Color,
    val inverseCapsule: Color,
    val onInverseCapsule: Color,
    val scrim: Color,
) {
    companion object {
        val Light = LuminaExtendedColors(
            isDark = false,
            wall = Color(0xFFF4ECE1), wallShade = Color(0xFFE7DCCB), surfaceSunken = Color(0xFFEDE3D4),
            lampGlow = Color(0xB3FFF4E0),
            plankTop = Color(0xFFD2A879), plankFront = Color(0xFFA9784B),
            plankHighlight = Color(0x99E8C9A0), plankShadow = Color(0x473A2614),
            pageEdge = Color(0xFFF3EAD9), pageEdgeLine = Color(0xFFD9CCB5),
            bookShadow = Color(0x592A1608),
            foilLight = Color(0xFFF0D69A), foilDark = Color(0xFFB98D3E), brass = Color(0xFFC9A45C),
            success = Color(0xFF2F6B55), onSuccess = Color(0xFFFFFFFF),
            favorite = Color(0xFFD6455D), ribbon = Color(0xFFA64A23),
            inverseCapsule = Color(0xFF2A211B), onInverseCapsule = Color(0xFFF6EFE6),
            scrim = Color(0xFF1A120C),
        )
        val Dark = LuminaExtendedColors(
            isDark = true,
            wall = Color(0xFF16110E), wallShade = Color(0xFF0B0807), surfaceSunken = Color(0xFF1B1511),
            lampGlow = Color(0x24FFB45E),
            plankTop = Color(0xFF5A3F2C), plankFront = Color(0xFF3E2A1D),
            plankHighlight = Color(0x807A583D), plankShadow = Color(0x8C000000),
            pageEdge = Color(0xFFCBBFAE), pageEdgeLine = Color(0xFF9F937F),
            bookShadow = Color(0x8C000000),
            foilLight = Color(0xFFF0D69A), foilDark = Color(0xFFB98D3E), brass = Color(0xFFE2C27A),
            success = Color(0xFF8CCFB3), onSuccess = Color(0xFF0B2A1F),
            favorite = Color(0xFFFF8FA3), ribbon = Color(0xFFF0A06B),
            inverseCapsule = Color(0xFFF1E6D8), onInverseCapsule = Color(0xFF1E1712),
            scrim = Color(0xFF000000),
        )
    }
}

val LocalLuminaColors = staticCompositionLocalOf { LuminaExtendedColors.Light }

object Lumina {
    val colors: LuminaExtendedColors
        @Composable @ReadOnlyComposable get() = LocalLuminaColors.current
}

object LuminaMotion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Hinge: Easing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

    const val DurationShort = 120
    const val DurationMedium = 240
    const val DurationLong = 420
    const val PageTurnTapMs = 280
    const val CurlTapMs = 360

    /**
     * Sign of graphicsLayer.rotationY that makes a layer pinned at TransformOrigin(0f, .5f)
     * swing its RIGHT edge TOWARD the viewer (book cover opening, page flip).
     * Verify once on a device: rotationY = HingeSign * 60f must make the right edge look TALLER.
     */
    const val HingeSign = -1f

    fun <T> lift(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 400f)
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 300f)
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
    fun <T> ribbon(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 500f)
    fun <T> pageRelease(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 300f)
}

object LuminaShape {
    val Book = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(20.dp)
    val Tile = RoundedCornerShape(14.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Panel = RoundedCornerShape(28.dp)
}

object LuminaDimens {
    val TouchTarget = 48.dp
    val ScreenGutter = 20.dp
    val ChromeMargin = 12.dp
    val CapsuleHeight = 56.dp
    val DockHeight = 64.dp
    val DockClearance = 112.dp
    val Hairline = 1.dp
}

object HighlightPalette {
    @Immutable
    data class Swatch(val id: String, val label: String, val hex: String, val color: Color)

    val Yellow = Swatch("yellow", "Жёлтый", "#F6D365", Color(0xFFF6D365))
    val Green = Swatch("green", "Зелёный", "#A8E6A1", Color(0xFFA8E6A1))
    val Blue = Swatch("blue", "Голубой", "#9AD0F5", Color(0xFF9AD0F5))
    val Pink = Swatch("pink", "Розовый", "#F5A3C7", Color(0xFFF5A3C7))
    val Lilac = Swatch("lilac", "Сиреневый", "#C9B6F2", Color(0xFFC9B6F2))
    val all: List<Swatch> = listOf(Yellow, Green, Blue, Pink, Lilac)

    fun fromHex(hex: String?): Swatch =
        all.firstOrNull { it.hex.equals(hex, ignoreCase = true) } ?: Yellow // legacy #FFEB3B -> Yellow

    fun fillAlpha(isDarkTheme: Boolean): Float = if (isDarkTheme) 0.28f else 0.38f
}

/** True when the user disabled animations (Developer options / Accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

object LuminaHaptics {
    fun tick(view: View) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun longPress(view: View) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun clock(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        )
    }
    fun reject(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        )
    }
}
