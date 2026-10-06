package com.lumina.reader.core.database

import androidx.room.*
import com.lumina.reader.core.model.Book
import com.lumina.reader.platform.AppClock
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Long): Book?

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByPath(filePath: String): Book?

    @Query("SELECT DISTINCT TRIM(collection) FROM books WHERE TRIM(collection) != '' ORDER BY collection COLLATE NOCASE")
    fun getCollections(): Flow<List<String>>

    @Query("SELECT DISTINCT TRIM(seriesName) FROM books WHERE TRIM(seriesName) != '' ORDER BY seriesName COLLATE NOCASE")
    fun getSeriesNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Query("UPDATE books SET isFavorite = :isFav WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFav: Boolean)

    /** Changes the completion status without moving the reader's saved position. */
    @Query("""
        UPDATE books
        SET isCompleted = :isComp,
            startedAt = CASE
                WHEN :isComp AND startedAt IS NULL THEN :completedAt
                ELSE startedAt
            END,
            completedAt = CASE WHEN :isComp THEN :completedAt ELSE NULL END
        WHERE id = :id
    """)
    suspend fun updateCompleted(
        id: Long,
        isComp: Boolean,
        completedAt: Long = AppClock.nowMillis()
    )

    @Query("UPDATE books SET collection = :collection WHERE id = :id")
    suspend fun updateCollection(id: Long, collection: String)

    /**
     * Completion is recorded on crossing 99%, not on every save near the end:
     * an explicit "unread" choice must survive reopening and saving that page.
     */
    @Query("""
        UPDATE books
        SET currentChapterIndex = :chapterIndex,
            currentParagraphIndex = :paragraphIndex,
            currentCharOffset = :charOffset,
            currentProgressPercent = :progress,
            lastReadTimestamp = :timestamp,
            startedAt = CASE
                WHEN startedAt IS NULL
                 AND (:progress > 0.0 OR :chapterIndex > 0 OR :paragraphIndex > 0)
                THEN :timestamp
                ELSE startedAt
            END,
            completedAt = CASE
                WHEN :progress >= 99.0 AND currentProgressPercent < 99.0
                     AND completedAt IS NULL THEN :timestamp
                ELSE completedAt
            END
        WHERE id = :bookId
    """)
    suspend fun updateProgress(
        bookId: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        charOffset: Int,
        progress: Float,
        timestamp: Long = AppClock.nowMillis()
    )

    @Query("UPDATE books SET collection = :collection, seriesName = :seriesName, seriesOrder = :seriesOrder WHERE id = :id")
    suspend fun updateOrganization(
        id: Long,
        collection: String,
        seriesName: String,
        seriesOrder: Int
    )

    @Query("SELECT * FROM books WHERE collection = :collection ORDER BY lastReadTimestamp DESC")
    fun getBooksByCollection(collection: String): Flow<List<Book>>

    @Query("""
        SELECT * FROM books
        WHERE seriesName = :seriesName COLLATE NOCASE
        ORDER BY CASE WHEN seriesOrder > 0 THEN 0 ELSE 1 END,
                 seriesOrder ASC,
                 title COLLATE NOCASE ASC
    """)
    fun getBooksBySeries(seriesName: String): Flow<List<Book>>

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: Long)

    // ---- Library, import and download support (added at the end on purpose) ----

    /** One-shot snapshot of the whole library (duplicate checks, AI matching). */
    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    suspend fun getAllBooksOnce(): List<Book>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int

    /** How many books point at [filePath]; a file is only deleted when this drops to zero. */
    @Query("SELECT COUNT(*) FROM books WHERE filePath = :filePath")
    suspend fun countBooksWithPath(filePath: String): Int

    /** SQLite NOCASE only folds ASCII; match shelves the same way as the UI. */
    @Transaction
    suspend fun renameCollection(oldName: String, newName: String): Int {
        fun normalized(name: String) = name.trim().replace(Regex("\\s+"), " ")
        val from = normalized(oldName)
        val matches = getAllBooksOnce().filter {
            normalized(it.collection).equals(from, ignoreCase = true)
        }
        for (book in matches) updateCollection(book.id, normalized(newName))
        return matches.size
    }

    /** Bookmarks of a deleted book; mirrors BookmarkDao.deleteBookmarksForBook. */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksOfBook(bookId: Long)

    /** Highlights and notes of a deleted book; mirrors BookmarkDao.deleteHighlightsForBook. */
    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteHighlightsOfBook(bookId: Long)
}
