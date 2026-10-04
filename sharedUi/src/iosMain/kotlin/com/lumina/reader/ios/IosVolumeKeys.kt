package com.lumina.reader.ios

import com.lumina.reader.core.library.AppServices
import com.lumina.reader.core.tts.TtsStatus
import com.lumina.reader.ui.reader.PageTurnDirection
import com.lumina.reader.ui.reader.ReaderPageNavigation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.outputVolume
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectMake
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UISlider
import kotlin.math.abs

/**
 * Turns the volume buttons into page turns while a book is open, as Android's
 * MainActivity does (volume down = next page, up = previous).
 *
 * iOS has no API for the buttons. The known way is to watch the system output
 * volume: each press changes it, and a press is a press. It is read on a short
 * timer while a reader is open (the KVO route needs an NSObject override that
 * Kotlin/Native does not expose for this category). Two things keep that
 * usable for reading:
 *
 * - The volume is put back after every press, through the slider of a
 *   [MPVolumeView] kept (invisible, off screen) in the window — which is also
 *   what stops the system volume indicator from covering the page.
 * - Pressing «up» at full volume or «down» at silence changes nothing, so
 *   nothing would be seen. While a reader is open the volume is held between
 *   15% and 85%, so there is always room to move. Leaving the book does not
 *   restore the old level: that would be a second, surprising change.
 *
 * Nothing happens unless a reader has registered with [ReaderPageNavigation]
 * (it does that only when «Листать кнопками громкости» is on), and not while
 * the book is read aloud, when the buttons mean volume.
 */
@OptIn(ExperimentalForeignApi::class)
object IosVolumeKeys {
    private const val LOWEST = 0.15f
    private const val HIGHEST = 0.85f
    private const val POLL_READING_MILLIS = 80L
    private const val POLL_IDLE_MILLIS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var volumeView: MPVolumeView? = null
    private var lastVolume = 0.5f
    private var restoring = false

    /** Starts watching; safe to call more than once. Call on the main thread. */
    fun start() {
        if (started) return
        started = true
        val session = AVAudioSession.sharedInstance()
        // Ambient: music and podcasts keep playing, and the session is only
        // here so that outputVolume is reported.
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        session.setActive(true, null)
        lastVolume = session.outputVolume
        scope.launch {
            while (true) {
                if (readerIsListening()) {
                    delay(POLL_READING_MILLIS)
                    onVolumeChanged(session.outputVolume)
                    keepHeadroom()
                } else {
                    // Follow the volume, so the first press in a book has a right "before".
                    delay(POLL_IDLE_MILLIS)
                    lastVolume = session.outputVolume
                }
            }
        }
    }

    private fun readerIsListening(): Boolean =
        ReaderPageNavigation.hasActiveReader() && !isReadingAloud()

    private fun isReadingAloud(): Boolean {
        val status = AppServices.reader.readAloud.state.value.status
        return status == TtsStatus.PLAYING || status == TtsStatus.PREPARING
    }

    private fun onVolumeChanged(volume: Float) {
        if (restoring) {
            // The echo of our own correction.
            restoring = false
            lastVolume = volume
            return
        }
        val before = lastVolume
        lastVolume = volume
        if (!readerIsListening() || volume == before) return
        val direction = if (volume > before) PageTurnDirection.PREVIOUS else PageTurnDirection.NEXT
        ReaderPageNavigation.dispatch(direction)
        setSystemVolume(before.coerceIn(LOWEST, HIGHEST))
    }

    private fun keepHeadroom() {
        if (lastVolume < LOWEST || lastVolume > HIGHEST) setSystemVolume(lastVolume.coerceIn(LOWEST, HIGHEST))
    }

    private fun setSystemVolume(target: Float) {
        val slider = volumeSlider() ?: return
        if (abs(slider.value - target) < 0.001f) return
        restoring = true
        slider.value = target
    }

    /** The slider inside the hidden volume view, which is how an app sets the system volume. */
    private fun volumeSlider(): UISlider? {
        val view = volumeView ?: run {
            val window = UIApplication.sharedApplication.keyWindow ?: return null
            MPVolumeView(frame = CGRectMake(-1000.0, -1000.0, 1.0, 1.0)).also {
                // Not hidden: a hidden volume view does not suppress the system indicator.
                it.alpha = 0.01
                window.addSubview(it)
                volumeView = it
            }
        }
        return view.subviews.filterIsInstance<UISlider>().firstOrNull()
    }
}
