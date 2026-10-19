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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending a book through the circle.
 *
 * The server is a relay, so what has to be right is the handling of the bytes:
 * only the recipient ever reads them, a claim is what deletes them, a refused
 * upload leaves nothing behind, and nothing uncollected outlives its welcome.
 */
class TransferTest {

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

    private fun tempLimits(maxFileBytes: Long = 1024 * 1024, ttlDays: Int = 14): TransferLimits {
        val dir = File(System.getProperty("java.io.tmpdir"), "lumina-test-${UUID.randomUUID()}")
        return TransferLimits(
            directory = dir.absolutePath,
            maxFileBytes = maxFileBytes,
            maxStoreBytes = 4 * 1024 * 1024,
            ttlDays = ttlDays,
        )
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun aBookTravelsToTheFriendAndLeavesTheServerOnClaim() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        val store = TransferStore(limits)
        application { socialModule(db, admin, limits, store) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        val bytes = "это файл книги".toByteArray()

        val id = send(client, ivan, petya, bytes, title = "Дюна", author = "Герберт").let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.body<CreateTransferResponse>().id
        }

        // Петя sees it waiting, with the metadata the sender gave.
        val incoming = incoming(client, petya).transfers
        assertEquals(1, incoming.size)
        val info = incoming.single()
        assertEquals("Дюна", info.title)
        assertEquals("Герберт", info.author)
        assertEquals("Иван", info.fromDisplayName)
        assertEquals(bytes.size.toLong(), info.sizeBytes)

        // The sender does not see it in their own incoming list.
        assertTrue(incoming(client, ivan).transfers.isEmpty())

        val downloaded = client.get("/api/transfers/$id/file") { bearer(petya) }
        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertEquals(bytes.toList(), downloaded.bodyAsBytes().toList())

        // Still here before the claim, so a broken download can be retried.
        assertNotNull(store.file(id), "the file must survive being served")

        assertEquals(HttpStatusCode.NoContent, claim(client, petya, id).status)
        assertNull(store.file(id), "a claim must delete the file")
        assertTrue(incoming(client, petya).transfers.isEmpty())
        assertEquals(0L, db.pendingTransferBytes())
    }

    @Test
    fun nobodyButTheRecipientCanReadOrClaimIt() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        val store = TransferStore(limits)
        application { socialModule(db, admin, limits, store) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        val masha = register(client, "Маша")
        val id = send(client, ivan, petya, "книга".toByteArray()).body<CreateTransferResponse>().id

        // A third member, and even the sender, are told it does not exist.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/transfers/$id/file") { bearer(masha) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/transfers/$id/file") { bearer(ivan) }.status)
        assertEquals(HttpStatusCode.NotFound, claim(client, masha, id).status)
        assertNotNull(store.file(id), "a stranger's claim must not delete anything")

        // And without a token at all.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/transfers/$id/file").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/transfers/incoming").status)
    }

