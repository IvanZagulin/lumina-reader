package com.lumina.reader.ui.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.core.preferences.LibraryPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {

    private val books = listOf(
        book(1, "Дюна", author = "Герберт", collection = "Фантастика", format = BookFormat.FB2),
        book(2, "Мастер и Маргарита", author = "Булгаков", collection = "Основная", format = BookFormat.EPUB)
            .copy(isCompleted = true),
        book(3, "Ведьмак", author = "Сапковский", collection = " фантастика ", format = BookFormat.FB2_ZIP),
        book(4, "Пустая серия", author = "Автор", collection = "Фантастика", format = BookFormat.PDF)
            .copy(seriesName = "Цикл", seriesOrder = 1)
    )

    @Test
    fun shelfNamesStartWithMainThenUserShelvesThenShelvesOnBooks() {
        val merged = mergeShelfNames(
            customShelves = listOf("Избранное", "  Новая   полка "),
            shelvesOnBooks = listOf("фантастика", "Основная", "Избранное", "Алгебра")
        )
        assertEquals(listOf("Основная", "Избранное", "Новая полка", "Алгебра", "фантастика"), merged)
    }

    @Test
    fun shelfFilterIgnoresCaseAndSpacesAndHidesSeriesBooks() {
        val result = filterLibraryBooks(
            allBooks = books,
            query = "",
            format = null,
            status = ReadingStatus.COLLECTIONS,
            shelf = ShelfSelection(collection = "Фантастика")
        )
        assertEquals(listOf("Ведьмак", "Дюна"), result.map(Book::title))
    }

    @Test
    fun unreadTabHidesFinishedBooksButAllTabShowsThem() {
        val unread = filterLibraryBooks(books, "", null, ReadingStatus.UNREAD, ShelfSelection())
        assertEquals(false, unread.any { it.id == 2L })
        val all = filterLibraryBooks(books, "", null, ReadingStatus.ALL, ShelfSelection())
        assertEquals(4, all.size)
    }

    @Test
    fun manualUnreadStatusWinsOverPercentageInEveryFilter() {
        val unread = books.first().copy(currentProgressPercent = 100f, isCompleted = false, completedAt = null)
        val finished = books.last().copy(currentProgressPercent = 99f, completedAt = 123L)
        val all = listOf(unread, finished)
        for (status in listOf(ReadingStatus.UNREAD, ReadingStatus.READING)) {
            assertEquals(listOf(unread.id), filterLibraryBooks(all, "", null, status, ShelfSelection()).map(Book::id))
        }
        assertEquals(listOf(finished.id), filterLibraryBooks(all, "", null, ReadingStatus.COMPLETED, ShelfSelection()).map(Book::id))
    }

    @Test
    fun searchAndFormatFiltersCombine() {
        val byAuthor = filterLibraryBooks(books, "  булгаков ", null, ReadingStatus.ALL, ShelfSelection())
        assertEquals(listOf(2L), byAuthor.map(Book::id))

        val fb2Family = filterLibraryBooks(books, "", BookFormat.FB2, ReadingStatus.ALL, ShelfSelection())
        assertEquals(setOf(1L, 3L), fb2Family.map(Book::id).toSet())
    }

    @Test
    fun storedShelvesDecodeWithDefaultsAndCleanup() {
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, LibraryPreferences.decodeShelves(null))
        assertEquals(emptyList<String>(), LibraryPreferences.decodeShelves("[]"))
        assertEquals(
            listOf("Фантастика", "Учёба"),
            LibraryPreferences.decodeShelves("""[" Фантастика ","фантастика","","Учёба"]""")
        )
        assertEquals(
            listOf("А", "Б"),
            LibraryPreferences.decodeShelves(LibraryPreferences.encodeShelves(listOf("А", "Б")))
        )
    }

    private fun book(
        id: Long,
        title: String,
        author: String,
        collection: String,
        format: BookFormat
    ) = Book(
        id = id,
        title = title,
        author = author,
        filePath = "/books/$id",
        collection = collection,
        format = format
    )
}
