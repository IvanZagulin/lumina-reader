package com.lumina.social

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asking for a book off someone else's shelf.
 *
 * The server never holds the file, so an ask is only a note for the owner's
 * app. What has to be right is who may leave one, who may read it, and that
 * tapping twice does not turn into two.
 */
class RequestTest {

    private val admin = "admin-secret"

    private class Member(val userId: String, val token: String)

    private fun freshDatabase(): Database {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            maximumPoolSize = 4
        }
        return Database(HikariDataSource(config)).also { it.migrate() }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun anAskReachesTheOwnerAndNobodyElse() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val owner = register(client, "Иван")
        val asker = register(client, "Петя")
        val bystander = register(client, "Маша")

        val response = ask(client, asker, owner, "дюна|герберт", "Дюна", "Герберт")
        assertEquals(HttpStatusCode.OK, response.status)

        val waiting = pending(client, owner).requests
        assertEquals(1, waiting.size)
        assertEquals("Дюна", waiting.single().title)
        assertEquals("дюна|герберт", waiting.single().bookKey)
        assertEquals("Петя", waiting.single().fromDisplayName)

        // Nobody else is told about it, not even the one who asked.
        assertTrue(pending(client, asker).requests.isEmpty())
        assertTrue(pending(client, bystander).requests.isEmpty())
    }

    @Test
    fun askingTwiceIsStillOneAsk() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val owner = register(client, "Иван")
        val asker = register(client, "Петя")

        val first = ask(client, asker, owner, "дюна|герберт", "Дюна", "Герберт").body<CreateRequestResponse>().id
        val second = ask(client, asker, owner, "дюна|герберт", "Дюна", "Герберт").body<CreateRequestResponse>().id

        assertEquals(first, second, "a second tap must not make a second request")
        assertEquals(1, pending(client, owner).requests.size)
    }

    @Test
    fun youCannotAskYourselfOrAStranger() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        assertEquals(HttpStatusCode.BadRequest, ask(client, ivan, ivan, "k", "Книга", "Автор").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            askRaw(client, ivan, "no-such-user", "k", "Книга", "Автор").status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/requests/pending").status)
    }

    @Test
    fun bothSidesMayDropTheAskAndNobodyElseMay() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val owner = register(client, "Иван")
        val asker = register(client, "Петя")
        val bystander = register(client, "Маша")

        val id = ask(client, asker, owner, "дюна|герберт", "Дюна", "Герберт").body<CreateRequestResponse>().id
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/requests/$id") { bearer(bystander) }.status,
            "a stranger must not be able to drop someone else's request",
        )
        // The owner answers it by sending the book, then drops the note.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/requests/$id") { bearer(owner) }.status)
        assertTrue(pending(client, owner).requests.isEmpty())

        // And the one who asked may change their mind instead.
        val again = ask(client, asker, owner, "иное|автор", "Иное", "Автор").body<CreateRequestResponse>().id
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/requests/$again") { bearer(asker) }.status)
        assertTrue(pending(client, owner).requests.isEmpty())
    }

    // ---- helpers -------------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(member: Member) {
        header(HttpHeaders.Authorization, "Bearer ${member.token}")
    }

    private suspend fun register(client: io.ktor.client.HttpClient, name: String): Member {
        val code = client.post("/api/invites") { header("X-Admin-Token", admin) }.body<CreateInviteResponse>().code
        val redeemed = client.post("/api/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, name))
        }.body<RedeemResponse>()
        return Member(redeemed.userId, redeemed.token)
    }

    private suspend fun ask(
        client: io.ktor.client.HttpClient,
        asker: Member,
        owner: Member,
        bookKey: String,
        title: String,
        author: String,
    ): HttpResponse = askRaw(client, asker, owner.userId, bookKey, title, author)

    private suspend fun askRaw(
        client: io.ktor.client.HttpClient,
        asker: Member,
        owner: String,
        bookKey: String,
        title: String,
        author: String,
    ): HttpResponse = client.post("/api/requests") {
        bearer(asker)
        url {
            parameters.append("to", owner)
            parameters.append("bookKey", bookKey)
            parameters.append("title", title)
            parameters.append("author", author)
        }
    }

    private suspend fun pending(client: io.ktor.client.HttpClient, member: Member): PendingRequestsResponse =
        client.get("/api/requests/pending") { bearer(member) }.body()
}
