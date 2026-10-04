@file:OptIn(ExperimentalForeignApi::class)

package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLCreateWithFileSystemPath
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPDFDocumentCreateWithURL
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentIsUnlocked
import platform.CoreGraphics.CGPDFDocumentRef
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGPDFPageGetDrawingTransform
import platform.CoreGraphics.CGPDFPageGetRotationAngle
import platform.CoreGraphics.CGPDFPageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGPDFCropBox
import platform.Foundation.NSLock
import platform.Foundation.NSProcessInfo

/**
 * CoreGraphics: the document is opened once, as PdfParser.ios does for covers,
 * and each page is drawn on white straight into the pixels of a Skia image.
 * Opening, page geometry and the size rules match Android's PdfRenderer, so a
 * page comes out the same size on both platforms.
 */
actual fun openPdfDocument(path: Path): PdfDocumentRenderer? {
    val document = openDocument(path.toString()) ?: return null
    // PdfRenderer refuses a document locked by a password; so does the reader here.
    if (!CGPDFDocumentIsUnlocked(document)) {
        CGPDFDocumentRelease(document)
        return null
    }
    return IosPdfDocumentRenderer(document)
}

private fun openDocument(path: String): CGPDFDocumentRef? = run {
    val cfPath = CFStringCreateWithCString(null, path, kCFStringEncodingUTF8) ?: return@run null
    try {
        val url = CFURLCreateWithFileSystemPath(null, cfPath, kCFURLPOSIXPathStyle, false) ?: return@run null
        try {
            CGPDFDocumentCreateWithURL(url)
        } finally {
            CFRelease(url)
        }
    } finally {
        CFRelease(cfPath)
    }
}

/**
 * Stands in for Android's per-app heap limit (Runtime.maxMemory), which iOS
 * does not have: jetsam ends the app well below the physical RAM, and an
 * eighth of it is about what Android grants the same phone class.
 */
private val heapBudgetBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong() / 8

/**
 * CGPDFDocument is not documented as thread-safe, so rendering and closing are
 * serialised by a mutex like on Android. The cache is read from the main
 * thread by [cached], so it has its own lock. Kotlin/Native cannot catch an
 * out-of-memory failure, which is why the budgets come from [heapBudgetBytes]
 * rather than being left to an OutOfMemoryError as on Android.
 */
private class IosPdfDocumentRenderer(private val document: CGPDFDocumentRef) : PdfDocumentRenderer {
    private val mutex = Mutex()
    private var closed = false
    private val maxBitmapBytes: Long = (heapBudgetBytes / 10).coerceAtLeast(8L * 1024 * 1024)
    private val cacheLock = NSLock()
    private val cache = PageImageCache((heapBudgetBytes / 4).coerceIn(16L * 1024 * 1024, 160L * 1024 * 1024))

    override val pageCount: Int = CGPDFDocumentGetNumberOfPages(document).toInt()

    private fun key(pageIndex: Int, viewportWidthPx: Int) = "$pageIndex@$viewportWidthPx"

    override fun cached(pageIndex: Int, viewportWidthPx: Int): ImageBitmap? =
        locked { cache[key(pageIndex, viewportWidthPx)] }

    /** Drawing is CPU work, so it runs on [Dispatchers.Default] as the other iOS decoders do. */
    override suspend fun render(pageIndex: Int, viewportWidthPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        if (pageIndex !in 0 until pageCount) return@withContext null
        val cacheKey = key(pageIndex, viewportWidthPx)
        locked { cache[cacheKey] }?.let { return@withContext it }
        mutex.withLock {
            if (closed) {
                null
            } else {
                locked { cache[cacheKey] } ?: renderLocked(pageIndex, viewportWidthPx)?.also { image ->
                    locked { cache.put(cacheKey, image) }
                }
            }
        }
    }

