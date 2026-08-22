package com.lumina.reader.ui.stats

import java.time.DayOfWeek

/**
 * A few achievement counters are presentation-only derivatives of the already
 * calculated stats state. Keeping them here avoids duplicating raw database
 * aggregation just for badge conditions.
 */
internal object AchievementCompat {
    var activeSundayCount: Int = 0
        private set
    var fridayEveningSessionCount: Int = 0
        private set
    var maxBooksSameAuthor: Int = 0
        private set

    fun update(base: ReadingStatsUiState, advanced: AdvancedStatsUiState) {
        activeSundayCount = base.dailyActivity.count {
            it.date.dayOfWeek == DayOfWeek.SUNDAY && it.sessionCount > 0
        }
        val fridaySessions = base.dailyActivity
            .filter { it.date.dayOfWeek == DayOfWeek.FRIDAY }
            .sumOf(DailyReadingActivity::sessionCount)
        val fridayEveningSeconds = advanced.rhythmMatrix.firstOrNull {
            it.dayOfWeek == DayOfWeek.FRIDAY && it.part == AdvancedDayPart.EVENING
        }?.durationSeconds ?: 0L
        // Existing session rows do not store a separate end-hour bucket counter.
        // Require seven Friday sessions plus actual Friday-evening reading so the
        // badge remains conservative instead of unlocking from unrelated days.
        fridayEveningSessionCount = if (fridayEveningSeconds > 0L) fridaySessions else 0
        maxBooksSameAuthor = advanced.authorStats.maxOfOrNull(AuthorReadingStats::bookCount) ?: 0
    }
}

internal val HabitSignals.activeSundayCount: Int
    get() = AchievementCompat.activeSundayCount

internal val HabitSignals.fridayEveningSessionCount: Int
    get() = AchievementCompat.fridayEveningSessionCount

internal val HabitSignals.maxBooksSameAuthor: Int
    get() = AchievementCompat.maxBooksSameAuthor
