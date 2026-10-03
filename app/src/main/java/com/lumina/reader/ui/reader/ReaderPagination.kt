package com.lumina.reader.ui.reader

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Line geometry of one laid-out paragraph, copied out of a TextLayoutResult
 * so the page-splitting rules can run (and be tested) without a measurer.
 * Offsets are plain-text offsets of the paragraph.
 */
internal class ParagraphLines(
    val lineTops: FloatArray,
    val lineBottoms: FloatArray,
    val lineStarts: IntArray,
    val textLength: Int
) {
    init {
        require(lineTops.size == lineBottoms.size && lineTops.size == lineStarts.size)
    }

    val lineCount: Int get() = lineTops.size

    /** Offset just after line [lineIndex] (the start of the next line). */
    fun lineEnd(lineIndex: Int): Int =
        if (lineIndex + 1 < lineCount) lineStarts[lineIndex + 1] else textLength

    /** Top of [fromLine] in whole pixels, as the reader positions the slice. */
    fun topPx(fromLine: Int): Int = lineTops[fromLine].roundToInt()

    /** Height in whole pixels of lines [fromLine, toLine). */
    fun heightPx(fromLine: Int, toLine: Int): Int {
        if (toLine <= fromLine) return 0
        return (ceil(lineBottoms[toLine - 1].toDouble()).toInt() - topPx(fromLine)).coerceAtLeast(0)
    }
}

/** A paragraph prepared for pagination. Blank paragraphs are left out. */
internal sealed interface MeasuredParagraph {
    val paragraphIndex: Int

    class Text(
        override val paragraphIndex: Int,
        val lines: ParagraphLines
    ) : MeasuredParagraph

    data class Image(
        override val paragraphIndex: Int,
        val imageId: String
    ) : MeasuredParagraph

    /**
     * An empty paragraph: parsers use it for a stanza break or a blank line.
     * It becomes vertical space between blocks, never at the top of a page.
     */
    data class Gap(
        override val paragraphIndex: Int,
        val heightPx: Int
    ) : MeasuredParagraph
}

/** Something drawn on a page. */
internal sealed interface PageBlock {
    val paragraphIndex: Int
    val startOffset: Int

    /**
     * Lines [startLine, endLine) of a paragraph. The reader lays out the whole
     * paragraph and shows only these lines: it shifts the text up by [topPx]
     * and clips it to [heightPx]. Justification and hyphenation therefore stay
     * identical across a page break and a continuation has no first-line indent.
     */
    data class TextSlice(
        override val paragraphIndex: Int,
        val startLine: Int,
        val endLine: Int,
        override val startOffset: Int,
        val endOffset: Int,
        val topPx: Int,
        val heightPx: Int,
        /** The paragraph ends on this page, so paragraph spacing follows. */
        val endsParagraph: Boolean
    ) : PageBlock

    data class Image(
        override val paragraphIndex: Int,
        val imageId: String
    ) : PageBlock {
        override val startOffset: Int get() = 0
    }

    /** Blank space for an empty paragraph; never the first block of a page. */
    data class Gap(
        override val paragraphIndex: Int,
        val heightPx: Int
    ) : PageBlock {
        override val startOffset: Int get() = 0
    }
}

/** One page of a chapter. The first page of a chapter shows its title. */
internal data class ReaderPage(
    val blocks: List<PageBlock>,
    val showsTitle: Boolean
) {
    /** First character on the page; the page's reading position. */
    val start: TextAnchor = blocks.firstOrNull()
        ?.let { TextAnchor(it.paragraphIndex, it.startOffset) }
        ?: TextAnchor.START
}

/** Share of the page that text may fill before an illustration is moved to its own page. */
internal const val IMAGE_SHARES_PAGE_BELOW = 0.55f

/**
 * Splits measured paragraphs into pages of [contentHeightPx].
 *
 * Rules, in order:
 * - a paragraph that fits is placed whole, followed by [paragraphSpacingPx];
 * - otherwise it is split between lines, never inside a line, so no text is
 *   lost or duplicated at a page break;
 * - a lone first line at the foot of a page that already has text moves to
 *   the next page (orphan), and a lone last line is not left for the next
 *   page when at least three lines fit (widow);
 * - an illustration shares a page with text only while that text fills at
 *   most [IMAGE_SHARES_PAGE_BELOW] of it; it then fills the rest of the page;
 * - a gap (empty paragraph) is dropped at the top of a page and when it does
 *   not fit, so a page never starts with blank space.
 *
 * [titleHeightPx] is reserved at the top of the first page (0 for no title).
 */
