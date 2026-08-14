package com.lumina.reader.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.ReadingStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

enum class StatsPeriod {
    TODAY,
    SEVEN_DAYS,
    ALL_TIME
}

data class ReadingSummary(
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0,
    val bookCount: Int = 0
) {
    val estimatedPages: Long
        get() = if (wordsRead == 0L) 0 else (wordsRead + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE

    companion object {
        private const val WORDS_PER_PAGE = 250L
    }
}

data class DailyReadingActivity(
    val date: LocalDate,
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0
)

data class BookReadingSummary(
    val bookId: Long,
    val title: String,
    val author: String,
    val durationSeconds: Long,
    val wordsRead: Long,
    val sessionCount: Int,
    val lastReadTimestamp: Long
)

data class RecentReadingSession(
    val id: Long,
    val bookTitle: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val wordsRead: Long
)

data class ReadingStatsUiState(
    val isLoading: Boolean = true,
    val today: ReadingSummary = ReadingSummary(),
    val sevenDays: ReadingSummary = ReadingSummary(),
    val allTime: ReadingSummary = ReadingSummary(),
    val dailyActivity: List<DailyReadingActivity> = emptyList(),
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    val activeReadingDays: Int = 0,
    val averageSessionSeconds: Long = 0,
    val longestSessionSeconds: Long = 0,
    val averageWordsPerMinute: Int = 0,
    val readingRhythm: String = "Пока собираем ваш ритм",
    val mostReadBooks: List<BookReadingSummary> = emptyList(),
    val recentSessions: List<RecentReadingSession> = emptyList(),
    val libraryBookCount: Int = 0,
    val readingBookCount: Int = 0,
    val completedBookCount: Int = 0,
    val favoriteBookCount: Int = 0,
    val shelfCount: Int = 0,
    val seriesCount: Int = 0,
    val averageLibraryProgress: Int = 0,
    val ignoredSessionCount: Int = 0,
    val nextAchievementTitle: String = "Первая глава пути",
    val nextAchievementDetail: String = "Читайте, чтобы открыть достижение",
    val nextAchievementProgress: Float = 0f
) {
    fun summary(period: StatsPeriod): ReadingSummary = when (period) {
        StatsPeriod.TODAY -> today
        StatsPeriod.SEVEN_DAYS -> sevenDays
        StatsPeriod.ALL_TIME -> allTime
    }
}

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)

    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            // Keeps "today" and the rolling seven-day window fresh while this screen is open.
            delay(CLOCK_REFRESH_MILLIS)
        }
    }

    val uiState: StateFlow<ReadingStatsUiState> = combine(
        database.readingStatsDao().getAllStats(),
        database.bookDao().getAllBooks(),
        clock
    ) { stats, books, nowMillis ->
        ReadingStatsCalculator.calculate(
            stats = stats,
            books = books,
            nowMillis = nowMillis,
            zoneId = ZoneId.systemDefault()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ReadingStatsUiState()
    )

    private companion object {
        const val CLOCK_REFRESH_MILLIS = 60_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Pure calculations kept outside Compose so the figures stay consistent and testable. */
internal object ReadingStatsCalculator {
    private const val MAX_SESSION_SECONDS = 24L * 60L * 60L
    private const val MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L
    private const val RECENT_SESSION_LIMIT = 8
    private const val TOP_BOOK_LIMIT = 5

    fun calculate(
        stats: List<ReadingStats>,
        books: List<Book>,
        nowMillis: Long,
        zoneId: ZoneId
    ): ReadingStatsUiState {
        val oldestAcceptedTimestamp = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
        val newestAcceptedTimestamp = nowMillis + MAX_FUTURE_SKEW_MILLIS
        val validStats = stats.asSequence()
            .filter {
                it.timestamp in oldestAcceptedTimestamp..newestAcceptedTimestamp &&
                    it.sessionDurationSeconds in 1..MAX_SESSION_SECONDS &&
                    it.wordsReadCount >= 0
            }
            .sortedByDescending(ReadingStats::timestamp)
            .toList()

        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val sevenDayStart = today.minusDays(6)
        val sessionsByDate = validStats.groupBy { it.localDate(zoneId) }
        val todayStats = sessionsByDate[today].orEmpty()
        val sevenDayStats = validStats.filter { !it.localDate(zoneId).isBefore(sevenDayStart) }
        val readingDates = sessionsByDate.keys.sorted()

        val dailyActivity = (6L downTo 0L).map { daysAgo ->
            val date = today.minusDays(daysAgo)
            val sessions = sessionsByDate[date].orEmpty()
            DailyReadingActivity(
                date = date,
                durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                sessionCount = sessions.size
            )
        }

        val bookById = books.associateBy(Book::id)
        val mostReadBooks = validStats
            .groupBy(ReadingStats::bookId)
            .map { (bookId, sessions) ->
                val book = bookById[bookId]
                BookReadingSummary(
                    bookId = bookId,
                    title = book?.title?.takeIf(String::isNotBlank) ?: "Удалённая книга",
                    author = book?.author?.takeIf(String::isNotBlank).orEmpty(),
                    durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                    wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                    sessionCount = sessions.size,
                    lastReadTimestamp = sessions.maxOf { it.timestamp }
                )
            }
            .sortedWith(
                compareByDescending<BookReadingSummary> { it.durationSeconds }
                    .thenByDescending { it.wordsRead }
                    .thenByDescending { it.lastReadTimestamp }
            )
            .take(TOP_BOOK_LIMIT)

        val recentSessions = validStats.take(RECENT_SESSION_LIMIT).map { session ->
            RecentReadingSession(
                id = session.id,
                bookTitle = bookById[session.bookId]?.title?.takeIf(String::isNotBlank)
                    ?: "Удалённая книга",
                timestamp = session.timestamp,
                durationSeconds = session.sessionDurationSeconds,
                wordsRead = session.wordsReadCount.toLong()
            )
        }

        val sessionsWithMeasuredWords = validStats.filter { it.wordsReadCount > 0 }
        val measuredSeconds = sessionsWithMeasuredWords.sumOf { it.sessionDurationSeconds }
        val measuredWords = sessionsWithMeasuredWords.sumOf { it.wordsReadCount.toLong() }
        val averageWordsPerMinute = if (measuredSeconds == 0L) {
            0
        } else {
            (measuredWords.toDouble() * 60.0 / measuredSeconds.toDouble())
                .toInt()
                .coerceAtLeast(0)
        }

        val favoritePartOfDay = validStats
            .groupBy { it.partOfDay(zoneId) }
            .maxByOrNull { (_, sessions) -> sessions.sumOf { it.sessionDurationSeconds } }
            ?.key

        val completedBookCount = books.count { it.isCompleted || it.currentProgressPercent >= 99f }
        val achievement = when {
            currentStreak(readingDates.toSet(), today) < 7 -> AchievementProgress(
                title = "Неделя с книгой",
                detail = "Читайте 7 дней подряд",
                current = currentStreak(readingDates.toSet(), today),
                target = 7
            )
            completedBookCount < 5 -> AchievementProgress(
                title = "Пять завершённых книг",
                detail = "Отметьте пять книг прочитанными",
                current = completedBookCount,
                target = 5
            )
            else -> AchievementProgress(
                title = "Книжный марафон",
                detail = "Прочитайте 10 000 слов за неделю",
                current = sevenDayStats.sumOf { it.wordsReadCount.toLong() }.coerceAtMost(10_000).toInt(),
                target = 10_000
            )
        }

        return ReadingStatsUiState(
            isLoading = false,
            today = summaryOf(todayStats),
            sevenDays = summaryOf(sevenDayStats),
            allTime = summaryOf(validStats),
            dailyActivity = dailyActivity,
            currentStreakDays = currentStreak(readingDates.toSet(), today),
            bestStreakDays = bestStreak(readingDates),
            activeReadingDays = readingDates.size,
            averageSessionSeconds = if (validStats.isEmpty()) 0 else {
                validStats.sumOf { it.sessionDurationSeconds } / validStats.size
            },
            longestSessionSeconds = validStats.maxOfOrNull { it.sessionDurationSeconds } ?: 0,
            averageWordsPerMinute = averageWordsPerMinute,
            readingRhythm = favoritePartOfDay?.label ?: "Пока собираем ваш ритм",
            mostReadBooks = mostReadBooks,
            recentSessions = recentSessions,
            libraryBookCount = books.size,
            readingBookCount = books.count {
                it.currentProgressPercent > 0f && !it.isCompleted && it.currentProgressPercent < 99f
            },
            completedBookCount = completedBookCount,
            favoriteBookCount = books.count(Book::isFavorite),
            shelfCount = books.map { it.collection.trim() }.filter(String::isNotEmpty).distinct().size,
            seriesCount = books.map { it.seriesName.trim() }.filter(String::isNotEmpty).distinct().size,
            averageLibraryProgress = if (books.isEmpty()) 0 else {
                books.map { it.currentProgressPercent.coerceIn(0f, 100f) }.average().toInt()
            },
            ignoredSessionCount = stats.size - validStats.size,
            nextAchievementTitle = achievement.title,
            nextAchievementDetail = achievement.detail,
            nextAchievementProgress = achievement.current.toFloat() / achievement.target.toFloat()
        )
    }

    private fun summaryOf(stats: List<ReadingStats>) = ReadingSummary(
        durationSeconds = stats.sumOf { it.sessionDurationSeconds },
        wordsRead = stats.sumOf { it.wordsReadCount.toLong() },
        sessionCount = stats.size,
        bookCount = stats.map(ReadingStats::bookId).distinct().size
    )

    private fun currentStreak(readingDates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in readingDates -> today
            today.minusDays(1) in readingDates -> today.minusDays(1)
            else -> return 0
        }
        var result = 0
        while (cursor in readingDates) {
            result++
            cursor = cursor.minusDays(1)
        }
        return result
    }

    private fun bestStreak(readingDates: List<LocalDate>): Int {
        if (readingDates.isEmpty()) return 0
        var best = 1
        var current = 1
        readingDates.zipWithNext().forEach { (previous, next) ->
            if (next == previous.plusDays(1)) {
                current++
                best = maxOf(best, current)
            } else if (next != previous) {
                current = 1
            }
        }
        return best
    }

    private fun ReadingStats.localDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()

    private fun ReadingStats.partOfDay(zoneId: ZoneId): PartOfDay {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zoneId).hour
        return when (hour) {
            in 5..11 -> PartOfDay.MORNING
            in 12..17 -> PartOfDay.DAY
            in 18..22 -> PartOfDay.EVENING
            else -> PartOfDay.NIGHT
        }
    }

    private enum class PartOfDay(val label: String) {
        MORNING("Чаще читаете утром"),
        DAY("Чаще читаете днём"),
        EVENING("Чаще читаете вечером"),
        NIGHT("Чаще читаете ночью")
    }

    private data class AchievementProgress(
        val title: String,
        val detail: String,
        val current: Int,
        val target: Int
    )
}
