package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import platform.Foundation.NSProcessInfo

private const val MB = 1024L * 1024

/**
 * Image.makeFromEncoded is lazy: it reads the codec header (so width and
 * height are known) and decodes the pixels only when they are first read.
 * It throws for bytes Skia cannot decode.
 */
private fun lazyImageOrNull(bytes: ByteArray): Image? =
    if (bytes.isEmpty()) null else runCatching { Image.makeFromEncoded(bytes) }.getOrNull()

actual fun encodedImageSize(bytes: ByteArray): IntSize? {
    val image = lazyImageOrNull(bytes) ?: return null
    try {
        if (image.width <= 0 || image.height <= 0) return null
        return IntSize(image.width, image.height)
    } finally {
        image.close()
    }
}

/**
 * Skia has no sampled decode like BitmapFactory's inSampleSize, so the image
 * is scaled into a raster of the sampled size. Skia decodes the full image for
 * that only transiently: the caching hint is off, so the full-size pixels are
 * not kept in its resource cache.
 */
actual fun decodeBookImage(bytes: ByteArray, inSampleSize: Int, onOutOfMemory: () -> Unit): ImageBitmap? {
    val image = lazyImageOrNull(bytes) ?: return null
    try {
        val sample = inSampleSize.coerceAtLeast(1)
        val width = (image.width / sample).coerceAtLeast(1)
        val height = (image.height / sample).coerceAtLeast(1)
        val bitmap = Bitmap()
        // Not opaque: illustrations (PNG, GIF) can have transparent pixels.
        val pixels = if (bitmap.allocN32Pixels(width, height, false)) bitmap.peekPixels() else null
        if (pixels == null) {
            bitmap.close()
            return null
        }
        val scaled = try {
            image.scalePixels(pixels, SamplingMode.LINEAR, false)
        } finally {
            pixels.close()
        }
        if (!scaled) {
            bitmap.close()
            return null
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    } finally {
        image.close()
    }
}

/** [decodeBookImage] allocates N32 rasters: four bytes per pixel. */
actual fun bookImageByteCount(image: ImageBitmap): Int = image.width * image.height * 4

/**
 * A sixteenth of the device's RAM, between 128 and 512 MB: 256 MB on a 4 GB
 * iPhone, the size of a typical Android heap. iOS gives no per-app heap limit
 * and jetsam's foreground limit sits well below physical memory, so the share
 * stays small.
 */
actual fun readerHeapBudgetBytes(): Long {
    val physical = NSProcessInfo.processInfo.physicalMemory.toLong()
    return (physical / 16).coerceIn(128 * MB, 512 * MB)
}

actual val readerIoDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
