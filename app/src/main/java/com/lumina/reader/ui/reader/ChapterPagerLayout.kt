package com.lumina.reader.ui.reader

/**
 * Maps real pages of one chapter into a pager that may also contain a
 * previous/next chapter boundary page. Keeping this math outside Compose makes
 * the edge behaviour deterministic and easy to regression-test.
 */
internal data class ChapterPagerLayout(
    val contentPageCount: Int,
    val hasPreviousChapter: Boolean,
    val hasNextChapter: Boolean
) {
    init {
        require(contentPageCount > 0) { "A chapter must contain at least one page" }
    }

    val leadingBoundaryPages: Int = if (hasPreviousChapter) 1 else 0
    val pageCount: Int = contentPageCount +
        leadingBoundaryPages +
        if (hasNextChapter) 1 else 0

    fun pagerPageForContent(contentPage: Int): Int {
        require(contentPage in 0 until contentPageCount)
        return contentPage + leadingBoundaryPages
    }

    fun contentPageForPager(pagerPage: Int): Int? =
        (pagerPage - leadingBoundaryPages).takeIf { it in 0 until contentPageCount }

    fun boundaryDirectionFor(pagerPage: Int): PageTurnDirection? = when {
        hasPreviousChapter && pagerPage == 0 -> PageTurnDirection.PREVIOUS
        hasNextChapter && pagerPage == pageCount - 1 -> PageTurnDirection.NEXT
        else -> null
    }
}
