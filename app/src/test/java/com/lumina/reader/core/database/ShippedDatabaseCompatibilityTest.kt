package com.lumina.reader.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.lumina.reader.core.model.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A database written by the app as it shipped before the database moved to
 * :shared (Room in :app, release v1.1.62) must open unchanged with the Room
 * setup of :shared: same file name, same version, same schema and the same
 * identity hash, so no migration runs and Room accepts the file as is.
 *
 * The statements and hashes below were taken from AppDatabase_Impl in the
 * released APK (LuminaReader-1.1.62.apk, classes2.dex): they are what that
 * build executed on every install, not a re-derivation from the entities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShippedDatabaseCompatibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "shipped-compat.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun theAppKeepsItsDatabaseFile() {
        assertEquals("lumina_reader.db", AppDatabase.DATABASE_NAME)
    }

    @Test
    fun shippedVersion8DatabaseOpensWithoutMigration() {
        createShippedDatabase(withRoomMasterTable = true)
        runBlocking {
            val database = open()
            try {
                assertShippedRows(database)
                // The identity row is left alone: the hash matched.
                assertEquals(SHIPPED_IDENTITY_HASH, storedIdentityHash(database))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun shippedSchemaPassesRoomsValidationAndHasTheSameIdentityHash() {
        // Without room_master_table Room compares every table, column (type,
        // NOT NULL, default, primary key) and index of the file with the
        // entities, then records its own identity hash.
        createShippedDatabase(withRoomMasterTable = false)
        runBlocking {
            val database = open()
            try {
                assertShippedRows(database)
                assertEquals(SHIPPED_IDENTITY_HASH, storedIdentityHash(database))
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun freshInstallCreatesTheShippedSchema() {
        context.deleteDatabase(databaseName)
        runBlocking {
            val database = open()
            try {
                // Forces the file to be created.
                assertEquals(0, database.bookDao().countBooks())
                assertEquals(SHIPPED_IDENTITY_HASH, storedIdentityHash(database))
                assertEquals(8, database.openHelper.readableDatabase.version)

                val created = mutableSetOf<String>()
                database.openHelper.readableDatabase.query(
                    "SELECT sql FROM sqlite_master WHERE type IN ('table', 'index') " +
                        "AND name IN ('books', 'bookmarks', 'highlights', 'reading_stats', " +
                        "'index_bookmarks_bookId', 'index_highlights_bookId', 'index_reading_stats_bookId')"
                ).use { cursor ->
                    while (cursor.moveToNext()) created += cursor.getString(0)
                }
                // SQLite stores CREATE statements without "IF NOT EXISTS".
                val expected = SHIPPED_SCHEMA.map { it.replace(" IF NOT EXISTS ", " ") }.toSet()
                assertEquals(expected, created)
            } finally {
                database.close()
            }
        }
    }

    private fun open(): AppDatabase =
        AppDatabase.databaseBuilder(context, databaseName)
            .allowMainThreadQueries()
            .build()

    private fun storedIdentityHash(database: AppDatabase): String? =
        database.openHelper.readableDatabase
            .query("SELECT identity_hash FROM room_master_table WHERE id = 42")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private suspend fun assertShippedRows(database: AppDatabase) {
        val book = database.bookDao().getBookById(1)
        assertNotNull(book)
        book!!
        assertEquals("Книга", book.title)
        assertEquals("Автор", book.author)
        assertEquals("/data/user/0/com.lumina.reader/files/books/a.fb2", book.filePath)
        assertEquals(BookFormat.FB2, book.format)
        assertEquals(3, book.currentChapterIndex)
        assertEquals(7, book.currentParagraphIndex)
        assertEquals(120, book.currentCharOffset)
        assertEquals(42.5f, book.currentProgressPercent, 0.001f)
        assertEquals(500L, book.startedAt)
        assertEquals(null, book.completedAt)
        assertEquals("Учёба", book.collection)
        assertEquals("Цикл", book.seriesName)
        assertEquals(2, book.seriesOrder)
        assertTrue(book.isFavorite)

        val bookmark = database.bookmarkDao().getBookmarksForBook(1).first().single()
        assertEquals(7, bookmark.paragraphIndex)
        assertEquals(15, bookmark.charOffset)

        val highlight = database.bookmarkDao().getHighlightsForBook(1).first().single()
        assertEquals("цитата", highlight.selectedText)
        assertEquals("заметка", highlight.note)
        assertEquals(4, highlight.startOffset)
        assertEquals(10, highlight.endOffset)

        assertEquals(600L, database.readingStatsDao().getTotalReadingTimeSeconds())
        assertEquals(listOf("Учёба"), database.bookDao().getCollections().first())
    }

    private fun createShippedDatabase(withRoomMasterTable: Boolean) {
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            SHIPPED_SCHEMA.forEach { database.execSQL(it) }
            if (withRoomMasterTable) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                        "VALUES(42, '$SHIPPED_IDENTITY_HASH')"
                )
            }
            SAMPLE_ROWS.forEach { database.execSQL(it) }
            database.version = 8
        } finally {
            database.close()
        }
    }

    private companion object {
        /** RoomOpenHelper(…, identityHash, legacyHash) in the shipped AppDatabase_Impl. */
        const val SHIPPED_IDENTITY_HASH = "794b6e5292a14de48d2533fabe428539"

        /** AppDatabase_Impl.createAllTables of the shipped build, in its order. */
        val SHIPPED_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `filePath` TEXT NOT NULL, `coverPath` TEXT, " +
                "`format` TEXT NOT NULL, `currentChapterIndex` INTEGER NOT NULL, " +
                "`currentParagraphIndex` INTEGER NOT NULL, `currentCharOffset` INTEGER NOT NULL DEFAULT 0, " +
                "`currentProgressPercent` REAL NOT NULL, `totalChapters` INTEGER NOT NULL, " +
                "`lastReadTimestamp` INTEGER NOT NULL, `startedAt` INTEGER, `fileSizeBytes` INTEGER NOT NULL, " +
                "`language` TEXT NOT NULL, `description` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                "`isCompleted` INTEGER NOT NULL, `completedAt` INTEGER, `collection` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, `seriesName` TEXT NOT NULL, `seriesOrder` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `paragraphIndex` INTEGER NOT NULL, " +
                "`chapterTitle` TEXT NOT NULL, `snippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`charOffset` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)",
            "CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `selectedText` TEXT NOT NULL, " +
                "`note` TEXT, `colorHex` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`paragraphIndex` INTEGER NOT NULL DEFAULT 0, `startOffset` INTEGER NOT NULL DEFAULT 0, " +
                "`endOffset` INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS `index_highlights_bookId` ON `highlights` (`bookId`)",
            "CREATE TABLE IF NOT EXISTS `reading_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `sessionDurationSeconds` INTEGER NOT NULL, " +
                "`wordsReadCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_reading_stats_bookId` ON `reading_stats` (`bookId`)"
        )

        val SAMPLE_ROWS = listOf(
            "INSERT INTO books (id, title, author, filePath, coverPath, format, currentChapterIndex, " +
                "currentParagraphIndex, currentCharOffset, currentProgressPercent, totalChapters, " +
                "lastReadTimestamp, startedAt, fileSizeBytes, language, description, isFavorite, isCompleted, " +
                "completedAt, collection, tags, seriesName, seriesOrder) VALUES (1, 'Книга', 'Автор', " +
                "'/data/user/0/com.lumina.reader/files/books/a.fb2', NULL, 'FB2', 3, 7, 120, 42.5, 10, 1000, " +
                "500, 2048, 'ru', '', 1, 0, NULL, 'Учёба', '', 'Цикл', 2)",
            "INSERT INTO bookmarks (id, bookId, chapterIndex, paragraphIndex, chapterTitle, snippet, createdAt, " +
                "charOffset) VALUES (1, 1, 3, 7, 'Глава 4', 'Текст', 1000, 15)",
            "INSERT INTO highlights (id, bookId, chapterIndex, selectedText, note, colorHex, createdAt, " +
                "paragraphIndex, startOffset, endOffset) VALUES (1, 1, 3, 'цитата', 'заметка', '#FFEB3B', 1000, " +
                "7, 4, 10)",
            "INSERT INTO reading_stats (id, bookId, sessionDurationSeconds, wordsReadCount, timestamp) " +
                "VALUES (1, 1, 600, 1500, 900)"
        )
    }
}
