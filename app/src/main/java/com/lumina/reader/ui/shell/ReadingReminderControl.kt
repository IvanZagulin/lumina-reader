package com.lumina.reader.ui.shell

import android.content.Context
import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.core.reminder.ReadingReminder
import kotlinx.coroutines.flow.Flow

/**
 * Android's [ReminderControl]: the settings sheet's calls go to
 * [ReadingReminder] exactly as they did when the sheet called it directly
 * (save, then reschedule the alarm). It stays in :app with ReadingReminder;
 * `LuminaApp` installs it through [ShellServices.installReminders].
 */
class ReadingReminderControl(context: Context) : ReminderControl {
    private val appContext = context.applicationContext

    override val settings: Flow<ReminderSettings>
        get() = ReadingReminder.settings(appContext)

    override suspend fun setEnabled(enabled: Boolean) {
        ReadingReminder.setEnabled(appContext, enabled)
    }

    override suspend fun setTime(hour: Int, minute: Int) {
        ReadingReminder.setTime(appContext, hour, minute)
    }
}
