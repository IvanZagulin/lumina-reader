package com.lumina.reader.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query(
        "SELECT * FROM bookmarks WHERE bookId = :bookId " +
            "ORDER BY chapterIndex ASC, paragraphIndex ASC, charOffset ASC"
    )
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)

    /** Removes every bookmark of a book; call it when the book is deleted. */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: Long)

    @Query(
        "SELECT * FROM highlights WHERE bookId = :bookId " +
            "ORDER BY chapterIndex ASC, paragraphIndex ASC, startOffset ASC"
    )
    fun getHighlightsForBook(bookId: Long): Flow<List<ReadingHighlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: ReadingHighlight): Long

    @Delete
    suspend fun deleteHighlight(highlight: ReadingHighlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlightById(id: Long)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateHighlightNote(id: Long, note: String?)

    /** Removes every highlight and note of a book; call it when the book is deleted. */
    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteHighlightsForBook(bookId: Long)
}

@Dao
interface ReadingStatsDao {
    @Query("SELECT * FROM reading_stats ORDER BY timestamp DESC")
    fun getAllStats(): Flow<List<ReadingStats>>

    @Query("SELECT * FROM reading_stats WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getStatsForBook(bookId: Long): Flow<List<ReadingStats>>

    @Query("SELECT SUM(sessionDurationSeconds) FROM reading_stats")
    suspend fun getTotalReadingTimeSeconds(): Long?

    @Query("SELECT SUM(wordsReadCount) FROM reading_stats")
    suspend fun getTotalWordsRead(): Long?

    @Query("SELECT COUNT(*) FROM reading_stats WHERE timestamp >= :startOfDayMillis")
    suspend fun countSessionsSince(startOfDayMillis: Long): Int

    /** Latest sessions in which text was actually read, for the reading-speed estimate. */
    @Query(
        "SELECT * FROM reading_stats " +
            "WHERE wordsReadCount > 0 AND sessionDurationSeconds >= :minSeconds " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRecentReadingSessions(limit: Int, minSeconds: Long): List<ReadingStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: ReadingStats)
}
