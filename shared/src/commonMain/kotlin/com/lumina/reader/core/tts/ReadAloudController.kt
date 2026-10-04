package com.lumina.reader.core.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-aloud as the reader screen drives it: one process-wide session that
 * outlives the screen, so leaving the book does not stop the voice.
 *
 * It exists because the two platforms keep that session alive differently.
 * Android wraps `TtsController` (:app), whose foreground service, media
 * notification and audio focus must stay exactly as they are; the iPhone
 * drives the same [TtsPlayer] with AVSpeechSynthesizer (`IosReadAloud`).
 * Either way the reader only sees [state] and these commands.
 *
 * Methods may be called from any thread; implementations move the work to
 * the thread [TtsPlayer] lives on.
 */
interface ReadAloudController {
    /** The session of the whole app; its `bookId` tells which book is being read. */
    val state: StateFlow<TtsPlaybackState>

    /** Starts reading [chapter] at [startParagraph], continuing into the chapters [source] supplies. */
    fun start(
        bookId: Long,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    )

    fun pause()
    fun resume()
    fun togglePlayPause()
    fun stop()

    /** Jumps to [index] of the original paragraph list of the chapter being read. */
    fun skipToParagraph(index: Int)
    fun nextParagraph()
    fun previousParagraph()

    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)

    /** Pauses after [minutes]; null switches the timer off. */
    fun setSleepTimer(minutes: Int?)
    fun setStopAtChapterEnd(enabled: Boolean)
}
