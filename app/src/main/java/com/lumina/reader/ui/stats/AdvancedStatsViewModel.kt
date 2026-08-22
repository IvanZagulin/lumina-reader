package com.lumina.reader.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

enum class AdvancedDayPart(val title: String) {
    MORNING("Утро"),
    DAY("День"),
    EVENING("Вечер"),
    NIGHT("Ночь")
}

data class WrappedSummary(
    val key: String,
    val title: String,
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val completedBooks: Int = 0,
    val activeDays: Int = 0,
    val averageWpm: Int = 0,
    val topBookTitle: String = "—",
    val bestDay: LocalDate? = null,
    val bestDaySeconds: Long = 0,
    val comparisonDurationPercent: Int? = null
) {
    val estimatedPages: Long
        get() = if (wordsRead <= 0) 0 else (wordsRead + 249) / 250
}

data class RhythmMatrixCell(
    val dayOfWeek: DayOfWeek,
    val part: AdvancedDayPart,
    val durationSeconds: Long
)

data class RegularityScore(
    val score: Int = 0,
    val label: String = "Случайный",
    val activeDaysPerWeek: Double = 0.0,
    val longestGapDays: Int = 0
)

data class SessionBucket(val label: String, val count: Int)

data class SessionAnalytics(
    val total: Int = 0,
    val averageSeconds: Long = 0,
    val medianSeconds: Long = 0,
    val shortestSeconds: Long = 0,
    val longestSeconds: Long = 0,
    val over30Minutes: Int = 0,
    val over60Minutes: Int = 0,
    val over120Minutes: Int = 0,
    val buckets: List<SessionBucket> = emptyList()
)

data class DayPartSpeed(
    val part: AdvancedDayPart,
    val wordsPerMinute: Int = 0,
    val durationSeconds: Long = 0
)

data class BestMonthRecords(
    val timeMonth: YearMonth? = null,
    val timeSeconds: Long = 0,
    val wordsMonth: YearMonth? = null,
    val words: Long = 0,
    val booksMonth: YearMonth? = null,
    val books: Int = 0,
    val activeDaysMonth: YearMonth? = null,
    val activeDays: Int = 0
)

data class FormatReadingStats(
    val format: BookFormat,
    val durationSeconds: Long,
    val bookCount: Int,
    val completedCount: Int,
    val averageSessionSeconds: Long
)

data class SeriesReadingStats(
    val name: String,
    val totalBooks: Int,
    val completedBooks: Int,
    val durationSeconds: Long,
    val progressPercent: Int,
    val isComplete: Boolean
)

data class AuthorReadingStats(
    val name: String,
    val bookCount: Int,
    val durationSeconds: Long,
    val wordsRead: Long
)

data class CompletionAnalytics(
    val completedWithHistory: Int = 0,
    val averageReadingSeconds: Long = 0,
    val averageCalendarDays: Double = 0.0,
    val fastestTitle: String = "—",
    val fastestSeconds: Long = 0,
    val slowestTitle: String = "—",
    val slowestSeconds: Long = 0
)

data class AbandonedBookStats(
    val bookId: Long,
    val title: String,
    val author: String,
    val progressPercent: Int,
    val daysSinceRead: Long
)

data class BacklogStats(
    val unfinishedBooks: Int = 0,
    val estimatedMonths: Int? = null,
    val completedPerMonth: Double = 0.0
)

data class PredictionItem(
    val title: String,
    val value: String,
    val detail: String
)

data class HabitSignals(
    val nightSessionCount: Int = 0,
    val morningSessionCount: Int = 0,
    val beforeSixSessionCount: Int = 0,
    val crossMidnightSessionCount: Int = 0,
    val distinctHoursRead: Int = 0,
    val distinctWeekdaysRead: Int = 0,
    val comebackAfter7Days: Int = 0,
    val comebackAfter30Days: Int = 0,
    val comebackAfter90Days: Int = 0,
    val maxWeekendSeconds: Long = 0,
    val returnedAndCompletedBooks: Int = 0,
    val completedSeriesCount: Int = 0,
    val maxSeriesSizeCompleted: Int = 0,
    val maxConsecutiveSeriesCompletions: Int = 0,
    val distinctAuthors: Int = 0,
    val completedFormats: Int = 0,
    val longestNightSessionSeconds: Long = 0
)

