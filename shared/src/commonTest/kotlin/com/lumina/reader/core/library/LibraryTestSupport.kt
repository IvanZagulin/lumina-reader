package com.lumina.reader.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.model.Book
import com.lumina.reader.platform.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** A fresh directory in the platform's temporary directory, deleted afterwards. */
internal inline fun <T> withTempDir(block: (Path) -> T): T {
    val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("lumina-library-test-" + Ids.randomUuid())
    FileSystem.SYSTEM.createDirectories(dir)
    try {
        return block(dir)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }
}

/** Library files under [root] the way the iPhone app lays them out, but with short names. */
internal fun testLibraryFiles(root: Path): RootedLibraryFiles =
    RootedLibraryFiles(
        root = root,
        booksDir = root / "books",
        coversDir = root / "covers",
        incomingDir = root / "incoming"
    )

/** Names of the files in [dir] (none when it does not exist). */
internal fun fileNames(dir: Path): List<String> =
    FileSystem.SYSTEM.listOrNull(dir).orEmpty().map { it.name }.sorted()

/** DataStore in memory: what the settings classes see of a preferences file. */
internal class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** The `books` table in memory, with Room's id assignment; enough for the library code. */
internal class FakeBookDao : BookDao {
    private val rows = MutableStateFlow<List<Book>>(emptyList())
    private var nextId = 1L

    val books: List<Book> get() = rows.value
    val deletedBookmarksOf = mutableListOf<Long>()
    val deletedHighlightsOf = mutableListOf<Long>()

    private fun change(transform: (List<Book>) -> List<Book>) {
        rows.value = transform(rows.value)
    }

    private fun update(id: Long, transform: (Book) -> Book) = change { list -> list.map { if (it.id == id) transform(it) else it } }

    override fun getAllBooks(): Flow<List<Book>> = rows

    override suspend fun getBookById(id: Long): Book? = books.firstOrNull { it.id == id }

    override suspend fun getBookByPath(filePath: String): Book? = books.firstOrNull { it.filePath == filePath }

    override fun getCollections(): Flow<List<String>> =
        rows.map { list -> list.map { it.collection.trim() }.filter { it.isNotEmpty() }.distinct() }

    override fun getSeriesNames(): Flow<List<String>> =
        rows.map { list -> list.map { it.seriesName.trim() }.filter { it.isNotEmpty() }.distinct() }

    override suspend fun insertBook(book: Book): Long {
        val id = if (book.id == 0L) nextId++ else book.id
        change { list -> list.filterNot { it.id == id } + book.copy(id = id) }
        return id
    }

    override suspend fun updateBook(book: Book) = update(book.id) { book }

    override suspend fun updateFavorite(id: Long, isFav: Boolean) = update(id) { it.copy(isFavorite = isFav) }

    override suspend fun updateCompleted(id: Long, isComp: Boolean, completedAt: Long) =
        update(id) { it.copy(isCompleted = isComp, completedAt = if (isComp) completedAt else null) }

    override suspend fun updateCollection(id: Long, collection: String) = update(id) { it.copy(collection = collection) }

    override suspend fun updateProgress(
        bookId: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        charOffset: Int,
        progress: Float,
        timestamp: Long
    ) = update(bookId) {
        it.copy(
            currentChapterIndex = chapterIndex,
            currentParagraphIndex = paragraphIndex,
            currentCharOffset = charOffset,
            currentProgressPercent = progress,
            lastReadTimestamp = timestamp
        )
    }

    override suspend fun updateOrganization(id: Long, collection: String, seriesName: String, seriesOrder: Int) =
        update(id) { it.copy(collection = collection, seriesName = seriesName, seriesOrder = seriesOrder) }

    override fun getBooksByCollection(collection: String): Flow<List<Book>> =
        rows.map { list -> list.filter { it.collection == collection } }

    override fun getBooksBySeries(seriesName: String): Flow<List<Book>> =
        rows.map { list -> list.filter { it.seriesName.equals(seriesName, ignoreCase = true) } }

    override suspend fun deleteBook(book: Book) = deleteBookById(book.id)

    override suspend fun deleteBookById(id: Long) = change { list -> list.filterNot { it.id == id } }

    override suspend fun getAllBooksOnce(): List<Book> = books

    override suspend fun countBooks(): Int = books.size

    override suspend fun countBooksWithPath(filePath: String): Int = books.count { it.filePath == filePath }

    override suspend fun renameCollection(oldName: String, newName: String): Int {
        var changed = 0
        change { list ->
            list.map {
                if (it.collection.trim().equals(oldName, ignoreCase = true)) {
                    changed++
                    it.copy(collection = newName)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun deleteBookmarksOfBook(bookId: Long) {
        deletedBookmarksOf += bookId
    }

    override suspend fun deleteHighlightsOfBook(bookId: Long) {
        deletedHighlightsOf += bookId
    }
}
