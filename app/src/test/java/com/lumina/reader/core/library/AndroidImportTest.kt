package com.lumina.reader.core.library

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.databaseBuilder
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.preferences.LibraryPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The import pipeline as the Android app runs it: books under
 * filesDir/books with absolute paths in the database, work files under
 * cacheDir/incoming, documents read through the ContentResolver, Room's
 * withTransaction when a book is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "import-test-${System.nanoTime()}.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun libraryFilesAreWhereTheAppAlwaysKeptThem() {
        val files = AndroidLibraryFiles(context)
        assertEquals(File(context.filesDir, "books").absolutePath, files.booksDir.toString())
        assertEquals(File(context.filesDir, "covers").absolutePath, files.coversDir.toString())
        assertEquals(File(context.cacheDir, "incoming").absolutePath, files.incomingDir.toString())

        val book = File(File(context.filesDir, "books"), "4f1c2d3e-aaaa-bbbb-cccc-123456789abc.fb2")
        assertEquals(book.absolutePath, files.toStored(book.toOkioPath()))
        assertEquals(book.absolutePath, files.resolve(book.absolutePath).toString())
    }

    @Test
    fun aSharedDocumentIsCopiedImportedAndDeletedLikeBefore() = runBlocking {
        val database = AppDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        try {
            val files = AndroidLibraryFiles(context)
            val preferences = LibraryPreferences(InMemoryStore())
            val pipeline = ImportPipeline(database.bookDao(), preferences, files)
            val document = File(context.cacheDir, "shared-book.fb2").apply { writeText(FB2, Charsets.UTF_8) }
            val uri = Uri.fromFile(document)

            val source = UriImportSource(context, uri)
            assertEquals("shared-book.fb2", source.displayName)
            val result = pipeline.importSource(source)
            assertTrue(result.toString(), result is ImportResult.Imported)
            val book = (result as ImportResult.Imported).book
            assertEquals("Тестовая книга", book.title)
            assertEquals(BookFormat.FB2, book.format)

            val stored = File(book.filePath)
            assertEquals(File(context.filesDir, "books").absolutePath, stored.parent)
            assertTrue(stored.name, BookFileNames.isSafeStoredName(stored.name))
            assertEquals(document.length(), stored.length())
            assertTrue(document.isFile)
            assertEquals(emptyList<String>(), File(context.cacheDir, "incoming").list().orEmpty().toList())
            assertEquals(book, database.bookDao().getBookById(book.id))

            val again = pipeline.importSource(UriImportSource(context, uri))
            assertEquals(ImportResult.Duplicate(book), again)

            val missing = pipeline.importSource(UriImportSource(context, Uri.fromFile(File(context.cacheDir, "gone.fb2"))))
            assertTrue(missing.toString(), missing is ImportResult.Failed)

            LibraryRepository(database, preferences, files).deleteBook(book)
            assertEquals(0, database.bookDao().countBooks())
            assertFalse(stored.exists())
        } finally {
            database.close()
        }
    }

    private class InMemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private companion object {
        const val FB2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook><description><title-info><book-title>Тестовая книга</book-title></title-info></description>
<body><section><title><p>Глава 1</p></title><p>Текст главы.</p></section></body></FictionBook>"""
    }
}
