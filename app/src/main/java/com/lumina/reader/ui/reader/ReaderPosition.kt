package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ReadingStats
import kotlin.math.ceil

/**
 * A character inside a chapter: [charOffset] counts characters of the
 * paragraph's [ParagraphMarkup.plainText].
 */
data class TextAnchor(
    val paragraphIndex: Int,
    val charOffset: Int
) : Comparable<TextAnchor> {
    override fun compareTo(other: TextAnchor): Int =
        if (paragraphIndex != other.paragraphIndex) {
            paragraphIndex.compareTo(other.paragraphIndex)
        } else {
            charOffset.compareTo(other.charOffset)
        }

    companion object {
        val START = TextAnchor(0, 0)

        /** Sorts after every real position of a chapter. */
        val CHAPTER_END = TextAnchor(Int.MAX_VALUE, 0)
    }
}

/**
 * The reading position: the first visible character of the current page (or
 * of the first visible line in scrolling mode).
 */
data class ReaderPosition(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int = 0
) {
    val anchor: TextAnchor get() = TextAnchor(paragraphIndex, charOffset)
}

/**
 * A request from the view model to show a position. Viewers scroll or page
 * to it and then acknowledge [id]. [countAsReading] is true only for page
 * turns made by the reader (next/previous chapter), never for jumps.
 */
data class NavigationRequest(
    val id: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val toChapterEnd: Boolean = false,
    val countAsReading: Boolean = false
) {
    val anchor: TextAnchor
        get() = if (toChapterEnd) TextAnchor.CHAPTER_END else TextAnchor(paragraphIndex, charOffset)
}

/** What is on screen: [start] inclusive, [end] exclusive. */
data class VisibleRange(
    val chapterIndex: Int,
    val start: TextAnchor,
    val end: TextAnchor
) {
    fun contains(chapterIndex: Int, paragraphIndex: Int, charOffset: Int): Boolean {
        if (chapterIndex != this.chapterIndex) return false
        val anchor = TextAnchor(paragraphIndex, charOffset)
        return anchor >= start && anchor < end
    }
}

/** A restored position; [atChapterEnd] asks the reader to open the last page. */
internal data class RestoredPosition(
    val position: ReaderPosition,
    val atChapterEnd: Boolean
)

/**
 * A book can be reparsed after an app update, which may change its chapter
 * count or paragraph lengths. Keep the persisted position valid so the reader
 * never opens as a blank screen. Older versions stored [Int.MAX_VALUE] as the
 * paragraph to mean "end of the chapter".
 */
internal fun restoreReaderPosition(
    chapters: List<Chapter>,
    chapterIndex: Int,
    paragraphIndex: Int,
    charOffset: Int
): RestoredPosition {
    val safeChapterIndex = chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
    val paragraphs = chapters.getOrNull(safeChapterIndex)?.paragraphs.orEmpty()
    if (paragraphIndex == Int.MAX_VALUE) {
        return RestoredPosition(
            ReaderPosition(safeChapterIndex, paragraphs.lastIndex.coerceAtLeast(0), 0),
            atChapterEnd = true
        )
    }
    if (paragraphs.isEmpty()) {
        return RestoredPosition(ReaderPosition(safeChapterIndex, 0, 0), atChapterEnd = false)
    }
    val safeParagraphIndex = paragraphIndex.coerceIn(0, paragraphs.lastIndex)
    val textLength = ParagraphMarkup.plainText(paragraphs[safeParagraphIndex]).length
    val safeOffset = charOffset.coerceIn(0, textLength.coerceAtLeast(0))
    return RestoredPosition(
        ReaderPosition(safeChapterIndex, safeParagraphIndex, safeOffset),
        atChapterEnd = false
    )
}

/** Index of the word-start characters in [text]: a word is a run of non-whitespace. */
internal inline fun forEachWordStart(text: String, start: Int, end: Int, action: (Int) -> Unit) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    for (index in from until to) {
        if (!text[index].isWhitespace() && (index == 0 || text[index - 1].isWhitespace())) {
            action(index)
        }
    }
}

/** Number of words that start in [start, end). */
internal fun countWords(text: String, start: Int = 0, end: Int = text.length): Int {
    var count = 0
    forEachWordStart(text, start, end) { count++ }
    return count
}

