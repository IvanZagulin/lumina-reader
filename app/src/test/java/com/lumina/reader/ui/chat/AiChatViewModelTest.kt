package com.lumina.reader.ui.chat

import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.core.network.AiMessage
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Camel-case names: the test moves to sharedUi commonTest, which also runs on Kotlin/Native. */
class AiChatViewModelTest {

    @Test
    fun keepsSystemMessageAndOnlyTheLatestConversationMessages() {
        val messages = listOf(AiMessage("system", "instructions")) +
            (1..14).map { AiMessage("user", "message $it") }

        val request = messagesForRequest(messages)

        assertEquals(13, request.size)
        assertEquals("instructions", request.first().content)
        assertEquals("message 3", request[1].content)
        assertEquals("message 14", request.last().content)
    }

    @Test
    fun usesWebVerificationForBookAndSeriesRequests() {
        assertEquals(true, needsBibliographicVerification("Сколько книг в этой серии?"))
        assertEquals(true, needsBibliographicVerification("Скачай все тома по порядку"))
        assertEquals(false, needsBibliographicVerification("Привет, как дела?"))
    }

    @Test
    fun countsActiveReadingDaysInTheGivenTimeZone() {
        // 23:30 and 00:30 UTC on consecutive days: two days in UTC, but the
        // same calendar day three hours west (20:30 and 21:30 local).
        val evening = 86_400_000L - 30 * 60_000L
        val afterMidnight = 86_400_000L + 30 * 60_000L
        val stats = listOf(
            ReadingStats(bookId = 1, sessionDurationSeconds = 60, wordsReadCount = 250, timestamp = evening),
            ReadingStats(bookId = 1, sessionDurationSeconds = 60, wordsReadCount = 250, timestamp = afterMidnight)
        )

        assertEquals(
            "Прочитано слов: 500\nПрочитано страниц: 2\nСредний темп: 250 сл/мин\nДней активного чтения: 2",
            buildStatsContext(stats, TimeZone.UTC)
        )
        assertEquals(
            "Прочитано слов: 500\nПрочитано страниц: 2\nСредний темп: 250 сл/мин\nДней активного чтения: 1",
            buildStatsContext(stats, TimeZone.of("UTC-03:00"))
        )
    }
}
