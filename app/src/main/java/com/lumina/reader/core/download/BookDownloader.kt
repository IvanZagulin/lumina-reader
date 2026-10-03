package com.lumina.reader.core.download

import com.lumina.reader.core.library.BookFileNames
import com.lumina.reader.core.library.ImportException
import com.lumina.reader.core.library.ImportLimits
import com.lumina.reader.core.library.StreamCopier
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.opds.OpdsHttp
import com.lumina.reader.core.opds.OpdsUrls
import com.lumina.reader.core.opds.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What to download; [key] (the URL) identifies the download in the UI. */
data class DownloadRequest(
    val url: String,
    val title: String,
    val author: String = "",
    /** Format the catalogue announced; the real one is detected from the content. */
    val formatHint: BookFormat? = null,
    /** Extra headers, e.g. HTTP Basic credentials of the catalogue. */
    val headers: Map<String, String> = emptyMap(),
    /** Other domains of the same site to try when [url] fails. */
    val mirrorBaseUrls: List<String> = emptyList()
) {
    val key: String
        get() = url
}

/** A completely received file. */
data class DownloadedFile(
    val file: File,
    val bytes: Long,
    val sha256: String,
    val contentType: String?,
    /** From Content-Disposition, else the last URL segment. */
    val fileName: String?,
    val finalUrl: String
)

/**
 * Streams a book to a file with progress reports. No call timeout (large
 * files take long); the read timeout detects a stalled connection. Mirrors
 * are tried in order; the size limit is enforced while streaming.
 */
class BookDownloader(private val client: OkHttpClient = OpdsHttp.downloadClient) {

    suspend fun download(
        request: DownloadRequest,
        target: File,
        maxBytes: Long = ImportLimits.MAX_BOOK_BYTES,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in OpdsUrls.candidates(request.url, request.mirrorBaseUrls)) {
            try {
                return@withContext downloadOnce(candidate, request, target, maxBytes, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                throw e // too large: another mirror would not help
            } catch (e: HttpStatusException) {
                lastError = e
                // Wrong credentials are the same on every mirror.
                if (e.code == 401) throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Не удалось скачать книгу")
    }

    private suspend fun downloadOnce(
        url: String,
        request: DownloadRequest,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit
    ): DownloadedFile {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", OpdsHttp.USER_AGENT)
            .header("Accept", "*/*")
        val ownHost = OpdsUrls.host(request.url)
        val mirrorHosts = request.mirrorBaseUrls.mapNotNull { OpdsUrls.host(it) }
        val targetHost = OpdsUrls.host(url)
        if (targetHost != null && (targetHost == ownHost || targetHost in mirrorHosts)) {
            request.headers.forEach { (name, value) -> builder.header(name, value) }
        }
        val call = client.newCall(builder.build())

        return coroutineScope {
            // A blocking read does not notice coroutine cancellation; cancelling
            // the call closes the socket and ends it.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    val body = response.body ?: throw IOException("Пустой ответ сервера")
                    val total = body.contentLength().takeIf { it > 0 }
                    if (total != null && total > maxBytes) throw ImportException(ImportLimits.TOO_LARGE_MESSAGE)
                    onProgress(0, total)

                    val context = currentCoroutineContext()
                    val copy = body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            StreamCopier.copy(
                                input = input,
                                output = output,
                                maxBytes = maxBytes,
                                beforeChunk = { context.ensureActive() },
                                onProgress = { read -> onProgress(read, total) }
                            )
                        }
                    }
                    if (copy.bytes == 0L) throw ImportException("Сервер прислал пустой файл")

                    val finalUrl = response.request.url.toString()
                    DownloadedFile(
                        file = target,
                        bytes = copy.bytes,
                        sha256 = copy.sha256,
                        contentType = response.header("Content-Type"),
                        fileName = BookFileNames.fileNameFromContentDisposition(response.header("Content-Disposition"))
                            ?: BookFileNames.fileNameFromUrl(finalUrl),
                        finalUrl = finalUrl
                    )
                }
            } finally {
                watcher.cancel()
            }
        }
    }
}
