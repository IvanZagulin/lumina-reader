package com.lumina.reader.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Daily reading reminder settings. Defaults keep the historical behaviour: on, at 20:00. */
data class ReminderSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = DEFAULT_MINUTE
) {
    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_HOUR = 20
        const val DEFAULT_MINUTE = 0
    }
}

private val Context.reminderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reminder_settings"
)

/**
 * DataStore-backed storage for [ReminderSettings]. Changing the settings does
 * not reschedule the alarm by itself: use
 * [com.lumina.reader.core.reminder.ReadingReminder.setEnabled] /
 * [com.lumina.reader.core.reminder.ReadingReminder.setTime], which save and
 * reschedule in one call.
 */
class ReminderPreferences(context: Context) {

    private val dataStore = context.applicationContext.reminderDataStore

    val settingsFlow: Flow<ReminderSettings> = dataStore.data.map { preferences ->
        ReminderSettings(
            enabled = preferences[Keys.ENABLED] ?: ReminderSettings.DEFAULT_ENABLED,
            hour = (preferences[Keys.HOUR] ?: ReminderSettings.DEFAULT_HOUR).coerceIn(0, 23),
            minute = (preferences[Keys.MINUTE] ?: ReminderSettings.DEFAULT_MINUTE).coerceIn(0, 59)
        )
    }

    suspend fun current(): ReminderSettings = settingsFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.ENABLED] = enabled }
    }

    /** Values outside 0..23 / 0..59 are clamped. */
    suspend fun setTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.HOUR] = hour.coerceIn(0, 23)
            preferences[Keys.MINUTE] = minute.coerceIn(0, 59)
        }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("reminder_enabled")
        val HOUR = intPreferencesKey("reminder_hour")
        val MINUTE = intPreferencesKey("reminder_minute")
    }
}
