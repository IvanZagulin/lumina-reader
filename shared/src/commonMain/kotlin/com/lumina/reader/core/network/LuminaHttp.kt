package com.lumina.reader.core.network

import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.text.charset.TextCharset
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.IOException

/**
 * Timeouts of one HTTP client in milliseconds, named and meant as OkHttp's
 * builder takes them; 0 means "no limit", OkHttp's convention.
 *
 * - [connectMillis]: establishing the connection;
 * - [readMillis] / [writeMillis]: the longest pause while receiving / sending,
 *   which detects a stalled connection without cutting off a long download;
 * - [callMillis]: the whole call, redirects and reading the body included.
 */
class HttpTimeouts(
    val connectMillis: Long,
    val readMillis: Long,
    val writeMillis: Long,
    val callMillis: Long
)

/**
 * A Ktor client on the platform's own HTTP stack, used by the OPDS catalogues,
 * the book downloads and the AI assistant.
 *
 * - Android: Ktor's OkHttp engine, configured exactly like the OkHttpClients
 *   the app used before Ktor: the same [timeouts], OkHttp following redirects
 *   itself (https -> http included, at most 20 hops, credentials dropped when
 *   the host changes), OkHttp's TLS, proxy and connection pool, and OkHttp's
 *   default User-Agent on requests that set none.
 * - iOS: Ktor's Darwin engine (NSURLSession); Ktor follows the redirects,
 *   https -> http included.
 *
 * On both, so that callers behave the same on the two platforms:
 * - a non-2xx answer is an ordinary response (`expectSuccess = false`), the
 *   caller checks the status, as it did with OkHttp;
 * - Ktor's default transformers are off, so Ktor adds no catch-all `Accept`
 *   and no `Accept-Charset` header: a request carries the caller's headers.
 *   Bodies are read with [readBodyBytes] / [readBodyText] or streamed with
 *   `bodyAsChannel()`;
 * - [finalUrl] is the address that finally answered, after redirects.
 *
 * The clients live as long as the process and are never closed, like the
 * OkHttpClients before them.
 */
expect fun luminaHttpClient(timeouts: HttpTimeouts, proxy: ProxySettings? = null): HttpClient

/**
 * Response header in which the Android engine reports the URL OkHttp finally
 * fetched: OkHttp follows redirects inside one call, so Ktor itself only knows
 * the URL it asked for. Internal to [finalUrl]; never sent to a server.
 */
const val FINAL_URL_HEADER: String = "X-Lumina-Final-Url"

/**
 * The URL that answered after redirects (OkHttp's `response.request.url`):
 * relative links of a feed are resolved against it, and a download without
 * Content-Disposition is named after it.
 */
val HttpResponse.finalUrl: String
    get() = headers[FINAL_URL_HEADER] ?: call.request.url.toString()

/**
 * The last value of the header [name], as OkHttp's `Response.header(name)`
 * returns it (Ktor's `headers[name]` would return the first one).
 */
fun HttpResponse.lastHeader(name: String): String? = headers.getAll(name)?.lastOrNull()

/**
 * The whole body, or null as soon as it turns out longer than [maxBytes].
 *
 * A transfer that breaks off throws (see [readBodyChunk]), so a cut-off body
 * is never returned as complete.
 */
suspend fun HttpResponse.readBodyBytes(maxBytes: Long = Long.MAX_VALUE): ByteArray? {
    val channel = bodyAsChannel()
    val out = Buffer()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        val read = channel.readBodyChunk(chunk)
        if (read < 0) break
        if (read == 0) continue
        if (out.size + read > maxBytes) return null
        out.write(chunk, 0, read)
    }
    return out.readByteArray()
}

/**
 * Reads the next bytes of a response body into [chunk]: their count, or -1
 * once the body is complete.
 *
 * A body channel closed by a network error either throws that error or
 * reports its end with the error kept as `closedCause`; both become an
 * exception here ([transferFailure]), so a cut-off body never passes for a
 * complete one. A channel cancelled while the caller is still active (the
 * engine dropped the call) throws an I/O error rather than the
 * CancellationException the channel raised.
 */
suspend fun ByteReadChannel.readBodyChunk(chunk: ByteArray): Int {
    val read = try {
        readAvailable(chunk, 0, chunk.size)
    } catch (e: CancellationException) {
        throw transferFailure(e)
    }
    if (read < 0) closedCause?.let { throw transferFailure(it) }
    return read
}

/**
 * The exception to throw for a body channel closed with [cause]. A
 * cancellation of the transfer while the caller itself is still active is a
 * broken connection, not the caller's cancellation: callers treat
 * CancellationException as "the user cancelled" and would drop the error.
 * When the caller is cancelled, its own CancellationException is thrown.
 */
suspend fun transferFailure(cause: Throwable): Throwable {
    currentCoroutineContext().ensureActive()
    return if (cause is CancellationException) IOException("Соединение прервано", cause) else cause
}

/**
 * The exception the platform's HTTP stack itself threw, for screens that show
 * an error's message as is (the AI chat shows "Произошла ошибка: <message>").
 *
 * Failures of the call itself already arrive as OkHttp threw them (see the
 * Android actual of [luminaHttpClient]), but while the body streams, Ktor's
 * OkHttp engine replaces OkHttp's java.net.SocketTimeoutException ("timeout")
 * with its own SocketTimeoutException (or ConnectTimeoutException), whose
 * message reads "Socket timeout has expired [url=…, socket_timeout=unknown] ms",
 * and the body channel it closes wraps that again in I/O errors with the same
 * message. Android always showed OkHttp's text, so the original exception
 * (the wrapper's cause) is returned. A timeout Ktor raises itself has no cause
 * (the Darwin engine, HttpTimeout) and, like every other error, is returned
 * unchanged. `describeNetworkError` needs none of this: it maps the wrappers
 * and the originals to the same text.
 */
fun Throwable.unwrapEngineTimeout(): Throwable {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is ConnectTimeoutException || current is SocketTimeoutException) {
            return current.cause ?: this
        }
        current = current.cause
        depth++
    }
    return this
}

/**
 * The body as text, decoded like OkHttp's `ResponseBody.string()`: a
 * byte-order mark wins, then the charset of Content-Type, else UTF-8.
 * Malformed bytes become U+FFFD (java.nio on Android, as before).
 */
suspend fun HttpResponse.readBodyText(): String {
    val bytes = readBodyBytes() ?: ByteArray(0)
    val bom = TextEncoding.detectBom(bytes)
    if (bom != null) return bom.charset.decode(bytes, bom.length, bytes.size - bom.length)
    // OkHttp ignores a malformed Content-Type or an unknown charset, and so do we.
    val declared = try {
        contentType()?.parameter("charset")?.let { TextCharset.forName(it.trim()) }
    } catch (e: Exception) {
        null
    }
    return (declared ?: TextCharset.UTF_8).decode(bytes)
}

private const val READ_CHUNK_BYTES = 8 * 1024

/** As deep as describeNetworkError looks into a cause chain. */
private const val MAX_CAUSE_DEPTH = 6
