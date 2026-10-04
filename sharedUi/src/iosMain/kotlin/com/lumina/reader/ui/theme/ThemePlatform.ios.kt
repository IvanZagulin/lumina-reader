package com.lumina.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
@NonSkippableComposable
internal actual fun SystemBarsAppearance(darkTheme: Boolean) {
    // The status bar style follows the system appearance, as the theme does;
    // the root view controller takes it over with the reader (stage 10e).
}

/** Settings › Accessibility › Motion › Reduce Motion. */
@Composable
actual fun rememberReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

@Composable
actual fun rememberLuminaHaptics(): LuminaHapticFeedback {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { ComposeHaptics(haptics) }
}

/** The Android constants mapped onto Compose's haptic types (UIKit feedback generators). */
private class ComposeHaptics(private val haptics: HapticFeedback) : LuminaHapticFeedback {
    override fun tick() = haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    override fun longPress() = haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    override fun clock() = haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    override fun confirm() = haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    override fun reject() = haptics.performHapticFeedback(HapticFeedbackType.Reject)
}
