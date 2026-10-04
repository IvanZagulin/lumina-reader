package com.lumina.reader.core.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.download.BookDownloader
import com.lumina.reader.core.download.DownloadEvent
import com.lumina.reader.core.download.DownloadNotifier
import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.DownloadStates
import com.lumina.reader.core.download.ProgressThrottle
import com.lumina.reader.core.download.describeNetworkError
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.BookParserFactory
import com.lumina.reader.core.parser.parse
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Outcome of adding one file to the library. */
sealed interface ImportResult {
    data class Imported(val book: Book) : ImportResult
    data class Duplicate(val book: Book) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * App-scoped import and download service. Work runs in its own scope, so it
 * continues when the user leaves the screen that started it; results are
 * reported through [AppMessages] (snackbar on any screen), system
 * notifications (downloads) and the per-URL [downloads] state.
 *
 * Files are stored as `books/<uuid>.<ext>`; the format is detected from the
 * content; duplicates are recognised by their bytes; failed imports leave
 * nothing behind.
 */
class BookImporter private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getDatabase(appContext)
    private val bookDao = database.bookDao()
    private val libraryPreferences = LibraryPreferences(appContext)
    private val downloader = BookDownloader()
    private val notifier = DownloadNotifier(appContext)

    private val downloadSlots = Semaphore(MAX_PARALLEL_DOWNLOADS)
    /** Parsing a large book takes a lot of memory: one import at a time. */
    private val importLock = Mutex()
    private val seedLock = Mutex()

    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Download state per URL (see [DownloadRequest.key]). */
    val downloads: StateFlow<Map<String, DownloadState>> = mutableDownloads.asStateFlow()

    private val mutableActiveImports = MutableStateFlow(0)

    /** Number of local file imports ("Добавить книгу", "Открыть с помощью") in progress. */
    val activeImports: StateFlow<Int> = mutableActiveImports.asStateFlow()

