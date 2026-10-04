package com.lumina.reader.ui.theme

import android.app.Activity
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// The Android side of the design system: the code that lived in :app's
// ui/theme before the move, unchanged.

@Composable
@NonSkippableComposable
internal actual fun SystemBarsAppearance(darkTheme: Boolean) {
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
}

/** True when the user disabled animations (Developer options / Accessibility "Remove animations"). */
@Composable
actual fun rememberReducedMotion(): Boolean {
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

@Composable
actual fun rememberLuminaHaptics(): LuminaHapticFeedback {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}

/** [LuminaHaptics] bound to one view. */
private class ViewHaptics(private val view: View) : LuminaHapticFeedback {
    override fun tick() = LuminaHaptics.tick(view)
    override fun longPress() = LuminaHaptics.longPress(view)
    override fun clock() = LuminaHaptics.clock(view)
    override fun confirm() = LuminaHaptics.confirm(view)
    override fun reject() = LuminaHaptics.reject(view)
}
