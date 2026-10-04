package com.lumina.reader.core.repository

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.platform.Ids
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The reader reuses parsed books only while they still match the file on disk. */
class BookCacheRepositoryTest {

    private fun book(text: String, images: Map<String, ByteArray> = emptyMap()) = ParsedBook(
        title = "Книга",
        author = "Автор",
        chapters = listOf(Chapter(index = 0, title = "Глава", paragraphs = listOf(text))),
        images = images,
        format = BookFormat.TXT
    )

    private fun tempFile(text: String): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("cache-test" + Ids.randomUuid() + ".txt")
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
        return path
    }

    @Test
    fun cachedBookIsReusedUntilTheFileChanges() {
        val file = tempFile("один")
        try {
            val parsed = book("один")
            BookCacheRepository.put(file.toString(), parsed)
            assertSame(parsed, BookCacheRepository.get(file.toString()))

            FileSystem.SYSTEM.write(file) { writeUtf8("один и ещё немного текста") }
            assertNull(BookCacheRepository.get(file.toString()))
        } finally {
            BookCacheRepository.remove(file.toString())
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }

    @Test
    fun removeForgetsTheBook() {
        val file = tempFile("два")
        try {
            BookCacheRepository.put(file.toString(), book("два"))
            BookCacheRepository.remove(file.toString())
            assertNull(BookCacheRepository.get(file.toString()))
        } finally {
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }

    @Test
    fun hugeBooksAreNotCached() {
        val file = tempFile("три")
        try {
            val huge = book("три", images = mapOf("big" to ByteArray(BookCacheRepository.MAX_CACHE_BYTES)))
            BookCacheRepository.put(file.toString(), huge)
            assertNull(BookCacheRepository.get(file.toString()))
        } finally {
            BookCacheRepository.remove(file.toString())
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }

    @Test
    fun deletedFileIsNotServed() {
        val file = tempFile("четыре")
        try {
            BookCacheRepository.put(file.toString(), book("четыре"))
            FileSystem.SYSTEM.delete(file)
            assertNull(BookCacheRepository.get(file.toString()))
        } finally {
            BookCacheRepository.remove(file.toString())
        }
    }

    @Test
    fun sizeCountsTextAndImages() {
        val parsed = book("абвгд", images = mapOf("pic" to ByteArray(1_000)))
        // Two bytes per character of title, author, chapter title and text.
        val chars = "Книга".length + "Автор".length + "Глава".length + "абвгд".length
        assertEquals(chars * 2 + 1_000, estimateParsedBookBytes(parsed))
    }

    @Test
    fun lruEvictsTheLeastRecentlyUsedEntriesFirst() {
        val lru = LruMap<String, Int>(maxSize = 10) { it }
        lru.put("a", 4)
        lru.put("b", 4)
        assertEquals(4, lru["a"]) // "a" is now the most recently used
        lru.put("c", 4) // 12 > 10: "b" goes
        assertEquals(listOf("a", "c"), lru.keys)
        assertEquals(8L, lru.size)

        assertEquals(4, lru.put("a", 1)) // replacing adjusts the size and counts as a use
        assertEquals(listOf("c", "a"), lru.keys)
        assertEquals(5L, lru.size)

        lru.put("d", 10) // "c" and then "a" go; "d" alone fits exactly
        assertEquals(listOf("d"), lru.keys)
        lru.put("e", 11) // larger than the whole budget: nothing stays, as in LruCache
        assertEquals(emptyList(), lru.keys)
        assertEquals(0L, lru.size)
        assertNull(lru.remove("x"))
        assertFailsWith<IllegalStateException> { lru.put("negative", -1) }
    }
}
