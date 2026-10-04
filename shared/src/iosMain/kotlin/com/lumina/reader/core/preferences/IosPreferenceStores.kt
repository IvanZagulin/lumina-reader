package com.lumina.reader.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lumina.reader.platform.IosAppDirs
import okio.Path.Companion.toPath
import platform.Foundation.NSUserDefaults

/**
 * The iPhone app's settings files: one DataStore per file and process (a
 * second instance on the same file would break DataStore), at
 * `Application Support/datastore/<name>.preferences_pb`, the same names and
 * keys as on Android.
 */
object IosPreferenceStores {
    val reader: DataStore<Preferences> by lazy { create(PreferenceFiles.READER) }
    val library: DataStore<Preferences> by lazy { create(PreferenceFiles.LIBRARY) }
    val catalogs: DataStore<Preferences> by lazy { create(PreferenceFiles.CATALOGS) }
    val reminders: DataStore<Preferences> by lazy { create(PreferenceFiles.REMINDERS) }

    /** A DataStore for [name] in [directory] (the app's datastore folder by default). */
    fun create(name: String, directory: String? = null): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                val dir = directory ?: IosAppDirs.inApplicationSupport("datastore", directory = true)
                "$dir/${PreferenceFiles.dataStoreFileName(name)}".toPath()
            }
        )
}

/** Reader settings of this app ([PreferenceFiles.READER]). */
fun ReaderPreferences(): ReaderPreferences = ReaderPreferences(IosPreferenceStores.reader)

/** Shelves and library flags of this app ([PreferenceFiles.LIBRARY]). */
fun LibraryPreferences(): LibraryPreferences = LibraryPreferences(IosPreferenceStores.library)

/** OPDS catalogues of this app ([PreferenceFiles.CATALOGS]). */
fun CatalogPreferences(): CatalogPreferences = CatalogPreferences(IosPreferenceStores.catalogs)

/** Reading reminder settings of this app ([PreferenceFiles.REMINDERS]). */
fun ReminderPreferences(): ReminderPreferences = ReminderPreferences(IosPreferenceStores.reminders)

/** The process-wide UI preferences, in the app's standard NSUserDefaults. */
fun AppUiPreferences.Companion.get(): AppUiPreferences = IosUiPreferencesInstance.value

/** [UiPreferencesStore] over NSUserDefaults (the iOS counterpart of SharedPreferences). */
class NSUserDefaultsUiStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : UiPreferencesStore {

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }
}

private object IosUiPreferencesInstance {
    val value: AppUiPreferences by lazy { AppUiPreferences(NSUserDefaultsUiStore()) }
}
