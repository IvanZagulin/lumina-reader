// setCategory/setActive take an NSError** (a CPointer), which needs the opt-in.
@file:OptIn(ExperimentalForeignApi::class)

package com.lumina.reader.core.tts

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * Process-wide read-aloud on the iPhone: the common [TtsPlayer] speaking
 * through [IosSpeechEngine], with the audio session Android's TtsController
 * gets from audio focus.
 *
 * While reading, the session has the playback category, so speech ignores the
 * silent switch and (with the app's "audio" background mode) goes on after
 * the screen locks; a phone call or another app's audio pauses it, and it
 * resumes when the system says it may, as after a transient focus loss on
 * Android. There is no lock-screen player yet.
 *
 * Calls are moved to the main thread through [scope]: Dispatchers.Main.immediate
 * runs them in place when already there, and a call made while the player is
 * updating its state waits until that update is over (TtsController posts such
 * nested calls for the same reason).
 */
object IosReadAloud : ReadAloudController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val player: TtsPlayer by lazy {
        TtsPlayer(
            scope = scope,
            engineFactory = SpeechEngineFactory { callbacks -> IosSpeechEngine(callbacks) }
        ).also { created ->
            // Dispatchers.Main (not immediate): react after the player finished
            // its own update instead of re-entering it from inside setState.
            scope.launch(Dispatchers.Main) { created.state.collect { onStateChanged(it) } }
            observeInterruptions()
        }
    }

    // Main-thread state.
    private var sessionActive = false
    private var pausedByInterruption = false

    override val state: StateFlow<TtsPlaybackState>
        get() = player.state

    override fun start(
        bookId: Long,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    ) {
        onMain {
            pausedByInterruption = false
            player.start(bookId, bookTitle, chapter, startParagraph, source)
        }
    }

    override fun pause() {
        onMain {
            pausedByInterruption = false
            player.pause()
        }
    }

    override fun resume() {
        onMain {
            pausedByInterruption = false
            player.resume()
        }
    }

    override fun togglePlayPause() {
        onMain {
            pausedByInterruption = false
            player.togglePlayPause()
        }
    }

    override fun stop() {
        onMain {
            pausedByInterruption = false
            player.stop()
        }
    }

    override fun skipToParagraph(index: Int) {
        onMain { player.skipToParagraph(index) }
    }

    override fun nextParagraph() {
        onMain { player.nextParagraph() }
    }

    override fun previousParagraph() {
        onMain { player.previousParagraph() }
    }

    override fun setSpeechRate(rate: Float) {
        onMain { player.setSpeechRate(rate) }
    }

    override fun setPitch(pitch: Float) {
        onMain { player.setPitch(pitch) }
    }

    override fun setSleepTimer(minutes: Int?) {
        onMain { player.setSleepTimer(minutes) }
    }

    override fun setStopAtChapterEnd(enabled: Boolean) {
        onMain { player.setStopAtChapterEnd(enabled) }
    }

    // ---- Audio session (main thread) -------------------------------------------

    private fun onStateChanged(state: TtsPlaybackState) {
        when (state.status) {
            TtsStatus.PLAYING, TtsStatus.PREPARING -> activateSession()
            // Kept while an interruption paused us, so the system tells us when it ends.
            TtsStatus.PAUSED -> if (!pausedByInterruption) deactivateSession()
            TtsStatus.IDLE, TtsStatus.ERROR -> {
                pausedByInterruption = false
                deactivateSession()
            }
        }
    }

    private fun activateSession() {
        if (sessionActive) return
        val session = AVAudioSession.sharedInstance()
        // Failures are not fatal: speech still plays, only the silent switch wins.
        session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, 0uL, null)
        sessionActive = session.setActive(true, null)
    }

    private fun deactivateSession() {
        if (!sessionActive) return
        sessionActive = false
        // Lets music another app paused for us continue.
        AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }

    private fun observeInterruptions() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification -> onInterruption(notification) }
    }

    private fun onInterruption(notification: NSNotification?) {
        val info = notification?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue ?: return
        onMain {
            val status = player.state.value.status
            when (type) {
                AVAudioSessionInterruptionTypeBegan -> {
                    if (status == TtsStatus.PLAYING || status == TtsStatus.PREPARING) {
                        pausedByInterruption = true
                        player.pause()
                    }
                    // The system has already deactivated the session.
                    sessionActive = false
                }
                AVAudioSessionInterruptionTypeEnded -> {
                    val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0uL
                    val mayResume = (options and AVAudioSessionInterruptionOptionShouldResume) != 0uL
                    if (pausedByInterruption && status == TtsStatus.PAUSED && mayResume) {
                        pausedByInterruption = false
                        player.resume()
                    } else if (pausedByInterruption) {
                        pausedByInterruption = false
                        if (status == TtsStatus.PAUSED) deactivateSession()
                    }
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
}
