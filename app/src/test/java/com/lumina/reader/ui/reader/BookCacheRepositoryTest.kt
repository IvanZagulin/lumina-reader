package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.repository.BookCacheRepository
import com.lumina.reader.core.repository.estimateParsedBookBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The reader reuses parsed books only while they still match the file on disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookCacheRepositoryTest {

    private fun book(text: String, images: Map<String, ByteArray> = emptyMap()) = ParsedBook(
        title = "Книга",
        author = "Автор",
        chapters = listOf(Chapter(index = 0, title = "Глава", paragraphs = listOf(text))),
        images = images,
        format = BookFormat.TXT
    )

    @Test
    fun cachedBookIsReusedUntilTheFileChanges() {
        val file = File.createTempFile("cache-test", ".txt").apply { writeText("один") }
        try {
            val parsed = book("один")
            BookCacheRepository.put(file.path, parsed)
            assertSame(parsed, BookCacheRepository.get(file.absolutePath))

            file.writeText("один и ещё немного текста")
            assertNull(BookCacheRepository.get(file.path))
        } finally {
            BookCacheRepository.remove(file.path)
            file.delete()
        }
    }

    @Test
    fun removeForgetsTheBook() {
        val file = File.createTempFile("cache-test", ".txt").apply { writeText("два") }
        try {
            BookCacheRepository.put(file.path, book("два"))
            BookCacheRepository.remove(file.absolutePath)
            assertNull(BookCacheRepository.get(file.path))
        } finally {
            file.delete()
        }
    }

    @Test
    fun hugeBooksAreNotCached() {
        val file = File.createTempFile("cache-test", ".txt").apply { writeText("три") }
        try {
            val huge = book("три", images = mapOf("big" to ByteArray(BookCacheRepository.MAX_CACHE_BYTES)))
            BookCacheRepository.put(file.path, huge)
            assertNull(BookCacheRepository.get(file.path))
        } finally {
            BookCacheRepository.remove(file.path)
            file.delete()
        }
    }

    @Test
    fun sizeCountsTextAndImages() {
        val parsed = book("абвгд", images = mapOf("pic" to ByteArray(1_000)))
        // Two bytes per character of title, author, chapter title and text.
        val chars = "Книга".length + "Автор".length + "Глава".length + "абвгд".length
        assertEquals(chars * 2 + 1_000, estimateParsedBookBytes(parsed))
    }
}
