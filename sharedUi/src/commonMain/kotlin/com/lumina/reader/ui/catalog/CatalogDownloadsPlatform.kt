package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.BookDownloader
import com.lumina.reader.core.download.DownloadEvent
import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.DownloadStates
import com.lumina.reader.core.download.ProgressThrottle
import com.lumina.reader.core.download.describeNetworkError
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.ImportPipeline
import com.lumina.reader.core.library.ImportResult
import com.lumina.reader.core.library.IncomingFile
import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.LuminaLog
import com.lumina.reader.platform.PlatformLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path

/**
 * Catalogue downloads for a platform without Android's BookImporter (the
 * iPhone app): the download half of BookImporter on the shared
 * [ImportPipeline] and [BookDownloader], with the same queue (at most
 * [maxParallel] at a time), states, progress throttling and messages.
 *
 * It has no system notifications (Android's progress notifications have no
 * iPhone counterpart; the download island and AppMessages report instead).
 * [keepRunning] wraps each running download so the platform can keep the app
 * alive meanwhile (iOS: a UIKit background task); by default it just runs it.
 *
 * Work runs in [scope], so downloads continue after the screen that started
 * them is closed. Thread-safe: the maps are guarded by a [PlatformLock] and the
 * state is a [MutableStateFlow] updated atomically.
 */
class CatalogDownloadQueue(
    private val pipeline: ImportPipeline,
    private val downloader: BookDownloader,
    private val scope: CoroutineScope,
    maxParallel: Int = MAX_PARALLEL_DOWNLOADS,
    private val keepRunning: suspend (name: String, work: suspend () -> Unit) -> Unit = { _, work -> work() }
) : CatalogDownloads {

    private val downloadSlots = Semaphore(maxParallel)

    private val lock = PlatformLock()
    private val requests = HashMap<String, DownloadRequest>()
    private val jobs = HashMap<String, Job>()

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val downloads: StateFlow<Map<String, DownloadState>> = mutableDownloads.asStateFlow()

    override fun download(request: DownloadRequest): Boolean {
        val key = request.key
        var accepted = false
        mutableDownloads.update { current ->
            accepted = DownloadStates.canStart(current[key])
            if (accepted) DownloadStates.reduce(current, DownloadEvent.Enqueued(key)) else current
        }
        if (!accepted) return false
        lock.withLock { requests[key] = request }
        val job = scope.launch { runDownload(request) }
        lock.withLock { jobs[key] = job }
        // Registered after the job is stored: a job that already finished runs it at once.
        job.invokeOnCompletion {
            lock.withLock { if (jobs[key] === job) jobs.remove(key) }
        }
        return true
    }

    override fun retry(key: String): Boolean {
        val request = lock.withLock { requests[key] } ?: return false
        return download(request)
    }

    override fun cancel(key: String) {
        lock.withLock { jobs[key] }?.cancel()
    }

    override fun dismiss(key: String) {
        val state = mutableDownloads.value[key] ?: return
        if (state.isFinished) dispatch(DownloadEvent.Cleared(key))
    }

    /**
     * Forgets completed downloads that point at [bookId] (the book was deleted),
     * so catalogue rows offer the download again instead of «Открыть».
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
                keepRunning("Загрузка «$title»") {
                    dispatch(DownloadEvent.Progress(key, 0, null))
                    val temp = pipeline.newIncomingFile("download-", ".part")
                    try {
                        val uiThrottle = ProgressThrottle(minIntervalMillis = 200, minPercentStep = 1)
                        val started = AppClock.monotonic()
                        val downloaded = downloader.download(request, temp) { bytesRead, totalBytes ->
                            val now = started.elapsedNow().inWholeMilliseconds
                            if (uiThrottle.shouldEmit(bytesRead, totalBytes, now)) {
                                dispatch(DownloadEvent.Progress(key, bytesRead, totalBytes))
                            }
                        }
                        dispatch(DownloadEvent.Progress(key, downloaded.bytes, downloaded.bytes))
                        dispatch(DownloadEvent.Importing(key))

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
                                AppMessages.postWithOpen("«${result.book.title}» скачана", result.book.id)
                            }
                            is ImportResult.Duplicate -> {
                                dispatch(DownloadEvent.Succeeded(key, result.book.id, result.book.title, alreadyInLibrary = true))
                                AppMessages.postWithOpen("Эта книга уже в библиотеке", result.book.id)
                            }
                            is ImportResult.Failed -> reportFailure(key, title, result.message)
                        }
                    } finally {
                        deleteQuietly(temp)
                    }
                }
            }
        } catch (e: Exception) {
            // A cancelled download may also end with an I/O error from the
            // closed connection: it is cleared, not reported as a failure.
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                dispatch(DownloadEvent.Cleared(key))
                throw e
            }
            LuminaLog.w(TAG, "Download of ${request.url} failed", e)
            reportFailure(key, title, describeNetworkError(e))
        }
    }

    private fun reportFailure(key: String, title: String, message: String) {
        dispatch(DownloadEvent.Failed(key, message))
        AppMessages.post("Не удалось скачать «$title»: $message", isError = true)
    }

    private fun dispatch(event: DownloadEvent) {
        mutableDownloads.update { DownloadStates.reduce(it, event) }
    }

    /** The partial file, or the received one the pipeline did not move into the library. */
    private fun deleteQuietly(path: Path) {
        try {
            pipeline.files.fileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            LuminaLog.w(TAG, "Could not delete $path", e)
        }
    }

    private companion object {
        const val TAG = "CatalogDownloadQueue"
        const val MAX_PARALLEL_DOWNLOADS = 3
    }
}
