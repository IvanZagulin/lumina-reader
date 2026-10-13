package com.lumina.reader.core.download

import com.lumina.reader.core.library.BookFileNames
import com.lumina.reader.core.library.ImportException
import com.lumina.reader.core.library.ImportLimits
import com.lumina.reader.core.library.PortableStreams
import com.lumina.reader.core.network.finalUrl
import com.lumina.reader.core.network.lastHeader
import com.lumina.reader.core.network.readBodyChunk
import com.lumina.reader.core.opds.HostHealth
import com.lumina.reader.core.opds.OpdsHttp
import com.lumina.reader.core.opds.OpdsUrls
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.Sink
import okio.use
import com.lumina.reader.core.network.NetworkProxy

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
class BookDownloader(private val clientProvider: (url: String) -> HttpClient = { OpdsHttp.downloadClientFor(it) }) {

    suspend fun download(
        request: DownloadRequest,
        target: Path,
        maxBytes: Long = ImportLimits.MAX_BOOK_BYTES,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        // Mirrors whose host just failed to answer go last: a book must not wait
        // out a blocked domain first every single time (see HostHealth).
        for (candidate in HostHealth.order(OpdsUrls.candidates(request.url, request.mirrorBaseUrls))) {
            // What another mirror sent is not necessarily the same file, so each
            // one starts from nothing; only the mirror in hand is resumed.
            FileSystem.SYSTEM.delete(target, mustExist = false)
            var transientRetries = 0
            var resumes = 0
            while (true) {
                val before = bytesOnDisk(target)
                try {
                    return@withContext downloadOnce(candidate, request, target, maxBytes, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportException) {
                    throw e // too large: another mirror would not help
                } catch (e: HttpStatusException) {
                    // A busy catalogue answers 502/503/504 now and the same request an instant later.
                    if (e.code in 502..504 && transientRetries++ < MAX_TRANSIENT_RETRIES) {
                        delay(TRANSIENT_RETRY_DELAY_MILLIS)
                        continue
                    }
                    lastError = e
                    // Wrong credentials are the same on every mirror.
                    if (e.code == 401) throw e
                    break
                } catch (e: Exception) {
                    // Cancelling closes the connection: the read may fail with an
                    // I/O error, which must end the download instead of trying the
                    // next mirror.
                    ensureActive()
                    lastError = e
                    val received = bytesOnDisk(target)
                    if (received > before && resumes++ < MAX_RESUME_ATTEMPTS) {
                        // The transfer broke off part-way. Twenty megabytes over a
                        // mobile connection are not worth starting again from zero,
                        // and the server is plainly there, so it is picked up where
                        // it stopped rather than given up on.
                        delay(RESUME_DELAY_MILLIS)
                        continue
                    }
                    // Nothing arrived at all: this host is the problem, not the file.
                    if (received == 0L) HostHealth.markUnreachable(candidate)
                    break
                }
            }
        }
        throw lastError ?: IOException("Не удалось скачать книгу")
    }

    /** What of [target] is already on disk, or 0 when there is nothing. */
    private fun bytesOnDisk(target: Path): Long =
        FileSystem.SYSTEM.metadataOrNull(target)?.size ?: 0L

    /**
     * One request for [url]. When part of the file is already on disk it asks
     * for the rest of it; a server that will not do that (no 206, or a 416
     * because what is on disk no longer matches) has the part thrown away and
     * is asked again from the beginning.
     */
    private suspend fun downloadOnce(
        url: String,
        request: DownloadRequest,
        target: Path,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit
    ): DownloadedFile = try {
        attempt(url, request, target, maxBytes, bytesOnDisk(target), onProgress)
    } catch (e: RangeRefused) {
        FileSystem.SYSTEM.delete(target, mustExist = false)
        attempt(url, request, target, maxBytes, 0L, onProgress)
    }

    private suspend fun attempt(
        url: String,
        request: DownloadRequest,
        target: Path,
        maxBytes: Long,
        resumeFrom: Long,
        onProgress: (Long, Long?) -> Unit
    ): DownloadedFile {
        val ownHost = OpdsUrls.host(request.url)
        val mirrorHosts = request.mirrorBaseUrls.mapNotNull { OpdsUrls.host(it) }
        val targetHost = OpdsUrls.host(url)
        // Credentials only go to the catalogue own hosts and mirrors.
        val sendCredentials = targetHost != null && (targetHost == ownHost || targetHost in mirrorHosts)
        NetworkProxy.ready()
        return clientProvider(url).prepareGet(url) {
            // set, not append: the same replace semantics as OkHttp Request.Builder.header.
            headers[HttpHeaders.UserAgent] = OpdsHttp.USER_AGENT
            headers[HttpHeaders.Accept] = "*/*"
            if (resumeFrom > 0) headers[HttpHeaders.Range] = "bytes=" + resumeFrom + "-"
            if (sendCredentials) {
                request.headers.forEach { (name, value) -> headers[name] = value }
            }
        }.execute { response ->
            // The host answered; even a refusal proves it is reachable.
            HostHealth.markReachable(url)
            // The range asked for is not there any more: what is on disk is stale
            // or already complete, and only a fresh start can tell which.
            if (resumeFrom > 0 && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                throw RangeRefused()
            }
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
            // A server is free to ignore Range and answer 200 with the whole file;
            // then what is on disk is in the way and the attempt starts over.
            if (resumeFrom > 0 && response.status != HttpStatusCode.PartialContent) throw RangeRefused()

            val remaining = response.lastHeader(HttpHeaders.ContentLength)?.toLongOrNull()?.takeIf { it > 0 }
            val total = remaining?.let { it + resumeFrom }
            if (total != null && total > maxBytes) throw ImportException(ImportLimits.TOO_LARGE_MESSAGE)
            onProgress(resumeFrom, total)

            val sink = if (resumeFrom > 0) {
                FileSystem.SYSTEM.appendingSink(target)
            } else {
                FileSystem.SYSTEM.sink(target)
            }
            val written = sink.use { out ->
                copyBody(response.bodyAsChannel(), out, maxBytes - resumeFrom) { read ->
                    onProgress(resumeFrom + read, total)
                }
            }
            val bytes = resumeFrom + written
            if (bytes == 0L) throw ImportException("Сервер прислал пустой файл")
            // A body that simply stops short of what was announced: the engine
            // usually reports that as a broken transfer, but a proxy in between can
            // close it cleanly. Saying so puts the file back in the hands of the
            // resume loop instead of importing most of a book as the whole of it.
            if (total != null && bytes < total) throw IOException(INCOMPLETE_MESSAGE)

            val finalUrl = response.finalUrl
            DownloadedFile(
                file = target,
                bytes = bytes,
                // Hashed from the finished file: with a resumed download the bytes
                // never pass through one stream, so hashing while writing cannot
                // see all of them.
                sha256 = PortableStreams.sha256(target).orEmpty(),
                contentType = response.lastHeader(HttpHeaders.ContentType),
                fileName = BookFileNames.fileNameFromContentDisposition(response.lastHeader(HttpHeaders.ContentDisposition))
                    ?: BookFileNames.fileNameFromUrl(finalUrl),
                finalUrl = finalUrl
            )
        }
    }

    /** The server would not continue the file; thrown inside [attempt] only. */
    private class RangeRefused : Exception("Сервер не поддерживает докачку")

    /**
     * Streams [channel] into [sink] and returns how many bytes arrived. Stops
     * with an [ImportException] as soon as more than [maxBytes] arrive, so an
     * endless or oversized response never fills the disk, and checks for
     * cancellation before every read. [sink] is flushed, not closed.
     */
    private suspend fun copyBody(
        channel: ByteReadChannel,
        sink: Sink,
        maxBytes: Long,
        onProgress: (Long) -> Unit
    ): Long {
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
            sink.write(buffer, read.toLong())
            onProgress(total)
        }
        sink.flush()
        return total
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TRANSIENT_RETRY_DELAY_MILLIS = 1_500L

        /** How often one mirror may be picked up again after the transfer broke off. */
        const val MAX_RESUME_ATTEMPTS = 3
        const val RESUME_DELAY_MILLIS = 2_000L
        const val MAX_TRANSIENT_RETRIES = 1
        const val INCOMPLETE_MESSAGE = "Файл получен не полностью"
    }
}
