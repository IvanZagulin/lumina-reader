package com.lumina.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Widest bitmap the reader decodes for a book illustration. */
internal const val MAX_IMAGE_DECODE_WIDTH = 2048

/** Tallest bitmap the reader decodes, so very long strips stay affordable. */
internal const val MAX_IMAGE_DECODE_HEIGHT = 4096

/**
 * Largest power-of-two sample size that keeps the decoded image at least
 * [requestedWidth] wide (capped at [MAX_IMAGE_DECODE_WIDTH]) and no taller
 * than [MAX_IMAGE_DECODE_HEIGHT].
 */
internal fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val targetWidth = requestedWidth.coerceIn(1, MAX_IMAGE_DECODE_WIDTH)
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
    while (sourceHeight / sample > MAX_IMAGE_DECODE_HEIGHT || sourceWidth / sample > MAX_IMAGE_DECODE_WIDTH) {
        sample *= 2
    }
    return sample
}

/**
 * Decoded book illustrations, sized for the screen and bounded by memory.
 * Decoding happens off the main thread through [load].
 */
internal class ReaderImageCache(maxBytes: Int) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val bounds = HashMap<String, Pair<Int, Int>>()

    private fun key(imageId: String, targetWidth: Int) = "$imageId@$targetWidth"

    fun peek(imageId: String, targetWidth: Int): ImageBitmap? =
        cache.get(key(imageId, targetWidth))?.asImageBitmap()

    /** Decodes [bytes] for [targetWidth]; blocking, call from a background thread. */
    fun load(imageId: String, bytes: ByteArray, targetWidth: Int): ImageBitmap? {
        val key = key(imageId, targetWidth)
        cache.get(key)?.let { return it.asImageBitmap() }
        val size = imageSize(imageId, bytes) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(size.first, size.second, targetWidth)
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: OutOfMemoryError) {
            cache.evictAll()
            null
        } ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    /** Width and height of the encoded image without decoding its pixels. */
    fun imageSize(imageId: String, bytes: ByteArray): Pair<Int, Int>? {
        synchronized(bounds) { bounds[imageId] }?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val size = options.outWidth to options.outHeight
        synchronized(bounds) { bounds[imageId] = size }
        return size
    }

    fun clear() {
        cache.evictAll()
        synchronized(bounds) { bounds.clear() }
    }

    companion object {
        /** An eighth of the heap, at most 32 MB. */
        fun defaultMaxBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 8).coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024).toInt()
        }
    }
}

/** A book illustration that fills the space it is given (paged reader). */
@Composable
internal fun BookImageFill(
    imageId: String,
    bytes: ByteArray?,
    cache: ReaderImageCache,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val targetWidth = constraints.maxWidth.coerceIn(1, MAX_IMAGE_DECODE_WIDTH)
        val bitmap by produceState(cache.peek(imageId, targetWidth), imageId, bytes, targetWidth) {
            if (value == null && bytes != null) {
                value = withContext(Dispatchers.IO) { cache.load(imageId, bytes, targetWidth) }
            }
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Иллюстрация книги",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * A book illustration in scrolling mode: full width, with its height reserved
 * from the image header before the pixels are decoded so the list does not
 * jump when the bitmap arrives.
 */
@Composable
internal fun BookImageInline(
    imageId: String,
    bytes: ByteArray?,
    cache: ReaderImageCache,
    modifier: Modifier = Modifier
) {
    if (bytes == null) return
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val targetWidth = screenWidthPx.coerceIn(1, MAX_IMAGE_DECODE_WIDTH)
    val size = remember(imageId, bytes) { cache.imageSize(imageId, bytes) } ?: return
    val ratio = (size.first.toFloat() / size.second.toFloat()).coerceIn(0.2f, 5f)
    val bitmap by produceState(cache.peek(imageId, targetWidth), imageId, bytes, targetWidth) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { cache.load(imageId, bytes, targetWidth) }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Иллюстрация книги",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