/**
 * Counts every word of the session once, however often its page is shown.
 * A word belongs to the page on which it starts.
 */
internal class SessionWordTracker {
    private val counted = HashMap<Long, java.util.BitSet>()

    /** Counts the words starting in [start, end) of a paragraph that were not counted yet. */
    fun count(chapterIndex: Int, paragraphIndex: Int, plainText: String, start: Int, end: Int): Int {
        val key = (chapterIndex.toLong() shl 32) or (paragraphIndex.toLong() and 0xFFFFFFFFL)
        var bits: java.util.BitSet? = counted[key]
        var added = 0
        forEachWordStart(plainText, start, end) { wordStart ->
            val set = bits ?: java.util.BitSet(plainText.length).also {
                bits = it
                counted[key] = it
            }
            if (!set.get(wordStart)) {
                set.set(wordStart)
                added++
            }
        }
        return added
    }

    fun clear() {
        counted.clear()
    }
}

/** Default reading speed until the reader has some history. */
internal const val DEFAULT_WORDS_PER_MINUTE = 200

/**
 * Average reading speed over recent sessions. Sessions without words are
 * ignored; with too little history the default is used. The result is kept
 * within a plausible range so one odd session cannot distort the estimate.
 */
internal fun averageWordsPerMinute(sessions: List<ReadingStats>): Int {
    val useful = sessions.filter { it.wordsReadCount > 0 && it.sessionDurationSeconds > 0 }
    val words = useful.sumOf { it.wordsReadCount.toLong() }
    val seconds = useful.sumOf { it.sessionDurationSeconds }
    if (seconds < 120 || words < 100) return DEFAULT_WORDS_PER_MINUTE
    return (words * 60.0 / seconds).toInt().coerceIn(60, 1_200)
}

/** Minutes needed for [wordsLeft] words; at least one minute while text remains. */
internal fun estimateMinutesLeft(wordsLeft: Int, wordsPerMinute: Int): Int {
    if (wordsLeft <= 0) return 0
    val speed = wordsPerMinute.coerceAtLeast(1)
    return ceil(wordsLeft.toDouble() / speed).toInt().coerceAtLeast(1)
}

/** "12 мин", "1 ч 05 мин"; null when there is nothing to show. */
internal fun formatTimeLeft(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    if (minutes < 60) return "$minutes мин"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours ч" else "$hours ч ${rest.toString().padStart(2, '0')} мин"
}

/**
 * Words from [paragraphIndex]/[charOffset] to the end of the chapter.
 * [paragraphWordCounts] holds the word count of every paragraph.
 */
internal fun wordsRemainingInChapter(
    plainParagraphs: List<String>,
    paragraphWordCounts: IntArray,
    paragraphIndex: Int,
    charOffset: Int
): Int {
    if (plainParagraphs.isEmpty()) return 0
    val current = paragraphIndex.coerceIn(0, plainParagraphs.lastIndex)
    val text = plainParagraphs[current]
    var words = countWords(text, charOffset.coerceIn(0, text.length), text.length)
    for (index in current + 1 until paragraphWordCounts.size) words += paragraphWordCounts[index]
    return words
}

/** Plain text of a paragraph for statistics and search; illustrations have no words. */
internal fun paragraphPlainText(raw: String): String =
    if (ParagraphMarkup.isImage(raw)) "" else ParagraphMarkup.plainText(raw)

/** Short text that identifies a bookmark in the list. */
internal fun bookmarkSnippet(
    chapter: Chapter,
    paragraphIndex: Int,
    charOffset: Int,
    isPdf: Boolean,
    maxLength: Int = 120
): String {
    if (isPdf || chapter.paragraphs.isEmpty()) return "Страница ${chapter.index + 1}"
    var index = paragraphIndex.coerceIn(0, chapter.paragraphs.lastIndex)
    var offset = charOffset
    while (index < chapter.paragraphs.size) {
        val text = paragraphPlainText(chapter.paragraphs[index])
        val start = offset.coerceIn(0, text.length)
        val rest = text.substring(start).replace(Regex("\\s+"), " ").trim()
        if (rest.isNotEmpty()) {
            return if (rest.length > maxLength) {
                rest.take(maxLength).trimEnd() + "…"
            } else {
                rest
            }
        }
        index++
        offset = 0
    }
    return "Закладка"
}
