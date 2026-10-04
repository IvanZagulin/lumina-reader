package com.lumina.reader.core.tts

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * The reader's [ReadAloudController] on Android: every call goes to
 * [TtsController] unchanged, so the foreground service, the media
 * notification and audio focus behave exactly as before.
 *
 * Built lazily with the reader services (see `LuminaApp`), i.e. when the
 * first book opens; that is when the reader used to call [TtsController.init]
 * itself, so the controller is still initialised at the same moment.
 */
class TtsControllerReadAloud(context: Context) : ReadAloudController {

    init {
        TtsController.init(context)
    }

    override val state: StateFlow<TtsPlaybackState>
        get() = TtsController.state

    override fun start(
        bookId: Long,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    ) {
        TtsController.start(bookId, bookTitle, chapter, startParagraph, source)
    }

    override fun pause() = TtsController.pause()

    override fun resume() = TtsController.resume()

    override fun togglePlayPause() = TtsController.togglePlayPause()

    override fun stop() = TtsController.stop()

    override fun skipToParagraph(index: Int) = TtsController.skipToParagraph(index)

    override fun nextParagraph() = TtsController.nextParagraph()

    override fun previousParagraph() = TtsController.previousParagraph()

    override fun setSpeechRate(rate: Float) = TtsController.setSpeechRate(rate)

    override fun setPitch(pitch: Float) = TtsController.setPitch(pitch)

    override fun setSleepTimer(minutes: Int?) = TtsController.setSleepTimer(minutes)

    override fun setStopAtChapterEnd(enabled: Boolean) = TtsController.setStopAtChapterEnd(enabled)
}
