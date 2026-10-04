package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher

// Platform half of the reader's illustration cache (ReaderImages.kt). The cache
// itself is common: it picks the sample size, keeps the LRU and the header
// sizes; only reading the image header, decoding the pixels and sizing the
// memory budget differ per platform.
//
// Public, not internal: ReaderImages.kt still lives in :app until the reader
// moves here, and internal is invisible across modules.

/**
 * Width and height of the encoded image from its header, without decoding the
 * pixels, or null when [bytes] are not an image this platform can read.
 * Android: BitmapFactory with inJustDecodeBounds, as before; iOS: a lazy Skia
 * image, whose size comes from the codec header.
 */
expect fun encodedImageSize(bytes: ByteArray): IntSize?

/**
 * Decodes [bytes] at 1/[inSampleSize] of their size in each dimension, or
 * returns null when they cannot be decoded.
 *
 * Android: BitmapFactory with inSampleSize, as before. When the heap has no
 * room for the pixels, [onOutOfMemory] runs (the cache drops what it holds so
 * the next decode can succeed) and null is returned. Kotlin/Native has no
 * catchable OutOfMemoryError, so iOS never calls it.
 */
expect fun decodeBookImage(bytes: ByteArray, inSampleSize: Int, onOutOfMemory: () -> Unit): ImageBitmap?

/**
 * Bytes the pixels of [image] (made by [decodeBookImage]) occupy: the LRU's
 * size of an entry. Android: Bitmap.allocationByteCount, which LruCache.sizeOf
 * returned before; iOS: four bytes per pixel of the N32 raster.
 */
expect fun bookImageByteCount(image: ImageBitmap): Int

/**
 * The memory the reader sizes its bitmap caches and PDF pages against.
 * Android: the Java heap limit (Runtime.maxMemory()). iOS has no heap limit;
 * the app is killed by jetsam long before physical memory runs out, so this is
 * a conservative share of the device's RAM in the range of an Android heap.
 */
expect fun readerHeapBudgetBytes(): Long

/**
 * Where the reader runs blocking work such as decoding illustrations:
 * Dispatchers.IO on both platforms. A seam because the reader's common code
 * is kept free of Dispatchers.IO: on the JVM it is a member, on Native an
 * extension that needs its own import (kotlinx.coroutines.IO).
 */
expect val readerIoDispatcher: CoroutineDispatcher
