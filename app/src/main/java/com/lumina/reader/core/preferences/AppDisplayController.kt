package com.lumina.reader.core.preferences

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Centralizes display-related preferences that apply to the whole app.
 * Brightness is stored separately from reader typography settings because it
 * controls the Android window rather than book layout.
 */
object AppDisplayController {
    private const val PREFS_NAME = "display_settings"
    private const val KEY_USE_SYSTEM_BRIGHTNESS = "use_system_brightness"
    private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
    private const val DEFAULT_BRIGHTNESS = 0.70f
    private const val MIN_BRIGHTNESS = 0.05f
    private const val TARGET_REFRESH_RATE = 120f

    fun useSystemBrightness(context: Context): Boolean =
        preferences(context).getBoolean(KEY_USE_SYSTEM_BRIGHTNESS, true)

    fun savedBrightness(context: Context): Float =
        preferences(context)
            .getFloat(KEY_SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(MIN_BRIGHTNESS, 1f)

    fun saveBrightness(
        context: Context,
        useSystemBrightness: Boolean,
        brightness: Float
    ) {
        preferences(context)
            .edit()
            .putBoolean(KEY_USE_SYSTEM_BRIGHTNESS, useSystemBrightness)
            .putFloat(KEY_SCREEN_BRIGHTNESS, brightness.coerceIn(MIN_BRIGHTNESS, 1f))
            .apply()
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
        val params = activity.window.attributes
        params.screenBrightness = if (useSystemBrightness) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            brightness.coerceIn(MIN_BRIGHTNESS, 1f)
        }
        activity.window.attributes = params
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

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
