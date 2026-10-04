package com.lumina.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same sizes on both platforms: PdfRenderer and CoreGraphics both draw at [pdfRenderSize]. */
class PdfRenderSizeTest {

    @Test
    fun pdfPagesRenderAtTwiceTheViewportWithinLimits() {
        val unlimited = Long.MAX_VALUE
        val a4 = pdfRenderSize(595, 842, viewportWidthPx = 1000, maxBitmapBytes = unlimited)
        assertEquals(2000, a4.width)
        assertEquals(2830, a4.height)

        // A tall page is capped on its long side.
        val tall = pdfRenderSize(500, 5000, viewportWidthPx = 1080, maxBitmapBytes = unlimited)
        assertEquals(MAX_PDF_RENDER_SIDE, tall.height)
        assertTrue(tall.width <= 410)

        // And the bitmap never exceeds the memory budget.
        val budget = 8L * 1024 * 1024
        val capped = pdfRenderSize(595, 842, viewportWidthPx = 1440, maxBitmapBytes = budget)
        assertTrue(capped.width.toLong() * capped.height * 4 <= budget)
        assertTrue(capped.width > 1000)
    }

    @Test
    fun emptyPageAndViewportStillGiveADrawableBitmap() {
        // Zero sizes count as one point / one pixel; a zero budget means no budget.
        val size = pdfRenderSize(0, 0, viewportWidthPx = 0, maxBitmapBytes = 0)
        assertEquals(PdfRenderSize(2, 2), size)
    }
}
