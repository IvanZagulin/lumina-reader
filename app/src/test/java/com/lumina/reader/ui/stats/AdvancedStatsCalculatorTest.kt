package com.lumina.reader.ui.stats

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStats
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTime::class)
class AdvancedStatsCalculatorTest {
    private val zone = TimeZone.of("Europe/Moscow")
    private val now = millis("2026-08-22T12:00:00Z")

    @Test
    fun `builds wrapped matrix sessions authors series and formats`() {
        val stats = listOf(
            session(1, "2026-08-22T08:00:00Z", 1_800, 5_000),
            session(1, "2026-08-21T20:00:00Z", 3_600, 8_000),
            session(2, "2026-08-16T14:00:00Z", 900, 2_000),
            session(2, "2026-07-20T14:00:00Z", 1_200, 2_500)
        )
        val books = listOf(
            book(1, "Первая", "Автор А", BookFormat.EPUB).copy(
                seriesName = "Цикл",
                isCompleted = true,
                currentProgressPercent = 100f,
                startedAt = millis("2026-08-01T12:00:00Z"),
                completedAt = millis("2026-08-22T10:00:00Z")
            ),
            book(2, "Вторая", "Автор Б", BookFormat.PDF).copy(
                seriesName = "Цикл",
                currentProgressPercent = 40f,
                lastReadTimestamp = millis("2026-07-20T14:00:00Z")
            )
        )

        val result = AdvancedStatsCalculator.calculate(stats, books, now, zone)

        assertEquals(12, result.monthlyWrapped.size)
        assertEquals(3, result.yearlyWrapped.size)
        assertEquals(28, result.rhythmMatrix.size)
        assertEquals(4, result.sessionAnalytics.total)
        assertEquals(2, result.formatStats.size)
        assertEquals(1, result.seriesStats.size)
        assertEquals(2, result.authorStats.size)
        assertNotNull(result.monthlyWrapped.last().bestDay)
        assertTrue(result.regularity.score in 0..100)
        assertTrue(result.predictions.isNotEmpty())
    }

    @Test
    fun `detects abandoned backlog and completion duration`() {
        val oldRead = millis("2026-06-01T12:00:00Z")
        val completed = book(1, "Готовая", "Автор", BookFormat.FB2).copy(
            isCompleted = true,
            currentProgressPercent = 100f,
            startedAt = millis("2026-08-01T12:00:00Z"),
            completedAt = millis("2026-08-10T12:00:00Z")
        )
        val abandoned = book(2, "Брошенная", "Автор", BookFormat.TXT).copy(
            currentProgressPercent = 35f,
            lastReadTimestamp = oldRead
        )
        val result = AdvancedStatsCalculator.calculate(
            listOf(
                session(1, "2026-08-03T12:00:00Z", 3_600, 7_000),
                session(1, "2026-08-10T12:00:00Z", 3_600, 7_000),
                session(2, "2026-06-01T12:00:00Z", 600, 1_000)
            ),
            listOf(completed, abandoned),
            now,
            zone
        )

        assertEquals(1, result.abandonedBooks.size)
        assertEquals(1, result.backlog.unfinishedBooks)
        assertEquals(1, result.completionAnalytics.completedWithHistory)
        assertTrue(result.completionAnalytics.averageCalendarDays >= 1.0)
    }

    private fun session(bookId: Long, timestamp: String, seconds: Long, words: Int) = ReadingStats(
        id = timestamp.hashCode().toLong(),
        bookId = bookId,
        sessionDurationSeconds = seconds,
        wordsReadCount = words,
        timestamp = millis(timestamp)
    )

    private fun millis(isoInstant: String): Long = Instant.parse(isoInstant).toEpochMilliseconds()

    private fun book(id: Long, title: String, author: String, format: BookFormat) = Book(
        id = id,
        title = title,
        author = author,
        filePath = "/books/$id.${format.name.lowercase()}",
        format = format
    )
}
