package com.lumina.reader.core.social

import com.lumina.reader.core.download.describeNetworkError
import com.lumina.reader.core.network.HttpTimeouts
import com.lumina.reader.core.network.luminaHttpClient
import com.lumina.reader.core.network.readBodyBytes
import com.lumina.reader.core.network.readBodyText
import com.lumina.reader.core.opds.OpdsUrls
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The app's side of the reading circle (server/README.md).
 *
 * Four calls: join with an invite, check the token, push my shelf, read
 * everyone else's. The client never asks the server to change another person's
 * data, because the server has no route that could.
 *
 * Bodies are written and read by hand, like [com.lumina.reader.core.network.AiClient]
 * does: `luminaHttpClient` deliberately runs without Ktor's default
 * transformers, so nothing adds an `Accept` header or decodes a body behind our
 * back.
 */
class SocialClient(
    private val client: HttpClient = defaultClient,
    /**
     * The client that carries a book file. Separate because its timeouts are
     * download-sized, and injectable for the same reason [client] is: a test
     * must be able to answer an upload without a network.
     */
    private val transfers: HttpClient = transferClient,
) {

    suspend fun redeem(serverUrl: String, code: String, displayName: String): RedeemResponse =
        call(serverUrl, "/api/redeem") { url ->
            client.post(url) { jsonBody(RedeemRequest(code.trim(), displayName.trim())) }
        }.decode()

    suspend fun me(serverUrl: String, token: String): MeResponse =
        call(serverUrl, "/api/me") { url ->
            client.get(url) { bearer(token) }
        }.decode()

    /** Replaces this reader's shared shelf with [books]; an empty list clears it. */
    suspend fun pushBooks(serverUrl: String, token: String, books: List<SharedBook>) {
        call(serverUrl, "/api/me/books") { url ->
            client.put(url) {
                bearer(token)
                jsonBody(PushBooksRequest(books))
            }
        }
    }

    suspend fun circle(serverUrl: String, token: String): CircleResponse =
        call(serverUrl, "/api/circle") { url ->
            client.get(url) { bearer(token) }
        }.decode()

    // ---- Sending a book ------------------------------------------------------------

    /**
     * Uploads [bytes] as a book for [recipientId] and returns the transfer's id.
     *
     * The metadata rides in the query and the file in the body, which is what
     * the server expects; a value is encoded here rather than interpolated, so
     * a title with a `&` in it cannot invent a parameter.
     */
    suspend fun sendBook(
        serverUrl: String,
        token: String,
        recipientId: String,
        title: String,
        author: String,
        format: String,
        bytes: ByteArray,
        seriesName: String? = null,
        seriesOrder: Int = 0,
    ): CreateTransferResponse {
        val query = buildString {
            append("?to=").append(recipientId.encodeURLParameter())
            append("&title=").append(title.trim().encodeURLParameter())
            append("&author=").append(author.trim().encodeURLParameter())
            append("&format=").append(format.lowercase().encodeURLParameter())
            if (!seriesName.isNullOrBlank()) {
                append("&series=").append(seriesName.trim().encodeURLParameter())
                append("&seriesOrder=").append(seriesOrder.coerceAtLeast(0))
            }
        }
        return call(serverUrl, "/api/transfers$query") { url ->
            transfers.post(url) {
                bearer(token)
                setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
            }
        }.decode()
    }

    /** Books sent to this device and not yet collected. */
    suspend fun incomingTransfers(serverUrl: String, token: String): IncomingTransfersResponse =
        call(serverUrl, "/api/transfers/incoming") { url ->
            client.get(url) { bearer(token) }
        }.decode()

    /**
     * The bytes of a waiting book, refusing anything past [maxBytes].
     *
     * Claiming is deliberately a separate call: the server keeps the file until
     * [claimTransfer], so a download that breaks off can simply be repeated.
     */
    suspend fun downloadTransfer(
        serverUrl: String,
        token: String,
        transferId: String,
        maxBytes: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        val base = OpdsUrls.normalizeUserUrl(serverUrl)?.trimEnd('/')
            ?: throw SocialException("Некорректный адрес сервера")
        val response = try {
            transfers.get("$base/api/transfers/$transferId/file") { bearer(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SocialException(describeNetworkError(e))
        }
        if (!response.status.isSuccess()) throw failure(response.status.value, response.readBodyText())
        response.readBodyBytes(maxBytes)
            ?: throw SocialException("Книга оказалась больше, чем обещал сервер")
    }

    /** Confirms the book arrived; this is what deletes it from the server. */
    suspend fun claimTransfer(serverUrl: String, token: String, transferId: String) {
        call(serverUrl, "/api/transfers/$transferId/claim") { url ->
            client.post(url) { bearer(token) }
        }
    }

    /** Takes back something sent but not yet collected. */
    suspend fun cancelTransfer(serverUrl: String, token: String, transferId: String) {
        call(serverUrl, "/api/transfers/$transferId") { url ->
            client.delete(url) { bearer(token) }
        }
    }

    /**
     * One request: builds the address, turns a refusal into a sentence and a
     * transport failure into the same wording the catalogues use.
     */
    private suspend fun call(
        serverUrl: String,
        path: String,
        request: suspend (String) -> HttpResponse,
    ): String = withContext(Dispatchers.IO) {
        val base = OpdsUrls.normalizeUserUrl(serverUrl)?.trimEnd('/')
            ?: throw SocialException("Некорректный адрес сервера")
        val response = try {
            request(base + path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SocialException(describeNetworkError(e))
        }
        val body = response.readBodyText()
        if (!response.status.isSuccess()) throw failure(response.status.value, body)
        body
    }

    private inline fun <reified T> String.decode(): T = try {
        json.decodeFromString<T>(this)
    } catch (e: Exception) {
        throw SocialException("Сервер ответил в неизвестном формате")
    }

    private fun failure(code: Int, body: String): SocialException {
        // The server explains itself in Russian already; use that when it did.
        val reported = runCatching { json.decodeFromString<ErrorResponse>(body).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val fallback = when (code) {
            401 -> "Круг больше не принимает этот токен — подключись заново"
            403 -> "Доступ запрещён"
            404 -> "По этому адресу нет сервера круга (HTTP 404)"
            409 -> "Код недействителен или уже использован"
            in 500..599 -> "Ошибка на стороне сервера (HTTP $code)"
            else -> "Сервер ответил ошибкой HTTP $code"
        }
        return SocialException(reported ?: fallback)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        setBody(TextContent(json.encodeToString(value), ContentType.Application.Json.withCharset(Charsets.UTF_8)))
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        headers[HttpHeaders.Authorization] = "Bearer $token"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Short timeouts: these calls carry a list of titles, not a book. */
        private val defaultClient: HttpClient by lazy {
            luminaHttpClient(
                HttpTimeouts(connectMillis = 10_000, readMillis = 20_000, writeMillis = 20_000, callMillis = 30_000),
            )
        }

        /**
         * The one client that does carry a book file, so its timeouts are the
         * download-sized ones rather than the circle's: a 50 MB book on a phone
         * connection takes minutes, and that is not a failure.
         */
        private val transferClient: HttpClient by lazy {
            luminaHttpClient(
                HttpTimeouts(
                    connectMillis = 15_000,
                    readMillis = 120_000,
                    writeMillis = 120_000,
                    callMillis = 15 * 60_000,
                ),
            )
        }
    }
}
