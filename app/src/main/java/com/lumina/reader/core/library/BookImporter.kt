package com.lumina.reader.core.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.download.BookDownloader
import com.lumina.reader.core.download.DownloadEvent
import com.lumina.reader.core.download.DownloadNotifications
import com.lumina.reader.core.download.DownloadNotifier
import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.DownloadStates
import com.lumina.reader.core.download.ProgressThrottle
import com.lumina.reader.core.download.describeNetworkError
import com.lumina.reader.core.preferences.LibraryPreferences
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped import and download service. Work runs in its own scope, so it
 * continues when the user leaves the screen that started it; results are
 * reported through [AppMessages] (snackbar on any screen), system
 * notifications (downloads) and the per-URL [downloads] state.
 *
 * Adding a file to the library (format detection, storage as
 * `books/<uuid>.<ext>`, duplicate check, parsing, the database entry, the
 * welcome book) is the common [ImportPipeline] in :shared; this class adds
 * the Android sources (content URIs) and the downloads, which stay here with
 * BookDownloader and the notifications.
 */
class BookImporter private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getDatabase(appContext)
    private val bookDao = database.bookDao()
    private val pipeline = ImportPipeline(
        bookDao = bookDao,
        libraryPreferences = LibraryPreferences(appContext),
        files = AndroidLibraryFiles(appContext)
    )
    private val downloader = BookDownloader()
    private val notifier: DownloadNotifications = DownloadNotifier(appContext)

    private val downloadSlots = Semaphore(MAX_PARALLEL_DOWNLOADS)

    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Download state per URL (see [DownloadRequest.key]). */
    val downloads: StateFlow<Map<String, DownloadState>> = mutableDownloads.asStateFlow()

    /** Number of local file imports ("Добавить книгу", "Открыть с помощью") in progress. */
    val activeImports: StateFlow<Int> = pipeline.activeImports

    init {
        // Nothing runs in this process yet: progress notifications and partial
        // files still around belong to a process that died mid-download.
        notifier.clearStaleProgress()
        scope.launch { pipeline.clearIncoming() }
    }

    // ---- Local files ---------------------------------------------------------

    /**
     * Copies a document from another app into the library. With
     * [openAfterImport] the reader opens the book (or the existing copy of a
     * duplicate) when done, which is what "Открыть с помощью" expects.
     */
    fun importFromUri(uri: Uri, openAfterImport: Boolean = false): Job = scope.launch {
        pipeline.importAndReport(UriImportSource(appContext, uri), openAfterImport)
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
                val temp = pipeline.newIncomingFile("download-", ".part")
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

                    val result = pipeline.importFile(
                        IncomingFile(
                            path = downloaded.file,
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
                    deleteQuietly(temp)
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

    /**
     * Removes the partial file, or the received one the pipeline did not move
     * into the library; never throws, like java.io.File.delete before.
     */
    private fun deleteQuietly(path: Path) {
        try {
            pipeline.files.fileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            // Left for clearIncoming at the next start.
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
        pipeline.seedWelcomeBookIfNeeded()
    }

    companion object {
        private const val TAG = "BookImporter"
        private const val MAX_PARALLEL_DOWNLOADS = 3

        @Volatile
        private var instance: BookImporter? = null

        fun get(context: Context): BookImporter =
            instance ?: synchronized(this) {
                instance ?: BookImporter(context.applicationContext).also { instance = it }
            }
    }
}
