package com.lumina.reader.core.download

import com.lumina.reader.core.library.ImportException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** describeNetworkError stays with the Android network code until stage 7 (split from DownloadStatesTest). */
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
}