    @Test
    fun anOversizedBookIsRefusedAndStoresNothing() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits(maxFileBytes = 64)
        val store = TransferStore(limits)
        application { socialModule(db, admin, limits, store) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")

        val response = send(client, ivan, petya, ByteArray(5_000) { 'x'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        // Neither a row nor a file may be left over.
        assertEquals(0L, db.pendingTransferBytes())
        assertTrue(incoming(client, petya).transfers.isEmpty())
        assertEquals(emptyList(), store.orphans(emptySet()), "a refused upload must leave no file")
    }

    @Test
    fun aBookCannotGoToYourselfOrToAStranger() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        application { socialModule(db, admin, limits, TransferStore(limits)) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        assertEquals(
            HttpStatusCode.BadRequest,
            send(client, ivan, ivan, "книга".toByteArray()).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            sendRaw(client, ivan, "no-such-user", "книга".toByteArray(), "Дюна", "Герберт", "epub").status,
        )
    }

    @Test
    fun onlyFormatsTheReaderOpensAreRelayed() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        application { socialModule(db, admin, limits, TransferStore(limits)) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        assertEquals(
            HttpStatusCode.BadRequest,
            sendRaw(client, ivan, petya.userId, "x".toByteArray(), "Книга", "Автор", "exe").status,
        )
        assertEquals(
            HttpStatusCode.OK,
            sendRaw(client, ivan, petya.userId, "x".toByteArray(), "Книга", "Автор", "fb2.zip").status,
        )
    }

    @Test
    fun aSeriesKeepsItsNameAndOrderSoTheShelfCanRegroupIt() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        application { socialModule(db, admin, limits, TransferStore(limits)) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")

        send(client, ivan, petya, "том 1".toByteArray(), title = "Дюна", series = "Хроники Дюны", seriesOrder = 1)
        send(client, ivan, petya, "том 2".toByteArray(), title = "Мессия Дюны", series = "Хроники Дюны", seriesOrder = 2)

        val waiting = incoming(client, petya).transfers
        assertEquals(2, waiting.size)
        assertEquals(listOf("Хроники Дюны", "Хроники Дюны"), waiting.map { it.seriesName })
        assertEquals(listOf(1, 2), waiting.map { it.seriesOrder })
    }

    @Test
    fun aShelfTheReaderNamedTravelsWithTheBook() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        application { socialModule(db, admin, limits, TransferStore(limits)) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")

        // A shelf is not in the file and a series cannot stand in for it, so
        // without this the book arrives unplaced - which is what used to happen.
        send(client, ivan, petya, "книга".toByteArray(), title = "Ареал", shelf = "Ареал")
        send(client, ivan, petya, "другая".toByteArray(), title = "Без полки")

        val waiting = incoming(client, petya).transfers
        assertEquals("Ареал", waiting.first { it.title == "Ареал" }.shelf)
        assertEquals(null, waiting.first { it.title == "Без полки" }.shelf, "nothing sent, nothing invented")
    }

    @Test
    fun theSenderCanTakeItBackWhileItIsUncollected() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits()
        val store = TransferStore(limits)
        application { socialModule(db, admin, limits, store) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        val id = send(client, ivan, petya, "книга".toByteArray()).body<CreateTransferResponse>().id

        // Not the recipient's to cancel.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/transfers/$id") { bearer(petya) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/transfers/$id") { bearer(ivan) }.status)
        assertNull(store.file(id))
        assertTrue(incoming(client, petya).transfers.isEmpty())
    }

    @Test
    fun theSweeperDropsWhatNobodyCollectedAndFilesWithNoRow() = testApplication {
        val db = freshDatabase()
        val limits = tempLimits(ttlDays = 14)
        val store = TransferStore(limits)
        application { socialModule(db, admin, limits, store) }
        val client = jsonClient()

        val ivan = register(client, "Иван")
        val petya = register(client, "Петя")
        val fresh = send(client, ivan, petya, "книга".toByteArray()).body<CreateTransferResponse>().id

        // Well inside the time to live, so a sweep must leave it alone.
        sweepTransfers(db, store, limits)
        assertNotNull(store.file(fresh), "a fresh transfer must survive the sweeper")

        // A file nobody has a row for is removed whatever its age: without this
        // nothing would ever reclaim the bytes of a write whose insert failed.
        val orphan = "orphan-file-id"
        store.write(orphan, "ничей файл".byteInputStream(), alreadyStored = 0)
        assertNotNull(store.file(orphan))
        sweepTransfers(db, store, limits)
        assertNull(store.file(orphan), "a file with no row must be swept")
        assertNotNull(store.file(fresh), "sweeping orphans must not touch live transfers")

        // One that nobody collected in time: written straight to the database
        // with an old timestamp, since the route always stamps "now".
        val stale = "stale-transfer-id"
        val staleSize = store.write(stale, "забытая книга".byteInputStream(), alreadyStored = 0)
        db.createTransfer(
            id = stale,
            fromUser = ivan.userId,
            toUser = petya.userId,
            title = "Забытая",
            author = "Автор",
            seriesName = null,
            seriesOrder = 0,
            format = "epub",
            sizeBytes = staleSize,
            now = System.currentTimeMillis() - limits.ttlMillis - 1_000,
        )
        assertTrue(incoming(client, petya).transfers.any { it.id == stale })

        sweepTransfers(db, store, limits)
        assertNull(store.file(stale), "an uncollected transfer past its ttl must be swept")
        assertTrue(
            incoming(client, petya).transfers.none { it.id == stale },
            "the swept transfer must stop being offered",
        )
        assertNotNull(store.file(fresh), "the fresh transfer still stands")
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

    private suspend fun send(
        client: io.ktor.client.HttpClient,
        from: Member,
        to: Member,
        bytes: ByteArray,
        title: String = "Книга",
        author: String = "Автор",
        format: String = "epub",
        series: String? = null,
        seriesOrder: Int = 0,
        shelf: String? = null,
    ): HttpResponse = client.post("/api/transfers") {
        bearer(from)
        url {
            parameters.append("to", to.userId)
            parameters.append("title", title)
            parameters.append("author", author)
            parameters.append("format", format)
            if (series != null) {
                parameters.append("series", series)
                parameters.append("seriesOrder", seriesOrder.toString())
            }
            if (shelf != null) parameters.append("shelf", shelf)
        }
        setBody(bytes)
    }

    /** Sends with a recipient id and format given literally, for the invalid cases. */
    private suspend fun sendRaw(
        client: io.ktor.client.HttpClient,
        from: Member,
        to: String,
        bytes: ByteArray,
        title: String,
        author: String,
        format: String,
    ): HttpResponse = client.post("/api/transfers") {
        bearer(from)
        url {
            parameters.append("to", to)
            parameters.append("title", title)
            parameters.append("author", author)
            parameters.append("format", format)
        }
        setBody(bytes)
    }

    private suspend fun incoming(client: io.ktor.client.HttpClient, member: Member): IncomingTransfersResponse =
        client.get("/api/transfers/incoming") { bearer(member) }.body()

    private suspend fun claim(client: io.ktor.client.HttpClient, member: Member, id: String): HttpResponse =
        client.post("/api/transfers/$id/claim") { bearer(member) }
}
