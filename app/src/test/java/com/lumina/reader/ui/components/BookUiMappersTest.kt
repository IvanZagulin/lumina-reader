package com.lumina.reader.ui.components

import com.lumina.reader.core.model.Book
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Book → shared component model mappers that stay in :app (the rest is in :sharedUi's commonTest). */
class BookUiMappersTest {

    private val eps = 0.001f

    @Test
    fun coverModelOfABook() {
        val book = Book(id = 5, title = "Дюна", author = "Герберт", filePath = "/b/5.fb2", coverPath = "/c/5.jpg")
        val model = book.toCoverModel()
        assertEquals(5L, model.bookId)
        assertEquals("book:5", model.key)
        assertEquals("/c/5.jpg".toPath(), model.image)
        assertNull(book.copy(coverPath = " ").toCoverModel().image)
    }

    @Test
    fun shelfBookUiAndDescription() {
        val book = Book(
            id = 3,
            title = "Ведьмак",
            author = "Сапковский",
            filePath = "/b/3.fb2",
            currentProgressPercent = 42.4f,
            isFavorite = true,
            seriesName = "Ведьмак",
            seriesOrder = 3
        )
        val ui = book.toShelfBookUi()
        assertEquals(0.424f, ui.progress, eps)
        assertEquals(3, ui.seriesNumber)
        assertEquals(false, ui.isNew)
        assertEquals(
            "«Ведьмак», Сапковский, прочитано 42%, в избранном, книга 3 в серии",
            shelfBookDescription(ui)
        )
        val finished = book.copy(isCompleted = true, isFavorite = false, seriesName = "").toShelfBookUi()
        assertNull(finished.seriesNumber)
        assertEquals("«Ведьмак», Сапковский, прочитана", shelfBookDescription(finished))
        assertTrue(book.copy(currentProgressPercent = 0f).toShelfBookUi().isNew)
    }
}
