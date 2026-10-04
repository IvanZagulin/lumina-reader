package com.lumina.reader.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.Foundation.NSLock

/**
 * Cover colours on iOS: the file is decoded with Skia, sampled down to ≤ 96 px
 * like Android's `inSampleSize`, and run through [CoverColorQuantizer] (the
 * Palette algorithm). Cached in memory (LRU, 200 covers, keyed by path).
 */
actual object CoverPalette {
    private const val MAX_INPUT_PX = 96
    private val lock = NSLock()
    private val cache = LruMap<String, CoverColors>(200)

    actual fun peek(path: String?): CoverColors? = path?.let { key -> locked { cache[key] } }

    actual suspend fun colorsFor(path: String): CoverColors? {
        locked { cache[path] }?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val file = path.toPath()
                if (FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile != true) return@withContext null
                val bitmap = FileSystem.SYSTEM.read(file) { readByteArray() }.decodeToImageBitmap()
                val step = paletteSampleSize(bitmap.width, bitmap.height, MAX_INPUT_PX)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.readPixels(pixels)
                CoverColorQuantizer.extract(samplePixels(pixels, bitmap.width, bitmap.height, step))
                    .also { colors -> locked { cache.put(path, colors) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
