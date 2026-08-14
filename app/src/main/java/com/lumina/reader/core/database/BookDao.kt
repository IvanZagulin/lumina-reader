package com.lumina.reader.core.database

import androidx.room.*
import com.lumina.reader.core.model.Book
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

    @Query("""
        UPDATE books
        SET isCompleted = :isComp,
            currentProgressPercent = CASE WHEN :isComp THEN 100.0 ELSE currentProgressPercent END
        WHERE id = :id
    """)
    suspend fun updateCompleted(id: Long, isComp: Boolean)

    @Query("UPDATE books SET collection = :collection WHERE id = :id")
    suspend fun updateCollection(id: Long, collection: String)

    @Query("UPDATE books SET currentChapterIndex = :chapterIndex, currentParagraphIndex = :paragraphIndex, currentProgressPercent = :progress, lastReadTimestamp = :timestamp WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, chapterIndex: Int, paragraphIndex: Int, progress: Float, timestamp: Long = System.currentTimeMillis())

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
}
