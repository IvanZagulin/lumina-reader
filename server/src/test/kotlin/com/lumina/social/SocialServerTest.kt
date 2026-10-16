package com.lumina.social

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
 * The server end to end, over HTTP, against an in-memory H2 that runs the same
 * SQL PostgreSQL does. What is checked is the part that must be right: an invite
 * works exactly once, a shelf is only ever the caller's own, and nobody reads
 * anything without a good token.
 */
class SocialServerTest {

    private val admin = "admin-secret"

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

    private suspend fun HttpResponse.statusAnd(block: suspend HttpResponse.() -> Unit) = block()

    @Test
    fun anInviteMintsExactlyOneUserAndThenIsSpent() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val code = client.post("/api/invites") { header("X-Admin-Token", admin) }
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<CreateInviteResponse>().code

        val redeemed = client.post("/api/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, "Иван"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }.body<RedeemResponse>()
        assertTrue(redeemed.token.isNotBlank())

        // The same code a second time is refused, so a leaked invite cannot be reused.
        val second = client.post("/api/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, "Самозванец"))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun mintingAnInviteNeedsTheAdminToken() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/invites").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/invites") { header("X-Admin-Token", "wrong") }.status,
        )
    }

    @Test
    fun theCircleShowsOtherReadersAndNeverYourself() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")

        push(client, ivan, listOf(sharedBook("Дюна", "Герберт", rating = 5, progress = 100, status = "DONE")))
        push(client, petya, listOf(sharedBook("Ведьмак", "Сапковский", progress = 40)))

        val ivanSees = circle(client, ivan)
        assertEquals(listOf("Петя"), ivanSees.members.map { it.displayName }, "Ivan must not see his own shelf")
        assertEquals("Ведьмак", ivanSees.members.single().books.single().title)

        val petyaSees = circle(client, petya)
        assertEquals(listOf("Иван"), petyaSees.members.map { it.displayName })
        val book = petyaSees.members.single().books.single()
        assertEquals(5, book.rating)
        assertEquals(100, book.progress)
    }

    @Test
    fun pushingAShelfReplacesTheLastOne() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        push(client, ivan, listOf(sharedBook("Дюна", "Герберт"), sharedBook("1984", "Оруэлл")))
        push(client, ivan, listOf(sharedBook("1984", "Оруэлл", progress = 10)))

        val books = circle(client, petya).members.single().books
        assertEquals(listOf("1984"), books.map { it.title }, "an earlier push must not linger")
        assertEquals(10, books.single().progress)
    }

    @Test
    fun readingAnythingNeedsAGoodToken() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/circle").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/circle") { header(HttpHeaders.Authorization, "Bearer nonsense") }.status,
        )
    }

    @Test
    fun aBookWithAnImpossibleRatingOrStatusIsRejected() = testApplication {
        val db = freshDatabase()
        application { socialModule(db, admin) }
        val client = jsonClient()
        val ivan = register(client, "Иван")

        assertEquals(
            HttpStatusCode.BadRequest,
            put(client, ivan, listOf(sharedBook("Дюна", "Герберт", rating = 9))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            put(client, ivan, listOf(sharedBook("Дюна", "Герберт", status = "НЕПОНЯТНО"))).status,
        )
    }

    // ---- helpers -------------------------------------------------------------------

    private suspend fun register(client: io.ktor.client.HttpClient, name: String): String {
        val code = client.post("/api/invites") { header("X-Admin-Token", admin) }.body<CreateInviteResponse>().code
        return client.post("/api/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, name))
        }.body<RedeemResponse>().token
    }

    private suspend fun push(client: io.ktor.client.HttpClient, token: String, books: List<SharedBook>) {
        val response = put(client, token, books)
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private suspend fun put(client: io.ktor.client.HttpClient, token: String, books: List<SharedBook>): HttpResponse =
        client.put("/api/me/books") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(PushBooksRequest(books))
        }

    private suspend fun circle(client: io.ktor.client.HttpClient, token: String): CircleResponse =
        client.get("/api/circle") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

    private fun sharedBook(
        title: String,
        author: String,
        rating: Int? = null,
        progress: Int = 0,
        status: String = "READING",
    ) = SharedBook(
        bookKey = "${title.lowercase()}|${author.lowercase()}",
        title = title,
        author = author,
        rating = rating,
        progress = progress,
        status = status,
        updatedAt = System.currentTimeMillis(),
    )
}
