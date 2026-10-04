package com.lumina.reader.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

/**
 * DataStore-backed storage for [ReminderSettings] (file
 * [PreferenceFiles.REMINDERS]; on Android `ReminderPreferences(context)` in
 * androidMain gives the process-wide store). Changing the settings does not
 * reschedule the alarm by itself: on Android use
 * `com.lumina.reader.core.reminder.ReadingReminder.setEnabled` / `setTime`,
 * which save and reschedule in one call.
 */
class ReminderPreferences(private val dataStore: DataStore<Preferences>) {

    /** Never fails on an unreadable file: falls back to the defaults instead. */
    val settingsFlow: Flow<ReminderSettings> = dataStore.data
        .catch { error ->
            // okio.IOException is java.io.IOException on Android.
            if (error is okio.IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
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
