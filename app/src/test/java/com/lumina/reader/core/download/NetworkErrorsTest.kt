package com.lumina.reader.core.download

import com.lumina.reader.core.library.ImportException
import com.lumina.reader.core.network.unwrapEngineTimeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException

/**
 * describeNetworkError with the Android network stack: OkHttp's java.net
 * exceptions (through platformNetworkErrorMessage) and the Ktor wrappers its
 * OkHttp engine adds, plus unwrapEngineTimeout for messages shown as is.
 * JVM-only (java.net), so it belongs in :shared androidUnitTest once
 * NetworkErrors.kt moves.
 */
class NetworkErrorsTest {

    @Test
    fun describesErrorsForUsers() {
        assertEquals("Доступ запрещён (HTTP 403)", describeNetworkError(HttpStatusException(403)))
        assertEquals(
            "Нет подключения к интернету или сервер не найден",
            describeNetworkError(IOException("wrap", UnknownHostException("flibusta.is")))
        )
        assertEquals("Файл пустой", describeNetworkError(ImportException("Файл пустой")))
    }

    @Test
    fun connectTimeoutWrappedByKtorStillReadsAsSlowServer() {
        // Ktor's ConnectTimeoutException (what its OkHttp engine makes of a connect
        // timeout it gets unwrapped) is a java.net.ConnectException on the JVM.
        val wrapped = ConnectTimeoutException("Connect timeout has expired", SocketTimeoutException("connect timed out"))
        assertEquals("Сервер слишком долго не отвечает", describeNetworkError(wrapped))
        assertEquals("Сервер слишком долго не отвечает", describeNetworkError(SocketTimeoutException("timeout")))
    }

    @Test
    fun ktorTimeoutWrappersGiveBackOkHttpsOwnException() {
        // A read timeout while a body streams: Ktor's SocketTimeoutException around
        // OkHttp's, inside the I/O error of the closed body channel. The AI chat
        // shows the message as is, which was OkHttp's "timeout".
        val original = SocketTimeoutException("timeout")
        val streamed = IOException(
            "Socket timeout has expired",
            KtorSocketTimeoutException("Socket timeout has expired", original)
        )
        assertSame(original, streamed.unwrapEngineTimeout())
        // A timeout Ktor raised itself (no cause) and other errors stay as they are.
        val own = KtorSocketTimeoutException("Socket timeout has expired", null)
        assertSame(own, own.unwrapEngineTimeout())
        val other = IOException("unexpected end of stream", UnknownHostException("flibusta.is"))
        assertSame(other, other.unwrapEngineTimeout())
    }

    @Test
    fun unknownErrorsShowTheirMessage() {
        assertEquals("Too many follow-up requests: 21", describeNetworkError(IOException("Too many follow-up requests: 21")))
        assertEquals("Неизвестная ошибка сети", describeNetworkError(IOException()))
    }
}
