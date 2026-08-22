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
     * Ask Android for the fastest display mode available at the current
     * resolution. Android can still override this preference for power saver,
     * thermal throttling or user display settings.
     */
    @Suppress("DEPRECATION")
    fun applyPreferredRefreshRate(activity: Activity) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        } ?: return

        val currentMode = display.mode
        val preferredMode = display.supportedModes
            .asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: return

        if (preferredMode.refreshRate <= 60.5f) return

        val params = activity.window.attributes
        params.preferredDisplayModeId = preferredMode.modeId
        params.preferredRefreshRate = preferredMode.refreshRate
        activity.window.attributes = params
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