    init {
        // Nothing runs in this process yet: progress notifications and partial
        // files still around belong to a process that died mid-download.
        notifier.clearStaleProgress()
        scope.launch {
            File(appContext.cacheDir, INCOMING_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    private val booksDir: File
        get() = File(appContext.filesDir, BOOKS_DIR).apply { mkdirs() }

    private val coversDir: File
        get() = File(appContext.filesDir, COVERS_DIR).apply { mkdirs() }

    private val incomingDir: File
        get() = File(appContext.cacheDir, INCOMING_DIR).apply { mkdirs() }

    // ---- Local files ---------------------------------------------------------

    /**
     * Copies a document from another app into the library. With
     * [openAfterImport] the reader opens the book (or the existing copy of a
     * duplicate) when done, which is what "Открыть с помощью" expects.
     */
    fun importFromUri(uri: Uri, openAfterImport: Boolean = false): Job = scope.launch {
        mutableActiveImports.update { it + 1 }
        try {
            when (val result = importUri(uri)) {
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

    private suspend fun importUri(uri: Uri): ImportResult {
        val resolver = appContext.contentResolver
        var displayName: String? = null
        var declaredSize: Long? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query metadata of $uri", e)
        }
        if (displayName == null) displayName = uri.lastPathSegment
        val size = declaredSize
        if (size != null && size > ImportLimits.MAX_BOOK_BYTES) {
            return ImportResult.Failed(ImportLimits.TOO_LARGE_MESSAGE)
        }
        val mimeType = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }

        val temp = File(incomingDir, "import-${UUID.randomUUID()}.tmp")
        return try {
            val input = resolver.openInputStream(uri)
                ?: return ImportResult.Failed("Не удалось открыть файл")
            val copy = input.use { stream ->
                FileOutputStream(temp).use { output ->
                    StreamCopier.copy(stream, output, ImportLimits.MAX_BOOK_BYTES)
                }
            }
            importPreparedFile(
                IncomingFile(
                    file = temp,
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
        } catch (e: SecurityException) {
            ImportResult.Failed("Нет доступа к файлу")
        } catch (e: Exception) {
            Log.w(TAG, "Import of $uri failed", e)
            ImportResult.Failed(e.localizedMessage ?: "не удалось прочитать файл")
        } finally {
            temp.delete()
        }
    }

    // ---- Downloads -----------------------------------------------------------

    /**
     * Starts downloading [request] in the background unless the same URL is
     * already queued or running. Returns false when nothing was started.
     */
    fun download(request: DownloadRequest): Boolean {
        val key = request.key
        var accepted = false
        mutableDownloads.update { current ->
            accepted = DownloadStates.canStart(current[key])
            if (accepted) DownloadStates.reduce(current, DownloadEvent.Enqueued(key)) else current
        }
        if (!accepted) return false
        requests[key] = request
        val job = scope.launch { runDownload(request) }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        return true
    }

    /**
     * Starts (or joins) the download and waits until it is completed or failed.
     * A download cancelled by the user (its state disappears) ends as [DownloadState.Failed].
     */
    suspend fun downloadAndAwait(request: DownloadRequest): DownloadState {
        val current = mutableDownloads.value[request.key]
        if (current is DownloadState.Completed && bookDao.getBookById(current.bookId) != null) {
            return current
        }
        download(request)
        val finished = downloads
            .map { it[request.key] }
            .first { it == null || it.isFinished }
        return finished ?: DownloadState.Failed("Загрузка отменена")
    }

    fun retry(key: String): Boolean {
        val request = requests[key] ?: return false
        return download(request)
    }

    /** Cancels a queued or running download; its state disappears. */
    fun cancel(key: String) {
        jobs[key]?.cancel()
    }

    /** Forgets a finished download (for example after the user opened the book). */
    fun dismiss(key: String) {
        val state = mutableDownloads.value[key] ?: return
        if (state.isFinished) dispatch(DownloadEvent.Cleared(key))
    }

    /**
     * Forgets completed downloads that point at [bookId] (the book was deleted),
     * so catalogue rows offer the download again instead of "Открыть".
     */
    fun forgetBook(bookId: Long) {
        mutableDownloads.value
            .filterValues { it is DownloadState.Completed && it.bookId == bookId }
            .keys
            .forEach { key -> dispatch(DownloadEvent.Cleared(key)) }
    }

    private suspend fun runDownload(request: DownloadRequest) {
        val key = request.key
        val title = request.title.trim().ifEmpty { "Книга" }
        try {
            downloadSlots.withPermit {
                dispatch(DownloadEvent.Progress(key, 0, null))
                notifier.showProgress(key, title, 0, null)
                val temp = File(incomingDir, "download-${UUID.randomUUID()}.part")
                try {
                    val uiThrottle = ProgressThrottle(minIntervalMillis = 200, minPercentStep = 1)
                    val notificationThrottle = ProgressThrottle(minIntervalMillis = 1000, minPercentStep = 100)
                    val downloaded = downloader.download(request, temp) { bytesRead, totalBytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (uiThrottle.shouldEmit(bytesRead, totalBytes, now)) {
                            dispatch(DownloadEvent.Progress(key, bytesRead, totalBytes))
                        }
                        if (notificationThrottle.shouldEmit(bytesRead, totalBytes, now)) {
                            notifier.showProgress(key, title, bytesRead, totalBytes)
                        }
                    }
                    dispatch(DownloadEvent.Progress(key, downloaded.bytes, downloaded.bytes))
                    dispatch(DownloadEvent.Importing(key))
                    notifier.showImporting(key, title)

                    val result = importPreparedFile(
                        IncomingFile(
                            file = downloaded.file,
                            bytes = downloaded.bytes,
                            sha256 = downloaded.sha256,
                            displayName = downloaded.fileName,
                            mimeType = downloaded.contentType,
                            fallbackTitle = request.title,
                            fallbackAuthor = request.author,
                            formatHint = request.formatHint
                        )
                    )
                    when (result) {
                        is ImportResult.Imported -> {
                            dispatch(DownloadEvent.Succeeded(key, result.book.id, result.book.title, alreadyInLibrary = false))
                            notifier.showCompleted(key, result.book.title, result.book.id, alreadyInLibrary = false)
                            AppMessages.postWithOpen("«${result.book.title}» скачана", result.book.id)
                        }
                        is ImportResult.Duplicate -> {
                            dispatch(DownloadEvent.Succeeded(key, result.book.id, result.book.title, alreadyInLibrary = true))
                            notifier.showCompleted(key, result.book.title, result.book.id, alreadyInLibrary = true)
                            AppMessages.postWithOpen("Эта книга уже в библиотеке", result.book.id)
                        }
                        is ImportResult.Failed -> reportFailure(key, title, result.message)
                    }
                } finally {
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            // A cancelled download may also end with an IOException from the
            // closed connection: it is cleared, not reported as a failure.
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                dispatch(DownloadEvent.Cleared(key))
                notifier.cancel(key)
                throw e
            }
            Log.w(TAG, "Download of ${request.url} failed", e)
            reportFailure(key, title, describeNetworkError(e))
        }
    }

    private fun reportFailure(key: String, title: String, message: String) {
        dispatch(DownloadEvent.Failed(key, message))
        notifier.showFailed(key, title, message)
        AppMessages.post("Не удалось скачать «$title»: $message", isError = true)
    }

    private fun dispatch(event: DownloadEvent) {
        mutableDownloads.update { DownloadStates.reduce(it, event) }
    }

    // ---- Shared import pipeline ----------------------------------------------

    private class IncomingFile(
        val file: File,
        val bytes: Long,
        val sha256: String,
        val displayName: String?,
        val mimeType: String?,
        val fallbackTitle: String? = null,
        val fallbackAuthor: String? = null,
        val formatHint: BookFormat? = null
    )

    private suspend fun importPreparedFile(incoming: IncomingFile): ImportResult =
        importLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    storeAndRegister(incoming)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportException) {
                    ImportResult.Failed(e.userMessage)
                } catch (e: OutOfMemoryError) {
                    ImportResult.Failed("Книга слишком большая для этого устройства")
                } catch (e: Exception) {
                    Log.w(TAG, "Import failed", e)
                    ImportResult.Failed(e.localizedMessage ?: "не удалось прочитать книгу")
                }
            }
        }

    private suspend fun storeAndRegister(incoming: IncomingFile): ImportResult {
        if (incoming.bytes <= 0L) throw ImportException("Файл пустой")
        val header = StreamCopier.readHeader(incoming.file)
        val zip = if (BookFormatDetector.isZip(header)) ZipInspector.inspect(incoming.file) else null

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
        val dest = File(booksDir, storedName)
        var coverFile: File? = null
        var keep = false
        try {
            val stored: CopyResult = if (detected == BookFormat.FB2_ZIP) {
                val entry = zip?.entryNames?.let(BookFormatDetector::fb2EntryName)
                    ?: throw ImportException("В архиве не найден файл FB2")
                ZipInspector.extractEntry(incoming.file, entry, dest, ImportLimits.MAX_FB2_UNPACKED_BYTES)
            } else {
                moveOrCopy(incoming.file, dest)
                CopyResult(incoming.bytes, incoming.sha256)
            }

            val library = bookDao.getAllBooksOnce()
            val candidates = library.map { book ->
                val file = File(book.filePath)
                DedupeCandidate(
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    format = book.format,
                    fileSizeBytes = if (file.isFile) file.length() else null
                )
            }
            val hashCache = HashMap<Long, String?>()
            val hashOf: (DedupeCandidate) -> String? = { candidate ->
                hashCache.getOrPut(candidate.bookId) {
                    library.firstOrNull { it.id == candidate.bookId }?.let { StreamCopier.sha256(File(it.filePath)) }
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
                val file = File(coversDir, "cover_${UUID.randomUUID()}.jpg")
                file.writeBytes(coverBytes)
                coverFile = file
            }

            val book = Book(
                title = title,
                author = author,
                filePath = dest.absolutePath,
                coverPath = coverFile?.absolutePath,
                format = storedFormat,
                totalChapters = parsed.chapters.size,
                fileSizeBytes = stored.bytes,
                description = parsed.description,
                seriesName = parsed.seriesName.trim().replace(WHITESPACE, " "),
                seriesOrder = parsed.seriesOrder.coerceAtLeast(0)
            )
            val id = bookDao.insertBook(book)
            keep = true
            BookCacheRepository.put(dest.absolutePath, parsed)
            return ImportResult.Imported(book.copy(id = id))
        } finally {
            if (!keep) {
                dest.delete()
                coverFile?.delete()
            }
        }
    }

    private fun moveOrCopy(source: File, dest: File) {
        if (source.renameTo(dest)) return
        source.inputStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    // ---- Background helpers ----------------------------------------------------

    /** Runs [block] in the app scope: it survives the screen that started it. */
    fun launchInBackground(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /**
     * Adds the welcome book once, on the very first start with an empty
     * library. Later empty libraries (the user deleted everything) stay empty.
     */
    fun seedWelcomeBookIfNeeded(): Job = scope.launch {
        seedLock.withLock {
            if (libraryPreferences.isWelcomeBookSeeded()) return@withLock
            try {
                if (bookDao.countBooks() == 0) seedWelcomeBook()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot add the welcome book", e)
            }
            libraryPreferences.markWelcomeBookSeeded()
        }
    }

    private suspend fun seedWelcomeBook() {
        val file = File(booksDir, WELCOME_FILE_NAME)
        file.writeText(WELCOME_TEXT, Charsets.UTF_8)
        val parsed = BookParserFactory.getParser(BookFormat.TXT).parse(file)
        bookDao.insertBook(
            Book(
                title = "Добро пожаловать в Lumina",
                author = "Lumina Team",
                filePath = file.absolutePath,
                format = BookFormat.TXT,
                totalChapters = parsed.chapters.size.coerceAtLeast(1),
                fileSizeBytes = file.length(),
                description = "Руководство пользователя и демонстрация возможностей читалки Lumina Reader."
            )
        )
    }

    companion object {
        private const val TAG = "BookImporter"
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"
        private const val INCOMING_DIR = "incoming"
        private const val MAX_PARALLEL_DOWNLOADS = 3
        private const val WELCOME_FILE_NAME = "welcome.txt"
        private val WHITESPACE = Regex("\\s+")

        @Volatile
        private var instance: BookImporter? = null

        fun get(context: Context): BookImporter =
            instance ?: synchronized(this) {
                instance ?: BookImporter(context.applicationContext).also { instance = it }
            }

        private val WELCOME_TEXT = """
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
