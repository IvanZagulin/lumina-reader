package com.lumina.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.ReadingTheme
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController

/** The idle timer is the app's, so it is simply switched back on when the reader leaves. */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}

@Composable
actual fun ReaderSystemBars(chromeVisible: Boolean, pageTheme: ReadingTheme) {
    // An iPhone has no navigation bar to hide, and the status bar stays
    // visible in reading mode anyway. Its style follows the system
    // appearance, as SystemBarsAppearance does; the root view controller
    // takes it over with the reader (stage 10e).
}

@Composable
actual fun rememberTextSharer(): (String) -> Unit = remember {
    { text: String ->
        val top = topViewController()
        if (top == null) {
            AppMessages.post("Не удалось поделиться", isError = true)
        } else {
            val sheet = UIActivityViewController(listOf(text), null)
            // On iPad the sheet is a popover and UIKit throws without an anchor.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
    }
}

/**
 * The controller to present from: the root of the key window, or what it
 * already presents (a second share sheet cannot be presented from a
 * controller that is busy presenting).
 */
private fun topViewController(): UIViewController? {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    return generateSequence(root) { it.presentedViewController }.last()
}

@Composable
actual fun rememberScreenBrightness(): ScreenBrightness = DeviceScreenBrightness

@Composable
actual fun ReaderBrightness() {
    DisposableEffect(Unit) {
        DeviceScreenBrightness.applySaved()
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
                DeviceScreenBrightness.restore()
            },
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
                DeviceScreenBrightness.applySaved()
            }
        )
        onDispose {
            observers.forEach { center.removeObserver(it) }
            DeviceScreenBrightness.restore()
        }
    }
}

/**
 * [ScreenBrightness] over UIScreen. Its brightness is the device's: it stays
 * after the app goes to the background, until the device locks, and there is
 * no "follow the system" value to set (Android's BRIGHTNESS_OVERRIDE_NONE).
 * So the user's own level is remembered when the reader first overrides it and
 * set back when the override ends. The choice is kept in the standard
 * NSUserDefaults under Android's "display_settings" keys.
 */
private object DeviceScreenBrightness : ScreenBrightness {
    private const val KEY_USE_SYSTEM_BRIGHTNESS = "use_system_brightness"
    private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
    private const val DEFAULT_BRIGHTNESS = 0.70f

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    /** The user's own level while the reader overrides it, null otherwise. */
    private var systemLevel: Double? = null

    override fun useSystem(): Boolean =
        if (defaults.objectForKey(KEY_USE_SYSTEM_BRIGHTNESS) == null) {
            true
        } else {
            defaults.boolForKey(KEY_USE_SYSTEM_BRIGHTNESS)
        }

    override fun savedLevel(): Float {
        val level = if (defaults.objectForKey(KEY_SCREEN_BRIGHTNESS) == null) {
            DEFAULT_BRIGHTNESS
        } else {
            defaults.floatForKey(KEY_SCREEN_BRIGHTNESS)
        }
        return level.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
    }

    override fun apply(useSystem: Boolean, level: Float) {
        if (useSystem) {
            restore()
        } else {
            val screen = UIScreen.mainScreen
            if (systemLevel == null) systemLevel = screen.brightness
            screen.brightness = level.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f).toDouble()
        }
    }

    override fun save(useSystem: Boolean, level: Float) {
        defaults.setBool(useSystem, forKey = KEY_USE_SYSTEM_BRIGHTNESS)
        defaults.setFloat(level.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f), forKey = KEY_SCREEN_BRIGHTNESS)
    }

    /** Applies the saved choice: the reader opened, or the app came back to the foreground. */
    fun applySaved() = apply(useSystem(), savedLevel())

    /** Gives the user's own level back, if the reader had overridden it. */
    fun restore() {
        val level = systemLevel ?: return
        systemLevel = null
        UIScreen.mainScreen.brightness = level
    }
}
