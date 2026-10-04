package com.lumina.reader.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Palette extraction for local cover files: decoded with `inSampleSize` down
 * to ≤ 96 px on [Dispatchers.Default], cached in memory (LRU, 200 covers,
 * keyed by path). Nothing is stored in the database.
 */
actual object CoverPalette {
    private const val MAX_INPUT_PX = 96
    private val cache = LruCache<String, CoverColors>(200)

    /** Cached colours of [path], without decoding anything. */
    actual fun peek(path: String?): CoverColors? = path?.let { cache.get(it) }

    actual suspend fun colorsFor(path: String): CoverColors? {
        cache.get(path)?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val file = File(path)
                if (!file.isFile) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = paletteSampleSize(bounds.outWidth, bounds.outHeight, MAX_INPUT_PX)
                }
                val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext null
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                bitmap.recycle()
                CoverColors(
                    darkMuted = palette.darkMutedSwatch?.rgb?.let { Color(it) },
                    vibrant = palette.vibrantSwatch?.rgb?.let { Color(it) },
                    dominant = palette.dominantSwatch?.rgb?.let { Color(it) }
                ).also { cache.put(path, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}
