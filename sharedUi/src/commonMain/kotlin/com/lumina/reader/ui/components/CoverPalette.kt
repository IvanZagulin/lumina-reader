package com.lumina.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color

/** Colours picked from a cover image (spec §1.8). */
@Immutable
data class CoverColors(
    val darkMuted: Color?,
    val vibrant: Color?,
    val dominant: Color?
) {
    /** The colour that best stands for the book (endpapers, glows). */
    val base: Color? get() = darkMuted ?: dominant ?: vibrant
}

/**
 * Palette extraction for local cover files: decoded down to ≤ 96 px off the
 * main thread, cached in memory (LRU, 200 covers, keyed by path). Nothing is
 * stored in the database.
 *
 * Android: androidx Palette on a `BitmapFactory` decode with `inSampleSize`
 * (unchanged). iOS: the same algorithm in common code ([CoverColorQuantizer]).
 */
expect object CoverPalette {
    /** Cached colours of [path], without decoding anything. */
    fun peek(path: String?): CoverColors?

    /** Colours of the cover file at [path]; null when it cannot be read or decoded. */
    suspend fun colorsFor(path: String): CoverColors?
}

/** Smallest power-of-two sample size that brings the longer side down to ≤ [target] px. */
fun paletteSampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val longer = maxOf(width, height)
    if (longer <= 0 || target <= 0) return 1
    while (longer / sample > target) sample *= 2
    return sample
}

/**
 * Every [step]-th pixel of every [step]-th row of an image [width] × [height]
 * ([pixels] row by row): a nearest-neighbour stand-in for `inSampleSize`.
 */
internal fun samplePixels(pixels: IntArray, width: Int, height: Int, step: Int): IntArray {
    if (step <= 1) return pixels
    val sampledWidth = (width + step - 1) / step
    val sampledHeight = (height + step - 1) / step
    return IntArray(sampledWidth * sampledHeight) { i ->
        val x = (i % sampledWidth) * step
        val y = (i / sampledWidth) * step
        pixels[y * width + x]
    }
}

/** A small least-recently-used map (not thread-safe; callers synchronise). */
internal class LruMap<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = map.size

    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    fun put(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
    }
}

/** Colours of the cover at [coverPath] (null while loading or without a cover). */
@Composable
fun rememberCoverColors(coverPath: String?): CoverColors? {
    val state = produceState(initialValue = CoverPalette.peek(coverPath), coverPath) {
        value = coverPath?.let { CoverPalette.colorsFor(it) }
    }
    return state.value
}
