package com.lumina.reader.core.database

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.platform.Ids
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The database on the bundled SQLite driver, in a file of its own (as the app opens it). */
@OptIn(ExperimentalForeignApi::class)
class IosDatabaseTest {

    private val directory = NSTemporaryDirectory() + "lumina-db-" + Ids.randomUuid()

    @AfterTest
    fun removeFiles() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }

    @Test
    fun daosRoundTripOnTheBundledDriver() = runBlocking {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        val database = createAppDatabase("$directory/${AppDatabase.DATABASE_NAME}")
        try {
            val books = database.bookDao()
            val id = books.insertBook(
                Book(title = "Книга", author = "Автор", filePath = "Books/a.fb2", format = BookFormat.FB2)
            )
            books.updateProgress(
                bookId = id,
                chapterIndex = 2,
                paragraphIndex = 5,
                charOffset = 40,
                progress = 50f,
                timestamp = 1_000L
            )
            val book = books.getBookById(id)
            assertNotNull(book)
            assertEquals(BookFormat.FB2, book.format)
            assertEquals(40, book.currentCharOffset)
            assertEquals(1_000L, book.startedAt)
            assertEquals(listOf("Основная"), books.getCollections().first())

            books.updateCompleted(id, isComp = true, completedAt = 2_000L)
            val completed = books.getBookById(id)!!
            assertTrue(completed.isDone())
            assertEquals(2_000L, completed.completedAt)
            assertEquals(50f, completed.currentProgressPercent)

            books.updateCompleted(id, isComp = false, completedAt = 2_100L)
            assertFalse(books.getBookById(id)!!.isDone())
            assertEquals(40, books.getBookById(id)!!.currentCharOffset)
            books.updateProgress(id, 2, 5, 40, 99f, 3_000L)
            assertTrue(books.getBookById(id)!!.isDone())
            books.updateCompleted(id, isComp = false, completedAt = 3_100L)
            books.updateProgress(id, 2, 5, 40, 100f, 3_200L)
            assertFalse(books.getBookById(id)!!.isDone())

            // The same Unicode matching runs inside a Room transaction on iOS.
            books.updateCollection(id, " НАУЧНАЯ   ФАНТАСТИКА ")
            assertEquals(1, books.renameCollection("научная фантастика", "Архив"))
            assertEquals("Архив", books.getBookById(id)!!.collection)
            assertEquals(1, books.renameCollection("АРХИВ", "Основная"))

            val notes = database.bookmarkDao()
            notes.insertBookmark(Bookmark(bookId = id, chapterIndex = 2, paragraphIndex = 5, snippet = "x"))
            notes.insertHighlight(
                ReadingHighlight(bookId = id, chapterIndex = 2, selectedText = "цитата", startOffset = 1, endOffset = 4)
            )
            assertEquals(1, notes.getBookmarksForBook(id).first().size)
            assertEquals("цитата", notes.getHighlightsForBook(id).first().single().selectedText)

            val stats = database.readingStatsDao()
            stats.insertStats(ReadingStats(bookId = id, sessionDurationSeconds = 90, wordsReadCount = 300))
            assertEquals(90L, stats.getTotalReadingTimeSeconds())
            assertEquals(1, stats.getRecentReadingSessions(limit = 5, minSeconds = 30).size)

            books.deleteBookmarksOfBook(id)
            books.deleteHighlightsOfBook(id)
            books.deleteBookById(id)
            assertEquals(0, books.countBooks())
        } finally {
            database.close()
        }
    }
}
