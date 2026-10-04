package com.lumina.reader.core.download

import com.lumina.reader.core.library.BookFileNames
import com.lumina.reader.core.library.CopyResult
import com.lumina.reader.core.library.ImportException
import com.lumina.reader.core.library.ImportLimits
import com.lumina.reader.core.network.finalUrl
import com.lumina.reader.core.network.lastHeader
import com.lumina.reader.core.network.readBodyChunk
import com.lumina.reader.core.opds.OpdsHttp
import com.lumina.reader.core.opds.OpdsUrls
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.Sink
import okio.use

/** A completely received file. */
data class DownloadedFile(
    val file: Path,
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
 *
 * Reading the body is a suspending, cancellable Ktor call, so cancelling the
 * download ends it at once (OkHttp needed a watcher that cancelled the call).
 */
class BookDownloader(private val client: HttpClient = OpdsHttp.downloadClient) {

    suspend fun download(
        request: DownloadRequest,
        target: Path,
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
                // Cancelling closes the connection: the read may fail with an
                // I/O error, which must end the download instead of trying the
                // next mirror.
                ensureActive()
                lastError = e
            }
        }
        throw lastError ?: IOException("Не удалось скачать книгу")
    }

    private suspend fun downloadOnce(
        url: String,
        request: DownloadRequest,
        target: Path,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit
    ): DownloadedFile {
        val ownHost = OpdsUrls.host(request.url)
        val mirrorHosts = request.mirrorBaseUrls.mapNotNull { OpdsUrls.host(it) }
        val targetHost = OpdsUrls.host(url)
        // Credentials only go to the catalogue's own hosts and mirrors.
        val sendCredentials = targetHost != null && (targetHost == ownHost || targetHost in mirrorHosts)
        return client.prepareGet(url) {
            // set, not append: the same replace semantics as OkHttp's Request.Builder.header.
            headers[HttpHeaders.UserAgent] = OpdsHttp.USER_AGENT
            headers[HttpHeaders.Accept] = "*/*"
            if (sendCredentials) {
                request.headers.forEach { (name, value) -> headers[name] = value }
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
            val total = response.lastHeader(HttpHeaders.ContentLength)?.toLongOrNull()?.takeIf { it > 0 }
            if (total != null && total > maxBytes) throw ImportException(ImportLimits.TOO_LARGE_MESSAGE)
            onProgress(0, total)

            val copy = FileSystem.SYSTEM.sink(target).use { sink ->
                copyBody(response.bodyAsChannel(), sink, maxBytes) { read -> onProgress(read, total) }
            }
            if (copy.bytes == 0L) throw ImportException("Сервер прислал пустой файл")

            val finalUrl = response.finalUrl
            DownloadedFile(
                file = target,
                bytes = copy.bytes,
                sha256 = copy.sha256,
                contentType = response.lastHeader(HttpHeaders.ContentType),
                fileName = BookFileNames.fileNameFromContentDisposition(response.lastHeader(HttpHeaders.ContentDisposition))
                    ?: BookFileNames.fileNameFromUrl(finalUrl),
                finalUrl = finalUrl
            )
        }
    }

    /**
     * Streams [channel] into [sink] while hashing it (SHA-256, lower-case hex,
     * as StreamCopier does for local files). Stops with an [ImportException] as
     * soon as more than [maxBytes] arrive, so an endless or oversized response
     * never fills the disk, and checks for cancellation before every read.
     * [sink] is flushed, not closed.
     */
    private suspend fun copyBody(
        channel: ByteReadChannel,
        sink: Sink,
        maxBytes: Long,
        onProgress: (Long) -> Unit
    ): CopyResult {
        val hashing = HashingSink.sha256(sink)
        val chunk = ByteArray(BUFFER_SIZE)
        val buffer = Buffer()
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            // A transfer that broke off throws here: a cut-off book must fail,
            // not be imported as complete.
            val read = channel.readBodyChunk(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw ImportException(ImportLimits.TOO_LARGE_MESSAGE)
            buffer.write(chunk, 0, read)
            hashing.write(buffer, read.toLong())
            onProgress(total)
        }
        hashing.flush()
        return CopyResult(total, hashing.hash.hex())
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
