package com.lumina.reader.core.tts

/** Lifecycle of the read-aloud session exposed by [ReadAloudController.state]. */
enum class TtsStatus { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

/**
 * Snapshot of the read-aloud session.
 *
 * [paragraphIndex] always points into the ORIGINAL paragraph list of the
 * chapter (blank and image paragraphs included), so the reader can highlight
 * or scroll to it directly. [sentenceRange] is the character range of the
 * sentence being spoken inside `ParagraphMarkup.plainText(paragraph)`.
 */
data class TtsPlaybackState(
    val status: TtsStatus = TtsStatus.IDLE,
    val bookId: Long? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val sentenceRange: IntRange? = null,
    val speechRate: Float = 1f,
    val pitch: Float = 1f,
    val sleepTimerEndsAt: Long? = null,
    val stopAtChapterEnd: Boolean = false,
    val errorMessage: String? = null
)

/** Supplied by the reader so playback can continue into the next chapter. */
interface TtsChapterSource {
    /** Returns the chapter with [index], or null when there is no such chapter (end of book). */
    suspend fun chapter(index: Int): TtsChapter?
}

data class TtsChapter(val index: Int, val title: String, val paragraphs: List<String>)
