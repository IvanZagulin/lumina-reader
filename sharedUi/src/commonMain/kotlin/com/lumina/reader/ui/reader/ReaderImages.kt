package com.lumina.reader.ui.reader

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.repository.LruMap
import com.lumina.reader.platform.PlatformLock
import kotlinx.coroutines.withContext
import com.lumina.reader.ui.components.screenWidthDp

/** Widest bitmap the reader decodes for a book illustration. */
const val MAX_IMAGE_DECODE_WIDTH = 2048

/** Tallest bitmap the reader decodes, so very long strips stay affordable. */
const val MAX_IMAGE_DECODE_HEIGHT = 4096

/**
 * Largest power-of-two sample size that keeps the decoded image at least
 * [requestedWidth] wide (capped at [MAX_IMAGE_DECODE_WIDTH]) and no taller
 * than [MAX_IMAGE_DECODE_HEIGHT].
 */
fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int): Int {
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
 * Decoding happens off the main thread through [load]; the platform half
 * (header size, sampled decode, byte count) is in ReaderImagesPlatform.
 */
class ReaderImageCache(maxBytes: Int) {
    /**
     * Guards [cache] and [bounds]. android.util.LruCache locked itself and
     * LruMap does not, while [load] runs on several decode threads at once and
     * [peek] runs in composition.
     */
    private val lock = PlatformLock()
    private val cache = LruMap<String, ImageBitmap>(maxBytes.coerceAtLeast(1)) { bookImageByteCount(it) }
    private val bounds = HashMap<String, IntSize>()

    private fun key(imageId: String, targetWidth: Int) = "$imageId@$targetWidth"

    fun peek(imageId: String, targetWidth: Int): ImageBitmap? =
        lock.withLock { cache[key(imageId, targetWidth)] }

    /** Decodes [bytes] for [targetWidth]; blocking, call from a background thread. */
    fun load(imageId: String, bytes: ByteArray, targetWidth: Int): ImageBitmap? {
        val key = key(imageId, targetWidth)
        lock.withLock { cache[key] }?.let { return it }
        val size = imageSize(imageId, bytes) ?: return null
        val inSampleSize = calculateInSampleSize(size.width, size.height, targetWidth)
        val bitmap = decodeBookImage(bytes, inSampleSize, onOutOfMemory = { lock.withLock { cache.clear() } })
            ?: return null
        lock.withLock { cache.put(key, bitmap) }
        return bitmap
    }

    /** Width and height of the encoded image without decoding its pixels. */
    fun imageSize(imageId: String, bytes: ByteArray): IntSize? {
        lock.withLock { bounds[imageId] }?.let { return it }
        val size = encodedImageSize(bytes) ?: return null
        lock.withLock { bounds[imageId] = size }
        return size
    }

    fun clear() {
        lock.withLock {
            cache.clear()
            bounds.clear()
        }
    }

    companion object {
        /** An eighth of the heap, at most 32 MB. */
        fun defaultMaxBytes(): Int {
            val heap = readerHeapBudgetBytes()
            return (heap / 8).coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024).toInt()
        }
    }
}

/** A book illustration that fills the space it is given (paged reader). */
@Composable
fun BookImageFill(
    imageId: String,
    bytes: ByteArray?,
    cache: ReaderImageCache,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val targetWidth = constraints.maxWidth.coerceIn(1, MAX_IMAGE_DECODE_WIDTH)
        val bitmap by produceState(cache.peek(imageId, targetWidth), imageId, bytes, targetWidth) {
            // produceState keeps its value when the keys change, so always
            // resolve the bitmap for the current keys.
            value = cache.peek(imageId, targetWidth)
                ?: bytes?.let { data -> withContext(readerIoDispatcher) { cache.load(imageId, data, targetWidth) } }
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
fun BookImageInline(
    imageId: String,
    bytes: ByteArray?,
    cache: ReaderImageCache,
    modifier: Modifier = Modifier
) {
    if (bytes == null) return
    val density = LocalDensity.current
    // Becomes screenWidthDp() (ui.components, internal to :sharedUi; the same
    // value on Android) when this file moves there.
    val screenWidthPx = with(density) { screenWidthDp().dp.roundToPx() }
    val targetWidth = screenWidthPx.coerceIn(1, MAX_IMAGE_DECODE_WIDTH)
    val size = remember(imageId, bytes) { cache.imageSize(imageId, bytes) } ?: return
    val ratio = (size.width.toFloat() / size.height.toFloat()).coerceIn(0.2f, 5f)
    val bitmap by produceState(cache.peek(imageId, targetWidth), imageId, bytes, targetWidth) {
        value = cache.peek(imageId, targetWidth)
            ?: withContext(readerIoDispatcher) { cache.load(imageId, bytes, targetWidth) }
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
