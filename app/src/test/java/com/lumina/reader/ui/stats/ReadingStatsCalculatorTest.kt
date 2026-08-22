package com.lumina.reader.ui.stats

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.ReadingStats
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatsCalculatorTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli()

    @Test
    fun `calculates period totals streaks and reading habits`() {
        val stats = listOf(
            session(1, "2026-08-14T10:00:00Z", 600, 1_000),
            session(1, "2026-08-14T11:00:00Z", 300, 500),
            session(2, "2026-08-13T10:00:00Z", 1_200, 2_000),
            session(1, "2026-08-12T10:00:00Z", 60, 100),
            session(2, "2026-08-05T10:00:00Z", 180, 300)
        )

        val result = ReadingStatsCalculator.calculate(
            stats = stats,
            books = listOf(book(1, "Первая"), book(2, "Вторая")),
            nowMillis = now,
            zoneId = zone
        )

        assertEquals(900, result.today.durationSeconds)
        assertEquals(1_500, result.today.wordsRead)
        assertEquals(2, result.today.sessionCount)
        assertEquals(6, result.today.estimatedPages)
        assertEquals(2_160, result.sevenDays.durationSeconds)
        assertEquals(2_340, result.allTime.durationSeconds)
        assertEquals(3, result.currentStreakDays)
        assertEquals(3, result.bestStreakDays)
        assertEquals(4, result.activeReadingDays)
        assertEquals(370, result.dailyActivity.size)
        assertEquals(2, result.mostReadBooks.size)
        assertEquals("Вторая", result.mostReadBooks.first().title)
        assertTrue(result.readingRhythm.contains("Любимый день"))
        assertEquals(2, result.allTime.bookCount)
        assertEquals(0, result.ignoredSessionCount)
        assertEquals(24, result.hourlyActivity.size)
        assertEquals(7, result.weekdayActivity.size)
        assertEquals(12, result.monthlyActivity.size)
    }

    @Test
    fun `continues current streak from yesterday`() {
        val stats = listOf(
            session(1, "2026-08-13T10:00:00Z", 120, 200),
            session(1, "2026-08-12T10:00:00Z", 120, 200)
        )

        val result = ReadingStatsCalculator.calculate(stats, emptyList(), now, zone)

        assertEquals(2, result.currentStreakDays)
        assertEquals(2, result.bestStreakDays)
    }

    @Test
    fun `uses supplied timezone at day boundary`() {
        val boundaryNow = Instant.parse("2026-08-14T00:30:00Z").toEpochMilli()
        val result = ReadingStatsCalculator.calculate(
            stats = listOf(session(1, "2026-08-13T23:30:00Z", 90, 150)),
            books = emptyList(),
            nowMillis = boundaryNow,
            zoneId = ZoneId.of("Europe/Moscow")
        )

        assertEquals(1, result.today.sessionCount)
        assertEquals(1, result.currentStreakDays)
    }

    @Test
    fun `ignores malformed sessions without breaking valid totals`() {
        val valid = session(1, "2026-08-14T10:00:00Z", 60, 100)
        val stats = listOf(
            valid,
            valid.copy(id = 2, sessionDurationSeconds = -1),
            valid.copy(id = 3, sessionDurationSeconds = 90_000),
            valid.copy(id = 4, wordsReadCount = -20),
            valid.copy(id = 5, timestamp = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli())
        )

        val result = ReadingStatsCalculator.calculate(stats, emptyList(), now, zone)

        assertEquals(1, result.allTime.sessionCount)
        assertEquals(60, result.allTime.durationSeconds)
        assertEquals(100, result.allTime.wordsRead)
        assertEquals(4, result.ignoredSessionCount)
        assertTrue(result.recentSessions.isNotEmpty())
    }

    @Test
    fun `empty history still produces full heatmap history`() {
        val result = ReadingStatsCalculator.calculate(emptyList(), emptyList(), now, zone)

        assertEquals(0, result.allTime.sessionCount)
        assertEquals(0, result.currentStreakDays)
        assertEquals(0, result.bestStreakDays)
        assertEquals(370, result.dailyActivity.size)
        assertTrue(result.mostReadBooks.isEmpty())
        assertTrue(result.recentSessions.isEmpty())
    }

    @Test
    fun `uses completion timestamps for monthly and yearly book goals`() {
        val completedAt = Instant.parse("2026-08-10T12:00:00Z").toEpochMilli()
        val completedBook = book(1, "Готовая").copy(
            isCompleted = true,
            currentProgressPercent = 100f,
            completedAt = completedAt
        )

        val result = ReadingStatsCalculator.calculate(
            stats = listOf(session(1, "2026-08-10T10:00:00Z", 600, 1_000)),
            books = listOf(completedBook),
            nowMillis = now,
            zoneId = zone,
            goalSettings = StatsGoalSettings(yearlyBooksTarget = 12)
        )

        assertEquals(1, result.completedBooksThisYear)
        assertTrue(result.yearlyGoalProgress > 0f)
        assertEquals(1, result.monthlyActivity.last().completedBooks)
        assertNotNull(result.bookOfMonth)
    }

    private fun session(
        bookId: Long,
        timestamp: String,
        durationSeconds: Long,
        words: Int
    ) = ReadingStats(
        id = timestamp.hashCode().toLong(),
        bookId = bookId,
        sessionDurationSeconds = durationSeconds,
        wordsReadCount = words,
        timestamp = Instant.parse(timestamp).toEpochMilli()
    )

    private fun book(id: Long, title: String) = Book(
        id = id,
        title = title,
        author = "Автор",
        filePath = "/books/$id.epub"
    )
}
