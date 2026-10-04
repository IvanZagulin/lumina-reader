package com.lumina.reader.ui.shell

import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.core.reminder.DailyReadingReminder
import com.lumina.reader.platform.LuminaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * iOS needs no install: the sheet drives :shared's [DailyReadingReminder],
 * whose iOS environment (the iOS preference store and the
 * UNUserNotificationCenter scheduler) builds itself on first use.
 */
internal actual fun defaultReminderControl(): ReminderControl = IosReminderControl

/**
 * The settings sheet's view of the iPhone's daily reminder: the same
 * [ReminderSettings] file as Android, saved and planned by
 * [DailyReadingReminder].
 *
 * The planning itself stays in one place, :shared's `IosReminderScheduler`.
 * A second planner here would remove and re-add the same seven request
 * identifiers on its own schedule; two plans running at once (both re-plan
 * when the app goes to the background) could then leave a mix of both plans
 * behind and show the reminder twice on one day. The launch-time plan and
 * the re-plan on every background transition are `IosReadingReminders.start()`
 * (:shared iosMain), which the iOS app calls at launch.
 */
private object IosReminderControl : ReminderControl {
    private const val TAG = "ReadingReminder"

    override val settings: Flow<ReminderSettings>
        get() = DailyReadingReminder.settings()

    override suspend fun setEnabled(enabled: Boolean) {
        guarded { DailyReadingReminder.setEnabled(enabled) }
    }

    override suspend fun setTime(hour: Int, minute: Int) {
        guarded { DailyReadingReminder.setTime(hour, minute) }
    }

    /**
     * The sheet launches these calls in its own scope with no handler, and an
     * uncaught exception terminates a Kotlin/Native app; a reminder that could
     * not be saved or planned must not, so it is logged instead.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            LuminaLog.w(TAG, "Could not change the reading reminder", error)
        }
    }
}
