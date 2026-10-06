package com.lumina.reader.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lumina.reader.core.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDaoBehaviorTest {
    private fun withDao(block: suspend (BookDao) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            block(database.bookDao())
        } finally {
            database.close()
        }
    }

    @Test
    fun completionToggleKeepsReadingPositionAndPercentage() = withDao { dao ->
        val original = Book(title = "Book", filePath = "book.epub", currentProgressPercent = 42f,
            currentChapterIndex = 3, currentParagraphIndex = 7, currentCharOffset = 12)
        val id = dao.insertBook(original)
        dao.updateCompleted(id, true, 100L)
        assertTrue(dao.getBookById(id)!!.isDone())
        assertEquals(42f, dao.getBookById(id)!!.currentProgressPercent, 0f)
        dao.updateCompleted(id, false, 200L)
        val unread = dao.getBookById(id)!!
        assertFalse(unread.isDone())
        assertNull(unread.completedAt)
        assertEquals(42f, unread.currentProgressPercent, 0f)
        assertEquals(3, unread.currentChapterIndex)
        assertEquals(7, unread.currentParagraphIndex)
        assertEquals(12, unread.currentCharOffset)
    }

    @Test
    fun savingAnUnreadBookAtTheEndDoesNotCompleteItAgain() = withDao { dao ->
        val id = dao.insertBook(Book(title = "Book", filePath = "book.epub"))
        dao.updateProgress(id, 4, 8, 12, 99f, 100L)
        assertTrue(dao.getBookById(id)!!.isDone())
        dao.updateCompleted(id, false, 200L)
        dao.updateProgress(id, 4, 8, 12, 100f, 300L)
        assertFalse(dao.getBookById(id)!!.isDone())
        assertEquals(100f, dao.getBookById(id)!!.currentProgressPercent, 0f)
        // Reading through the ending again records a new completion.
        dao.updateProgress(id, 3, 0, 0, 90f, 400L)
        dao.updateProgress(id, 4, 8, 12, 99f, 500L)
        assertTrue(dao.getBookById(id)!!.isDone())
        assertEquals(500L, dao.getBookById(id)!!.completedAt)
    }

    @Test
    fun oldCompletedRowsCanBeMarkedUnreadWithoutLosingPosition() = withDao { dao ->
        val id = dao.insertBook(Book(title = "Old book", filePath = "book.epub",
            currentProgressPercent = 100f, currentChapterIndex = 4, isCompleted = true, completedAt = 100L))
        dao.updateCompleted(id, false, 200L)
        val book = dao.getBookById(id)!!
        assertFalse(book.isDone())
        assertEquals(4, book.currentChapterIndex)
        assertEquals(100f, book.currentProgressPercent, 0f)
    }

    @Test
    fun revisitingAnEarlierChapterDoesNotEraseCompletion() = withDao { dao ->
        val id = dao.insertBook(Book(title = "Book", filePath = "book.epub", completedAt = 100L))
        dao.updateProgress(id, 1, 0, 0, 20f, 200L)
        assertTrue(dao.getBookById(id)!!.isDone())
        assertEquals(100L, dao.getBookById(id)!!.completedAt)
    }

    @Test
    fun renameCollectionMatchesCyrillicAndNormalizedWhitespace() = withDao { dao ->
        for (name in listOf("Научная фантастика", " НАУЧНАЯ   ФАНТАСТИКА ", "Учеба")) {
            dao.insertBook(Book(title = name, filePath = name, collection = name))
        }
        assertEquals(2, dao.renameCollection("научная фантастика", "Архив"))
        assertEquals(2, dao.getAllBooksOnce().count { it.collection == "Архив" })
        assertEquals(2, dao.renameCollection("АРХИВ", "Основная"))
        assertEquals(1, dao.getAllBooksOnce().count { it.collection == "Учеба" })
    }
}
