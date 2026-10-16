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
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.application.ApplicationCall
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
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        socialModule(database, config.adminToken)
    }.start(wait = true)
}

/**
 * The whole API. Five routes: redeem an invite, check the token, push my shelf,
 * read the circle, and (admin only) mint an invite. Everything the app can do to
 * another person's data is nothing - it only ever reads the circle and writes
 * its own shelf.
 */
fun Application.socialModule(database: Database, adminToken: String) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Некорректный запрос"))
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
