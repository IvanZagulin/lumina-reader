package com.lumina.reader.ui.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.preferences.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfGroupingTest {

    private fun book(
        id: Long,
        title: String = "Книга $id",
        progress: Float = 0f,
        completed: Boolean = false,
        collection: String = "Основная",
        series: String = "",
        order: Int = 0,
        lastRead: Long = id * 1000,
        author: String = "Автор"
    ) = Book(
        id = id,
        title = title,
        author = author,
        filePath = "/books/$id.epub",
        format = BookFormat.EPUB,
        currentProgressPercent = progress,
        isCompleted = completed,
        collection = collection,
        seriesName = series,
        seriesOrder = order,
        lastReadTimestamp = lastRead
    )

    private fun List<ShelfSection>.keys() = map(ShelfSection::key)

    @Test
    fun sectionsComeInSpecOrder() {
        val books = listOf(
            book(1, progress = 30f, lastRead = 10),
            book(2, progress = 50f, lastRead = 20),
            book(3),
            book(4, collection = "Фантастика"),
            book(5, series = "Ведьмак", order = 2),
            book(6, completed = true),
            book(7, progress = 100f)
        )
        val sections = groupIntoShelves(books, heroBookId = 2, customShelfNames = emptyList())
        assertEquals(
            listOf("reading", "new", "series:ведьмак", "custom:фантастика", "main", "finished"),
            sections.keys()
        )
    }

    @Test
    fun readingShelfExcludesHeroAndIsLastReadFirst() {
        val books = listOf(
            book(1, progress = 10f, lastRead = 100),
            book(2, progress = 20f, lastRead = 300),
            book(3, progress = 30f, lastRead = 200),
            book(4, progress = 99.5f, lastRead = 400)
        )
        val reading = groupIntoShelves(books, heroBookId = 2, customShelfNames = emptyList())
            .first { it.kind == ShelfKind.READING }
        assertEquals(listOf(3L, 1L), reading.books.map(Book::id))
    }

    @Test
    fun newShelfHoldsTwelveNewestUntouchedBooks() {
        val books = (1L..15L).map { book(it) } + book(16, progress = 5f) + book(17, completed = true)
        val fresh = groupIntoShelves(books, null, emptyList()).first { it.kind == ShelfKind.NEW }
        assertEquals((15L downTo 4L).toList(), fresh.books.map(Book::id))
    }

    @Test
    fun seriesIncludeFinishedBooksInOrderAndCountRead() {
        val books = listOf(
            book(1, series = "Дюна", order = 3),
            book(2, series = " дюна ", order = 1, completed = true),
            book(3, series = "Дюна", order = 2, progress = 40f),
            book(4, series = "Азбука", order = 1)
        )
        val series = groupIntoShelves(books, null, emptyList()).filter { it.kind == ShelfKind.SERIES }
        // The series with a book in progress comes first even though «Азбука» sorts earlier.
        assertEquals(listOf("series:дюна", "series:азбука"), series.keys())
        val dune = series.first()
        assertEquals(listOf(2L, 3L, 1L), dune.books.map(Book::id))
        assertEquals(1, dune.readCount)
        assertEquals(3, dune.totalCount)
        assertEquals("ДЮНА · 1/3", dune.plateText())
    }

    @Test
    fun customShelvesKeepUnfinishedLooseBooksAndRenderWhenEmpty() {
        val books = listOf(
            book(1, collection = "Фантастика", lastRead = 5),
            book(2, collection = " фантастика ", lastRead = 9),
            book(3, collection = "Фантастика", completed = true),
            book(4, collection = "Фантастика", series = "Цикл"),
            book(5, collection = "Учёба", completed = true)
        )
        val custom = groupIntoShelves(books, null, customShelfNames = listOf("Пустая", "Основная"))
            .filter { it.kind == ShelfKind.CUSTOM }
        assertEquals(listOf("Пустая", "Фантастика"), custom.map(ShelfSection::title))
        assertEquals(emptyList<Long>(), custom[0].books.map(Book::id))
        assertEquals(listOf(2L, 1L), custom[1].books.map(Book::id))
        // A shelf that only exists on finished books does not render.
        assertFalse(custom.any { it.title == "Учёба" })
        assertNull(custom[0].plateText())
    }

    @Test
    fun mainShelfShowsOnlyUnreadBooksStartedFirst() {
        val books = listOf(
            book(1, title = "Б"),
            book(2, title = "А"),
            book(3, title = "В", progress = 10f, lastRead = 50),
            book(4, title = "Г", completed = true),
            book(5, title = "Д", collection = "")
        )
        val main = groupIntoShelves(books, null, emptyList()).first { it.kind == ShelfKind.MAIN }
        assertEquals(listOf("В", "А", "Б", "Д"), main.books.map(Book::title))
    }

    @Test
    fun finishedShelfHasLooseFinishedBooksOnly() {
        val books = listOf(
            book(1, completed = true, lastRead = 1),
            book(2, progress = 99f, lastRead = 5),
            book(3, completed = true, series = "Серия")
        )
        val finished = groupIntoShelves(books, null, emptyList()).first { it.kind == ShelfKind.FINISHED }
        assertEquals(listOf(2L, 1L), finished.books.map(Book::id))
    }

    @Test
    fun emptyLibraryHasNoShelves() {
        assertTrue(groupIntoShelves(emptyList(), null, emptyList()).isEmpty())
    }

    @Test
    fun heroPrefersLastReadBookInProgressThenNewestUnread() {
        val inProgress = listOf(book(1, progress = 10f, lastRead = 5), book(2, progress = 20f, lastRead = 9), book(3))
        assertEquals(HeroPick(inProgress[1], resume = true), pickHeroBook(inProgress))

        val untouched = listOf(book(1), book(4), book(2, completed = true))
        assertEquals(HeroPick(untouched[1], resume = false), pickHeroBook(untouched))

        assertNull(pickHeroBook(listOf(book(1, completed = true))))
        assertNull(pickHeroBook(emptyList()))
    }

    @Test
    fun russianPluralsAndSubtitle() {
        assertEquals("книга", russianPlural(1, "книга", "книги", "книг"))
        assertEquals("книги", russianPlural(3, "книга", "книги", "книг"))
        assertEquals("книг", russianPlural(5, "книга", "книги", "книг"))
        assertEquals("книг", russianPlural(11, "книга", "книги", "книг"))
        assertEquals("книг", russianPlural(14, "книга", "книги", "книг"))
        assertEquals("книга", russianPlural(21, "книга", "книги", "книг"))
        assertEquals("книги", russianPlural(34, "книга", "книги", "книг"))
        assertEquals("книг", russianPlural(0, "книга", "книги", "книг"))

        assertEquals("34 книги · 3 читаю · 12 прочитано", LibrarySummary(34, 3, 12).subtitle())
        assertEquals("1 книга", LibrarySummary(1, 0, 0).subtitle())
        assertEquals("0 книг", LibrarySummary(0, 0, 0).subtitle())

        val summary = summarizeLibrary(listOf(book(1, progress = 5f), book(2, completed = true), book(3)))
        assertEquals(LibrarySummary(total = 3, reading = 1, finished = 1), summary)
    }

    @Test
    fun flatViewSorts() {
        val books = listOf(
            book(1, title = "в", author = "Б", progress = 0f, lastRead = 300),
            book(2, title = "А", author = "а", progress = 50f, lastRead = 100),
            book(3, title = "б", author = "а", progress = 20f, lastRead = 200)
        )
        assertEquals(listOf(3L, 2L, 1L), books.sortedForView(LibrarySort.RECENT).map(Book::id))
        assertEquals(listOf(2L, 3L, 1L), books.sortedForView(LibrarySort.TITLE).map(Book::id))
        assertEquals(listOf(2L, 3L, 1L), books.sortedForView(LibrarySort.AUTHOR).map(Book::id))
        assertEquals(listOf(3L, 2L, 1L), books.sortedForView(LibrarySort.ADDED).map(Book::id))
        assertEquals(listOf(2L, 3L, 1L), books.sortedForView(LibrarySort.PROGRESS).map(Book::id))
    }

    @Test
    fun heroMetaShowsChapterWhenKnown() {
        val withChapters = book(1, progress = 42.7f).copy(currentChapterIndex = 6, totalChapters = 18)
        assertEquals("42% · глава 7 из 18", heroMeta(withChapters))
        assertEquals("42%", heroMeta(book(2, progress = 42f).copy(totalChapters = 1)))
        val pdf = book(3, progress = 10f).copy(format = BookFormat.PDF, currentChapterIndex = 4, totalChapters = 120)
        assertEquals("10% · стр. 5 из 120", heroMeta(pdf))
    }

    @Test
    fun shareFileNamesAreSafe() {
        assertEquals("Мастер и Маргарита.epub", shareFileName("Мастер и Маргарита", "epub"))
        assertEquals("a b c.fb2", shareFileName("a/b:c", "fb2"))
        assertEquals("book.txt", shareFileName("  ", "txt"))
        assertEquals("application/pdf" to "pdf", BookFormat.PDF.shareMime())
        assertEquals("application/x-fictionbook+xml" to "fb2", BookFormat.FB2.shareMime())
        // A zipped FB2 is stored as the archive, so it is shared as one.
        assertEquals("application/zip" to "fb2.zip", BookFormat.FB2_ZIP.shareMime())
        assertEquals("Дюна.fb2.zip", shareFileName("Дюна", BookFormat.FB2_ZIP.shareMime().second))
    }
}
