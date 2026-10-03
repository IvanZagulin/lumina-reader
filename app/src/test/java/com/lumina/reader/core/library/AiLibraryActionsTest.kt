package com.lumina.reader.core.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.core.network.AiMessage
import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.FoundPublication
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.ui.chat.AiAction
import com.lumina.reader.ui.chat.AiChatViewModel
import com.lumina.reader.ui.chat.buildLibraryContext
import com.lumina.reader.ui.chat.buildStatsContext
import com.lumina.reader.ui.chat.messagesForRequest
import com.lumina.reader.ui.chat.parseAiActions
import com.lumina.reader.ui.chat.pickBestPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The library side of the assistant: commands, book choice, context. */
class AiLibraryActionsTest {

    @Test
    fun parsesDownloadAndOrganizeCommandsDownloadsFirst() {
        val answer = """
            Начинаю загрузку серии.
            [ORGANIZE:Дюна:Дюна|Мессия Дюны]
            [DOWNLOAD: Дюна ]
            [DOWNLOAD:Мессия Дюны]
            [DOWNLOAD:Дюна]
        """.trimIndent()
        assertEquals(
            listOf(
                AiAction.DownloadBook("Дюна"),
                AiAction.DownloadBook("Мессия Дюны"),
                AiAction.OrganizeSeries("Дюна", listOf("Дюна", "Мессия Дюны"))
            ),
            parseAiActions(answer)
        )
        assertTrue(parseAiActions("Просто ответ без команд").isEmpty())
    }

    @Test
    fun appStatusMessagesAreNeverSentToTheModel() {
        val conversation = listOf(
            AiMessage("system", "instructions"),
            AiMessage("user", "Скачай Дюну"),
            AiMessage("assistant", "Ищу. [DOWNLOAD:Дюна]"),
            AiMessage(AiChatViewModel.ROLE_STATUS, "Ищу «Дюна» в каталогах…")
        )
        assertEquals(listOf("system", "user", "assistant"), messagesForRequest(conversation).map { it.role })
    }

    @Test
    fun picksAnExactTitleMatchThenContainsThenAnyDownloadable() {
        val catalog = BuiltInCatalogs.all.first()
        fun found(title: String, downloadable: Boolean = true) = FoundPublication(
            catalog,
            OpdsEntry.Publication(
                key = title,
                title = title,
                acquisitions = if (downloadable) listOf(OpdsAcquisition("https://x/$title", BookFormat.EPUB)) else emptyList()
            )
        )
        val results = listOf(found("Дети Дюны"), found("Дюна", downloadable = false), found("Дюна"), found("Другое"))
        assertEquals("https://x/Дюна", pickBestPublication("«дюна»", results)?.publication?.acquisitions?.single()?.url)
        assertEquals("Дети Дюны", pickBestPublication("дети", results)?.publication?.title)
        assertEquals("Дети Дюны", pickBestPublication("нет такой", results)?.publication?.title)
        assertNull(pickBestPublication("x", listOf(found("Скан", downloadable = false))))
    }

    @Test
    fun contextDescribesTheWholeLibraryAndStatistics() {
        val books = listOf(
            Book(id = 1, title = "Дюна", author = "Герберт", filePath = "a", seriesName = "Дюна", seriesOrder = 1),
            Book(id = 2, title = "Готово", author = "Автор", filePath = "b", isCompleted = true)
        )
        val library = buildLibraryContext(books)
        assertTrue(library.contains("- Дюна (Герберт) [Полка: Основная, Серия: Дюна #1, не начата]"))
        assertTrue(library.contains("Готово (Автор)") && library.contains("прочитана"))

        val day = 86_400_000L
        val stats = listOf(
            ReadingStats(bookId = 1, sessionDurationSeconds = 600, wordsReadCount = 2_000, timestamp = day),
            ReadingStats(bookId = 1, sessionDurationSeconds = 600, wordsReadCount = 1_000, timestamp = day + 1_000),
            ReadingStats(bookId = 1, sessionDurationSeconds = 0, wordsReadCount = 0, timestamp = 5 * day)
        )
        assertEquals(
            "Прочитано слов: 3000\nПрочитано страниц: 12\nСредний темп: 150 сл/мин\nДней активного чтения: 1",
            buildStatsContext(stats, ZoneOffset.UTC)
        )
    }
}