data class AdvancedStatsUiState(
    val isLoading: Boolean = true,
    val monthlyWrapped: List<WrappedSummary> = emptyList(),
    val yearlyWrapped: List<WrappedSummary> = emptyList(),
    val rhythmMatrix: List<RhythmMatrixCell> = emptyList(),
    val regularity: RegularityScore = RegularityScore(),
    val sessionAnalytics: SessionAnalytics = SessionAnalytics(),
    val speedByDayPart: List<DayPartSpeed> = emptyList(),
    val bestMonths: BestMonthRecords = BestMonthRecords(),
    val formatStats: List<FormatReadingStats> = emptyList(),
    val seriesStats: List<SeriesReadingStats> = emptyList(),
    val authorStats: List<AuthorReadingStats> = emptyList(),
    val completionAnalytics: CompletionAnalytics = CompletionAnalytics(),
    val abandonedBooks: List<AbandonedBookStats> = emptyList(),
    val backlog: BacklogStats = BacklogStats(),
    val predictions: List<PredictionItem> = emptyList(),
    val habits: HabitSignals = HabitSignals()
)

class AdvancedStatsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val uiState: StateFlow<AdvancedStatsUiState> = combine(
        database.readingStatsDao().getAllStats(),
        database.bookDao().getAllBooks(),
        clock
    ) { stats, books, now ->
        AdvancedStatsCalculator.calculate(stats, books, now, ZoneId.systemDefault())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AdvancedStatsUiState()
    )
}

internal object AdvancedStatsCalculator {
    private const val MAX_SESSION_SECONDS = 86_400L
    private const val MAX_FUTURE_SKEW = 5L * 60L * 1_000L

    fun calculate(
        stats: List<ReadingStats>,
        books: List<Book>,
        nowMillis: Long,
        zoneId: ZoneId
    ): AdvancedStatsUiState {
        val oldest = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
        val valid = stats.filter {
            it.timestamp in oldest..(nowMillis + MAX_FUTURE_SKEW) &&
                it.sessionDurationSeconds in 1..MAX_SESSION_SECONDS &&
                it.wordsReadCount >= 0
        }.sortedBy { it.timestamp }
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val bookById = books.associateBy(Book::id)
        val sessionsByBook = valid.groupBy(ReadingStats::bookId)
        val sessionsByDate = valid.groupBy { it.localDate(zoneId) }

        val monthlyWrapped = buildMonthlyWrapped(valid, books, bookById, today, zoneId)
        val yearlyWrapped = buildYearlyWrapped(valid, books, bookById, today, zoneId)
        val rhythmMatrix = DayOfWeek.entries.flatMap { day ->
            AdvancedDayPart.entries.map { part ->
                RhythmMatrixCell(
                    dayOfWeek = day,
                    part = part,
                    durationSeconds = valid.filter {
                        it.localDate(zoneId).dayOfWeek == day && it.dayPart(zoneId) == part
                    }.sumOf(ReadingStats::sessionDurationSeconds)
                )
            }
        }

        val regularity = buildRegularity(valid, today, zoneId)
        val sessionAnalytics = buildSessionAnalytics(valid)
        val speedByDayPart = AdvancedDayPart.entries.map { part ->
            val sessions = valid.filter { it.dayPart(zoneId) == part }
            val measured = sessions.filter { it.wordsReadCount > 0 }
            val seconds = measured.sumOf(ReadingStats::sessionDurationSeconds)
            val words = measured.sumOf { it.wordsReadCount.toLong() }
            DayPartSpeed(
                part = part,
                wordsPerMinute = if (seconds <= 0) 0 else
                    (words * 60.0 / seconds.toDouble()).roundToInt().coerceIn(0, 1_500),
                durationSeconds = sessions.sumOf(ReadingStats::sessionDurationSeconds)
            )
        }

        val bestMonths = buildBestMonths(valid, books, zoneId)
        val formatStats = books.groupBy(Book::format).map { (format, formatBooks) ->
            val ids = formatBooks.map(Book::id).toSet()
            val sessions = valid.filter { it.bookId in ids }
            FormatReadingStats(
                format = format,
                durationSeconds = sessions.sumOf(ReadingStats::sessionDurationSeconds),
                bookCount = formatBooks.size,
                completedCount = formatBooks.count(Book::isDone),
                averageSessionSeconds = if (sessions.isEmpty()) 0 else
                    sessions.sumOf(ReadingStats::sessionDurationSeconds) / sessions.size
            )
        }.sortedByDescending(FormatReadingStats::durationSeconds)

        val seriesStats = books.filter { it.seriesName.isNotBlank() }
            .groupBy { it.seriesName.trim() }
            .map { (name, seriesBooks) ->
                val ids = seriesBooks.map(Book::id).toSet()
                val completed = seriesBooks.count(Book::isDone)
                val progress = if (seriesBooks.isEmpty()) 0 else
                    seriesBooks.map { if (it.isDone()) 100f else it.currentProgressPercent.coerceIn(0f, 100f) }
                        .average().roundToInt()
                SeriesReadingStats(
                    name = name,
                    totalBooks = seriesBooks.size,
                    completedBooks = completed,
                    durationSeconds = valid.filter { it.bookId in ids }
                        .sumOf(ReadingStats::sessionDurationSeconds),
                    progressPercent = progress,
                    isComplete = seriesBooks.isNotEmpty() && completed == seriesBooks.size
                )
            }.sortedWith(
                compareByDescending<SeriesReadingStats> { it.durationSeconds }
                    .thenByDescending { it.completedBooks }
            )

        val authorStats = books.filter { it.author.isNotBlank() }
            .groupBy { it.author.trim() }
            .map { (author, authorBooks) ->
                val ids = authorBooks.map(Book::id).toSet()
                val sessions = valid.filter { it.bookId in ids }
                AuthorReadingStats(
                    name = author,
                    bookCount = authorBooks.size,
                    durationSeconds = sessions.sumOf(ReadingStats::sessionDurationSeconds),
                    wordsRead = sessions.sumOf { it.wordsReadCount.toLong() }
                )
            }.sortedByDescending(AuthorReadingStats::durationSeconds)

        val completionAnalytics = buildCompletionAnalytics(books, sessionsByBook, zoneId)
        val abandoned = books.asSequence()
            .filter { !it.isDone() && it.currentProgressPercent > 0f }
            .map {
                AbandonedBookStats(
                    bookId = it.id,
                    title = it.title,
                    author = it.author,
                    progressPercent = it.currentProgressPercent.roundToInt(),
                    daysSinceRead = ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(it.lastReadTimestamp).atZone(zoneId).toLocalDate(),
                        today
                    ).coerceAtLeast(0)
                )
            }
            .filter { it.daysSinceRead >= 30 }
            .sortedByDescending(AbandonedBookStats::daysSinceRead)
            .toList()

