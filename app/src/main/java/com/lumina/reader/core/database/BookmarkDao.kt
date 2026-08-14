package com.lumina.reader.core.database

import androidx.room.*
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, paragraphIndex ASC")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getHighlightsForBook(bookId: Long): Flow<List<ReadingHighlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: ReadingHighlight): Long

    @Delete
    suspend fun deleteHighlight(highlight: ReadingHighlight)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: ReadingStats)
}
