package com.lumina.reader.core.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// The app's settings files, exactly as they were created before the settings
// classes moved to :shared: Context.preferencesDataStore keeps one DataStore
// per file and process at filesDir/datastore/<name>.preferences_pb. Each name
// may have only one delegate in the whole app.
private val Context.readerSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = PreferenceFiles.READER
)
private val Context.librarySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = PreferenceFiles.LIBRARY
)
private val Context.catalogSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = PreferenceFiles.CATALOGS
)
private val Context.reminderSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = PreferenceFiles.REMINDERS
)

/** Reader settings of this app ([PreferenceFiles.READER]). */
fun ReaderPreferences(context: Context): ReaderPreferences =
    ReaderPreferences(context.readerSettingsStore)

/** Shelves and library flags of this app ([PreferenceFiles.LIBRARY]). */
fun LibraryPreferences(context: Context): LibraryPreferences =
    LibraryPreferences(context.librarySettingsStore)

/** OPDS catalogues of this app ([PreferenceFiles.CATALOGS]). */
fun CatalogPreferences(context: Context): CatalogPreferences =
    CatalogPreferences(context.catalogSettingsStore)

/** Reading reminder settings of this app ([PreferenceFiles.REMINDERS]). */
fun ReminderPreferences(context: Context): ReminderPreferences =
    ReminderPreferences(context.reminderSettingsStore)

/** The process-wide UI preferences, in the SharedPreferences file [PreferenceFiles.UI]. */
fun AppUiPreferences.Companion.get(context: Context): AppUiPreferences =
    AndroidUiPreferencesInstance.value ?: synchronized(AndroidUiPreferencesInstance) {
        AndroidUiPreferencesInstance.value
            ?: AppUiPreferences(SharedPreferencesUiStore(context)).also {
                AndroidUiPreferencesInstance.value = it
            }
    }

/** [UiPreferencesStore] over SharedPreferences; writes are applied asynchronously, as before. */
class SharedPreferencesUiStore(context: Context) : UiPreferencesStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PreferenceFiles.UI, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

private object AndroidUiPreferencesInstance {
    @Volatile
    var value: AppUiPreferences? = null
}
