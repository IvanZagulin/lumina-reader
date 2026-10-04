package com.lumina.reader.ui.reader

import androidx.compose.runtime.Composable
import com.lumina.reader.core.model.ReadingTheme

/**
 * Keeps the display awake while [enabled] and the reader is on screen
 * («Не гасить экран»). Leaving the reader always lets the display sleep
 * again, whatever the setting.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

/**
 * The system bars over the reading page. The status bar stays visible so the
 * clock, battery level and system indicators remain available, with icons
 * that suit [pageTheme]; Android's navigation bar shows only together with
 * the reader chrome ([chromeVisible]). Leaving the reader hands the bars back
 * in the app's colours.
 */
@Composable
expect fun ReaderSystemBars(chromeVisible: Boolean, pageTheme: ReadingTheme)

/**
 * Opens the system share sheet for plain text: a quote, or «Читаю …». A
 * failure shows «Не удалось поделиться»: a short toast on Android, as before
 * the move, and an AppMessages error on iOS.
 */
@Composable
expect fun rememberTextSharer(): (String) -> Unit

/**
 * The reader's own screen brightness (§7.3, «Яркость»): either the system
 * brightness («Авто») or a manual level, saved app-wide. Android overrides
 * the brightness of the activity window only; iOS has just the device-wide
 * UIScreen brightness, so its side also gives the user's own level back when
 * the override ends (see [ReaderBrightness]).
 */
interface ScreenBrightness {
    /** The saved choice to follow the system brightness; true until the reader picks a level. */
    fun useSystem(): Boolean

    /** The saved manual level, [MIN_SCREEN_BRIGHTNESS]..1 (0.70 until one is saved). */
    fun savedLevel(): Float

    /** Shows [level] (or the system brightness) now without saving it: the slider while it moves. */
    fun apply(useSystem: Boolean, level: Float)

    /** Saves the choice for the next start; it does not change the screen. */
    fun save(useSystem: Boolean, level: Float)
}

/** The dimmest manual level: at zero the screen goes black and the slider could not be found again. */
const val MIN_SCREEN_BRIGHTNESS: Float = 0.05f

/** The platform's [ScreenBrightness], for the brightness row of «Аа». */
@Composable
expect fun rememberScreenBrightness(): ScreenBrightness

/**
 * Keeps the saved brightness in force while the reader is on screen.
 *
 * On Android MainActivity applies it to the window of the whole app on every
 * start, so this does nothing there. On iOS the brightness belongs to the
 * device and outlives the app, so the reader sets it when it opens and gives
 * the user's own level back when it closes or the app leaves the foreground.
 */
@Composable
expect fun ReaderBrightness()
