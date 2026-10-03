package com.lumina.reader.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens databases written by older app versions through every migration.
 * Room validates the migrated schema against the entities when the database
 * is first used and throws on any mismatch, so touching every DAO is enough
 * to prove that the migrations and the entity annotations agree.
 *
 * The old schemas are the statements Room generated for the entities of
 * those versions (exported schemas are not available in this project).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesVersion5ToCurrent() {
        createDatabase(version = 5, statements = V5_SCHEMA + SAMPLE_ROWS_V5)
        runBlocking {
            val database = openWithMigrations()
            try {
                assertMigratedData(database, expectStartedAt = 900L)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun migratesVersion7ToCurrent() {
        createDatabase(version = 7, statements = V7_SCHEMA + SAMPLE_ROWS_V7)
        runBlocking {
            val database = openWithMigrations()
            try {
                assertMigratedData(database, expectStartedAt = 500L)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun freshInstallMatchesTheEntities() {
        context.deleteDatabase(databaseName)
        runBlocking {
            val database = openWithMigrations()
            try {
                val id = database.bookDao().insertBook(
                    Book(title = "Новая", filePath = "/books/new.epub", format = BookFormat.EPUB)
                )
                database.bookmarkDao().insertBookmark(
                    Bookmark(bookId = id, chapterIndex = 1, paragraphIndex = 2, charOffset = 30, snippet = "x")
                )
                database.bookmarkDao().insertHighlight(
                    ReadingHighlight(
                        bookId = id,
                        chapterIndex = 1,
                        selectedText = "x",
                        paragraphIndex = 2,
                        startOffset = 3,
                        endOffset = 9
                    )
                )
                assertEquals(30, database.bookmarkDao().getBookmarksForBook(id).first().single().charOffset)
                val highlight = database.bookmarkDao().getHighlightsForBook(id).first().single()
                assertEquals(3, highlight.startOffset)
                assertEquals(9, highlight.endOffset)
                assertIndices(database)
            } finally {
                database.close()
            }
        }
    }

    private suspend fun assertMigratedData(database: AppDatabase, expectStartedAt: Long) {
        val bookDao = database.bookDao()
        val bookmarkDao = database.bookmarkDao()
        val statsDao = database.readingStatsDao()

        val book = bookDao.getBookById(1)
        assertNotNull(book)
        book!!
        assertEquals("Книга", book.title)
        assertEquals(3, book.currentChapterIndex)
        assertEquals(7, book.currentParagraphIndex)
        assertEquals(0, book.currentCharOffset)
        assertEquals(42.5f, book.currentProgressPercent, 0.001f)
        assertEquals(BookFormat.FB2, book.format)
        assertEquals(expectStartedAt, book.startedAt)
        assertNull(book.completedAt)

        val bookmark = bookmarkDao.getBookmarksForBook(1).first().single()
        assertEquals(7, bookmark.paragraphIndex)
        assertEquals(0, bookmark.charOffset)

        val highlight = bookmarkDao.getHighlightsForBook(1).first().single()
        assertEquals("цитата", highlight.selectedText)
        assertEquals(0, highlight.paragraphIndex)
        assertEquals(0, highlight.startOffset)
        assertEquals(0, highlight.endOffset)

        assertEquals(1, statsDao.getStatsForBook(1).first().size)
        assertEquals(1, statsDao.getRecentReadingSessions(limit = 10, minSeconds = 30).size)

        // The new column is written by progress updates.
        bookDao.updateProgress(bookId = 1, chapterIndex = 4, paragraphIndex = 2, charOffset = 15, progress = 50f)
        val updated = bookDao.getBookById(1)!!
        assertEquals(4, updated.currentChapterIndex)
        assertEquals(15, updated.currentCharOffset)

        // Deleting a book's annotations.
        bookmarkDao.deleteBookmarksForBook(1)
        bookmarkDao.deleteHighlightsForBook(1)
        assertTrue(bookmarkDao.getBookmarksForBook(1).first().isEmpty())
        assertTrue(bookmarkDao.getHighlightsForBook(1).first().isEmpty())

        assertIndices(database)
    }

    private fun assertIndices(database: AppDatabase) {
        val names = mutableSetOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index'")
            .use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
        assertTrue(names.containsAll(
            listOf("index_bookmarks_bookId", "index_highlights_bookId", "index_reading_stats_bookId")
        ))
    }

    private fun createDatabase(version: Int, statements: List<String>) {
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            statements.forEach { database.execSQL(it) }
            database.version = version
        } finally {
            database.close()
        }
    }

    private fun openWithMigrations(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private companion object {
        val V5_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `filePath` TEXT NOT NULL, `coverPath` TEXT, " +
                "`format` TEXT NOT NULL, `currentChapterIndex` INTEGER NOT NULL, " +
                "`currentParagraphIndex` INTEGER NOT NULL, `currentProgressPercent` REAL NOT NULL, " +
                "`totalChapters` INTEGER NOT NULL, `lastReadTimestamp` INTEGER NOT NULL, " +
                "`fileSizeBytes` INTEGER NOT NULL, `language` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                "`isFavorite` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `collection` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, `seriesName` TEXT NOT NULL, `seriesOrder` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `paragraphIndex` INTEGER NOT NULL, " +
                "`chapterTitle` TEXT NOT NULL, `snippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `selectedText` TEXT NOT NULL, " +
                "`note` TEXT, `colorHex` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `reading_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `sessionDurationSeconds` INTEGER NOT NULL, " +
                "`wordsReadCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)"
        )

        /** Version 7 added the nullable completion and start timestamps to books. */
        val V7_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `filePath` TEXT NOT NULL, `coverPath` TEXT, " +
                "`format` TEXT NOT NULL, `currentChapterIndex` INTEGER NOT NULL, " +
                "`currentParagraphIndex` INTEGER NOT NULL, `currentProgressPercent` REAL NOT NULL, " +
                "`totalChapters` INTEGER NOT NULL, `lastReadTimestamp` INTEGER NOT NULL, " +
                "`startedAt` INTEGER, `fileSizeBytes` INTEGER NOT NULL, `language` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, " +
                "`completedAt` INTEGER, `collection` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                "`seriesName` TEXT NOT NULL, `seriesOrder` INTEGER NOT NULL)"
        ) + V5_SCHEMA.drop(1)

        private const val INSERT_BOOK_V5 =
            "INSERT INTO books (id, title, author, filePath, coverPath, format, currentChapterIndex, " +
                "currentParagraphIndex, currentProgressPercent, totalChapters, lastReadTimestamp, " +
                "fileSizeBytes, language, description, isFavorite, isCompleted, collection, tags, " +
                "seriesName, seriesOrder) VALUES (1, 'Книга', 'Автор', '/books/a.fb2', NULL, 'FB2', 3, 7, " +
                "42.5, 10, 1000, 2048, 'ru', '', 0, 0, 'Основная', '', '', 0)"

        private const val INSERT_BOOK_V7 =
            "INSERT INTO books (id, title, author, filePath, coverPath, format, currentChapterIndex, " +
                "currentParagraphIndex, currentProgressPercent, totalChapters, lastReadTimestamp, startedAt, " +
                "fileSizeBytes, language, description, isFavorite, isCompleted, completedAt, collection, tags, " +
                "seriesName, seriesOrder) VALUES (1, 'Книга', 'Автор', '/books/a.fb2', NULL, 'FB2', 3, 7, " +
                "42.5, 10, 1000, 500, 2048, 'ru', '', 0, 0, NULL, 'Основная', '', '', 0)"

        private val ANNOTATION_ROWS = listOf(
            "INSERT INTO bookmarks (id, bookId, chapterIndex, paragraphIndex, chapterTitle, snippet, createdAt) " +
                "VALUES (1, 1, 3, 7, 'Глава 4', 'Текст', 1000)",
            "INSERT INTO highlights (id, bookId, chapterIndex, selectedText, note, colorHex, createdAt) " +
                "VALUES (1, 1, 3, 'цитата', NULL, '#FFEB3B', 1000)",
            "INSERT INTO reading_stats (id, bookId, sessionDurationSeconds, wordsReadCount, timestamp) " +
                "VALUES (1, 1, 600, 1500, 900)"
        )

        val SAMPLE_ROWS_V5 = listOf(INSERT_BOOK_V5) + ANNOTATION_ROWS
        val SAMPLE_ROWS_V7 = listOf(INSERT_BOOK_V7) + ANNOTATION_ROWS
    }
}
