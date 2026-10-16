package com.lumina.reader.core.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The app talking to the circle, against answers instead of a network.
 *
 * What matters here is the same thing that mattered for the catalogues: which
 * request goes out, and that a refusal comes back as a sentence a person can
 * read rather than a stack trace.
 */
class SocialClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun clientOf(reply: (HttpRequestData) -> Pair<HttpStatusCode, String>): SocialClient {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = reply(request)
            respond(
                content = ByteReadChannel(body.encodeToByteArray()),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SocialClient(HttpClient(engine))
    }

    private fun bodyOf(request: HttpRequestData): String = (request.body as TextContent).text

    @Test
    fun joiningExchangesAnInviteForAToken() = runTest {
        val client = clientOf { HttpStatusCode.OK to """{"userId":"u1","token":"secret-token"}""" }

        val result = client.redeem("https://social.example", " code-42 ", " Иван ")

        assertEquals("secret-token", result.token)
        assertEquals("u1", result.userId)
        val request = requests.single()
        assertEquals("https://social.example/api/redeem", request.url.toString())
        // Trimmed, so a code pasted with a stray space still works.
        assertTrue(""""code":"code-42"""" in bodyOf(request), bodyOf(request))
        assertTrue(""""displayName":"Иван"""" in bodyOf(request), bodyOf(request))
    }

    @Test
    fun aSpentInviteIsReportedInTheServersOwnWords() = runTest {
        val client = clientOf {
            HttpStatusCode.Conflict to """{"error":"Код недействителен или уже использован"}"""
        }

        val failure = assertFailsWith<SocialException> {
            client.redeem("https://social.example", "code", "Иван")
        }
        assertEquals("Код недействителен или уже использован", failure.message)
    }

    @Test
    fun aTokenTheCircleNoLongerKnowsSaysSo() = runTest {
        val client = clientOf { HttpStatusCode.Unauthorized to "" }

        val failure = assertFailsWith<SocialException> {
            client.circle("https://social.example", "stale")
        }
        assertEquals("Круг больше не принимает этот токен — подключись заново", failure.message)
    }

    @Test
    fun pushingTheShelfSendsItUnderTheBearerToken() = runTest {
        val client = clientOf { HttpStatusCode.NoContent to "" }
        val books = listOf(
            SharedBook(bookKey = "дюна|герберт", title = "Дюна", author = "Герберт", rating = 5, progress = 100, status = ReadingStatus.DONE),
        )

        client.pushBooks("https://social.example/", "my-token", books)

        val request = requests.single()
        assertEquals("PUT", request.method.value)
        assertEquals("https://social.example/api/me/books", request.url.toString())
        assertEquals("Bearer my-token", request.headers[HttpHeaders.Authorization])
        assertTrue(""""title":"Дюна"""" in bodyOf(request), bodyOf(request))
    }

    @Test
    fun theCircleComesBackAsMembersWithTheirShelves() = runTest {
        val client = clientOf {
            HttpStatusCode.OK to """
                {"members":[{"userId":"u2","displayName":"Петя","books":[
                  {"bookKey":"ведьмак|сапковский","title":"Ведьмак","author":"Сапковский","progress":40,"status":"READING","updatedAt":5}
                ]}]}
            """.trimIndent()
        }

        val circle = client.circle("https://social.example", "my-token")

        val petya = circle.members.single()
        assertEquals("Петя", petya.displayName)
        assertEquals("Ведьмак", petya.books.single().title)
        assertEquals(40, petya.books.single().progress)
    }

    @Test
    fun anUnknownFieldFromANewerServerDoesNotBreakTheApp() = runTest {
        // The app must survive the server growing a field before it is updated.
        val client = clientOf {
            HttpStatusCode.OK to """{"members":[{"userId":"u2","displayName":"Петя","books":[],"mood":"бодрое"}]}"""
        }

        assertEquals("Петя", client.circle("https://social.example", "t").members.single().displayName)
    }

    @Test
    fun anAddressThatIsNotOneIsRefusedBeforeAnyRequest() = runTest {
        val client = clientOf { HttpStatusCode.OK to "{}" }

        val failure = assertFailsWith<SocialException> { client.me("не адрес", "token") }

        assertEquals("Некорректный адрес сервера", failure.message)
        assertTrue(requests.isEmpty(), "nothing should have been sent")
    }

    @Test
    fun mintingAnInviteSendsTheAdminTokenAndNotTheBearer() = runTest {
        val client = clientOf { HttpStatusCode.OK to """{"code":"abc123xyz"}""" }

        val invite = client.createInvite("https://social.example", "admin-secret")

        assertEquals("abc123xyz", invite.code)
        val request = requests.single()
        assertEquals("https://social.example/api/invites", request.url.toString())
        // The admin secret is its own header; a member's bearer cannot mint invites.
        assertEquals("admin-secret", request.headers["X-Admin-Token"])
        assertEquals(null, request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aWrongAdminTokenIsReportedAsTheServerPutsIt() = runTest {
        val client = clientOf { HttpStatusCode.Forbidden to """{"error":"Нужен админ-токен"}""" }

        val failure = assertFailsWith<SocialException> {
            client.createInvite("https://social.example", "wrong")
        }
        assertEquals("Нужен админ-токен", failure.message)
    }
}
