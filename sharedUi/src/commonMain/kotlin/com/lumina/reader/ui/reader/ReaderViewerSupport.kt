package com.lumina.reader.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderTapZones
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.ui.reader.selection.MarkColors
import com.lumina.reader.ui.theme.HighlightPalette

/**
 * Callbacks shared by the paged and the scrolling viewer. ReaderContent
 * creates one stable instance whose lambdas always call the latest handlers.
 */
@Stable
class ReaderViewerCallbacks(
    val onToggleControls: () -> Unit,
    val onNextChapter: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNavigationHandled: (requestId: Long) -> Unit,
    val onVisibleRangeChanged: (range: VisibleRange, countWords: Boolean) -> Unit,
    val onPageProgressChanged: (chapterIndex: Int, percent: Float) -> Unit,
    val onJumpToPosition: (chapterIndex: Int, paragraphIndex: Int, charOffset: Int) -> Unit,
    val onToggleProgressDisplay: () -> Unit,
    val onNoteClick: (noteId: String) -> Unit,
    val onHighlightClick: (highlightId: Long) -> Unit = {}
)

/** Highlights and the search match of the chapter on screen, by paragraph. */
class ChapterDecorations(
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
                        HighlightSpan(
                            start = it.startOffset,
                            end = it.endOffset,
                            // Palette swatch; the legacy #FFEB3B becomes «Жёлтый».
                            color = HighlightPalette.fromHex(it.colorHex).color,
                            id = it.id,
                            hasNote = !it.note.isNullOrBlank()
                        )
                    }
                ),
            searchMatch = searchMatch?.takeIf { it.chapterIndex == chapterIndex }
        )
    }
}

/** The sentence being read aloud: [start] inclusive, [end] exclusive, plain-text offsets. */
data class TtsSentenceMark(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
) {
    fun rangeFor(chapterIndex: Int, paragraphIndex: Int): OffsetRange? =
        if (chapterIndex == this.chapterIndex && paragraphIndex == this.paragraphIndex && end > start) {
            OffsetRange(start, end)
        } else {
            null
        }
}

/**
 * Whether read-aloud may move the page to its new sentence: only while the
 * reader still looks at the previously spoken one (or nothing was spoken or
 * shown yet). Paging away on purpose stops the following until the reader
 * comes back to the voice.
 */
fun shouldFollowReadAloud(
    previousSpoken: Pair<Int, TextAnchor>?,
    visible: VisibleRange?
): Boolean = previousSpoken == null || visible == null ||
    visible.contains(previousSpoken.first, previousSpoken.second.paragraphIndex, previousSpoken.second.charOffset)

/**
 * Whether the sentence being read aloud can be seen: its first or its last
 * character lies in [visible]. A sentence that runs over a page break is
 * visible from both pages, so starting to read at a page whose first
 * paragraph began on the previous page does not flip back.
 */
fun isSpokenSentenceVisible(
    visible: VisibleRange,
    chapterIndex: Int,
    paragraphIndex: Int,
    sentence: IntRange?
): Boolean {
    val start = sentence?.first ?: 0
    if (visible.contains(chapterIndex, paragraphIndex, start)) return true
    return sentence != null && !sentence.isEmpty() && visible.contains(chapterIndex, paragraphIndex, sentence.last)
}

/**
 * Decides whether a settled page counts as read. A page reached by a
 * navigation jump does not count unless the request says so (a page turn
 * into the next chapter does); any other settled page was turned by the
 * reader and counts.
 */
class PageReportGate {
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
class ScrollItems(
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
class BookTextInfo(
    val chapterLengths: IntArray,
    val localeTag: String?
)

/** A sample of the book text, enough to tell its script. */
fun bookTextSample(book: ParsedBook, maxChars: Int = 4_000): String {
    val builder = StringBuilder()
    for (chapter in book.chapters) {
        for (paragraph in chapter.paragraphs) {
            if (builder.length >= maxChars) return builder.toString()
            builder.append(paragraphPlainText(paragraph)).append(' ')
        }
    }
    return builder.toString()
}

/**
 * What the viewers draw on top of the text model and need from the screen:
 * the spoken sentence, the search flash, mark colours and motion settings.
 */
@Immutable
data class ReaderPageExtras(
    val ttsSentence: TtsSentenceMark?,
    val readAloudDriving: Boolean,
    val markColors: MarkColors,
    /** Current alpha of the search-match flash; read while drawing only. */
    val searchAlpha: () -> Float,
    val minutesLeft: Int?,
    val reducedMotion: Boolean,
    val curlSupported: Boolean
)

/** What a tap on the page does. */
enum class TapAction {
    PREVIOUS,
    NEXT,
    MENU
}

/**
 * Tap zones of the paged reader (§7.1). Classic: left 30 % back, right 30 %
 * forward, the middle opens the menu. One-handed: only the left 20 % goes
 * back, a centre box (35–65 % both ways) opens the menu, anything else goes
 * forward. [inverted] swaps back and forward.
 */
fun tapZoneAction(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    zones: ReaderTapZones,
    inverted: Boolean
): TapAction {
    val w = width.coerceAtLeast(1f)
    val h = height.coerceAtLeast(1f)
    val raw = when (zones) {
        ReaderTapZones.CLASSIC -> when {
            x < w * 0.30f -> TapAction.PREVIOUS
            x > w * 0.70f -> TapAction.NEXT
            else -> TapAction.MENU
        }
        ReaderTapZones.ONE_HAND -> when {
            x < w * 0.20f -> TapAction.PREVIOUS
            x in (w * 0.35f)..(w * 0.65f) && y in (h * 0.35f)..(h * 0.65f) -> TapAction.MENU
            else -> TapAction.NEXT
        }
    }
    return if (!inverted) {
        raw
    } else {
        when (raw) {
            TapAction.PREVIOUS -> TapAction.NEXT
            TapAction.NEXT -> TapAction.PREVIOUS
            TapAction.MENU -> TapAction.MENU
        }
    }
}
