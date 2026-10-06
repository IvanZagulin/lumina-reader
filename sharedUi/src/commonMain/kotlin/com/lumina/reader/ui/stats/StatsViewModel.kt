package com.lumina.reader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.platform.AppClock
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

enum class StatsPeriod {
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS,
    YEAR,
    ALL_TIME
}

enum class DailyGoalType {
    MINUTES,
    PAGES,
    WORDS
}

data class StatsGoalSettings(
    val dailyType: DailyGoalType = DailyGoalType.MINUTES,
    val dailyTarget: Int = 30,
    val yearlyBooksTarget: Int = 24
)

data class ReadingSummary(
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0,
    val bookCount: Int = 0
) {
    val estimatedPages: Long
        get() = if (wordsRead == 0L) 0 else (wordsRead + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE

    companion object {
        const val WORDS_PER_PAGE = 250L
    }
}

data class PeriodComparison(
    val durationPercent: Int? = null,
    val wordsPercent: Int? = null,
    val sessionsPercent: Int? = null
)

data class DailyReadingActivity(
    val date: LocalDate,
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0
) {
    val estimatedPages: Long
        get() = if (wordsRead == 0L) 0 else
            (wordsRead + ReadingSummary.WORDS_PER_PAGE - 1) / ReadingSummary.WORDS_PER_PAGE
}

data class MonthlyReadingActivity(
    val month: YearMonth,
    val durationSeconds: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0,
    val averageWordsPerMinute: Int = 0,
    val averageSessionSeconds: Long = 0,
    val completedBooks: Int = 0
)

data class HourlyReadingActivity(
    val hour: Int,
    val durationSeconds: Long = 0
)

data class WeekdayReadingActivity(
    val dayOfWeek: DayOfWeek,
    val durationSeconds: Long = 0
)

data class BookReadingSummary(
    val bookId: Long,
    val title: String,
    val author: String,
    val coverPath: String?,
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

data class ReaderProfile(
    val title: String = "Исследователь",
    val description: String = "Продолжайте читать, чтобы Lumina точнее определила ваш ритм.",
    val favoritePartOfDay: String = "Пока нет данных",
    val favoriteWeekday: String = "Пока нет данных"
)

data class PersonalRecords(
    val bestStreakDays: Int = 0,
    val longestSessionSeconds: Long = 0,
    val bestDayDate: LocalDate? = null,
    val bestDaySeconds: Long = 0,
    val maxWordsDay: Long = 0,
    val fastestStableWpm: Int = 0,
    val maxCompletedBooksMonth: Int = 0
)

data class ReadingStatsUiState(
    val isLoading: Boolean = true,
    val today: ReadingSummary = ReadingSummary(),
    val sevenDays: ReadingSummary = ReadingSummary(),
    val thirtyDays: ReadingSummary = ReadingSummary(),
    val year: ReadingSummary = ReadingSummary(),
    val allTime: ReadingSummary = ReadingSummary(),
    val comparisons: Map<StatsPeriod, PeriodComparison> = emptyMap(),
    val dailyActivity: List<DailyReadingActivity> = emptyList(),
    val monthlyActivity: List<MonthlyReadingActivity> = emptyList(),
    val hourlyActivity: List<HourlyReadingActivity> = emptyList(),
    val weekdayActivity: List<WeekdayReadingActivity> = emptyList(),
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    val goalStreakDays: Int = 0,
    val activeReadingDays: Int = 0,
    val averageSessionSeconds: Long = 0,
    val longestSessionSeconds: Long = 0,
    val averageWordsPerMinute: Int = 0,
    val readingRhythm: String = "Пока собираем ваш ритм",
    val readerProfile: ReaderProfile = ReaderProfile(),
    val personalRecords: PersonalRecords = PersonalRecords(),
    val mostReadBooks: List<BookReadingSummary> = emptyList(),
    val bookOfMonth: BookReadingSummary? = null,
    val recentSessions: List<RecentReadingSession> = emptyList(),
    val libraryBookCount: Int = 0,
    val readingBookCount: Int = 0,
    val completedBookCount: Int = 0,
    val unreadBookCount: Int = 0,
    val favoriteBookCount: Int = 0,
    val shelfCount: Int = 0,
    val seriesCount: Int = 0,
    val averageLibraryProgress: Int = 0,
    val completedBooksThisYear: Int = 0,
    val projectedBooksThisYear: Int = 0,
    val goalSettings: StatsGoalSettings = StatsGoalSettings(),
    val dailyGoalProgress: Float = 0f,
    val yearlyGoalProgress: Float = 0f,
    val ignoredSessionCount: Int = 0,
    val nextAchievementTitle: String = "Первая глава пути",
    val nextAchievementDetail: String = "Читайте, чтобы открыть достижение",
    val nextAchievementProgress: Float = 0f
) {
    fun summary(period: StatsPeriod): ReadingSummary = when (period) {
        StatsPeriod.TODAY -> today
        StatsPeriod.SEVEN_DAYS -> sevenDays
        StatsPeriod.THIRTY_DAYS -> thirtyDays
        StatsPeriod.YEAR -> year
        StatsPeriod.ALL_TIME -> allTime
    }

    fun comparison(period: StatsPeriod): PeriodComparison =
        comparisons[period] ?: PeriodComparison()
}

/**
 * The statistics tab. [services] come from [StatsServicesHolder] (installed by
 * `LuminaApp` on Android, built from the app's database on iOS); the default
 * argument also gives the class the no-argument constructor that Android's
 * reflective `viewModel()` factory needs.
 */
class StatsViewModel(
    services: StatsServices = StatsServicesHolder.services
) : ViewModel() {

    private val goalPreferences = StatsGoalPreferences(services.goalStore)
    private val goals = MutableStateFlow(goalPreferences.load())

    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(AppClock.nowMillis())
            delay(CLOCK_REFRESH_MILLIS)
        }
    }

    val uiState: StateFlow<ReadingStatsUiState> = combine(
        services.readingStatsDao.getAllStats(),
        services.bookDao.getAllBooks(),
        clock,
        goals
    ) { stats, books, nowMillis, goalSettings ->
        ReadingStatsCalculator.calculate(
            stats = stats,
            books = books,
            nowMillis = nowMillis,
            timeZone = TimeZone.currentSystemDefault(),
            goalSettings = goalSettings
        )
    }
        // A year of daily history over every session, once a minute: off the
        // main thread, where it would stutter scrolling (above all on iPhone).
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ReadingStatsUiState()
        )

    fun updateDailyGoal(type: DailyGoalType, target: Int) {
        val safeTarget = when (type) {
            DailyGoalType.MINUTES -> target.coerceIn(5, 600)
            DailyGoalType.PAGES -> target.coerceIn(1, 1_000)
            DailyGoalType.WORDS -> target.coerceIn(100, 250_000)
        }
        val updated = goals.value.copy(dailyType = type, dailyTarget = safeTarget)
        goalPreferences.save(updated)
        goals.value = updated
    }

    fun updateYearlyBooksGoal(target: Int) {
        val updated = goals.value.copy(yearlyBooksTarget = target.coerceIn(1, 500))
        goalPreferences.save(updated)
        goals.value = updated
    }

    private companion object {
        const val CLOCK_REFRESH_MILLIS = 60_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The reading goals in [store]: on Android the SharedPreferences file
 * "reading_stats_goals" with the keys it has always had, so users keep their
 * goals. A broken type value falls back to minutes.
 */
private class StatsGoalPreferences(private val store: StatsGoalStore) {

    fun load(): StatsGoalSettings {
        val type = runCatching {
            DailyGoalType.valueOf(
                store.getString(KEY_DAILY_TYPE) ?: DailyGoalType.MINUTES.name
            )
        }.getOrDefault(DailyGoalType.MINUTES)

        val defaultTarget = when (type) {
            DailyGoalType.MINUTES -> 30
            DailyGoalType.PAGES -> 20
            DailyGoalType.WORDS -> 5_000
        }
        return StatsGoalSettings(
            dailyType = type,
            dailyTarget = store.getInt(KEY_DAILY_TARGET, defaultTarget),
            yearlyBooksTarget = store.getInt(KEY_YEAR_TARGET, 24)
        )
    }

    fun save(settings: StatsGoalSettings) {
        store.putAll(
            strings = mapOf(KEY_DAILY_TYPE to settings.dailyType.name),
            ints = mapOf(
                KEY_DAILY_TARGET to settings.dailyTarget,
                KEY_YEAR_TARGET to settings.yearlyBooksTarget
            )
        )
    }

    private companion object {
        const val KEY_DAILY_TYPE = "daily_goal_type"
        const val KEY_DAILY_TARGET = "daily_goal_target"
        const val KEY_YEAR_TARGET = "yearly_books_target"
    }
}

object ReadingStatsCalculator {
    private const val MAX_SESSION_SECONDS = 24L * 60L * 60L
    private const val MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L
    private const val RECENT_SESSION_LIMIT = 8
    private const val TOP_BOOK_LIMIT = 5
    private const val HISTORY_DAYS = 370L

    /** 2000-01-01T00:00:00Z: older timestamps come from a broken clock, not from reading. */
    private const val OLDEST_ACCEPTED_TIMESTAMP = 946_684_800_000L

    fun calculate(
        stats: List<ReadingStats>,
        books: List<Book>,
        nowMillis: Long,
        timeZone: TimeZone,
        goalSettings: StatsGoalSettings = StatsGoalSettings()
    ): ReadingStatsUiState {
        val times = LocalTimes(timeZone)
        val oldestAcceptedTimestamp = OLDEST_ACCEPTED_TIMESTAMP
        val newestAcceptedTimestamp = nowMillis + MAX_FUTURE_SKEW_MILLIS
        val validStats = stats.asSequence()
            .filter {
                it.timestamp in oldestAcceptedTimestamp..newestAcceptedTimestamp &&
                    it.sessionDurationSeconds in 1..MAX_SESSION_SECONDS &&
                    it.wordsReadCount >= 0
            }
            .sortedByDescending(ReadingStats::timestamp)
            .toList()

        val today = times.date(nowMillis)
        val currentMonth: YearMonth = today.yearMonth
        val currentYearStart = LocalDate(today.year, 1, 1)
        val sevenDayStart = today.minus(6, DateTimeUnit.DAY)
        val thirtyDayStart = today.minus(29, DateTimeUnit.DAY)
        val sessionsByDate = validStats.groupBy { it.localDate(times) }
        val readingDates = sessionsByDate.keys.sorted()

        val dailyActivity = (HISTORY_DAYS - 1 downTo 0L).map { daysAgo ->
            val date = today.minus(daysAgo, DateTimeUnit.DAY)
            val sessions = sessionsByDate[date].orEmpty()
            DailyReadingActivity(
                date = date,
                durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                sessionCount = sessions.size
            )
        }

        val todayStats = sessionsByDate[today].orEmpty()
        val sevenDayStats = validStats.filter { it.localDate(times) in sevenDayStart..today }
        val thirtyDayStats = validStats.filter { it.localDate(times) in thirtyDayStart..today }
        val yearStats = validStats.filter { it.localDate(times) in currentYearStart..today }

        val previousDayStats = sessionsByDate[today.minus(1, DateTimeUnit.DAY)].orEmpty()
        val previousSevenStart = sevenDayStart.minus(7, DateTimeUnit.DAY)
        val previousSevenEnd = sevenDayStart.minus(1, DateTimeUnit.DAY)
        val previousSevenStats = validStats.filter {
            it.localDate(times) in previousSevenStart..previousSevenEnd
        }
        val previousThirtyStart = thirtyDayStart.minus(30, DateTimeUnit.DAY)
        val previousThirtyEnd = thirtyDayStart.minus(1, DateTimeUnit.DAY)
        val previousThirtyStats = validStats.filter {
            it.localDate(times) in previousThirtyStart..previousThirtyEnd
        }
        val previousYearStart = currentYearStart.minus(1, DateTimeUnit.YEAR)
        val elapsedYearDays = currentYearStart.daysUntil(today)
        val previousYearEnd = previousYearStart.plus(elapsedYearDays, DateTimeUnit.DAY)
        val previousYearStats = validStats.filter {
            it.localDate(times) in previousYearStart..previousYearEnd
        }

        val summaries = mapOf(
            StatsPeriod.TODAY to summaryOf(todayStats),
            StatsPeriod.SEVEN_DAYS to summaryOf(sevenDayStats),
            StatsPeriod.THIRTY_DAYS to summaryOf(thirtyDayStats),
            StatsPeriod.YEAR to summaryOf(yearStats),
            StatsPeriod.ALL_TIME to summaryOf(validStats)
        )
        val comparisons = mapOf(
            StatsPeriod.TODAY to comparison(summaryOf(todayStats), summaryOf(previousDayStats)),
            StatsPeriod.SEVEN_DAYS to comparison(summaryOf(sevenDayStats), summaryOf(previousSevenStats)),
            StatsPeriod.THIRTY_DAYS to comparison(summaryOf(thirtyDayStats), summaryOf(previousThirtyStats)),
            StatsPeriod.YEAR to comparison(summaryOf(yearStats), summaryOf(previousYearStats)),
            StatsPeriod.ALL_TIME to PeriodComparison()
        )

        val bookById = books.associateBy(Book::id)
        val summariesByBook = validStats
            .groupBy(ReadingStats::bookId)
            .map { (bookId, sessions) ->
                val book = bookById[bookId]
                BookReadingSummary(
                    bookId = bookId,
                    title = book?.title?.takeIf(String::isNotBlank) ?: "Удалённая книга",
                    author = book?.author?.takeIf(String::isNotBlank).orEmpty(),
                    coverPath = book?.coverPath,
                    durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                    wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                    sessionCount = sessions.size,
                    lastReadTimestamp = sessions.maxOf { it.timestamp }
                )
            }
        val mostReadBooks = summariesByBook
            .sortedWith(
                compareByDescending<BookReadingSummary> { it.durationSeconds }
                    .thenByDescending { it.wordsRead }
                    .thenByDescending { it.lastReadTimestamp }
            )
            .take(TOP_BOOK_LIMIT)

        val monthStats = validStats.filter {
            it.localDate(times).yearMonth == currentMonth
        }
        val bookOfMonth = monthStats
            .groupBy(ReadingStats::bookId)
            .mapNotNull { (bookId, sessions) ->
                val book = bookById[bookId]
                if (book == null) null else BookReadingSummary(
                    bookId = bookId,
                    title = book.title,
                    author = book.author,
                    coverPath = book.coverPath,
                    durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                    wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                    sessionCount = sessions.size,
                    lastReadTimestamp = sessions.maxOf { it.timestamp }
                )
            }
            .maxByOrNull { it.durationSeconds }

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

        val monthlyActivity = (11 downTo 0).map { monthsAgo ->
            val month = currentMonth.minus(monthsAgo, DateTimeUnit.MONTH)
            val sessions = validStats.filter {
                it.localDate(times).yearMonth == month
            }
            val measured = sessions.filter { it.wordsReadCount > 0 && it.sessionDurationSeconds > 0 }
            val measuredSeconds = measured.sumOf { it.sessionDurationSeconds }
            val measuredWords = measured.sumOf { it.wordsReadCount.toLong() }
            val completed = books.count { book ->
                book.completedAt?.let { timestamp ->
                    times.date(timestamp).yearMonth == month
                } == true
            }
            MonthlyReadingActivity(
                month = month,
                durationSeconds = sessions.sumOf { it.sessionDurationSeconds },
                wordsRead = sessions.sumOf { it.wordsReadCount.toLong() },
                sessionCount = sessions.size,
                averageWordsPerMinute = if (measuredSeconds == 0L) 0 else
                    (measuredWords * 60.0 / measuredSeconds).roundToInt().coerceAtLeast(0),
                averageSessionSeconds = if (sessions.isEmpty()) 0 else
                    sessions.sumOf { it.sessionDurationSeconds } / sessions.size,
                completedBooks = completed
            )
        }

        val hourlyActivity = (0..23).map { hour ->
            HourlyReadingActivity(
                hour = hour,
                durationSeconds = validStats
                    .filter { it.hour(times) == hour }
                    .sumOf { it.sessionDurationSeconds }
            )
        }
        val weekdayActivity = DayOfWeek.entries.map { day ->
            WeekdayReadingActivity(
                dayOfWeek = day,
                durationSeconds = validStats
                    .filter { it.localDate(times).dayOfWeek == day }
                    .sumOf { it.sessionDurationSeconds }
            )
        }

        val sessionsWithMeasuredWords = validStats.filter {
            it.wordsReadCount > 0 && it.sessionDurationSeconds > 0
        }
        val measuredSeconds = sessionsWithMeasuredWords.sumOf { it.sessionDurationSeconds }
        val measuredWords = sessionsWithMeasuredWords.sumOf { it.wordsReadCount.toLong() }
        val averageWordsPerMinute = if (measuredSeconds == 0L) 0 else
            (measuredWords * 60.0 / measuredSeconds).roundToInt().coerceAtLeast(0)

        val favoritePart = validStats
            .groupBy { it.partOfDay(times) }
            .maxByOrNull { (_, sessions) -> sessions.sumOf { it.sessionDurationSeconds } }
            ?.key
        val favoriteWeekday = weekdayActivity.maxByOrNull { it.durationSeconds }
            ?.takeIf { it.durationSeconds > 0 }
            ?.dayOfWeek

        val currentStreak = currentStreak(readingDates.toSet(), today)
        val bestStreak = bestStreak(readingDates)
        val averageSession = if (validStats.isEmpty()) 0L else
            validStats.sumOf { it.sessionDurationSeconds } / validStats.size
        val longestSession = validStats.maxOfOrNull { it.sessionDurationSeconds } ?: 0L

        val completedBookCount = books.count(Book::isDone)
        val readingBookCount = books.count {
            it.currentProgressPercent > 0f && !it.isDone()
        }
        val unreadBookCount = (books.size - completedBookCount - readingBookCount).coerceAtLeast(0)

        val completedThisYear = books.count { book ->
            book.completedAt?.let {
                times.of(it).year == today.year
            } == true
        }
        val elapsedDaysInYear = today.dayOfYear.coerceAtLeast(1)
        val projectedBooks = if (completedThisYear == 0) 0 else {
            (completedThisYear * today.lengthOfYear().toDouble() / elapsedDaysInYear.toDouble())
                .roundToInt()
                .coerceAtLeast(completedThisYear)
        }

        val bestDay = dailyActivity.maxByOrNull { it.durationSeconds }
        val fastestStableWpm = validStats.asSequence()
            .filter { it.sessionDurationSeconds >= 180 && it.wordsReadCount >= 100 }
            .map {
                (it.wordsReadCount * 60.0 / it.sessionDurationSeconds.toDouble()).roundToInt()
            }
            .filter { it in 1..1_000 }
            .maxOrNull() ?: 0
        val records = PersonalRecords(
            bestStreakDays = bestStreak,
            longestSessionSeconds = longestSession,
            bestDayDate = bestDay?.takeIf { it.durationSeconds > 0 }?.date,
            bestDaySeconds = bestDay?.durationSeconds ?: 0,
            maxWordsDay = dailyActivity.maxOfOrNull { it.wordsRead } ?: 0,
            fastestStableWpm = fastestStableWpm,
            maxCompletedBooksMonth = monthlyActivity.maxOfOrNull { it.completedBooks } ?: 0
        )

        val profile = buildReaderProfile(
            validStats = validStats,
            favoritePart = favoritePart,
            favoriteWeekday = favoriteWeekday,
            averageSessionSeconds = averageSession,
            averageWpm = averageWordsPerMinute,
            currentStreak = currentStreak,
            times = times
        )

        val readingRhythm = listOfNotNull(
            favoritePart?.label,
            favoriteWeekday?.let { "Любимый день — ${weekdayLabel(it)}" }
        ).joinToString(" · ").ifBlank { "Пока собираем ваш ритм" }

        val goalStreak = goalStreak(
            activities = dailyActivity,
            today = today,
            settings = goalSettings
        )
        val dailyGoalProgress = goalProgress(
            activity = dailyActivity.lastOrNull() ?: DailyReadingActivity(today),
            settings = goalSettings
        )
        val yearlyGoalProgress = if (goalSettings.yearlyBooksTarget <= 0) 0f else
            (completedThisYear.toFloat() / goalSettings.yearlyBooksTarget.toFloat())
                .coerceAtLeast(0f)

        val achievement = when {
            currentStreak < 7 -> AchievementProgress(
                title = "Неделя с книгой",
                detail = "Читайте 7 дней подряд",
                current = currentStreak,
                target = 7
            )
            completedBookCount < 5 -> AchievementProgress(
                title = "Пять завершённых книг",
                detail = "Завершите пять книг",
                current = completedBookCount,
                target = 5
            )
            (summaries[StatsPeriod.SEVEN_DAYS]?.wordsRead ?: 0) < 10_000 -> AchievementProgress(
                title = "Книжный марафон",
                detail = "Прочитайте 10 000 слов за неделю",
                current = (summaries[StatsPeriod.SEVEN_DAYS]?.wordsRead ?: 0)
                    .coerceAtMost(10_000)
                    .toInt(),
                target = 10_000
            )
            else -> AchievementProgress(
                title = "Сто часов",
                detail = "Проведите 100 часов за книгами",
                current = ((summaries[StatsPeriod.ALL_TIME]?.durationSeconds ?: 0) / 3600)
                    .coerceAtMost(100)
                    .toInt(),
                target = 100
            )
        }

        return ReadingStatsUiState(
            isLoading = false,
            today = summaries.getValue(StatsPeriod.TODAY),
            sevenDays = summaries.getValue(StatsPeriod.SEVEN_DAYS),
            thirtyDays = summaries.getValue(StatsPeriod.THIRTY_DAYS),
            year = summaries.getValue(StatsPeriod.YEAR),
            allTime = summaries.getValue(StatsPeriod.ALL_TIME),
            comparisons = comparisons,
            dailyActivity = dailyActivity,
            monthlyActivity = monthlyActivity,
            hourlyActivity = hourlyActivity,
            weekdayActivity = weekdayActivity,
            currentStreakDays = currentStreak,
            bestStreakDays = bestStreak,
            goalStreakDays = goalStreak,
            activeReadingDays = readingDates.size,
            averageSessionSeconds = averageSession,
            longestSessionSeconds = longestSession,
            averageWordsPerMinute = averageWordsPerMinute,
            readingRhythm = readingRhythm,
            readerProfile = profile,
            personalRecords = records,
            mostReadBooks = mostReadBooks,
            bookOfMonth = bookOfMonth,
            recentSessions = recentSessions,
            libraryBookCount = books.size,
            readingBookCount = readingBookCount,
            completedBookCount = completedBookCount,
            unreadBookCount = unreadBookCount,
            favoriteBookCount = books.count(Book::isFavorite),
            shelfCount = books.map { it.collection.trim() }.filter(String::isNotEmpty).distinct().size,
            seriesCount = books.map { it.seriesName.trim() }.filter(String::isNotEmpty).distinct().size,
            averageLibraryProgress = if (books.isEmpty()) 0 else
                books.map { it.currentProgressPercent.coerceIn(0f, 100f) }.average().toInt(),
            completedBooksThisYear = completedThisYear,
            projectedBooksThisYear = projectedBooks,
            goalSettings = goalSettings,
            dailyGoalProgress = dailyGoalProgress,
            yearlyGoalProgress = yearlyGoalProgress,
            ignoredSessionCount = stats.size - validStats.size,
            nextAchievementTitle = achievement.title,
            nextAchievementDetail = achievement.detail,
            nextAchievementProgress = achievement.current.toFloat() / achievement.target.toFloat()
        )
    }

    private fun comparison(current: ReadingSummary, previous: ReadingSummary) = PeriodComparison(
        durationPercent = percentChange(current.durationSeconds, previous.durationSeconds),
        wordsPercent = percentChange(current.wordsRead, previous.wordsRead),
        sessionsPercent = percentChange(current.sessionCount.toLong(), previous.sessionCount.toLong())
    )

    private fun percentChange(current: Long, previous: Long): Int? {
        if (previous <= 0L) return if (current > 0L) 100 else null
        return (((current - previous).toDouble() / previous.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(-999, 999)
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
            today.minus(1, DateTimeUnit.DAY) in readingDates -> today.minus(1, DateTimeUnit.DAY)
            else -> return 0
        }
        var result = 0
        while (cursor in readingDates) {
            result++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        return result
    }

    private fun bestStreak(readingDates: List<LocalDate>): Int {
        if (readingDates.isEmpty()) return 0
        var best = 1
        var current = 1
        readingDates.zipWithNext().forEach { (previous, next) ->
            if (next == previous.plus(1, DateTimeUnit.DAY)) {
                current++
                best = maxOf(best, current)
            } else if (next != previous) {
                current = 1
            }
        }
        return best
    }

    private fun goalStreak(
        activities: List<DailyReadingActivity>,
        today: LocalDate,
        settings: StatsGoalSettings
    ): Int {
        val byDate = activities.associateBy(DailyReadingActivity::date)
        fun met(date: LocalDate): Boolean = goalProgress(
            byDate[date] ?: DailyReadingActivity(date),
            settings
        ) >= 1f

        var cursor = when {
            met(today) -> today
            met(today.minus(1, DateTimeUnit.DAY)) -> today.minus(1, DateTimeUnit.DAY)
            else -> return 0
        }
        var streak = 0
        while (met(cursor)) {
            streak++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        return streak
    }

    private fun goalProgress(
        activity: DailyReadingActivity,
        settings: StatsGoalSettings
    ): Float {
        val current = when (settings.dailyType) {
            DailyGoalType.MINUTES -> activity.durationSeconds / 60f
            DailyGoalType.PAGES -> activity.estimatedPages.toFloat()
            DailyGoalType.WORDS -> activity.wordsRead.toFloat()
        }
        return if (settings.dailyTarget <= 0) 0f else
            (current / settings.dailyTarget.toFloat()).coerceAtLeast(0f)
    }

    private fun buildReaderProfile(
        validStats: List<ReadingStats>,
        favoritePart: PartOfDay?,
        favoriteWeekday: DayOfWeek?,
        averageSessionSeconds: Long,
        averageWpm: Int,
        currentStreak: Int,
        times: LocalTimes
    ): ReaderProfile {
        if (validStats.isEmpty()) return ReaderProfile()

        val totalDuration = validStats.sumOf { it.sessionDurationSeconds }.coerceAtLeast(1)
        val weekendDuration = validStats
            .filter {
                val day = it.localDate(times).dayOfWeek
                day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
            }
            .sumOf { it.sessionDurationSeconds }
        val weekendShare = weekendDuration.toDouble() / totalDuration.toDouble()

        val title = when {
            favoritePart == PartOfDay.NIGHT -> "Ночной читатель"
            favoritePart == PartOfDay.MORNING -> "Жаворонок"
            averageSessionSeconds >= 45 * 60 -> "Марафонец"
            averageSessionSeconds in 1..15 * 60 -> "Читаю понемногу"
            currentStreak >= 7 -> "Ежедневный читатель"
            weekendShare >= 0.45 -> "Выходной книжник"
            averageWpm >= 300 -> "Скорочтец"
            else -> "Ровный ритм"
        }

        val description = when (title) {
            "Ночной читатель" -> "Главная книжная жизнь начинается тогда, когда нормальные люди уже выключили свет."
            "Жаворонок" -> "Чаще всего вы читаете утром, пока день ещё не успел испортить планы."
            "Марафонец" -> "Вы предпочитаете длинные сессии и редко ограничиваетесь парой страниц."
            "Читаю понемногу" -> "Короткие регулярные заходы складываются в вполне серьёзный объём."
            "Ежедневный читатель" -> "Чтение стало устойчивой привычкой, а не случайным событием."
            "Выходной книжник" -> "Основная часть чтения приходится на субботу и воскресенье."
            "Скорочтец" -> "Ваш устойчивый темп заметно выше среднего собственного ритма."
            else -> "У вас достаточно ровный режим без одного доминирующего сценария."
        }

        return ReaderProfile(
            title = title,
            description = description,
            favoritePartOfDay = favoritePart?.shortLabel ?: "Пока нет данных",
            favoriteWeekday = favoriteWeekday?.let(::weekdayLabel)?.replaceFirstChar { it.uppercase() }
                ?: "Пока нет данных"
        )
    }

    private fun ReadingStats.localDate(times: LocalTimes): LocalDate = times.date(timestamp)

    private fun ReadingStats.hour(times: LocalTimes): Int = times.of(timestamp).hour

    private fun ReadingStats.partOfDay(times: LocalTimes): PartOfDay {
        val hour = hour(times)
        return when (hour) {
            in 5..11 -> PartOfDay.MORNING
            in 12..17 -> PartOfDay.DAY
            in 18..22 -> PartOfDay.EVENING
            else -> PartOfDay.NIGHT
        }
    }

    fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "понедельник"
        DayOfWeek.TUESDAY -> "вторник"
        DayOfWeek.WEDNESDAY -> "среда"
        DayOfWeek.THURSDAY -> "четверг"
        DayOfWeek.FRIDAY -> "пятница"
        DayOfWeek.SATURDAY -> "суббота"
        DayOfWeek.SUNDAY -> "воскресенье"
    }

    private enum class PartOfDay(val label: String, val shortLabel: String) {
        MORNING("Чаще читаете утром", "Утро"),
        DAY("Чаще читаете днём", "День"),
        EVENING("Чаще читаете вечером", "Вечер"),
        NIGHT("Чаще читаете ночью", "Ночь")
    }

    private data class AchievementProgress(
        val title: String,
        val detail: String,
        val current: Int,
        val target: Int
    )
}
