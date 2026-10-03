package com.lumina.reader.core.library

import android.content.Context
import androidx.room.withTransaction
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Library changes shared by the library screen and the AI assistant. */
class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val bookDao = database.bookDao()
    private val preferences = LibraryPreferences(appContext)

    /**
     * Removes a book with everything that belongs to it: bookmarks, highlights,
     * the parsed-book cache entry, the cover and the file (unless another
     * library entry still uses the same file).
     */
    suspend fun deleteBook(book: Book) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                bookDao.deleteBookmarksOfBook(book.id)
                bookDao.deleteHighlightsOfBook(book.id)
                bookDao.deleteBookById(book.id)
            }
            BookCacheRepository.remove(book.filePath)
            if (bookDao.countBooksWithPath(book.filePath) == 0) {
                deleteQuietly(book.filePath)
            }
            val cover = book.coverPath
            if (cover != null) deleteQuietly(cover)
        }
    }

    /** Stores the full order of a series in one go. */
    suspend fun organizeSeries(seriesName: String, orderedBooks: List<Book>): Boolean = withContext(Dispatchers.IO) {
        val normalizedSeries = normalizeShelf(seriesName)
        if (normalizedSeries.isBlank() || orderedBooks.isEmpty()) return@withContext false
        database.withTransaction {
            orderedBooks.forEachIndexed { index, book ->
                bookDao.updateOrganization(
                    id = book.id,
                    collection = normalizeShelf(book.collection).ifBlank { LibraryPreferences.MAIN_SHELF },
                    seriesName = normalizedSeries,
                    seriesOrder = index + 1
                )
            }
        }
        true
    }

    /** Creates an (initially empty) shelf. Returns false when it already exists. */
    suspend fun createShelf(name: String, existing: List<String>): Boolean {
        val normalized = normalizeShelf(name)
        if (normalized.isEmpty() || existing.any { it.equals(normalized, ignoreCase = true) }) return false
        preferences.updateCustomShelves { current ->
            if (current.any { it.equals(normalized, ignoreCase = true) }) current else current + normalized
        }
        return true
    }

    /** Renames a shelf and moves its books. The main shelf cannot be renamed. */
    suspend fun renameShelf(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val from = normalizeShelf(oldName)
        val to = normalizeShelf(newName)
        if (from.isEmpty() || to.isEmpty() || from.equals(LibraryPreferences.MAIN_SHELF, ignoreCase = true)) {
            return@withContext false
        }
        preferences.updateCustomShelves { current ->
            val replaced = current.map { if (it.equals(from, ignoreCase = true)) to else it }
            val withNew = if (replaced.any { it.equals(to, ignoreCase = true) }) replaced else replaced + to
            withNew.distinctBy { it.lowercase() }
        }
        bookDao.renameCollection(from, to)
        true
    }

    /** Deletes a shelf; its books move to the main shelf. */
    suspend fun deleteShelf(name: String): Boolean = withContext(Dispatchers.IO) {
        val shelf = normalizeShelf(name)
        if (shelf.isEmpty() || shelf.equals(LibraryPreferences.MAIN_SHELF, ignoreCase = true)) {
            return@withContext false
        }
        preferences.updateCustomShelves { current -> current.filterNot { it.equals(shelf, ignoreCase = true) } }
        bookDao.renameCollection(shelf, LibraryPreferences.MAIN_SHELF)
        true
    }

    private fun deleteQuietly(path: String) {
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // A leftover file is harmless; the library entry is gone.
        }
    }

    private fun normalizeShelf(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
