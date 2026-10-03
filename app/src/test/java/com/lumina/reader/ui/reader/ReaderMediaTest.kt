package com.lumina.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMediaTest {

    @Test
    fun illustrationsAreDecodedForTheViewport() {
        assertEquals(1, calculateInSampleSize(800, 600, 1080))
        assertEquals(2, calculateInSampleSize(2400, 1600, 1080))
        assertEquals(4, calculateInSampleSize(4800, 3200, 1080))
        // Never wider than the cap, never taller than the height cap.
        assertEquals(4, calculateInSampleSize(8000, 4000, 4000))
        assertEquals(4, calculateInSampleSize(1000, 16000, 1080))
        assertEquals(1, calculateInSampleSize(0, 0, 1080))
    }

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
}
