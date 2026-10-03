package com.lumina.reader.core.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDeduplicationTest {

    private val existing = listOf(
        DedupeCandidate(1, "Дюна", "Фрэнк Герберт", BookFormat.FB2, fileSizeBytes = 1000),
        DedupeCandidate(2, "Другая", "Автор", BookFormat.EPUB, fileSizeBytes = 1000),
        DedupeCandidate(3, "Пропавший файл", "Автор", BookFormat.FB2, fileSizeBytes = null)
    )

    @Test
    fun identicalBytesAreADuplicate() {
        val incoming = IncomingFingerprint(BookFormat.FB2_ZIP, sizeBytes = 1000, sha256 = "AB12")
        val hashed = mutableListOf<Long>()
        val duplicate = BookDeduplication.findDuplicate(incoming, existing) { candidate ->
            hashed += candidate.bookId
            "ab12"
        }
        assertEquals(1L, duplicate?.bookId)
        // Only the size-and-format match was hashed; the EPUB of equal size was not.
        assertEquals(listOf(1L), hashed)
    }

    @Test
    fun sameSizeWithDifferentContentIsNotADuplicate() {
        val incoming = IncomingFingerprint(BookFormat.FB2, sizeBytes = 1000, sha256 = "ffff")
        assertNull(BookDeduplication.findDuplicate(incoming, existing) { "0000" })
    }

    @Test
    fun differentSizeIsNeverHashed() {
        val incoming = IncomingFingerprint(BookFormat.FB2, sizeBytes = 999, sha256 = "ab12")
        assertNull(BookDeduplication.findDuplicate(incoming, existing) { error("must not hash") })
    }

    @Test
    fun missingFilesAreNotDuplicates() {
        val incoming = IncomingFingerprint(BookFormat.FB2, sizeBytes = 0, sha256 = "x", title = "Пропавший файл")
        assertNull(BookDeduplication.findDuplicate(incoming, existing) { "x" })
    }

    @Test
    fun hugeFilesFallBackToSizeTitleAndAuthor() {
        val huge = BookDeduplication.MAX_HASHED_BYTES + 1
        val library = listOf(DedupeCandidate(7, "Большая книга", "Автор", BookFormat.PDF, huge))

        val beforeParsing = IncomingFingerprint(BookFormat.PDF, huge, "hash")
        assertNull(BookDeduplication.findDuplicate(beforeParsing, library) { error("must not hash huge files") })

        val parsed = beforeParsing.copy(title = "большая  книга", author = "автор")
        assertEquals(7L, BookDeduplication.findDuplicate(parsed, library) { error("must not hash huge files") }?.bookId)

        val otherTitle = beforeParsing.copy(title = "Совсем другая")
        assertNull(BookDeduplication.findDuplicate(otherTitle, library) { null })
    }

    @Test
    fun fb2AndZippedFb2AreOneFamily() {
        assertTrue(BookDeduplication.sameFormatFamily(BookFormat.FB2, BookFormat.FB2_ZIP))
        assertTrue(!BookDeduplication.sameFormatFamily(BookFormat.FB2, BookFormat.EPUB))
    }

    @Test
    fun seriesTitlesMatchExactlyBeforeContains() {
        val library = listOf(
            book(1, "Дети Дюны"),
            book(2, "Дюна"),
            book(3, "Мессия Дюны"),
            book(4, "Бог-император Дюны")
        )
        val matched = matchSeriesBooks(listOf("Дюна", "Мессия дюны", "Дети Дюны"), library)
        assertEquals(listOf(2L, 3L, 1L), matched.map(Book::id))
    }

    @Test
    fun seriesMatchingUsesDownloadsThenContainsAndUsesEachBookOnce() {
        val library = listOf(
            book(10, "Гарри Поттер и философский камень (иллюстрированное издание)"),
            book(11, "Гарри Поттер и Тайная комната"),
            book(12, "Совсем другая книга")
        )
        val downloaded = mapOf(normalizeTitleForMatch("Тайная комната") to 11L)
        val matched = matchSeriesBooks(
            listOf("Гарри Поттер и философский камень", "Тайная комната", "Гарри Поттер и Тайная комната", "Нет такой"),
            library,
            downloaded
        )
        assertEquals(listOf(10L, 11L), matched.map(Book::id))
    }

    @Test
    fun normalisesTitlesForMatching() {
        assertEquals("елка книга 1", normalizeTitleForMatch("«Ёлка» — (Книга 1)"))
    }

    private fun book(id: Long, title: String) = Book(id = id, title = title, filePath = "/books/$id.fb2")
}
