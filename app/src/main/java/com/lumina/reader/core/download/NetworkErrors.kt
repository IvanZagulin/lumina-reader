package com.lumina.reader.core.download

import com.lumina.reader.core.library.ImportException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A non-2xx HTTP response. */
class HttpStatusException(val code: Int, message: String = "HTTP $code") : IOException(message)

/** Short Russian explanation of a network or import failure for the user. */
fun describeNetworkError(error: Throwable): String {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
        when (current) {
            is ImportException -> return current.userMessage
            is HttpStatusException -> return describeHttpStatus(current.code)
            is UnknownHostException -> return "Нет подключения к интернету или сервер не найден"
            is SocketTimeoutException -> return "Сервер слишком долго не отвечает"
            is ConnectException -> return "Не удалось подключиться к серверу"
            is SSLException -> return "Ошибка защищённого соединения с сервером"
        }
        current = current.cause
        depth++
    }
    val message = error.localizedMessage?.takeIf { it.isNotBlank() }
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
