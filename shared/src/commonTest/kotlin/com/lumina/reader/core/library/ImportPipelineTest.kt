package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.EpubFixtures
import com.lumina.reader.core.parser.Fb2Fixtures
import com.lumina.reader.core.parser.TestZip
import com.lumina.reader.core.parser.utf8
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The common import pipeline on real files (temporary directory), an in-memory DAO and settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportPipelineTest {

    private class Fixture(val root: Path, scope: TestScope) {
        val dao = FakeBookDao()
        val preferences = LibraryPreferences(InMemoryPreferencesStore())
        val files = testLibraryFiles(root)
        val pipeline = ImportPipeline(dao, preferences, files, UnconfinedTestDispatcher(scope.testScheduler))

        /** A document called [name] in another app (stored under an ASCII name: JVMs may run with an ASCII file-name encoding). */
        fun source(name: String, bytes: ByteArray, mimeType: String? = null): FileImportSource {
            val path = root / "source" / "${sourceFiles++}.bin"
            FileSystem.SYSTEM.createDirectories(path.parent!!)
            FileSystem.SYSTEM.write(path) { write(bytes) }
            return FileImportSource(path, displayName = name, mimeType = mimeType)
        }

        private var sourceFiles = 0
    }

    private fun pipelineTest(block: suspend Fixture.() -> Unit) = runTest {
        withTempDir { root -> Fixture(root, this).block() }
    }

    @Test
    fun importsADocumentUnderARandomNameAndKeepsTheSource() = pipelineTest {
        val source = source("Ильф_Петров.fb2", Fb2Fixtures.sample.utf8())
        val result = pipeline.importSource(source)

        val book = assertIs<ImportResult.Imported>(result, result.toString()).book
        assertEquals("Двенадцать стульев", book.title)
        assertEquals("Ильф Илья, Петров Евгений", book.author)
        assertEquals("Аннотация книги.", book.description)
        assertEquals(BookFormat.FB2, book.format)
        assertEquals(Fb2Fixtures.sample.utf8().size.toLong(), book.fileSizeBytes)
        assertEquals("Остап", book.seriesName)
        assertEquals(1, book.seriesOrder)
        assertTrue(book.id > 0)
        assertEquals(listOf(book), dao.books)

        // Stored relative to the root, as books/<uuid>.fb2, next to its cover.
        assertTrue(book.filePath.startsWith("books/"), book.filePath)
        val storedName = book.filePath.removePrefix("books/")
        assertTrue(BookFileNames.isSafeStoredName(storedName), storedName)
        assertContentEquals(Fb2Fixtures.sample.utf8(), FileSystem.SYSTEM.read(files.resolve(book.filePath)) { readByteArray() })
        val cover = assertNotNull(book.coverPath)
        assertTrue(cover.startsWith("covers/cover_") && cover.endsWith(".jpg"), cover)
        assertContentEquals(Fb2Fixtures.coverBytes, FileSystem.SYSTEM.read(files.resolve(cover)) { readByteArray() })

        // The work copy is gone, the other app's file is untouched, the parsed book is cached.
        assertEquals(emptyList(), fileNames(files.incomingDir))
        assertTrue(FileSystem.SYSTEM.exists(root / "source" / "0.bin"))
        assertNotNull(BookCacheRepository.get(files.resolve(book.filePath).toString()))
    }

    @Test
    fun sameBytesAreADuplicateEvenUnderAnotherName() = pipelineTest {
        val bytes = EpubFixtures.sampleBook()
        val first = assertIs<ImportResult.Imported>(pipeline.importSource(source("book.epub", bytes))).book
        assertEquals("Тестовая книга", first.title)
        assertEquals(BookFormat.EPUB, first.format)

        val second = pipeline.importSource(source("copy.zip", bytes, mimeType = "application/octet-stream"))
        assertEquals(first, assertIs<ImportResult.Duplicate>(second).book)
        assertEquals(1, dao.books.size)
        assertEquals(1, fileNames(files.booksDir).size)
        assertEquals(1, fileNames(files.coversDir).size)
    }

    @Test
    fun zippedFb2IsUnpackedOnceAndStoredAsFb2() = pipelineTest {
        val zipped = Fb2Fixtures.zipped()
        val book = assertIs<ImportResult.Imported>(pipeline.importSource(source("book.fb2.zip", zipped))).book
        assertEquals(BookFormat.FB2, book.format)
        assertTrue(book.filePath.endsWith(".fb2"), book.filePath)
        assertEquals(Fb2Fixtures.sample.utf8().size.toLong(), book.fileSizeBytes)

        // The plain FB2 of the same text is the same book.
        val plain = pipeline.importSource(source("book.fb2", Fb2Fixtures.sample.utf8()))
        assertEquals(book.id, assertIs<ImportResult.Duplicate>(plain).book.id)
    }

    @Test
    fun downloadsUseCatalogMetadataWhenTheFileHasNone() = pipelineTest {
        val text = "Просто текст без заголовков.\n\nВторой абзац.".utf8()
        val path = pipeline.newIncomingFile("download-", ".part")
        FileSystem.SYSTEM.write(path) { write(text) }
        val result = pipeline.importFile(
            IncomingFile(
                path = path,
                bytes = text.size.toLong(),
                sha256 = text.toByteString().sha256().hex(),
                displayName = "12345",
                mimeType = "text/plain; charset=utf-8",
                fallbackTitle = "Каталожное название",
                fallbackAuthor = "Каталожный автор",
                formatHint = BookFormat.TXT
            )
        )
        val book = assertIs<ImportResult.Imported>(result).book
        assertEquals("Каталожное название", book.title)
        assertEquals("Каталожный автор", book.author)
        assertEquals(BookFormat.TXT, book.format)
        // The download was moved into the library.
        assertFalse(FileSystem.SYSTEM.exists(path))
    }

    @Test
    fun failuresLeaveNothingBehind() = pipelineTest {
        val html = "<!DOCTYPE html><html><body>Not found</body></html>".utf8()
        assertEquals(
            ImportResult.Failed("Получена веб-страница вместо книги"),
            pipeline.importSource(source("book.fb2", html))
        )
        assertEquals(ImportResult.Failed("Файл пустой"), pipeline.importSource(source("empty.epub", ByteArray(0))))
        assertEquals(
            ImportResult.Failed("Формат не поддерживается: архив RAR"),
            pipeline.importSource(source("book.rar", byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0)))
        )
        // An archive without a book in it.
        val photos = TestZip.build(linkedMapOf("photo.jpg" to Fb2Fixtures.coverBytes, "readme.txt" to "x".utf8()))
        assertEquals(
            ImportResult.Failed("Архив не содержит книгу FB2 или EPUB"),
            pipeline.importSource(source("photos.zip", photos))
        )

        assertEquals(emptyList(), dao.books)
        assertEquals(emptyList(), fileNames(files.booksDir))
        assertEquals(emptyList(), fileNames(files.coversDir))
        assertEquals(emptyList(), fileNames(files.incomingDir))
    }

    @Test
    fun sourcesAreCheckedAndTheirOwnFailuresExplained() = pipelineTest {
        val tooLarge = object : ImportSource {
            override val displayName = "huge.pdf"
            override val sizeBytes = ImportLimits.MAX_BOOK_BYTES + 1
            override val mimeType: String? = null
            override fun open(): Source = error("must not be opened")
        }
        assertEquals(ImportResult.Failed(ImportLimits.TOO_LARGE_MESSAGE), pipeline.importSource(tooLarge))

        val denied = object : ImportSource {
            override val displayName: String? = null
            override val sizeBytes: Long? = null
            override val mimeType: String? = null
            override fun open(): Source = throw IllegalStateException("permission denied")
            override fun failureMessage(error: Throwable): String? =
                if (error is IllegalStateException) "Нет доступа к файлу" else null
        }
        assertEquals(ImportResult.Failed("Нет доступа к файлу"), pipeline.importSource(denied))

        val unopenable = object : ImportSource {
            override val displayName: String? = null
            override val sizeBytes: Long? = null
            override val mimeType: String? = null
            override fun open(): Source = throw ImportException("Не удалось открыть файл")
        }
        assertEquals(ImportResult.Failed("Не удалось открыть файл"), pipeline.importSource(unopenable))

        val broken = object : ImportSource {
            override val displayName: String? = null
            override val sizeBytes: Long? = null
            override val mimeType: String? = null
            override fun open(): Source = throw IllegalArgumentException("read error")
        }
        assertEquals(ImportResult.Failed("read error"), pipeline.importSource(broken))
        assertEquals(emptyList(), fileNames(files.incomingDir))
    }

    @Test
    fun documentsFromOtherAppsReportTheirResult() = pipelineTest {
        pipeline.importAndReport(source("book.fb2", Fb2Fixtures.sample.utf8()), openAfterImport = true)
        val book = dao.books.single()
        val message = AppMessages.messages.first()
        assertEquals("Книга «Двенадцать стульев» добавлена", message.text)
        assertEquals(AppMessageAction.OpenBook(book.id), message.action)
        assertEquals(book.id, AppMessages.openBookRequests.first())

        pipeline.importAndReport(source("again.fb2", Fb2Fixtures.sample.utf8()))
        assertEquals("Эта книга уже в библиотеке", AppMessages.messages.first().text)

        pipeline.importAndReport(source("empty.txt", ByteArray(0)))
        val failure = AppMessages.messages.first()
        assertEquals("Не удалось добавить книгу: Файл пустой", failure.text)
        assertTrue(failure.isError)
        assertEquals(0, pipeline.activeImports.value)
    }

    @Test
    fun welcomeBookIsAddedOnceToAnEmptyLibrary() = pipelineTest {
        pipeline.seedWelcomeBookIfNeeded()
        val welcome = dao.books.single()
        assertEquals("Добро пожаловать в Lumina", welcome.title)
        assertEquals("Lumina Team", welcome.author)
        assertEquals(BookFormat.TXT, welcome.format)
        assertEquals("books/welcome.txt", welcome.filePath)
        assertEquals(ImportPipeline.WELCOME_TEXT, FileSystem.SYSTEM.read(files.resolve(welcome.filePath)) { readUtf8() })
        assertEquals(ImportPipeline.WELCOME_TEXT.encodeToByteArray().size.toLong(), welcome.fileSizeBytes)
        assertEquals(3, welcome.totalChapters)
        assertTrue(preferences.isWelcomeBookSeeded())

        // Deleted by the user: it does not come back.
        dao.deleteBookById(welcome.id)
        pipeline.seedWelcomeBookIfNeeded()
        assertEquals(emptyList(), dao.books)
    }

    @Test
    fun welcomeBookIsNotAddedToALibraryWithBooks() = pipelineTest {
        assertIs<ImportResult.Imported>(pipeline.importSource(source("book.fb2", Fb2Fixtures.sample.utf8())))
        pipeline.seedWelcomeBookIfNeeded()
        assertEquals(1, dao.books.size)
        assertTrue(preferences.isWelcomeBookSeeded())
    }

    @Test
    fun leftoversOfAnEarlierProcessAreCleared() = pipelineTest {
        val leftover = pipeline.newIncomingFile("download-", ".part")
        FileSystem.SYSTEM.write(leftover) { writeUtf8("partial") }
        assertTrue(leftover.name.startsWith("download-") && leftover.name.endsWith(".part"), leftover.name)
        assertEquals(files.incomingDir, leftover.parent)
        pipeline.clearIncoming()
        assertEquals(emptyList(), fileNames(files.incomingDir))
    }

    @Test
    fun storedPathsAreRelativeToTheRoot() = withTempDir { root ->
        val files = testLibraryFiles(root)
        val book = files.booksDir / "a.epub"
        assertEquals("books/a.epub", files.toStored(book))
        assertEquals(book, files.resolve("books/a.epub"))
        assertEquals(book, files.resolve(book.toString()))

        // Outside the root: stored and resolved as is.
        val elsewhere = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "elsewhere.epub"
        assertEquals(elsewhere.toString(), files.toStored(elsewhere))
        assertEquals(elsewhere, files.resolve(elsewhere.toString()))

        // A path from an earlier container location is found under the current one.
        val old = "/var/mobile/Containers/Data/Application/OLD-ID/Documents/Books/x.epub"
        assertEquals(root / "Documents" / "Books" / "x.epub", files.resolve(old))
        val oldCover = "/Users/me/Library/Developer/Data/Application/OLD/Library/Application Support/covers/c.jpg"
        assertEquals(root / "Library" / "Application Support" / "covers" / "c.jpg", files.resolve(oldCover))
        assertEquals("/nowhere/x.epub".toPath(), files.resolve("/nowhere/x.epub"))
    }
}
