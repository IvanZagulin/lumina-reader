package com.lumina.reader.core.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.LibraryPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {

    private class Fixture(root: Path, scope: TestScope) {
        val dao = FakeBookDao()
        val preferences = LibraryPreferences(InMemoryPreferencesStore())
        val files = testLibraryFiles(root)
        var transactions = 0
        val repository = LibraryRepository(
            bookDao = dao,
            preferences = preferences,
            files = files,
            transaction = { block ->
                transactions++
                block()
            },
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        )

        suspend fun addBook(name: String, cover: String? = null, collection: String = "Основная"): Book {
            val path = files.booksDir / name
            FileSystem.SYSTEM.createDirectories(files.booksDir)
            FileSystem.SYSTEM.write(path) { writeUtf8(name) }
            val coverPath = cover?.let { files.coversDir / it }?.also { file ->
                FileSystem.SYSTEM.createDirectories(files.coversDir)
                FileSystem.SYSTEM.write(file) { writeUtf8("jpeg") }
            }
            val book = Book(
                title = name,
                filePath = files.toStored(path),
                coverPath = coverPath?.let(files::toStored),
                collection = collection
            )
            return book.copy(id = dao.insertBook(book))
        }
    }

    private fun repositoryTest(block: suspend Fixture.() -> Unit) = runTest {
        withTempDir { root -> Fixture(root, this).block() }
    }

    @Test
    fun deletingABookRemovesItsDataFileAndCover() = repositoryTest {
        val book = addBook("a.fb2", cover = "cover_a.jpg")
        val other = addBook("b.fb2")
        repository.deleteBook(book)

        assertEquals(listOf(other), dao.books)
        assertEquals(listOf(book.id), dao.deletedBookmarksOf)
        assertEquals(listOf(book.id), dao.deletedHighlightsOf)
        assertEquals(1, transactions)
        assertEquals(listOf("b.fb2"), fileNames(files.booksDir))
        assertEquals(emptyList(), fileNames(files.coversDir))
    }

    @Test
    fun sharedFilesAndCoversStayWhileAnotherEntryUsesThem() = repositoryTest {
        val first = addBook("a.fb2", cover = "cover_a.jpg")
        // An old duplicate entry pointing at the same file and cover.
        val second = first.copy(id = dao.insertBook(first.copy(id = 0)))
        repository.deleteBook(first)
        assertEquals(listOf("a.fb2"), fileNames(files.booksDir))
        assertEquals(listOf("cover_a.jpg"), fileNames(files.coversDir))

        repository.deleteBook(second)
        assertEquals(emptyList(), fileNames(files.booksDir))
        assertEquals(emptyList(), fileNames(files.coversDir))
        // A file that is already gone is no error.
        repository.deleteBook(second)
    }

    @Test
    fun seriesOrderIsStoredInOneTransaction() = repositoryTest {
        val one = addBook("1.fb2", collection = "  ")
        val two = addBook("2.fb2", collection = "Фантастика")
        assertFalse(repository.organizeSeries("  ", listOf(one, two)))
        assertFalse(repository.organizeSeries("Дюна", emptyList()))
        assertEquals(0, transactions)

        assertTrue(repository.organizeSeries("  Дюна   хроники ", listOf(two, one)))
        assertEquals(1, transactions)
        val byId = dao.books.associateBy { it.id }
        assertEquals("Дюна хроники", byId.getValue(two.id).seriesName)
        assertEquals(1, byId.getValue(two.id).seriesOrder)
        assertEquals("Фантастика", byId.getValue(two.id).collection)
        assertEquals(2, byId.getValue(one.id).seriesOrder)
        assertEquals(LibraryPreferences.MAIN_SHELF, byId.getValue(one.id).collection)
    }

    @Test
    fun shelvesAreCreatedRenamedAndDeleted() = repositoryTest {
        val book = addBook("a.fb2", collection = "Учеба")
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, preferences.customShelves.first())

        assertTrue(repository.createShelf(" Новая  полка ", existing = emptyList()))
        assertFalse(repository.createShelf("новая полка", existing = listOf("Новая полка")))
        assertFalse(repository.createShelf("   ", existing = emptyList()))
        assertEquals(LibraryPreferences.DEFAULT_SHELVES + "Новая полка", preferences.customShelves.first())

        assertTrue(repository.renameShelf(" Учеба ", "Работа"))
        assertEquals(listOf("Избранное", "Работа", "Художественная", "Новая полка"), preferences.customShelves.first())
        assertEquals("Работа", dao.books.single { it.id == book.id }.collection)
        assertFalse(repository.renameShelf(LibraryPreferences.MAIN_SHELF, "Другая"))

        assertTrue(repository.deleteShelf("Работа"))
        assertEquals(listOf("Избранное", "Художественная", "Новая полка"), preferences.customShelves.first())
        assertEquals(LibraryPreferences.MAIN_SHELF, dao.books.single { it.id == book.id }.collection)
        assertFalse(repository.deleteShelf(LibraryPreferences.MAIN_SHELF))
    }
}
