package com.lumina.reader.core.download

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The checks and texts `describeNetworkError` has always applied, in the same order. */
actual fun platformNetworkErrorMessage(error: Throwable): String? = when (error) {
    is UnknownHostException -> "Нет подключения к интернету или сервер не найден"
    is SocketTimeoutException -> "Сервер слишком долго не отвечает"
    is ConnectException -> "Не удалось подключиться к серверу"
    is SSLException -> "Ошибка защищённого соединения с сервером"
    else -> null
}
