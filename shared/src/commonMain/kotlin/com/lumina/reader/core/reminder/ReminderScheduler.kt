package com.lumina.reader.core.reminder

import com.lumina.reader.core.preferences.ReminderPreferences
import com.lumina.reader.core.preferences.ReminderSettings

/**
 * Delivers the daily reading reminder, which is platform work by nature.
 *
 * Android: an AlarmManager alarm at the set time; its receiver posts the
 * notification only if nobody has read today, then sets the next alarm
 * (`ReadingReminderScheduler` in :app, which owns the activity and the icon).
 * iOS: local notifications from UNUserNotificationCenter. They fire without
 * running app code, so the "read today" check has to happen when they are
 * planned instead.
 */
interface ReminderScheduler {
    /** Schedules the next reminder for [settings], or cancels it when reminders are off. */
    suspend fun apply(settings: ReminderSettings)
}

/** Where the reminder's settings live and how it is delivered on this platform. */
class ReminderEnvironment(
    val preferences: ReminderPreferences,
    val scheduler: ReminderScheduler
)

/**
 * Android: an error, since the alarm lives in :app, which installs it (its
 * `ReadingReminder` does so on first use); iOS: the settings store and the
 * UNUserNotificationCenter scheduler.
 */
internal expect fun defaultReminderEnvironment(): ReminderEnvironment
