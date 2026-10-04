package com.lumina.reader.core.download

import com.lumina.reader.core.library.ImportException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import okio.IOException

/**
 * A non-2xx HTTP response. okio's IOException is java.io.IOException on
 * Android, so every catch site sees the same type as before.
 */
class HttpStatusException(val code: Int, message: String = "HTTP $code") : IOException(message)

/**
 * Short Russian explanation of a network or import failure for the user.
 *
 * Ktor's timeout exceptions come first. They are what iOS reports (the Darwin
 * engine's timeout, HttpTimeout's request timeout); on Android OkHttp's own
 * failures reach this function unchanged (wrapped once, see
 * luminaHttpClient's Android actual), except a read timeout while streaming a
 * body, which Ktor's OkHttp engine wraps into its SocketTimeoutException. A
 * ConnectTimeoutException is a ConnectException on the JVM and must not read
 * "Не удалось подключиться". The platform stack's own exceptions are
 * recognised by [platformNetworkErrorMessage].
 */
fun describeNetworkError(error: Throwable): String {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
        when (current) {
            is ImportException -> return current.userMessage
            is HttpStatusException -> return describeHttpStatus(current.code)
            is ConnectTimeoutException,
            is SocketTimeoutException,
            is HttpRequestTimeoutException -> return "Сервер слишком долго не отвечает"
        }
        platformNetworkErrorMessage(current)?.let { return it }
        current = current.cause
        depth++
    }
    // message, not the JVM-only localizedMessage: the JVM returns the same text
    // unless a subclass overrides it, and the network exceptions do not.
    val message = error.message?.takeIf { it.isNotBlank() }
    return message ?: "Неизвестная ошибка сети"
}

fun describeHttpStatus(code: Int): String = when (code) {
    401 -> "Нужен логин и пароль (HTTP 401)"
    403 -> "Доступ запрещён (HTTP 403)"
    404 -> "Не найдено на сервере (HTTP 404)"
    410 -> "Файл удалён с сервера (HTTP 410)"
    429 -> "Слишком много запросов, попробуйте позже (HTTP 429)"
    in 500..599 -> "Ошибка на стороне сервера (HTTP $code)"
    else -> "Сервер ответил ошибкой HTTP $code"
}
