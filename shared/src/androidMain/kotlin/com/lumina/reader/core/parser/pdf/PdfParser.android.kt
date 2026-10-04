package com.lumina.reader.core.parser.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lumina.reader.core.parser.pdf.PdfParser.Companion.MAX_COVER_SIDE
import okio.Path
import java.io.ByteArrayOutputStream

// PdfRenderer, unchanged from :app's PdfParser: the same page count and the
// same JPEG cover of the first page.

internal actual fun inspectPdf(path: Path): PdfSummary? {
    val file = path.toFile()
    var coverBytes: ByteArray? = null
    var pageCount = 0
    var failed = false

    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    try {
        val openedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = openedDescriptor
        val openedRenderer = PdfRenderer(openedDescriptor)
        renderer = openedRenderer
        pageCount = openedRenderer.pageCount
        if (pageCount > 0) {
            coverBytes = try {
                renderCover(openedRenderer)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        failed = true
    } finally {
        try {
            renderer?.close()
        } catch (e: Exception) {
            // already closed or never opened
        }
        try {
            descriptor?.close()
        } catch (e: Exception) {
            // ignore
        }
    }
    return if (failed) null else PdfSummary(pageCount, coverBytes)
}

private fun renderCover(renderer: PdfRenderer): ByteArray {
    val page = renderer.openPage(0)
    var bitmap: Bitmap? = null
    try {
        val pageWidth = page.width.coerceAtLeast(1)
        val pageHeight = page.height.coerceAtLeast(1)
        val scale = minOf(1.5f, MAX_COVER_SIDE.toFloat() / maxOf(pageWidth, pageHeight))
        val width = (pageWidth * scale).toInt().coerceIn(1, MAX_COVER_SIDE)
        val height = (pageHeight * scale).toInt().coerceIn(1, MAX_COVER_SIDE)
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = created
        created.eraseColor(Color.WHITE)
        page.render(created, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val stream = ByteArrayOutputStream()
        created.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    } finally {
        page.close()
        bitmap?.recycle()
    }
}
