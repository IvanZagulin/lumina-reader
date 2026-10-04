package com.lumina.reader.core.preferences

import android.app.Activity
import android.content.Context
import android.os.Build
import com.lumina.reader.ui.reader.WindowBrightness

/**
 * Centralizes display-related preferences that apply to the whole app.
 * Brightness is stored separately from reader typography settings because it
 * controls the Android window rather than book layout. The brightness code
 * itself is [WindowBrightness] in :sharedUi, which the reader's brightness
 * slider uses too; this object keeps MainActivity's entry points.
 */
object AppDisplayController {
    private const val TARGET_REFRESH_RATE = 120f

    fun useSystemBrightness(context: Context): Boolean =
        WindowBrightness.useSystemBrightness(context)

    fun savedBrightness(context: Context): Float =
        WindowBrightness.savedBrightness(context)

    fun saveBrightness(
        context: Context,
        useSystemBrightness: Boolean,
        brightness: Float
    ) {
        WindowBrightness.saveBrightness(context, useSystemBrightness, brightness)
    }

    fun applySavedBrightness(activity: Activity) {
        applyBrightness(
            activity = activity,
            useSystemBrightness = useSystemBrightness(activity),
            brightness = savedBrightness(activity)
        )
    }

    fun applyBrightness(
        activity: Activity,
        useSystemBrightness: Boolean,
        brightness: Float
    ) {
        WindowBrightness.applyBrightness(activity, useSystemBrightness, brightness)
    }

    /**
     * Request a high refresh rate without pinning the app to a specific display
     * mode. On Android 14+ the platform accepts an intended rate and selects the
     * best compatible display mode. Older Android versions require a supported
     * refresh rate, so use the fastest one reported by the current display.
     */
    @Suppress("DEPRECATION")
    fun applyPreferredRefreshRate(activity: Activity) {
        val targetRefreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TARGET_REFRESH_RATE
        } else {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                activity.windowManager.defaultDisplay
            } ?: return

            display.supportedModes
                .asSequence()
                .map { it.refreshRate }
                .maxOrNull()
                ?: display.refreshRate
        }

        val params = activity.window.attributes
        // preferredRefreshRate is ignored while preferredDisplayModeId is set.
        params.preferredDisplayModeId = 0
        params.preferredRefreshRate = targetRefreshRate
        activity.window.attributes = params

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Favor responsiveness while the user is touching/scrolling instead
            // of allowing power-saving heuristics to aggressively drop the rate.
            activity.window.setFrameRateBoostOnTouchEnabled(true)
            activity.window.setFrameRatePowerSavingsBalanced(false)
        }
    }
}
