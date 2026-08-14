package com.lumina.reader.ui.chat

import com.lumina.reader.core.network.AiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class AiChatViewModelTest {

    @Test
    fun `keeps system message and only the latest conversation messages`() {
        val messages = listOf(AiMessage("system", "instructions")) +
            (1..14).map { AiMessage("user", "message $it") }

        val request = messagesForRequest(messages)

        assertEquals(13, request.size)
        assertEquals("instructions", request.first().content)
        assertEquals("message 3", request[1].content)
        assertEquals("message 14", request.last().content)
    }

    @Test
    fun `uses web verification for book and series requests`() {
        assertEquals(true, needsBibliographicVerification("Сколько книг в этой серии?"))
        assertEquals(true, needsBibliographicVerification("Скачай все тома по порядку"))
        assertEquals(false, needsBibliographicVerification("Привет, как дела?"))
    }
}
