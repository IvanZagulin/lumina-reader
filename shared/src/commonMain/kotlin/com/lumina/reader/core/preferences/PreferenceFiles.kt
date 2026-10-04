package com.lumina.reader.core.preferences

/**
 * Names of the settings files. They are part of every installed app's data:
 * Android keeps DataStore files at `filesDir/datastore/<name>.preferences_pb`
 * and the UI preferences in the SharedPreferences file [UI]. Renaming one
 * loses the user's settings.
 */
object PreferenceFiles {
    /** Reader typography, themes, page turns, TTS speed ([ReaderPreferences]). */
    const val READER: String = "reader_settings"

    /** Shelves and one-time flags ([LibraryPreferences]). */
    const val LIBRARY: String = "library_settings"

    /** OPDS catalogues ([CatalogPreferences]). */
    const val CATALOGS: String = "opds_catalogs"

    /** Daily reading reminder ([ReminderPreferences]). */
    const val REMINDERS: String = "reminder_settings"

    /** Library view and app-shell choices ([AppUiPreferences], read synchronously). */
    const val UI: String = "lumina_ui"

    /** Every DataStore file, in no particular order. */
    val dataStores: List<String> = listOf(READER, LIBRARY, CATALOGS, REMINDERS)

    /** DataStore's file name for [name] (what Android's preferencesDataStoreFile uses). */
    fun dataStoreFileName(name: String): String = "$name.preferences_pb"
}
