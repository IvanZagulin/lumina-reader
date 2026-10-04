package com.lumina.reader.core.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [LruMap] replaced android.util.LruCache in the book cache and the reader's
 * image cache ([LruMap.clear] standing in for evictAll()). This runs both
 * over random operations: the reference is LruCache's algorithm on an
 * access-ordered java.util.LinkedHashMap (put adds the new size, subtracts
 * the replaced one, then evicts the eldest entries while over budget).
 */
class LruMapParityTest {

    private class ReferenceLruCache(private val maxSize: Int) {
        val map = LinkedHashMap<String, Int>(0, 0.75f, true)
        var size = 0L

        fun get(key: String): Int? = map[key]

        fun put(key: String, value: Int) {
            size += value
            val previous = map.put(key, value)
            if (previous != null) size -= previous
            while (size > maxSize && map.isNotEmpty()) {
                val eldest = map.entries.iterator().next()
                map.remove(eldest.key)
                size -= eldest.value
            }
        }

        fun remove(key: String) {
            val previous = map.remove(key)
            if (previous != null) size -= previous
        }

        /** evictAll(): trimToSize(-1), i.e. every entry goes. */
        fun evictAll() {
            map.clear()
            size = 0
        }
    }

    @Test
    fun evictsLikeLruCache() {
        val random = Random(48)
        repeat(50) { round ->
            val maxSize = 20 + random.nextInt(200)
            val reference = ReferenceLruCache(maxSize)
            val lru = LruMap<String, Int>(maxSize) { it }
            repeat(2_000) { step ->
                val key = "k" + random.nextInt(30)
                when (random.nextInt(40)) {
                    in 0..19 -> {
                        val value = random.nextInt(maxSize / 3 + 2)
                        reference.put(key, value)
                        lru.put(key, value)
                    }
                    in 20..29 -> assertEquals("round $round step $step get", reference.get(key), lru[key])
                    39 -> {
                        // The reader's image cache drops everything when a decode runs out of memory.
                        reference.evictAll()
                        lru.clear()
                    }
                    else -> {
                        reference.remove(key)
                        lru.remove(key)
                    }
                }
                assertEquals("round $round step $step order", reference.map.keys.toList(), lru.keys)
                assertEquals("round $round step $step size", reference.size, lru.size)
            }
        }
    }
}
