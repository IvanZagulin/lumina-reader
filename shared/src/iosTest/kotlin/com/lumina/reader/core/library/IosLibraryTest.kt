package com.lumina.reader.core.library

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.createAppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.parser.Fb2Fixtures
import com.lumina.reader.core.parser.utf8
import com.lumina.reader.core.preferences.LibraryPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The iPhone pieces of the library: container layout, file URLs, Room on the bundled driver. */
class IosLibraryTest {

    @Test
    fun booksCoversAndWorkFilesLiveInTheContainer() {
        assertEquals(NSHomeDirectory(), IosLibraryFiles.root.toString())
        val root = "/var/mobile/Containers/Data/Application/ABC".toPath()
        val files = IosLibraryFiles.create(root)
        assertEquals("Documents/Books/x.epub", files.toStored(files.booksDir / "x.epub"))
        assertEquals("Library/Application Support/covers/c.jpg", files.toStored(files.coversDir / "c.jpg"))
        assertEquals(root / "Library" / "Caches" / "incoming", files.incomingDir)
        assertEquals(files.booksDir / "x.epub", files.resolve("Documents/Books/x.epub"))
        // After an update the container has moved: old absolute paths are found again.
        assertEquals(
            files.booksDir / "x.epub",
            files.resolve("/var/mobile/Containers/Data/Application/OLD/Documents/Books/x.epub")
        )
    }

    @Test
    fun aFileUrlIsImported() = runBlocking {
        withTempDir { dir ->
            val bytes = Fb2Fixtures.sample.utf8()
            val file = dir / "Двенадцать стульев.fb2"
            FileSystem.SYSTEM.write(file) { write(bytes) }
            val source = UrlImportSource(NSURL.fileURLWithPath(file.toString()))
            assertEquals("Двенадцать стульев.fb2", source.displayName)
            assertEquals(bytes.size.toLong(), source.sizeBytes)
            assertContentEquals(bytes, source.open().buffer().use { it.readByteArray() })

            val dao = FakeBookDao()
            val pipeline = ImportPipeline(dao, LibraryPreferences(InMemoryPreferencesStore()), testLibraryFiles(dir / "library"))
            val book = assertIs<ImportResult.Imported>(pipeline.importSource(source)).book
            assertEquals("Двенадцать стульев", book.title)
            assertTrue(FileSystem.SYSTEM.exists(file))
        }
    }

    @Test
    fun importAndDeleteOnTheBundledDriver() = runBlocking {
        withTempDir { dir ->
            val database = createAppDatabase((dir / AppDatabase.DATABASE_NAME).toString())
            try {
                val files = testLibraryFiles(dir / "library")
                val preferences = LibraryPreferences(InMemoryPreferencesStore())
                val pipeline = ImportPipeline(database.bookDao(), preferences, files)
                val repository = LibraryRepository(database, preferences, files)
                val books = database.bookDao()

                val imported = assertIs<ImportResult.Imported>(
                    pipeline.importSource(FileImportSource(write(dir, "a.fb2", Fb2Fixtures.sample.utf8())))
                ).book
                assertEquals(imported, books.getBookById(imported.id))
                assertIs<ImportResult.Duplicate>(
                    pipeline.importSource(FileImportSource(write(dir, "b.fb2", Fb2Fixtures.sample.utf8())))
                )

                val other = books.getBookById(books.insertBook(Book(title = "Другая", filePath = "books/other.txt")))!!
                assertTrue(repository.organizeSeries("Серия", listOf(other, imported)))
                assertEquals(listOf(other.id, imported.id), books.getBooksBySeries("Серия").first().map(Book::id))

                database.bookmarkDao().insertBookmark(Bookmark(bookId = imported.id, chapterIndex = 0, snippet = "x"))
                repository.deleteBook(books.getBookById(imported.id)!!)
                assertEquals(listOf(other.id), books.getAllBooksOnce().map(Book::id))
                assertEquals(0, database.bookmarkDao().getBookmarksForBook(imported.id).first().size)
                assertEquals(emptyList(), fileNames(files.booksDir))
                assertEquals(emptyList(), fileNames(files.coversDir))
            } finally {
                database.close()
            }
        }
    }

    private fun write(dir: okio.Path, name: String, bytes: ByteArray): okio.Path {
        val path = dir / name
        FileSystem.SYSTEM.write(path) { write(bytes) }
        return path
    }
}
