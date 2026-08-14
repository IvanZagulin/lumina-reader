package com.lumina.reader.ui.library

import com.lumina.reader.core.model.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySeriesTest {

    @Test
    fun normalizesShelfNames() {
        assertEquals("Хроники Амбера", normalizeShelfName("  Хроники   Амбера  "))
    }

    @Test
    fun sortsNumberedSeriesBooksBeforeBooksWithoutPosition() {
        val books = listOf(
            book(title = "Без номера", order = 0),
            book(title = "Третья", order = 3),
            book(title = "Первая", order = 1),
            book(title = "Вторая", order = 2)
        )

        assertEquals(
            listOf("Первая", "Вторая", "Третья", "Без номера"),
            books.sortedForSeries().map(Book::title)
        )
    }

    @Test
    fun sortsUnnumberedSeriesBooksByTitle() {
        val books = listOf(
            book(title = "Янтарь", order = 0),
            book(title = "Аметист", order = 0)
        )

        assertEquals(
            listOf("Аметист", "Янтарь"),
            books.sortedForSeries().map(Book::title)
        )
    }

    @Test
    fun sortsSeriesInNumberOrderOnTheMainLibraryScreen() {
        val books = listOf(
            book(title = "Том 2", order = 2),
            book(title = "Одиночная", order = 0).copy(seriesName = ""),
            book(title = "Том 1", order = 1)
        )

        assertEquals(
            listOf("Том 1", "Том 2", "Одиночная"),
            books.sortedForLibrary().map(Book::title)
        )
    }

    private fun book(title: String, order: Int) = Book(
        title = title,
        filePath = "$title.fb2",
        seriesName = "Серия",
        seriesOrder = order
    )
}
