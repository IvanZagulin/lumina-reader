package com.lumina.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * [PdfRenderer] allows a single open page at a time, so rendering and closing
 * are serialised by a mutex. Rendered pages are cached by size in bytes; the
 * viewer prefetches the neighbours.
 */
private class AndroidPdfDocumentRenderer(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : PdfDocumentRenderer {
    private val mutex = Mutex()
    private var closed = false
    private val maxBitmapBytes: Long = (Runtime.getRuntime().maxMemory() / 10).coerceAtLeast(8L * 1024 * 1024)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(16L * 1024 * 1024, 160L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override val pageCount: Int = renderer.pageCount

    private fun key(pageIndex: Int, viewportWidthPx: Int) = "$pageIndex@$viewportWidthPx"

    override fun cached(pageIndex: Int, viewportWidthPx: Int): ImageBitmap? =
        cache.get(key(pageIndex, viewportWidthPx))?.asImageBitmap()

    /** Renders a page on [Dispatchers.IO], where the viewer used to switch before calling it. */
    override suspend fun render(pageIndex: Int, viewportWidthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        renderBitmap(pageIndex, viewportWidthPx)?.asImageBitmap()
    }

    private suspend fun renderBitmap(pageIndex: Int, viewportWidthPx: Int): Bitmap? {
        if (pageIndex !in 0 until pageCount) return null
        cache.get(key(pageIndex, viewportWidthPx))?.let { return it }
        return mutex.withLock {
            if (closed) {
                null
            } else {
                cache.get(key(pageIndex, viewportWidthPx)) ?: renderLocked(pageIndex, viewportWidthPx)
            }
        }
    }

    private fun renderLocked(pageIndex: Int, viewportWidthPx: Int): Bitmap? {
        var page: PdfRenderer.Page? = null
        return try {
            val openPage = renderer.openPage(pageIndex)
            page = openPage
            val size = pdfRenderSize(openPage.width, openPage.height, viewportWidthPx, maxBitmapBytes)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            openPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key(pageIndex, viewportWidthPx), bitmap)
            bitmap
        } catch (error: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (error: Exception) {
            null
        } finally {
            try {
                page?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                cache.evictAll()
                try {
                    renderer.close()
                } catch (ignored: Exception) {
                }
                try {
                    descriptor.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }
}

actual fun openPdfDocument(path: Path): PdfDocumentRenderer? {
    val descriptor = try {
        ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (error: Exception) {
        return null
    }
    return try {
        AndroidPdfDocumentRenderer(descriptor, PdfRenderer(descriptor))
    } catch (error: Exception) {
        try {
            descriptor.close()
        } catch (ignored: Exception) {
        }
        null
    }
}
