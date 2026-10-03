package com.lumina.reader.core.tts

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide read-aloud engine.
 *
 * Call [init] once (it is idempotent), then [start] playback for a chapter.
 * Speech goes sentence by sentence and continues into following chapters via
 * [TtsChapterSource]. While playing, a foreground [TtsPlaybackService] keeps
 * the process alive and shows a media notification; audio focus and
 * "headphones unplugged" pause playback.
 *
 * The notification's content intent opens [com.lumina.reader.MainActivity]
 * with the extra [EXTRA_OPEN_BOOK_ID] (Long) set to the playing book id.
 *
 * Methods may be called from any thread; work is moved to the main thread.
 */
object TtsController {
    /** Long extra on the notification's MainActivity intent: the book being read aloud. */
    const val EXTRA_OPEN_BOOK_ID = "open_book_id"

    private const val TAG = "TtsController"

    @Volatile
    private var appContext: Context? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    private val player: TtsPlayer by lazy {
        TtsPlayer(
            scope = scope,
            engineFactory = SpeechEngineFactory { callbacks ->
                val context = appContext ?: error("TtsController.init() was not called")
                AndroidSpeechEngine(context, callbacks)
            }
        )
    }

    // Main-thread state.
    private var audioFocus: TtsAudioFocus? = null
    private var pausedByFocusLoss = false
    private var serviceRequested = false
    private var serviceStartBlocked = false

    /** True while a controller call runs; nested calls (from state observers) are posted instead. */
    private var inCall = false

    val state: StateFlow<TtsPlaybackState>
        get() = player.state

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        runOnMain {
            val ctx = appContext ?: return@runOnMain
            if (audioFocus == null) audioFocus = TtsAudioFocus(ctx, ::onAudioFocusChange)
            // Dispatchers.Main (not immediate): react after the player finished
            // its own update instead of re-entering it from inside setState.
            scope.launch(Dispatchers.Main) { player.state.collect { onStateChanged(it) } }
        }
    }

    fun start(
        bookId: Long,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    ) {
        startInternal(bookId, bookTitle, chapter, startParagraph, source)
    }

    /** Like [start] but without a library book (no "open book" target in the notification). */
    internal fun startInternal(
        bookId: Long?,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    ) {
        if (appContext == null) {
            Log.w(TAG, "start() before init(); ignored")
            return
        }
        runOnMain {
            pausedByFocusLoss = false
            player.start(bookId, bookTitle, chapter, startParagraph, source)
        }
    }

    fun pause() {
        runOnMain {
            pausedByFocusLoss = false
            player.pause()
        }
    }

    fun resume() {
        runOnMain {
            pausedByFocusLoss = false
            player.resume()
        }
    }

    fun togglePlayPause() {
        runOnMain {
            pausedByFocusLoss = false
            player.togglePlayPause()
        }
    }

    fun stop() {
        runOnMain {
            pausedByFocusLoss = false
            player.stop()
        }
    }

    fun skipToParagraph(index: Int) {
        runOnMain { player.skipToParagraph(index) }
    }

    fun nextParagraph() {
        runOnMain { player.nextParagraph() }
    }

    fun previousParagraph() {
        runOnMain { player.previousParagraph() }
    }

    fun setSpeechRate(rate: Float) {
        runOnMain { player.setSpeechRate(rate) }
    }

    fun setPitch(pitch: Float) {
        runOnMain { player.setPitch(pitch) }
    }

    fun setSleepTimer(minutes: Int?) {
        runOnMain { player.setSleepTimer(minutes) }
    }

    fun setStopAtChapterEnd(enabled: Boolean) {
        runOnMain { player.setStopAtChapterEnd(enabled) }
    }

    // ---- Service / audio plumbing (main thread) --------------------------------

    /** Called by [TtsPlaybackService.onDestroy]. */
    internal fun onServiceDestroyed() {
        // Posted: never start a new instance from inside the old one's onDestroy().
        mainHandler.post {
            serviceRequested = false
            val status = player.state.value.status
            if (status == TtsStatus.PLAYING || status == TtsStatus.PREPARING) {
                // Destroyed while still reading (e.g. a stopSelf() racing a new start): bring it back.
                appContext?.let { startPlaybackService(it) }
            }
        }
    }

    private fun onStateChanged(state: TtsPlaybackState) {
        val context = appContext ?: return
        when (state.status) {
            TtsStatus.PLAYING, TtsStatus.PREPARING -> {
                val focus = audioFocus
                if (focus != null && !focus.request()) {
                    // A call or another player holds focus: do not talk over it.
                    player.pause()
                    return
                }
                if (!serviceRequested && !serviceStartBlocked) startPlaybackService(context)
            }
            TtsStatus.PAUSED -> {
                serviceStartBlocked = false
                if (!pausedByFocusLoss) audioFocus?.abandon()
            }
            TtsStatus.IDLE, TtsStatus.ERROR -> {
                serviceStartBlocked = false
                pausedByFocusLoss = false
                audioFocus?.abandon()
            }
        }
    }

    private fun startPlaybackService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TtsPlaybackService::class.java))
            serviceRequested = true
        } catch (e: Exception) {
            // Android 12+ refuses foreground service starts from the background
            // (ForegroundServiceStartNotAllowedException). Speech still works while
            // the app is visible; retry on the next play.
            serviceStartBlocked = true
            Log.w(TAG, "Could not start playback service", e)
        }
    }

    private fun onAudioFocusChange(focusChange: Int) {
        runOnMain {
            val status = player.state.value.status
            when (TtsFocusPolicy.decide(focusChange, status, pausedByFocusLoss)) {
                TtsFocusAction.PAUSE_TRANSIENT -> {
                    pausedByFocusLoss = true
                    audioFocus?.onTransientLoss()
                    player.pause()
                }
                TtsFocusAction.PAUSE_PERMANENT -> {
                    pausedByFocusLoss = false
                    audioFocus?.abandon()
                    player.pause()
                }
                TtsFocusAction.RESUME -> {
                    pausedByFocusLoss = false
                    audioFocus?.onGain()
                    player.resume()
                }
                TtsFocusAction.NONE -> when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> audioFocus?.onGain()
                    AudioManager.AUDIOFOCUS_LOSS -> audioFocus?.abandon()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> audioFocus?.onTransientLoss()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Runs [block] on the main thread: directly when already there and not
     * inside another controller call, otherwise posted. Posting nested calls
     * keeps a state observer that calls back into the controller from
     * re-entering the player halfway through an update.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper() && !inCall) {
            inCall = true
            try {
                block()
            } finally {
                inCall = false
            }
        } else {
            mainHandler.post { runOnMain(block) }
        }
    }
}
