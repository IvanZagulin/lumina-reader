package com.lumina.reader.core.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The app handing a book to the circle and taking one back.
 *
 * What matters: the metadata the server reads is in the query and properly
 * encoded (a title with an `&` must not invent a parameter), the file itself is
 * the body, and a refusal comes back as the server's own sentence.
 */
class SocialTransferClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun clientOf(
        reply: (HttpRequestData) -> Triple<HttpStatusCode, ByteArray, String>,
    ): SocialClient {
        val engine = MockEngine { request ->
            requests += request
            val (status, body, contentType) = reply(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        // Both clients are the mock: an upload must not escape to a real network.
        val http = HttpClient(engine)
        return SocialClient(http, http)
    }

    private fun json(status: HttpStatusCode, body: String) =
        Triple(status, body.encodeToByteArray(), "application/json")

    @Test
    fun sendingPutsTheMetadataInTheQueryAndTheFileInTheBody() = runTest {
        val client = clientOf { json(HttpStatusCode.OK, """{"id":"tr-1"}""") }
        val file = byteArrayOf(1, 2, 3, 4, 5)

        val result = client.sendBook(
            serverUrl = "https://social.example",
            token = "tok",
            recipientId = "user-2",
            title = "Дюна",
            author = "Герберт",
            format = "EPUB",
            bytes = file,
            seriesName = "Хроники Дюны",
            seriesOrder = 2,
        )

        assertEquals("tr-1", result.id)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/transfers", request.url.encodedPath)
        val query = request.url.parameters
        assertEquals("user-2", query["to"])
        assertEquals("Дюна", query["title"])
        assertEquals("Герберт", query["author"])
        // Lowercased, because the server only accepts the stored extensions.
        assertEquals("epub", query["format"])
        assertEquals("Хроники Дюны", query["series"])
        assertEquals("2", query["seriesOrder"])
        assertContentEquals(file, (request.body as ByteArrayContent).bytes())
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aTitleWithAnAmpersandCannotInventAParameter() = runTest {
        val client = clientOf { json(HttpStatusCode.OK, """{"id":"tr-2"}""") }

        client.sendBook(
            serverUrl = "https://social.example",
            token = "tok",
            recipientId = "user-2",
            title = "Хлеб & соль&to=someone-else",
            author = "Автор",
            format = "fb2.zip",
            bytes = byteArrayOf(9),
        )

        val query = requests.single().url.parameters
        assertEquals("Хлеб & соль&to=someone-else", query["title"])
        // Still exactly one recipient, and it is the one that was asked for.
        assertEquals(listOf("user-2"), query.getAll("to"))
        assertEquals("fb2.zip", query["format"])
    }

    @Test
    fun aSeriesIsOmittedWhenThereIsNone() = runTest {
        val client = clientOf { json(HttpStatusCode.OK, """{"id":"tr-3"}""") }

        client.sendBook(
            serverUrl = "https://social.example",
            token = "tok",
            recipientId = "user-2",
            title = "Одиночка",
            author = "Автор",
            format = "epub",
            bytes = byteArrayOf(1),
            seriesName = "   ",
        )

        val query = requests.single().url.parameters
        assertEquals(null, query["series"])
        assertEquals(null, query["seriesOrder"])
    }

    @Test
    fun anOversizedBookComesBackInTheServersOwnWords() = runTest {
        val client = clientOf {
            json(HttpStatusCode.PayloadTooLarge, """{"error":"Книга больше 50 МБ — такую переслать нельзя"}""")
        }

        val failure = assertFailsWith<SocialException> {
            client.sendBook(
                serverUrl = "https://social.example",
                token = "tok",
                recipientId = "user-2",
                title = "Атлас",
                author = "Автор",
                format = "pdf",
                bytes = ByteArray(10),
            )
        }
        assertEquals("Книга больше 50 МБ — такую переслать нельзя", failure.message)
    }

    @Test
    fun theWaitingListIsRead() = runTest {
        val client = clientOf {
            json(
                HttpStatusCode.OK,
                """
                {"transfers":[{"id":"t1","fromUserId":"u1","fromDisplayName":"Иван","title":"Дюна",
                "author":"Герберт","seriesName":"Хроники","seriesOrder":1,"format":"epub",
                "sizeBytes":2048,"createdAt":17}]}
                """.trimIndent(),
            )
        }

        val waiting = client.incomingTransfers("https://social.example", "tok").transfers

        val one = waiting.single()
        assertEquals("t1", one.id)
        assertEquals("Иван", one.fromDisplayName)
        assertEquals("Хроники", one.seriesName)
        assertEquals(2048, one.sizeBytes)
        assertEquals("/api/transfers/incoming", requests.single().url.encodedPath)
    }

    @Test
    fun theFileIsDownloadedAsBytes() = runTest {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val client = clientOf { Triple(HttpStatusCode.OK, payload, "application/octet-stream") }

        val bytes = client.downloadTransfer("https://social.example", "tok", "t1", maxBytes = 1024)

        assertContentEquals(payload, bytes)
        val request = requests.single()
        assertEquals("/api/transfers/t1/file", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aBookPastTheLimitIsRefusedRatherThanTruncated() = runTest {
        val payload = ByteArray(5_000)
        val client = clientOf { Triple(HttpStatusCode.OK, payload, "application/octet-stream") }

        // readBodyBytes answers null past the cap, which must not become a
        // half-written book in the library.
        assertFailsWith<SocialException> {
            client.downloadTransfer("https://social.example", "tok", "t1", maxBytes = 100)
        }
    }

    @Test
    fun aGoneFileIsReportedAndNotRetriedForever() = runTest {
        val client = clientOf { json(HttpStatusCode.Gone, """{"error":"Файл уже удалён с сервера"}""") }

        val failure = assertFailsWith<SocialException> {
            client.downloadTransfer("https://social.example", "tok", "t1", maxBytes = 1024)
        }
        assertEquals("Файл уже удалён с сервера", failure.message)
    }

    @Test
    fun claimingAndCancellingUseTheRightMethods() = runTest {
        val client = clientOf { json(HttpStatusCode.NoContent, "") }

        client.claimTransfer("https://social.example", "tok", "t1")
        client.cancelTransfer("https://social.example", "tok", "t2")

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals("/api/transfers/t1/claim", requests[0].url.encodedPath)
        assertEquals(HttpMethod.Delete, requests[1].method)
        assertEquals("/api/transfers/t2", requests[1].url.encodedPath)
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer tok" })
    }
}
