package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter

/**
 * Pages of every chapter measured with the reader's current typography.
 * [pageStartParagraphs] holds, for each chapter, the source paragraph that
 * opens each of its pages so a book-wide page number can be turned back into
 * a reader position.
 */
internal class BookPageMap(
    private val pageStartParagraphs: List<IntArray>
) {
    private val pagesBeforeChapter: IntArray = IntArray(pageStartParagraphs.size).also { offsets ->
        var total = 0
        pageStartParagraphs.forEachIndexed { index, starts ->
            offsets[index] = total
            total += starts.size.coerceAtLeast(1)
        }
    }

    val chapterCount: Int get() = pageStartParagraphs.size

    val totalPages: Int = if (pageStartParagraphs.isEmpty()) {
        1
    } else {
        pagesBeforeChapter.last() + pageStartParagraphs.last().size.coerceAtLeast(1)
    }

    fun pageCount(chapterIndex: Int): Int =
        pageStartParagraphs.getOrNull(chapterIndex)?.size?.coerceAtLeast(1) ?: 1

    /** Zero-based page index across the whole book. */
    fun globalPageIndex(chapterIndex: Int, localPage: Int): Int {
        if (pageStartParagraphs.isEmpty()) return 0
        val chapter = chapterIndex.coerceIn(0, pageStartParagraphs.lastIndex)
        return pagesBeforeChapter[chapter] + localPage.coerceIn(0, pageCount(chapter) - 1)
    }

    fun locate(globalPageIndex: Int): BookPageLocation {
        if (pageStartParagraphs.isEmpty()) return BookPageLocation(0, 0, 0)
        val target = globalPageIndex.coerceIn(0, totalPages - 1)
        var low = 0
        var high = pageStartParagraphs.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (pagesBeforeChapter[mid] <= target) low = mid else high = mid - 1
        }
        val localPage = (target - pagesBeforeChapter[low]).coerceIn(0, pageCount(low) - 1)
        val paragraph = pageStartParagraphs[low].getOrNull(localPage) ?: 0
        return BookPageLocation(low, localPage, paragraph)
    }
}

internal data class BookPageLocation(
    val chapterIndex: Int,
    val localPage: Int,
    val paragraphIndex: Int
)

/**
 * What the reader footer shows for a page. [isExact] is false while the
 * whole book is still being paginated and the numbers are extrapolated from
 * the text length of the chapters.
 */
internal data class BookPosition(
    val pageNumber: Int,
    val totalPages: Int,
    val percent: Float,
    val isExact: Boolean
)

internal fun chapterTextLengths(chapters: List<Chapter>): IntArray =
    IntArray(chapters.size) { index -> chapters[index].paragraphs.sumOf(::visibleTextLength) }

private fun visibleTextLength(paragraph: String): Int =
    if (paragraph.startsWith("[IMG:") && paragraph.endsWith("]")) 0 else paragraph.trim().length

/**
 * Book-wide position of [localPage] of [chapterIndex]. The percentage marks
 * the end of the page, so the final page of the book always reads 100%.
 */
internal fun bookPosition(
    chapterIndex: Int,
    localPage: Int,
    chapterPageCount: Int,
    chapterLengths: IntArray,
    pageMap: BookPageMap?
): BookPosition {
    if (pageMap != null && pageMap.chapterCount == chapterLengths.size && pageMap.chapterCount > 0) {
        val page = pageMap.globalPageIndex(chapterIndex, localPage) + 1
        return BookPosition(
            pageNumber = page,
            totalPages = pageMap.totalPages,
            percent = (page * 100f / pageMap.totalPages).coerceIn(0f, 100f),
            isExact = true
        )
    }

    val pagesInChapter = chapterPageCount.coerceAtLeast(1)
    val page = localPage.coerceIn(0, pagesInChapter - 1)
    val chapterCount = chapterLengths.size.coerceAtLeast(1)
    val chapter = chapterIndex.coerceIn(0, chapterCount - 1)
    val totalChars = chapterLengths.sumOf(Int::toLong)
    val currentChars = chapterLengths.getOrNull(chapter)?.toLong() ?: 0L

    if (totalChars <= 0L || currentChars <= 0L) {
        // Nothing to extrapolate from (an illustration-only chapter or book):
        // treat every chapter as equally long.
        val total = chapterCount * pagesInChapter
        val number = (chapter * pagesInChapter + page + 1).coerceIn(1, total)
        return BookPosition(number, total, number * 100f / total, isExact = false)
    }

    val charsBefore = (0 until chapter).sumOf { chapterLengths[it].toLong() }
    val charsPerPage = currentChars.toDouble() / pagesInChapter
    val total = kotlin.math.ceil(totalChars / charsPerPage).toInt().coerceAtLeast(1)
    val number = (kotlin.math.round(charsBefore / charsPerPage).toInt() + page + 1).coerceIn(1, total)
    val percent = ((charsBefore + currentChars * (page + 1).toDouble() / pagesInChapter) /
        totalChars * 100.0).toFloat().coerceIn(0f, 100f)
    return BookPosition(number, total, percent, isExact = false)
}

/**
 * Text-weighted book progress for a paragraph position, used when no page
 * layout is available (scrolling mode, or before the first page settles).
 * Weighting by text keeps a long first chapter from being worth as much as a
 * two-line epigraph.
 */
internal fun paragraphProgressPercent(
    chapters: List<Chapter>,
    chapterLengths: IntArray,
    chapterIndex: Int,
    paragraphIndex: Int
): Float {
    if (chapters.isEmpty()) return 0f
    val chapter = chapterIndex.coerceIn(0, chapters.lastIndex)
    val paragraphs = chapters[chapter].paragraphs
    val totalChars = chapterLengths.sumOf(Int::toLong)
    val chapterFraction = when {
        paragraphIndex == Int.MAX_VALUE -> 1.0
        paragraphs.isEmpty() -> 0.0
        chapterLengths.getOrNull(chapter)?.let { it > 0 } == true -> {
            val before = paragraphs
                .subList(0, paragraphIndex.coerceIn(0, paragraphs.size))
                .sumOf(::visibleTextLength)
            before.toDouble() / chapterLengths[chapter]
        }
        else -> paragraphIndex.coerceIn(0, paragraphs.size).toDouble() / paragraphs.size
    }

    if (totalChars <= 0L) {
        return ((chapter + chapterFraction) / chapters.size * 100.0).toFloat().coerceIn(0f, 100f)
    }
    val charsBefore = (0 until chapter).sumOf { chapterLengths.getOrNull(it)?.toLong() ?: 0L }
    val currentChars = chapterLengths.getOrNull(chapter)?.toLong() ?: 0L
    return ((charsBefore + currentChars * chapterFraction) / totalChars * 100.0)
        .toFloat()
        .coerceIn(0f, 100f)
}

internal fun formatBookPercent(percent: Float): String =
    String.format(java.util.Locale.US, "%.1f%%", percent)