internal fun paginateParagraphs(
    paragraphs: List<MeasuredParagraph>,
    contentHeightPx: Int,
    titleHeightPx: Int,
    paragraphSpacingPx: Int,
    checkCancelled: () -> Unit = {}
): List<ReaderPage> {
    val pageHeight = contentHeightPx.coerceAtLeast(1)
    val hasTitle = titleHeightPx > 0
    val pages = ArrayList<ReaderPage>()
    var blocks = ArrayList<PageBlock>()
    var used = if (hasTitle) titleHeightPx.coerceAtMost(pageHeight) else 0

    fun onTitlePage(): Boolean = hasTitle && pages.isEmpty()

    fun flush() {
        if (blocks.isEmpty() && !onTitlePage()) return
        pages += ReaderPage(blocks, showsTitle = onTitlePage())
        blocks = ArrayList()
        used = 0
    }

    for (paragraph in paragraphs) {
        checkCancelled()
        when (paragraph) {
            is MeasuredParagraph.Image -> {
                val image = PageBlock.Image(paragraph.paragraphIndex, paragraph.imageId)
                if (blocks.isNotEmpty() && used > pageHeight * IMAGE_SHARES_PAGE_BELOW) flush()
                blocks += image
                flush()
            }

            is MeasuredParagraph.Gap -> {
                if (blocks.isNotEmpty() && paragraph.heightPx > 0) {
                    if (used + paragraph.heightPx <= pageHeight) {
                        blocks += PageBlock.Gap(paragraph.paragraphIndex, paragraph.heightPx)
                        used += paragraph.heightPx
                    } else {
                        flush()
                    }
                }
            }

            is MeasuredParagraph.Text -> {
                val lines = paragraph.lines
                var line = 0
                while (line < lines.lineCount) {
                    val available = pageHeight - used
                    val remainingLines = lines.lineCount - line
                    if (lines.heightPx(line, lines.lineCount) <= available) {
                        blocks += slice(paragraph.paragraphIndex, lines, line, lines.lineCount)
                        used += lines.heightPx(line, lines.lineCount) + paragraphSpacingPx
                        break
                    }

                    var fit = fittingLines(lines, line, available)
                    if (blocks.isNotEmpty() && (fit == 0 || (fit == 1 && remainingLines > 1))) {
                        flush()
                        continue
                    }
                    if (fit == 0 && onTitlePage() && used > 0) {
                        // Not even one line fits under the title.
                        flush()
                        continue
                    }
                    if (fit == 0) fit = 1
                    if (remainingLines - fit == 1 && fit >= 3) fit -= 1

                    blocks += slice(paragraph.paragraphIndex, lines, line, line + fit)
                    used += lines.heightPx(line, line + fit)
                    line += fit
                    flush()
                }
            }
        }
    }
    flush()
    if (pages.isEmpty()) pages += ReaderPage(emptyList(), showsTitle = hasTitle)
    return pages
}

/** How many lines starting at [fromLine] fit into [availablePx]. */
internal fun fittingLines(lines: ParagraphLines, fromLine: Int, availablePx: Int): Int {
    var count = 0
    while (fromLine + count < lines.lineCount &&
        lines.heightPx(fromLine, fromLine + count + 1) <= availablePx
    ) {
        count++
    }
    return count
}

private fun slice(paragraphIndex: Int, lines: ParagraphLines, from: Int, to: Int) =
    PageBlock.TextSlice(
        paragraphIndex = paragraphIndex,
        startLine = from,
        endLine = to,
        startOffset = lines.lineStarts[from],
        endOffset = lines.lineEnd(to - 1),
        topPx = lines.topPx(from),
        heightPx = lines.heightPx(from, to),
        endsParagraph = to == lines.lineCount
    )

/** Pages of one chapter for one [PageLayoutSpec]. */
internal class ChapterPages(
    val chapterIndex: Int,
    val pages: List<ReaderPage>
) {
    init {
        require(pages.isNotEmpty()) { "A chapter has at least one page" }
    }

    /** The page that shows [anchor]: the last page starting at or before it. */
    fun pageIndexFor(anchor: TextAnchor): Int = pageIndexForAnchor(pages, anchor)

    /** What page [pageIndex] shows: from its start up to the next page's start. */
    fun visibleRange(pageIndex: Int): VisibleRange {
        val index = pageIndex.coerceIn(0, pages.lastIndex)
        val end = pages.getOrNull(index + 1)?.start ?: TextAnchor.CHAPTER_END
        return VisibleRange(chapterIndex, pages[index].start, end)
    }
}

internal fun pageIndexForAnchor(pages: List<ReaderPage>, anchor: TextAnchor): Int {
    if (pages.isEmpty()) return 0
    var low = 0
    var high = pages.lastIndex
    var result = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (pages[mid].start <= anchor) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
