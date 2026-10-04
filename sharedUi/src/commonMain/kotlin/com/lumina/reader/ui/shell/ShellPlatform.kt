package com.lumina.reader.ui.shell

import androidx.compose.runtime.Composable
import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.ui.components.screenWidthDp
import kotlinx.coroutines.flow.Flow

/**
 * The daily reading reminder as the settings sheet sees it: the stored
 * [ReminderSettings] and the two changes it can make. Saving alone is not
 * enough, the platform's notification has to be planned again, and that is
 * platform work: Android's `ReadingReminder` (AlarmManager plus a receiver
 * that checks whether the user read today) lives in :app; iOS goes through
 * :shared's `DailyReadingReminder`, which plans local notifications with
 * UNUserNotificationCenter.
 */
interface ReminderControl {
    /** The saved settings; never fails on an unreadable file. */
    val settings: Flow<ReminderSettings>

    /** Saves [enabled] and plans or cancels the reminder. */
    suspend fun setEnabled(enabled: Boolean)

    /** Saves the time ([hour] 0..23, [minute] 0..59, clamped) and plans the reminder again. */
    suspend fun setTime(hour: Int, minute: Int)
}

/**
 * The shell's platform services of this process, built on first use like
 * `AppServices`. Android's `LuminaApp.onCreate` installs them, because the
 * reminder needs :app's `ReadingReminder`; iOS needs no install.
 */
object ShellServices {
    private var reminderFactory: (() -> ReminderControl)? = null
    private var installedReminders: ReminderControl? = null

    /** Registers how to build the reminder control; [factory] runs on first use, not here. */
    fun installReminders(factory: () -> ReminderControl) {
        reminderFactory = factory
    }

    val reminders: ReminderControl
        get() = installedReminders
            ?: (reminderFactory?.invoke() ?: defaultReminderControl()).also { installedReminders = it }
}

/** The platform's own reminder control when none was installed (Android must install one). */
internal expect fun defaultReminderControl(): ReminderControl

/**
 * Width of the window in dp, which the dock divides between its items.
 * Public so the dock compiles while it is still in :app; it is the shared
 * `screenWidthDp()` (Android: `LocalConfiguration.screenWidthDp`, as before).
 */
@Composable
fun shellScreenWidthDp(): Int = screenWidthDp()
