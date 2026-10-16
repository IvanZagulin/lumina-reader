package com.lumina.social

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Raised for any request the server declines to process; becomes a 400. */
class BadRequestException(message: String) : Exception(message)

private const val MAX_DISPLAY_NAME = 64
private const val MAX_BOOKS = 10_000
private const val MAX_TEXT = 500
private const val MAX_KEY = 512

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database.postgres(config.databaseUrl, config.databaseUser, config.databasePassword).also { it.migrate() }
    val transfers = TransferStore(config.transfers)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        socialModule(database, config.adminToken, config.transfers, transfers)
    }.start(wait = true)
}

/**
 * The whole API. Five routes: redeem an invite, check the token, push my shelf,
 * read the circle, and (admin only) mint an invite. Everything the app can do to
 * another person's data is nothing - it only ever reads the circle and writes
 * its own shelf.
 */
fun Application.socialModule(
    database: Database,
    adminToken: String,
    transferLimits: TransferLimits = TransferLimits(
        directory = System.getProperty("java.io.tmpdir") + "/lumina-transfers",
    ),
    transfers: TransferStore = TransferStore(transferLimits),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Некорректный запрос"))
        }
        // An upload over a cap is not the sender's mistake to fix by retrying,
        // so it says what is full rather than just "bad request".
        exception<TransferRejected> { call, cause ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(cause.message ?: "Файл не принят"))
        }
        exception<Throwable> { call, cause ->
            // Malformed JSON and the like arrive as assorted exceptions from the
            // content negotiation plugin; they are the client's fault, not ours.
            if (cause is kotlinx.serialization.SerializationException || cause is io.ktor.server.plugins.BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный запрос"))
            } else {
                call.application.environment.log.error("Unhandled error", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка"))
            }
        }
    }

    routing {
        // Admin: create a one-time invite code to hand to a friend.
        post("/api/invites") {
            val provided = call.request.headers["X-Admin-Token"]
            if (provided == null || !secureEquals(provided, adminToken)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Нужен админ-токен"))
                return@post
            }
            val code = Secrets.newInviteCode()
            database.createInvite(code, System.currentTimeMillis())
            call.respond(CreateInviteResponse(code))
        }

        // Turn an invite into an account and a token.
        post("/api/redeem") {
            val request = call.receive<RedeemRequest>()
            val name = request.displayName.trim()
            if (name.isEmpty() || name.length > MAX_DISPLAY_NAME) {
                throw BadRequestException("Имя должно быть от 1 до $MAX_DISPLAY_NAME символов")
            }
            if (request.code.isBlank()) throw BadRequestException("Пустой код приглашения")
            val result = database.redeemInvite(request.code.trim(), name, System.currentTimeMillis())
                ?: return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("Код недействителен или уже использован"))
            call.respond(result)
        }

        // Who am I (and is my token still good).
        get("/api/me") {
            val user = call.authenticate(database) ?: return@get
            call.respond(MeResponse(user.id, user.displayName))
        }

        // Replace my shared shelf with what the app sends.
        put("/api/me/books") {
            val user = call.authenticate(database) ?: return@put
            val request = call.receive<PushBooksRequest>()
            if (request.books.size > MAX_BOOKS) throw BadRequestException("Слишком много книг в списке")
            val sanitized = request.books.map { it.sanitized() }
            database.replaceBooks(user.id, sanitized)
            call.respond(HttpStatusCode.NoContent)
        }

        // Everyone else's shelves: the feed.
        get("/api/circle") {
            val user = call.authenticate(database) ?: return@get
            call.respond(CircleResponse(database.circle(user.id)))
        }

        // ---- Sending a book ----------------------------------------------------
        // The file rides in the body and its metadata in the query, so neither
        // side needs multipart. The server is a relay: it holds the bytes until
        // the recipient claims them, then deletes them.
        post("/api/transfers") {
            val sender = call.authenticate(database) ?: return@post
            val query = call.request.queryParameters
            val recipient = query["to"]?.trim().orEmpty()
            if (recipient.isEmpty()) throw BadRequestException("Не указан получатель")
            if (recipient == sender.id) throw BadRequestException("Нельзя отправить книгу себе")
            if (!database.userExists(recipient)) throw BadRequestException("Такого участника нет в круге")

            val title = query["title"]?.trim().orEmpty()
            if (title.isEmpty() || title.length > MAX_TEXT) throw BadRequestException("Некорректное название книги")
            val author = query["author"]?.trim().orEmpty().take(MAX_TEXT)
            val format = query["format"]?.trim()?.lowercase().orEmpty()
            if (format !in ALLOWED_TRANSFER_FORMATS) throw BadRequestException("Такой формат переслать нельзя: $format")
            val seriesName = query["series"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
            val seriesOrder = query["seriesOrder"]?.trim()?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0

            val id = Secrets.newTransferId()
            val stored = database.pendingTransferBytes()
            val size = withContext(Dispatchers.IO) {
                transfers.write(id, call.receiveStream(), stored)
            }
            try {
                database.createTransfer(
                    id = id,
                    fromUser = sender.id,
                    toUser = recipient,
                    title = title,
                    author = author,
                    seriesName = seriesName,
                    seriesOrder = seriesOrder,
                    format = format,
                    sizeBytes = size,
                    now = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                // No row means nothing would ever collect or sweep those bytes.
                transfers.delete(id)
                throw e
            }
            call.respond(CreateTransferResponse(id))
        }

        /** What was sent to me and is still waiting; metadata only. */
        get("/api/transfers/incoming") {
            val user = call.authenticate(database) ?: return@get
            call.respond(IncomingTransfersResponse(database.incomingTransfers(user.id)))
        }

        // The bytes. Only the recipient, and only while the file is still here.
        get("/api/transfers/{id}/file") {
            val user = call.authenticate(database) ?: return@get
            val id = call.parameters["id"].orEmpty()
            val transfer = database.transferForRecipient(id, user.id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Эта передача больше недоступна"))
            val file = transfers.file(transfer.id)
                ?: return@get call.respond(HttpStatusCode.Gone, ErrorResponse("Файл уже удалён с сервера"))
            call.respondFile(file)
        }

        /**
         * Confirms the book arrived, which is what deletes it here. Claiming is
         * a separate step from downloading on purpose: a download that broke
         * halfway must be retryable, and it would not be if the bytes went away
         * as soon as they were served.
         */
        post("/api/transfers/{id}/claim") {
            val user = call.authenticate(database) ?: return@post
            val id = call.parameters["id"].orEmpty()
            val transfer = database.transferForRecipient(id, user.id)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Эта передача больше недоступна"))
            transfers.delete(transfer.id)
            database.deleteTransfer(transfer.id)
            call.respond(HttpStatusCode.NoContent)
        }

        // The sender changed their mind, while it is still uncollected.
        delete("/api/transfers/{id}") {
            val user = call.authenticate(database) ?: return@delete
            val id = call.parameters["id"].orEmpty()
            if (database.transferSender(id) != user.id) {
                return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Такой отправки нет"))
            }
            transfers.delete(id)
            database.deleteTransfer(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // Nothing uncollected may live for ever on a shared disk. The first sweep is
    // after the interval, not at start-up, so a short-lived test never runs one.
    launch {
        while (true) {
            delay(SWEEP_INTERVAL_MILLIS)
            try {
                sweepTransfers(database, transfers, transferLimits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Transfer sweep failed", e)
            }
        }
    }
}

private const val SWEEP_INTERVAL_MILLIS = 60L * 60 * 1000

/**
 * Drops what nobody collected in time, and any file whose row is gone. Both
 * halves matter: the row is the quota's record, the file is the disk's.
 */
internal suspend fun sweepTransfers(database: Database, transfers: TransferStore, limits: TransferLimits) {
    val cutoff = System.currentTimeMillis() - limits.ttlMillis
    for (id in database.expiredTransferIds(cutoff)) {
        transfers.delete(id)
        database.deleteTransfer(id)
    }
    val known = database.allTransferIds()
    for (orphan in withContext(Dispatchers.IO) { transfers.orphans(known) }) {
        transfers.delete(orphan)
    }
}

/** The caller behind a `Bearer` token, or null after answering 401 itself. */
private suspend fun ApplicationCall.authenticate(database: Database): User? {
    val header = request.headers[HttpHeaders.Authorization]
    val token = header?.removePrefix("Bearer ")?.trim()
    if (token.isNullOrEmpty()) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Нужен токен"))
        return null
    }
    val user = database.userByToken(token)
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Неизвестный токен"))
        return null
    }
    return user
}

/** A book trimmed to what the server will store: bounded strings, sane numbers. */
private fun SharedBook.sanitized(): SharedBook {
    if (bookKey.isBlank() || bookKey.length > MAX_KEY) throw BadRequestException("Некорректный ключ книги")
    if (title.length > MAX_TEXT || author.length > MAX_TEXT) throw BadRequestException("Слишком длинное название или автор")
    if (status !in ALLOWED_STATUSES) throw BadRequestException("Неизвестный статус: $status")
    val rating = rating?.also {
        if (it !in 0..5) throw BadRequestException("Оценка должна быть от 0 до 5")
    }
    return copy(
        title = title.trim(),
        author = author.trim(),
        progress = progress.coerceIn(0, 100),
        rating = rating,
    )
}