    private fun renderLocked(pageIndex: Int, viewportWidthPx: Int): ImageBitmap? = try {
        // Pages are numbered from 1; the page belongs to the document. Whatever
        // CoreGraphics autoreleases while drawing (fonts, images) is drained
        // here, as PdfParser.ios does for covers, not whenever the background
        // thread happens to drain its pool.
        CGPDFDocumentGetPage(document, (pageIndex + 1).toULong())?.let { page ->
            autoreleasepool { drawPage(page, viewportWidthPx) }
        }
    } catch (error: Exception) {
        null
    }

    /**
     * The crop box, not the media box the cover uses: it is the visible page
     * that PdfRenderer (PDFium) and every viewer show, without printer margins.
     */
    private fun drawPage(page: CGPDFPageRef, viewportWidthPx: Int): ImageBitmap? {
        var pageWidth = 1.0
        var pageHeight = 1.0
        CGPDFPageGetBoxRect(page, kCGPDFCropBox).useContents {
            pageWidth = size.width.coerceAtLeast(1.0)
            pageHeight = size.height.coerceAtLeast(1.0)
        }
        // A page turned by a quarter is shown with its sides swapped, as PdfRenderer reports it.
        if (CGPDFPageGetRotationAngle(page) % 180 != 0) {
            val swap = pageWidth
            pageWidth = pageHeight
            pageHeight = swap
        }
        // PdfRenderer measures pages in whole points.
        val size = pdfRenderSize(pageWidth.toInt(), pageHeight.toInt(), viewportWidthPx, maxBitmapBytes)
        val width = size.width
        val height = size.height
        val rowBytes = width * 4
        val pixels = ByteArray(rowBytes * height)
        val drawn = pixels.usePinned { pinned ->
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            // RGBA bytes in memory order, which is Skia's RGBA_8888. The page is
            // opaque, so premultiplied and straight alpha are the same here.
            val context = CGBitmapContextCreate(
                pinned.addressOf(0),
                width.toULong(),
                height.toULong(),
                8u,
                rowBytes.toULong(),
                colorSpace,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            )
            CGColorSpaceRelease(colorSpace)
            if (context == null) {
                false
            } else {
                try {
                    CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
                    CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
                    // A bitmap context keeps PDF's bottom-left origin and stores the
                    // top row first, so unlike the UIKit cover renderer no flip is
                    // needed. The page is stretched to the bitmap like PdfRenderer does
                    // with no transform; the drawing transform only handles rotation
                    // and the box origin, since it never scales up.
                    CGContextScaleCTM(context, width / pageWidth, height / pageHeight)
                    val transform = CGPDFPageGetDrawingTransform(
                        page,
                        kCGPDFCropBox,
                        CGRectMake(0.0, 0.0, pageWidth, pageHeight),
                        0,
                        true
                    )
                    CGContextConcatCTM(context, transform)
                    CGContextDrawPDFPage(context, page)
                    true
                } finally {
                    // The context draws into the pinned array; it must be gone before unpinning.
                    CGContextRelease(context)
                }
            }
        }
        if (!drawn) return null
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        return Image.makeRaster(info, pixels, rowBytes).toComposeImageBitmap()
    }

    override suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                locked { cache.clear() }
                CGPDFDocumentRelease(document)
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        cacheLock.lock()
        try {
            return block()
        } finally {
            cacheLock.unlock()
        }
    }
}

/**
 * Rendered pages, least recently used first, bounded by their pixel bytes like
 * Android's LruCache sized by allocationByteCount. Not thread-safe: the owner
 * guards it with its lock.
 */
private class PageImageCache(private val maxBytes: Long) {
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L

    operator fun get(key: String): ImageBitmap? {
        // Reinserting moves the page to the most recently used end.
        val image = entries.remove(key) ?: return null
        entries[key] = image
        return image
    }

    fun put(key: String, image: ImageBitmap) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        entries[key] = image
        bytes += sizeOf(image)
        while (bytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.let { bytes -= sizeOf(it) }
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0L
    }

    private fun sizeOf(image: ImageBitmap): Long = image.width.toLong() * image.height * 4L
}
