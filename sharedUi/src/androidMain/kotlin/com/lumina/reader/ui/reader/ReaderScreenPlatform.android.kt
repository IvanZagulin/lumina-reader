package com.lumina.reader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.ui.theme.Lumina

// The Android side of the reader screen: the window code that lived in :app's
// ReaderScreen and BrightnessRow before the move, unchanged.

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = context.findActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
actual fun ReaderSystemBars(chromeVisible: Boolean, pageTheme: ReadingTheme) {
    val context = LocalContext.current
    val view = LocalView.current
    // Keep the Android status bar visible in reading mode so the clock,
    // battery level and system indicators stay available. Only the navigation
    // bar remains immersive while the reader controls are hidden.
    val appIsDark = Lumina.colors.isDark
    DisposableEffect(chromeVisible, pageTheme, appIsDark) {
        val window = context.findActivity()?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.isAppearanceLightStatusBars = !pageTheme.isDark
            insetsController.show(WindowInsetsCompat.Type.statusBars())
            if (chromeVisible) {
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose {
            val currentWindow = context.findActivity()?.window
            if (currentWindow != null) {
                val controller = WindowCompat.getInsetsController(currentWindow, view)
                // Hand the status bar back in the app's colours, or a light reading
                // theme would leave dark-on-dark icons in the library.
                controller.isAppearanceLightStatusBars = !appIsDark
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
actual fun rememberTextSharer(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { text -> shareText(context, text) } }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось поделиться", Toast.LENGTH_SHORT).show()
    }
}

@Composable
actual fun rememberScreenBrightness(): ScreenBrightness {
    val context = LocalContext.current
    // Inside a ModalBottomSheet LocalContext is the sheet dialog's themed
    // wrapper, not the activity whose window brightness has to change.
    return remember(context) { ActivityScreenBrightness(context, context.findActivity()) }
}

/** [ScreenBrightness] over [WindowBrightness], for one activity window. */
private class ActivityScreenBrightness(
    private val context: Context,
    private val activity: Activity?
) : ScreenBrightness {
    override fun useSystem(): Boolean = WindowBrightness.useSystemBrightness(context)

    override fun savedLevel(): Float = WindowBrightness.savedBrightness(context)

    override fun apply(useSystem: Boolean, level: Float) {
        activity?.let { WindowBrightness.applyBrightness(it, useSystemBrightness = useSystem, brightness = level) }
    }

    override fun save(useSystem: Boolean, level: Float) {
        WindowBrightness.saveBrightness(context, useSystemBrightness = useSystem, brightness = level)
    }
}

@Composable
actual fun ReaderBrightness() {
    // The window override is app-wide: MainActivity applies the saved value
    // through AppDisplayController on create and resume.
}

/**
 * The window brightness override and its saved value (SharedPreferences
 * "display_settings"), stored apart from the reader typography because it
 * controls the window rather than the book layout. It is the brightness code
 * of :app's AppDisplayController, which delegates here so that MainActivity
 * and the reader's slider share one implementation.
 */
object WindowBrightness {
    private const val PREFS_NAME = "display_settings"
    private const val KEY_USE_SYSTEM_BRIGHTNESS = "use_system_brightness"
    private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
    private const val DEFAULT_BRIGHTNESS = 0.70f

    fun useSystemBrightness(context: Context): Boolean =
        preferences(context).getBoolean(KEY_USE_SYSTEM_BRIGHTNESS, true)

    fun savedBrightness(context: Context): Float =
        preferences(context)
            .getFloat(KEY_SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)

    fun saveBrightness(
        context: Context,
        useSystemBrightness: Boolean,
        brightness: Float
    ) {
        preferences(context)
            .edit()
            .putBoolean(KEY_USE_SYSTEM_BRIGHTNESS, useSystemBrightness)
            .putFloat(KEY_SCREEN_BRIGHTNESS, brightness.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f))
            .apply()
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
            brightness.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
        }
        activity.window.attributes = params
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** The activity behind [this] context, unwrapping ContextWrappers (dialogs, themed contexts). */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
