package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import okio.Path
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Longest side of a rendered PDF page bitmap. */
const val MAX_PDF_RENDER_SIDE = 4096

data class PdfRenderSize(
    val width: Int,
    val height: Int
)

/**
 * Bitmap size for a PDF page: twice the viewport width so pinch-zoom stays
 * sharp, at most [MAX_PDF_RENDER_SIDE] on the long side and at most
 * [maxBitmapBytes] of ARGB pixels.
 */
fun pdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    viewportWidthPx: Int,
    maxBitmapBytes: Long
): PdfRenderSize {
    val safePageWidth = pageWidth.coerceAtLeast(1).toDouble()
    val safePageHeight = pageHeight.coerceAtLeast(1).toDouble()
    var width = viewportWidthPx.coerceAtLeast(1) * 2.0
    var height = width * safePageHeight / safePageWidth
    val longSide = max(width, height)
    if (longSide > MAX_PDF_RENDER_SIDE) {
        val scale = MAX_PDF_RENDER_SIDE / longSide
        width *= scale
        height *= scale
    }
    var widthPx = width.roundToInt().coerceIn(1, MAX_PDF_RENDER_SIDE)
    var heightPx = height.roundToInt().coerceIn(1, MAX_PDF_RENDER_SIDE)
    val bytes = widthPx.toLong() * heightPx * 4L
    if (maxBitmapBytes > 0 && bytes > maxBitmapBytes) {
        // Rounding down keeps the bitmap inside the budget.
        val scale = sqrt(maxBitmapBytes.toDouble() / bytes)
        widthPx = (widthPx * scale).toInt().coerceAtLeast(1)
        heightPx = (heightPx * scale).toInt().coerceAtLeast(1)
    }
    return PdfRenderSize(widthPx, heightPx)
}

/**
 * One open PDF for the whole reading session. Android draws pages with
 * PdfRenderer, the iPhone with CoreGraphics; both allow one page in flight at a
 * time, so rendering and closing are serialised. Rendered pages are cached by
 * size in bytes, keyed by page and viewport width.
 */
interface PdfDocumentRenderer {
    val pageCount: Int

    /** The page already rendered for this viewport width, without rendering anything. */
    fun cached(pageIndex: Int, viewportWidthPx: Int): ImageBitmap?

    /**
     * The page drawn on white at [pdfRenderSize]; null if it cannot be drawn or
     * the document is closed. Main-safe: each platform moves the drawing to its
     * own background threads, so the viewer stays common.
     */
    suspend fun render(pageIndex: Int, viewportWidthPx: Int): ImageBitmap?

    /** Releases the document and drops the cache; later renders return null. */
    suspend fun close()
}

/**
 * Opens the PDF at [path]; null if it is not a readable PDF (a password-locked
 * document counts as unreadable). Blocking. [path] must be absolute: a stored
 * Book.filePath goes through resolveStoredLibraryPath first, because the
 * iPhone stores it relative to the app container.
 */
expect fun openPdfDocument(path: Path): PdfDocumentRenderer?
