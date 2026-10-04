package com.lumina.reader.core.library

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.BookParserFactory
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import com.lumina.reader.platform.Ids
import com.lumina.reader.platform.LuminaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.Source
import okio.use

/** Outcome of adding one file to the library. */
sealed interface ImportResult {
    data class Imported(val book: Book) : ImportResult
    data class Duplicate(val book: Book) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * A document another app hands over: a content URI on Android
 * (`UriImportSource`), a file from the document picker or "Open in" on iOS,
 * or any file ([FileImportSource]).
 */
interface ImportSource {
    /** The name the other app gives the document; it may contain a path (it is sanitised). */
    val displayName: String?

    /** The size the other app declares, if any; checked before anything is copied. */
    val sizeBytes: Long?

    val mimeType: String?

    /** Opens the content; the caller closes it. Throws [ImportException] with a message for the user, or any exception. */
    fun open(): Source

    /** A message for a failure specific to this kind of source (Android: no permission), or null for the general one. */
    fun failureMessage(error: Throwable): String? = null
}

/** A plain file as an [ImportSource] (iOS: a document picker copy, an "Open in" file); it is copied, never moved. */
class FileImportSource(
    private val path: Path,
    override val displayName: String? = path.name,
    override val mimeType: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM
) : ImportSource {

    override val sizeBytes: Long?
        get() = fileSystem.metadataOrNull(path)?.size

    override fun open(): Source = fileSystem.source(path)

    override fun toString(): String = path.toString()
}

/**
 * A complete file waiting to be added: a copied document or a finished
 * download, in [LibraryFiles.incomingDir]. [bytes] and [sha256] describe its
 * content; the other fields are hints for the format and the metadata.
 */
class IncomingFile(
    val path: Path,
    val bytes: Long,
    val sha256: String,
    val displayName: String?,
    val mimeType: String?,
    val fallbackTitle: String? = null,
    val fallbackAuthor: String? = null,
    val formatHint: BookFormat? = null
)

/**
 * Adds books to the library: the platform-independent part of the importer.
 *
 * Files are stored as `books/<uuid>.<ext>`; the format is detected from the
 * content; duplicates are recognised by their bytes; failed imports leave
 * nothing behind. Imports run one at a time (parsing a large book takes a lot
 * of memory). Results of documents from other apps are reported through
 * [AppMessages]; downloads report their own (see Android's BookImporter).
 */
class ImportPipeline(
    private val bookDao: BookDao,
    private val libraryPreferences: LibraryPreferences,
    val files: LibraryFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val fileSystem get() = files.fileSystem

    /** Parsing a large book takes a lot of memory: one import at a time. */
    private val importLock = Mutex()
    private val seedLock = Mutex()

    private val mutableActiveImports = MutableStateFlow(0)

    /** Number of imports of documents from other apps ("Добавить книгу", "Открыть с помощью") in progress. */
    val activeImports: StateFlow<Int> = mutableActiveImports.asStateFlow()

    // ---- Documents from other apps -------------------------------------------

    /**
     * Copies a document from another app into the library and reports the
     * result. With [openAfterImport] the reader opens the book (or the
     * existing copy of a duplicate) when done, which is what
     * "Открыть с помощью" expects.
     */
    suspend fun importAndReport(source: ImportSource, openAfterImport: Boolean = false) {
        mutableActiveImports.update { it + 1 }
        try {
            when (val result = importSource(source)) {
                is ImportResult.Imported -> {
                    AppMessages.postWithOpen("Книга «${result.book.title}» добавлена", result.book.id)
                    if (openAfterImport) AppMessages.requestOpenBook(result.book.id)
                }
                is ImportResult.Duplicate -> {
                    AppMessages.postWithOpen("Эта книга уже в библиотеке", result.book.id)
                    if (openAfterImport) AppMessages.requestOpenBook(result.book.id)
                }
                is ImportResult.Failed -> AppMessages.post("Не удалось добавить книгу: ${result.message}", isError = true)
            }
        } finally {
            mutableActiveImports.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /** Copies [source] (at most [ImportLimits.MAX_BOOK_BYTES]) to a work file and adds it. */
    suspend fun importSource(source: ImportSource): ImportResult = withContext(ioDispatcher) {
        var temp: Path? = null
        try {
            val displayName = source.displayName
            val size = source.sizeBytes
            if (size != null && size > ImportLimits.MAX_BOOK_BYTES) {
                return@withContext ImportResult.Failed(ImportLimits.TOO_LARGE_MESSAGE)
            }
            val mimeType = source.mimeType
            val target = newIncomingFile("import-", ".tmp")
            temp = target
            val copy = source.open().use { input ->
                fileSystem.sink(target).use { output ->
                    StreamCopier.copy(input, output, ImportLimits.MAX_BOOK_BYTES)
                }
            }
            importFile(
                IncomingFile(
                    path = target,
                    bytes = copy.bytes,
                    sha256 = copy.sha256,
                    displayName = BookFileNames.sanitizeDisplayName(displayName),
                    mimeType = mimeType
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            ImportResult.Failed(e.userMessage)
        } catch (e: Exception) {
            source.failureMessage(e)?.let { ImportResult.Failed(it) } ?: run {
                LuminaLog.w(TAG, "Import of $source failed", e)
                ImportResult.Failed(localizedMessageOf(e) ?: "не удалось прочитать файл")
            }
        } finally {
            temp?.let(::deleteQuietly)
        }
    }

    /** A new, unused path in [LibraryFiles.incomingDir] (the directory is created). */
    fun newIncomingFile(prefix: String, suffix: String): Path =
        directory(files.incomingDir) / "$prefix${Ids.randomUuid()}$suffix"

    /** Deletes what a previous process left in [LibraryFiles.incomingDir]. */
    fun clearIncoming() {
        val leftovers = try {
            fileSystem.listOrNull(files.incomingDir)
        } catch (e: Exception) {
            null
        }
        leftovers?.forEach(::deleteQuietly)
    }

    // ---- Shared import pipeline ----------------------------------------------

    /**
     * Adds [incoming] to the library: detects the format, stores the file under
     * a new name (unpacking `.fb2.zip`), skips duplicates, parses it and inserts
     * the book. [IncomingFile.path] is moved into the library or left for the
     * caller to delete.
     */
    suspend fun importFile(incoming: IncomingFile): ImportResult =
        importLock.withLock {
            withContext(ioDispatcher) {
                try {
                    storeAndRegister(incoming)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportException) {
                    ImportResult.Failed(e.userMessage)
                } catch (e: Exception) {
                    LuminaLog.w(TAG, "Import failed", e)
                    ImportResult.Failed(localizedMessageOf(e) ?: "не удалось прочитать книгу")
                } catch (e: Throwable) {
                    if (!isOutOfMemory(e)) throw e
                    ImportResult.Failed("Книга слишком большая для этого устройства")
                }
            }
        }

    private suspend fun storeAndRegister(incoming: IncomingFile): ImportResult {
        if (incoming.bytes <= 0L) throw ImportException("Файл пустой")
        val header = StreamCopier.readHeader(incoming.path)
        val zip = if (BookFormatDetector.isZip(header)) ZipInspector.inspect(incoming.path) else null

        val detection = BookFormatDetector.detect(
            header = header,
            fileName = incoming.displayName,
            mimeType = incoming.mimeType,
            zipEntryNames = zip?.entryNames,
            formatHint = incoming.formatHint
        )
        val detected = when (detection) {
            is FormatDetection.Supported -> detection.format
            is FormatDetection.Unsupported -> throw ImportException(detection.message)
        }
        if (detected == BookFormat.EPUB && zip != null &&
            zip.declaredUncompressedBytes > ImportLimits.MAX_EPUB_DECLARED_BYTES
        ) {
            throw ImportException("Архив книги слишком большой после распаковки")
        }

        // FB2 inside a zip is unpacked once (with a size cap) and stored as FB2.
        val storedFormat = if (detected == BookFormat.FB2_ZIP) BookFormat.FB2 else detected
        val storedName = BookFileNames.newStoredName(storedFormat)
        val dest = directory(files.booksDir) / storedName
        var coverFile: Path? = null
        var keep = false
        try {
            val stored: CopyResult = if (detected == BookFormat.FB2_ZIP) {
                val entry = zip?.entryNames?.let(BookFormatDetector::fb2EntryName)
                    ?: throw ImportException("В архиве не найден файл FB2")
                ZipInspector.extractEntry(incoming.path, entry, dest, ImportLimits.MAX_FB2_UNPACKED_BYTES)
            } else {
                moveOrCopy(incoming.path, dest)
                CopyResult(incoming.bytes, incoming.sha256)
            }

            val library = bookDao.getAllBooksOnce()
            val candidates = library.map { book ->
                DedupeCandidate(
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    format = book.format,
                    fileSizeBytes = regularFileSize(files.resolve(book.filePath))
                )
            }
            val hashCache = HashMap<Long, String?>()
            val hashOf: (DedupeCandidate) -> String? = { candidate ->
                hashCache.getOrPut(candidate.bookId) {
                    library.firstOrNull { it.id == candidate.bookId }
                        ?.let { StreamCopier.sha256(files.resolve(it.filePath)) }
                }
            }
            val fingerprint = IncomingFingerprint(storedFormat, stored.bytes, stored.sha256)
            BookDeduplication.findDuplicate(fingerprint, candidates, hashOf)?.let { duplicate ->
                library.firstOrNull { it.id == duplicate.bookId }?.let { return ImportResult.Duplicate(it) }
            }

            val parsed = BookParserFactory.getParser(storedFormat).parse(dest)
            if (parsed.chapters.isEmpty()) throw ImportException("В файле не найден текст книги")

            val title = ImportMetadata.resolveTitle(parsed.title, storedName, incoming.fallbackTitle, incoming.displayName)
            val author = ImportMetadata.resolveAuthor(parsed.author, incoming.fallbackAuthor)

            // Very large files are compared by size, title and author instead of hashing.
            BookDeduplication.findDuplicate(fingerprint.copy(title = title, author = author), candidates, hashOf)
                ?.let { duplicate ->
                    library.firstOrNull { it.id == duplicate.bookId }?.let { return ImportResult.Duplicate(it) }
                }

            val coverBytes = parsed.coverBytes
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                val file = directory(files.coversDir) / "cover_${Ids.randomUuid()}.jpg"
                fileSystem.write(file) { write(coverBytes) }
                coverFile = file
            }

            val book = Book(
                title = title,
                author = author,
                filePath = files.toStored(dest),
                coverPath = coverFile?.let(files::toStored),
                format = storedFormat,
                totalChapters = parsed.chapters.size,
                fileSizeBytes = stored.bytes,
                description = parsed.description,
                seriesName = parsed.seriesName.trim().replace(WHITESPACE, " "),
                seriesOrder = parsed.seriesOrder.coerceAtLeast(0)
            )
            val id = bookDao.insertBook(book)
            keep = true
            BookCacheRepository.put(dest.toString(), parsed)
            return ImportResult.Imported(book.copy(id = id))
        } finally {
            if (!keep) {
                deleteQuietly(dest)
                coverFile?.let(::deleteQuietly)
            }
        }
    }

    private fun moveOrCopy(source: Path, dest: Path) {
        try {
            fileSystem.atomicMove(source, dest)
            return
        } catch (e: IOException) {
            // Another volume, or the source cannot be moved: copy it instead.
        }
        fileSystem.copy(source, dest)
    }

    /** Size of the regular file at [path], or null when there is none (the file was deleted). */
    private fun regularFileSize(path: Path): Long? = try {
        fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size
    } catch (e: Exception) {
        null
    }

    private fun directory(path: Path): Path {
        fileSystem.createDirectories(path)
        return path
    }

    private fun deleteQuietly(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            // A leftover file is harmless.
        }
    }

    // ---- Welcome book --------------------------------------------------------

    /**
     * Adds the welcome book once, on the very first start with an empty
     * library. Later empty libraries (the user deleted everything) stay empty.
     */
    suspend fun seedWelcomeBookIfNeeded() {
        withContext(ioDispatcher) {
            seedLock.withLock {
                if (libraryPreferences.isWelcomeBookSeeded()) return@withLock
                try {
                    if (bookDao.countBooks() == 0) seedWelcomeBook()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LuminaLog.w(TAG, "Cannot add the welcome book", e)
                }
                libraryPreferences.markWelcomeBookSeeded()
            }
        }
    }

    private suspend fun seedWelcomeBook() {
        val file = directory(files.booksDir) / WELCOME_FILE_NAME
        fileSystem.write(file) { writeUtf8(WELCOME_TEXT) }
        val parsed = BookParserFactory.getParser(BookFormat.TXT).parse(file)
        bookDao.insertBook(
            Book(
                title = "Добро пожаловать в Lumina",
                author = "Lumina Team",
                filePath = files.toStored(file),
                format = BookFormat.TXT,
                totalChapters = parsed.chapters.size.coerceAtLeast(1),
                fileSizeBytes = fileSystem.metadataOrNull(file)?.size ?: 0L,
                description = "Руководство пользователя и демонстрация возможностей читалки Lumina Reader."
            )
        )
    }

    companion object {
        private const val TAG = "BookImporter"
        internal const val WELCOME_FILE_NAME = "welcome.txt"
        private val WHITESPACE = Regex("\\s+")

        internal val WELCOME_TEXT = """
Глава 1. Добро пожаловать в Lumina Reader

Lumina Reader — это современная, быстрая и эстетичная читалка, созданная специально для флагманских дисплеев Samsung Dynamic AMOLED 2X с частотой 120 Гц.

Здесь всё создано для комфортного чтения:
• Режим Pure OLED Black (#000000) для глубокого погружения и максимальной экономии батареи.
• Плавнейшее листание страниц и поддержка непрерывного вертикального скролла.
• Режим Bionic Reading, который ускоряет восприятие текста, выделяя опорные буквы каждого слова.
• Тонкая настройка типографики: меняйте размер шрифта, межстрочный интервал, поля и гарнитуру.

Глава 2. Возможности и управление

Коснитесь центра экрана во время чтения, чтобы открыть панель управления.
В нижней части экрана доступны:
1. Выбор тем оформления: OLED Black, Slate Dark, Сепия, Бумага, Тёплый янтарь.
2. Оглавление с возможностью мгновенного перехода к нужной главе.
3. Добавление закладок и создание заметок.
4. Озвучивание текста голосом (Text-to-Speech).

Глава 3. Приятного чтения!

Вы можете добавить любые ваши книги в форматах EPUB, FB2, FB2.ZIP, PDF или TXT через системный проводник или просто открыв файл из любого мессенджера. Книги из OPDS-каталогов скачиваются прямо в библиотеку — о готовности сообщит уведомление.

Погружайтесь в мир любимых историй вместе с Lumina Reader!
        """.trimIndent()
    }
}