        val completedLast180 = books.count { book ->
            book.completedAt?.let { completedAt ->
                val date = Instant.ofEpochMilli(completedAt).atZone(zoneId).toLocalDate()
                !date.isBefore(today.minusDays(179)) && !date.isAfter(today)
            } == true
        }
        val completionRate = completedLast180 / 6.0
        val unfinished = books.count { !it.isDone() }
        val backlog = BacklogStats(
            unfinishedBooks = unfinished,
            estimatedMonths = if (completionRate > 0.05) (unfinished / completionRate).roundToInt().coerceAtLeast(1) else null,
            completedPerMonth = completionRate
        )

        val habits = buildHabitSignals(valid, books, seriesStats, zoneId)
        val predictions = buildPredictions(valid, books, today, zoneId)

        return AdvancedStatsUiState(
            isLoading = false,
            monthlyWrapped = monthlyWrapped,
            yearlyWrapped = yearlyWrapped,
            rhythmMatrix = rhythmMatrix,
            regularity = regularity,
            sessionAnalytics = sessionAnalytics,
            speedByDayPart = speedByDayPart,
            bestMonths = bestMonths,
            formatStats = formatStats,
            seriesStats = seriesStats,
            authorStats = authorStats,
            completionAnalytics = completionAnalytics,
            abandonedBooks = abandoned,
            backlog = backlog,
            predictions = predictions,
            habits = habits
        )
    }

    private fun buildMonthlyWrapped(
        stats: List<ReadingStats>,
        books: List<Book>,
        bookById: Map<Long, Book>,
        today: LocalDate,
        zoneId: ZoneId
    ): List<WrappedSummary> {
        val current = YearMonth.from(today)
        return (11 downTo 0).map { offset ->
            val month = current.minusMonths(offset.toLong())
            val monthStats = stats.filter { YearMonth.from(it.localDate(zoneId)) == month }
            val previous = stats.filter {
                YearMonth.from(it.localDate(zoneId)) == month.minusMonths(1)
            }
            val byDate = monthStats.groupBy { it.localDate(zoneId) }
            val best = byDate.maxByOrNull { (_, sessions) ->
                sessions.sumOf(ReadingStats::sessionDurationSeconds)
            }
            val topBook = monthStats.groupBy(ReadingStats::bookId)
                .maxByOrNull { (_, sessions) -> sessions.sumOf(ReadingStats::sessionDurationSeconds) }
                ?.key?.let(bookById::get)?.title ?: "—"
            val measuredSeconds = monthStats.filter { it.wordsReadCount > 0 }
                .sumOf(ReadingStats::sessionDurationSeconds)
            val measuredWords = monthStats.filter { it.wordsReadCount > 0 }
                .sumOf { it.wordsReadCount.toLong() }
            val duration = monthStats.sumOf(ReadingStats::sessionDurationSeconds)
            val prevDuration = previous.sumOf(ReadingStats::sessionDurationSeconds)
            WrappedSummary(
                key = month.toString(),
                title = month.toString(),
                durationSeconds = duration,
                wordsRead = monthStats.sumOf { it.wordsReadCount.toLong() },
                completedBooks = books.count {
                    it.completedAt?.let { ts -> YearMonth.from(ts.localDate(zoneId)) == month } == true
                },
                activeDays = byDate.size,
                averageWpm = if (measuredSeconds == 0L) 0 else
                    (measuredWords * 60.0 / measuredSeconds).roundToInt(),
                topBookTitle = topBook,
                bestDay = best?.key,
                bestDaySeconds = best?.value?.sumOf(ReadingStats::sessionDurationSeconds) ?: 0,
                comparisonDurationPercent = percentChange(duration, prevDuration)
            )
        }
    }

    private fun buildYearlyWrapped(
        stats: List<ReadingStats>,
        books: List<Book>,
        bookById: Map<Long, Book>,
        today: LocalDate,
        zoneId: ZoneId
    ): List<WrappedSummary> {
        return (2 downTo 0).map { offset ->
            val year = today.year - offset
            val yearStats = stats.filter { it.localDate(zoneId).year == year }
            val previous = stats.filter { it.localDate(zoneId).year == year - 1 }
            val byDate = yearStats.groupBy { it.localDate(zoneId) }
            val best = byDate.maxByOrNull { (_, sessions) ->
                sessions.sumOf(ReadingStats::sessionDurationSeconds)
            }
            val topBook = yearStats.groupBy(ReadingStats::bookId)
                .maxByOrNull { (_, sessions) -> sessions.sumOf(ReadingStats::sessionDurationSeconds) }
                ?.key?.let(bookById::get)?.title ?: "—"
            val measured = yearStats.filter { it.wordsReadCount > 0 }
            val measuredSeconds = measured.sumOf(ReadingStats::sessionDurationSeconds)
            val measuredWords = measured.sumOf { it.wordsReadCount.toLong() }
            val duration = yearStats.sumOf(ReadingStats::sessionDurationSeconds)
            val previousDuration = previous.sumOf(ReadingStats::sessionDurationSeconds)
            WrappedSummary(
                key = year.toString(),
                title = year.toString(),
                durationSeconds = duration,
                wordsRead = yearStats.sumOf { it.wordsReadCount.toLong() },
                completedBooks = books.count {
                    it.completedAt?.let { ts -> ts.localDate(zoneId).year == year } == true
                },
                activeDays = byDate.size,
                averageWpm = if (measuredSeconds == 0L) 0 else
                    (measuredWords * 60.0 / measuredSeconds).roundToInt(),
                topBookTitle = topBook,
                bestDay = best?.key,
                bestDaySeconds = best?.value?.sumOf(ReadingStats::sessionDurationSeconds) ?: 0,
                comparisonDurationPercent = percentChange(duration, previousDuration)
            )
        }
    }

    private fun buildRegularity(
        stats: List<ReadingStats>,
        today: LocalDate,
        zoneId: ZoneId
    ): RegularityScore {
        if (stats.isEmpty()) return RegularityScore()
        val first = stats.first().localDate(zoneId)
        val windowStart = maxOf(first, today.minusDays(89))
        val windowDays = (ChronoUnit.DAYS.between(windowStart, today) + 1).coerceAtLeast(1)
        val activeDates = stats.map { it.localDate(zoneId) }
            .filter { it in windowStart..today }
            .distinct().sorted()
        val activeRatio = activeDates.size.toDouble() / windowDays.toDouble()
        val gaps = activeDates.zipWithNext().map { (a, b) ->
            (ChronoUnit.DAYS.between(a, b) - 1).toInt().coerceAtLeast(0)
        }
        val longestGap = gaps.maxOrNull() ?: 0
        val recentStreak = currentStreak(activeDates.toSet(), today)
        val score = (
            activeRatio * 70.0 +
                (20 - longestGap.coerceAtMost(20)) +
                recentStreak.coerceAtMost(10)
            ).roundToInt().coerceIn(0, 100)
        val label = when {
            score >= 90 -> "Железный режим"
            score >= 70 -> "Регулярный"
            score >= 45 -> "Стабильный"
            else -> "Случайный"
        }
        return RegularityScore(
            score = score,
            label = label,
            activeDaysPerWeek = activeRatio * 7.0,
            longestGapDays = longestGap
        )
    }

    private fun buildSessionAnalytics(stats: List<ReadingStats>): SessionAnalytics {
        if (stats.isEmpty()) return SessionAnalytics(
            buckets = listOf(
                SessionBucket("0–10", 0), SessionBucket("10–30", 0),
                SessionBucket("30–60", 0), SessionBucket("60–120", 0), SessionBucket("120+", 0)
            )
        )
        val sorted = stats.map(ReadingStats::sessionDurationSeconds).sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        fun count(min: Long, maxExclusive: Long? = null) = sorted.count {
            it >= min && (maxExclusive == null || it < maxExclusive)
        }
        return SessionAnalytics(
            total = sorted.size,
            averageSeconds = sorted.sum() / sorted.size,
            medianSeconds = median,
            shortestSeconds = sorted.first(),
            longestSeconds = sorted.last(),
            over30Minutes = sorted.count { it >= 1_800 },
            over60Minutes = sorted.count { it >= 3_600 },
            over120Minutes = sorted.count { it >= 7_200 },
            buckets = listOf(
                SessionBucket("0–10", count(0, 600)),
                SessionBucket("10–30", count(600, 1_800)),
                SessionBucket("30–60", count(1_800, 3_600)),
                SessionBucket("60–120", count(3_600, 7_200)),
                SessionBucket("120+", count(7_200))
            )
        )
    }

    private fun buildBestMonths(
        stats: List<ReadingStats>,
        books: List<Book>,
        zoneId: ZoneId
    ): BestMonthRecords {
        if (stats.isEmpty() && books.none { it.completedAt != null }) return BestMonthRecords()
        val grouped = stats.groupBy { YearMonth.from(it.localDate(zoneId)) }
        val timeBest = grouped.maxByOrNull { (_, sessions) ->
            sessions.sumOf(ReadingStats::sessionDurationSeconds)
        }
        val wordsBest = grouped.maxByOrNull { (_, sessions) ->
            sessions.sumOf { it.wordsReadCount.toLong() }
        }
        val activeBest = grouped.maxByOrNull { (_, sessions) ->
            sessions.map { it.localDate(zoneId) }.distinct().size
        }
        val completedByMonth = books.mapNotNull { it.completedAt }
            .groupingBy { YearMonth.from(it.localDate(zoneId)) }.eachCount()
        val booksBest = completedByMonth.maxByOrNull(Map.Entry<YearMonth, Int>::value)
        return BestMonthRecords(
            timeMonth = timeBest?.key,
            timeSeconds = timeBest?.value?.sumOf(ReadingStats::sessionDurationSeconds) ?: 0,
            wordsMonth = wordsBest?.key,
            words = wordsBest?.value?.sumOf { it.wordsReadCount.toLong() } ?: 0,
            booksMonth = booksBest?.key,
            books = booksBest?.value ?: 0,
            activeDaysMonth = activeBest?.key,
            activeDays = activeBest?.value?.map { it.localDate(zoneId) }?.distinct()?.size ?: 0
        )
    }

    private fun buildCompletionAnalytics(
        books: List<Book>,
        sessionsByBook: Map<Long, List<ReadingStats>>,
        zoneId: ZoneId
    ): CompletionAnalytics {
        val completed = books.filter { it.isDone() && it.completedAt != null }
        if (completed.isEmpty()) return CompletionAnalytics()
        val withDurations = completed.map { book ->
            book to sessionsByBook[book.id].orEmpty().sumOf(ReadingStats::sessionDurationSeconds)
        }.filter { it.second > 0 }
        val calendarDays = completed.mapNotNull { book ->
            val start = book.startedAt ?: return@mapNotNull null
            val end = book.completedAt ?: return@mapNotNull null
            (ChronoUnit.DAYS.between(start.localDate(zoneId), end.localDate(zoneId)) + 1)
                .coerceAtLeast(1)
        }
        val fastest = withDurations.minByOrNull { it.second }
        val slowest = withDurations.maxByOrNull { it.second }
        return CompletionAnalytics(
            completedWithHistory = completed.size,
            averageReadingSeconds = if (withDurations.isEmpty()) 0 else
                withDurations.sumOf { it.second } / withDurations.size,
            averageCalendarDays = if (calendarDays.isEmpty()) 0.0 else calendarDays.average(),
            fastestTitle = fastest?.first?.title ?: "—",
            fastestSeconds = fastest?.second ?: 0,
            slowestTitle = slowest?.first?.title ?: "—",
            slowestSeconds = slowest?.second ?: 0
        )
    }

    private fun buildHabitSignals(
        stats: List<ReadingStats>,
        books: List<Book>,
        seriesStats: List<SeriesReadingStats>,
        zoneId: ZoneId
    ): HabitSignals {
        val sortedDates = stats.map { it.localDate(zoneId) }.distinct().sorted()
        val gaps = sortedDates.zipWithNext().map { (a, b) -> ChronoUnit.DAYS.between(a, b).toInt() }
        val weekendByWeek = stats.groupBy {
            val date = it.localDate(zoneId)
            val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
            monday
        }.mapValues { (_, sessions) ->
            sessions.filter {
                val d = it.localDate(zoneId).dayOfWeek
                d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
            }.sumOf(ReadingStats::sessionDurationSeconds)
        }

        val completedChronological = books.filter { it.isDone() && it.completedAt != null }
            .sortedBy { it.completedAt }
        var maxConsecutiveSeries = 0
        var currentSeries = ""
        var currentCount = 0
        completedChronological.forEach { book ->
            val series = book.seriesName.trim()
            if (series.isNotEmpty() && series.equals(currentSeries, ignoreCase = true)) {
                currentCount++
            } else {
                currentSeries = series
                currentCount = if (series.isEmpty()) 0 else 1
            }
            maxConsecutiveSeries = maxOf(maxConsecutiveSeries, currentCount)
        }

        val returnedAndCompleted = books.count { book ->
            if (!book.isDone()) return@count false
            val dates = stats.filter { it.bookId == book.id }.map { it.localDate(zoneId) }.distinct().sorted()
            dates.zipWithNext().any { (a, b) -> ChronoUnit.DAYS.between(a, b) >= 30 }
        }

        val completedFormats = books.filter(Book::isDone).map(Book::format).distinct().size
        val distinctAuthors = books.map { it.author.trim() }
            .filter { it.isNotEmpty() && !it.equals("Неизвестный автор", true) }.distinct().size

        val night = stats.filter { it.dayPart(zoneId) == AdvancedDayPart.NIGHT }
        return HabitSignals(
            nightSessionCount = night.size,
            morningSessionCount = stats.count { it.dayPart(zoneId) == AdvancedDayPart.MORNING },
            beforeSixSessionCount = stats.count { it.hour(zoneId) < 6 },
            crossMidnightSessionCount = stats.count { it.crossesMidnight(zoneId) },
            distinctHoursRead = stats.map { it.hour(zoneId) }.distinct().size,
            distinctWeekdaysRead = stats.map { it.localDate(zoneId).dayOfWeek }.distinct().size,
            comebackAfter7Days = gaps.count { it >= 7 },
            comebackAfter30Days = gaps.count { it >= 30 },
            comebackAfter90Days = gaps.count { it >= 90 },
            maxWeekendSeconds = weekendByWeek.values.maxOrNull() ?: 0,
            returnedAndCompletedBooks = returnedAndCompleted,
            completedSeriesCount = seriesStats.count(SeriesReadingStats::isComplete),
            maxSeriesSizeCompleted = seriesStats.filter(SeriesReadingStats::isComplete)
                .maxOfOrNull(SeriesReadingStats::totalBooks) ?: 0,
            maxConsecutiveSeriesCompletions = maxConsecutiveSeries,
            distinctAuthors = distinctAuthors,
            completedFormats = completedFormats,
            longestNightSessionSeconds = night.maxOfOrNull(ReadingStats::sessionDurationSeconds) ?: 0
        )
    }

    private fun buildPredictions(
        stats: List<ReadingStats>,
        books: List<Book>,
        today: LocalDate,
        zoneId: ZoneId
    ): List<PredictionItem> {
        val month = YearMonth.from(today)
        val monthStats = stats.filter { YearMonth.from(it.localDate(zoneId)) == month }
        val monthSeconds = monthStats.sumOf(ReadingStats::sessionDurationSeconds)
        val projectedMonthSeconds = if (today.dayOfMonth > 0) {
            (monthSeconds.toDouble() / today.dayOfMonth * month.lengthOfMonth()).toLong()
        } else 0L
        val completedThisYear = books.count {
            it.completedAt?.let { ts -> ts.localDate(zoneId).year == today.year } == true
        }
        val projectedBooks = if (completedThisYear == 0) 0 else
            (completedThisYear * today.lengthOfYear().toDouble() / today.dayOfYear).roundToInt()

        val recentStart = today.minusDays(29)
        val recent = stats.filter { it.localDate(zoneId) in recentStart..today }
        val dailySeconds = recent.sumOf(ReadingStats::sessionDurationSeconds) / 30.0
        val dailyWords = recent.sumOf { it.wordsReadCount.toLong() } / 30.0
        val totalWords = stats.sumOf { it.wordsReadCount.toLong() }
        val nextMillion = ((totalWords / 1_000_000L) + 1L) * 1_000_000L
        val wordsRemaining = (nextMillion - totalWords).coerceAtLeast(0)
        val daysToMillion = if (dailyWords > 1.0) (wordsRemaining / dailyWords).roundToInt().coerceAtLeast(1) else null
        val daysTo100Hours = if (dailySeconds > 1.0) (360_000.0 / dailySeconds).roundToInt().coerceAtLeast(1) else null

        return listOf(
            PredictionItem(
                "К концу месяца",
                formatDuration(projectedMonthSeconds),
                "если текущий темп сохранится"
            ),
            PredictionItem(
                "К концу года",
                "$projectedBooks книг",
                "по текущей скорости завершения"
            ),
            PredictionItem(
                "Следующие 100 часов",
                daysTo100Hours?.let { "≈ $it дн." } ?: "Недостаточно данных",
                "по среднему за последние 30 дней"
            ),
            PredictionItem(
                "Следующий миллион слов",
                daysToMillion?.let { "≈ $it дн." } ?: "Недостаточно данных",
                "цель: ${nextMillion / 1_000_000} млн слов"
            )
        )
    }

    private fun percentChange(current: Long, previous: Long): Int? {
        if (previous <= 0) return if (current > 0) 100 else null
        return (((current - previous).toDouble() / previous.toDouble()) * 100.0)
            .roundToInt().coerceIn(-999, 999)
    }

    private fun currentStreak(dates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var result = 0
        while (cursor in dates) {
            result++
            cursor = cursor.minusDays(1)
        }
        return result
    }

    private fun ReadingStats.localDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()

    private fun Long.localDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()

    private fun ReadingStats.hour(zoneId: ZoneId): Int =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).hour

    private fun ReadingStats.dayPart(zoneId: ZoneId): AdvancedDayPart = when (hour(zoneId)) {
        in 5..11 -> AdvancedDayPart.MORNING
        in 12..17 -> AdvancedDayPart.DAY
        in 18..22 -> AdvancedDayPart.EVENING
        else -> AdvancedDayPart.NIGHT
    }

    private fun ReadingStats.crossesMidnight(zoneId: ZoneId): Boolean {
        val end = Instant.ofEpochMilli(timestamp).atZone(zoneId)
        val start = Instant.ofEpochMilli(timestamp - sessionDurationSeconds * 1_000L).atZone(zoneId)
        return start.toLocalDate() != end.toLocalDate()
    }

    private fun Book.isDone(): Boolean = isCompleted || currentProgressPercent >= 99f

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0 мин"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
            hours > 0 -> "$hours ч"
            else -> "${minutes.coerceAtLeast(1)} мин"
        }
    }
}
