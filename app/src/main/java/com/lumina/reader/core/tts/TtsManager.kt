package com.lumina.reader.core.tts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Legacy three-state view of [TtsStatus], kept for the existing reader UI. */
enum class TtsState {
    IDLE,
    PLAYING,
    PAUSED
}

/**
 * Legacy API kept for the reader screen until it switches to [TtsController].
 * Now a thin adapter: playback runs in [TtsController] (sentence by sentence,
 * in the background, with audio focus), started at the ORIGINAL paragraph
 * index and stopping at the end of the given paragraphs. New code should use
 * [TtsController] directly.
 */
class TtsManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        TtsController.init(context)
    }

    val state: StateFlow<TtsState> = TtsController.state
        .map { it.status.toLegacy() }
        .stateIn(scope, SharingStarted.Eagerly, TtsController.state.value.status.toLegacy())

    fun play(paragraphs: List<String>, startIndex: Int = 0) {
        TtsController.startInternal(
            bookId = null,
            bookTitle = "",
            chapter = TtsChapter(index = 0, title = "", paragraphs = paragraphs),
            startParagraph = startIndex,
            source = NoFurtherChapters
        )
    }

    fun pause() {
        TtsController.pause()
    }

    fun resume() {
        TtsController.resume()
    }

    fun stop() {
        TtsController.stop()
    }

    fun setRate(rate: Float) {
        TtsController.setSpeechRate(rate)
    }

    fun release() {
        TtsController.stop()
        scope.cancel()
    }

    private object NoFurtherChapters : TtsChapterSource {
        override suspend fun chapter(index: Int): TtsChapter? = null
    }
}

internal fun TtsStatus.toLegacy(): TtsState = when (this) {
    TtsStatus.IDLE -> TtsState.IDLE
    TtsStatus.PREPARING, TtsStatus.PLAYING -> TtsState.PLAYING
    TtsStatus.PAUSED, TtsStatus.ERROR -> TtsState.PAUSED
}
