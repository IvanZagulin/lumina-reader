package com.lumina.reader.ui.reader

import androidx.compose.runtime.Stable
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReadingHighlight

/**
 * Callbacks shared by the paged and the scrolling viewer. ReaderContent
 * creates one stable instance whose lambdas always call the latest handlers.
 */
@Stable
internal class ReaderViewerCallbacks(
    val onToggleControls: () -> Unit,
    val onNextChapter: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNavigationHandled: (requestId: Long) -> Unit,
    val onVisibleRangeChanged: (range: VisibleRange, countWords: Boolean) -> Unit,
    val onPageProgressChanged: (chapterIndex: Int, percent: Float) -> Unit,
    val onJumpToPosition: (chapterIndex: Int, paragraphIndex: Int, charOffset: Int) -> Unit,
    val onToggleProgressDisplay: () -> Unit,
    val onNoteClick: (noteId: String) -> Unit
)

/** Highlights and the search match of the chapter on screen, by paragraph. */
internal class ChapterDecorations(
    private val chapterIndex: Int,
    private val highlights: Map<Int, List<HighlightSpan>>,
    private val searchMatch: SearchMatch?
) {
    fun highlightsFor(paragraphIndex: Int): List<HighlightSpan> =
        highlights[paragraphIndex].orEmpty()

    fun searchMatchFor(paragraphIndex: Int): OffsetRange? =
        searchMatch
            ?.takeIf { it.chapterIndex == chapterIndex && it.paragraphIndex == paragraphIndex }
            ?.let { OffsetRange(it.start, it.end) }

    companion object {
        fun build(
            chapterIndex: Int,
            highlights: List<ReadingHighlight>,
            searchMatch: SearchMatch?
        ): ChapterDecorations = ChapterDecorations(
            chapterIndex = chapterIndex,
            highlights = highlights
                .filter { it.chapterIndex == chapterIndex && it.endOffset > it.startOffset }
                .groupBy(
                    keySelector = { it.paragraphIndex },
                    valueTransform = {
                        HighlightSpan(it.startOffset, it.endOffset, parseHighlightColor(it.colorHex))
                    }
                ),
            searchMatch = searchMatch?.takeIf { it.chapterIndex == chapterIndex }
        )
    }
}

/**
 * Decides whether a settled page counts as read. A page reached by a
 * navigation jump does not count unless the request says so (a page turn
 * into the next chapter does); any other settled page was turned by the
 * reader and counts.
 */
internal class PageReportGate {
    private var expectedPage: Int? = null
    private var expectedCounts = false

    fun expect(page: Int, countsAsReading: Boolean) {
        expectedPage = page
        expectedCounts = countsAsReading
    }

    fun clear() {
        expectedPage = null
    }

    /** Whether the words on [page], which just settled, count as read. */
    fun consume(page: Int): Boolean {
        val expected = expectedPage
        expectedPage = null
        return if (expected == page) expectedCounts else true
    }
}

/**
 * Item layout of the scrolling reader: an optional "previous chapter"
 * button, the title, one item per paragraph and a closing item.
 */
internal class ScrollItems(
    val hasPrevious: Boolean,
    val paragraphCount: Int,
    val hasNext: Boolean
) {
    val titleItemIndex: Int = if (hasPrevious) 1 else 0
    private val firstParagraphItem: Int = titleItemIndex + 1
    val endItemIndex: Int = firstParagraphItem + paragraphCount
    val itemCount: Int = endItemIndex + 1

    fun paragraphForItem(itemIndex: Int): Int? =
        (itemIndex - firstParagraphItem).takeIf { it in 0 until paragraphCount }

    fun itemForParagraph(paragraphIndex: Int): Int =
        firstParagraphItem + paragraphIndex.coerceIn(0, (paragraphCount - 1).coerceAtLeast(0))

    /** The item to scroll to for [anchor]; the chapter start shows the title. */
    fun itemIndexFor(anchor: TextAnchor): Int = when {
        anchor.paragraphIndex == Int.MAX_VALUE -> endItemIndex
        paragraphCount == 0 -> titleItemIndex
        anchor.paragraphIndex <= 0 && anchor.charOffset <= 0 -> titleItemIndex
        else -> itemForParagraph(anchor.paragraphIndex)
    }
}

/**
 * Facts about the whole book text that the reader needs on every page:
 * the plain-text length of each chapter and the language for hyphenation.
 */
internal class BookTextInfo(
    val chapterLengths: IntArray,
    val localeTag: String?
)

/** A sample of the book text, enough to tell its script. */
internal fun bookTextSample(book: ParsedBook, maxChars: Int = 4_000): String {
    val builder = StringBuilder()
    for (chapter in book.chapters) {
        for (paragraph in chapter.paragraphs) {
            if (builder.length >= maxChars) return builder.toString()
            builder.append(paragraphPlainText(paragraph)).append(' ')
        }
    }
    return builder.toString()
}
