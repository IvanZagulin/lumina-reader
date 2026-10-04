package com.lumina.reader.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.CatalogSettingsCodec
import com.lumina.reader.core.opds.OpdsCatalogConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * OPDS catalogues: the built-in defaults (which can only be switched off) plus
 * catalogues the user added, with optional HTTP Basic credentials. Stored in
 * the DataStore file [PreferenceFiles.CATALOGS]; on Android
 * `CatalogPreferences(context)` (androidMain) gives the process-wide store.
 */
class CatalogPreferences(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val USER_CATALOGS = stringPreferencesKey("user_catalogs_json")
        val DISABLED_BUILT_INS = stringSetPreferencesKey("disabled_builtin_ids")
    }

    /** All catalogues, built-ins first; disabled ones are included with enabled = false. */
    val catalogs: Flow<List<OpdsCatalogConfig>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            CatalogSettingsCodec.merge(
                builtIns = BuiltInCatalogs.all,
                disabledBuiltInIds = preferences[Keys.DISABLED_BUILT_INS].orEmpty(),
                userCatalogs = CatalogSettingsCodec.decodeUserCatalogs(preferences[Keys.USER_CATALOGS])
            )
        }
        .distinctUntilChanged()

    /** Adds a new user catalogue or replaces the one with the same id. */
    suspend fun saveCatalog(catalog: OpdsCatalogConfig) {
        if (catalog.builtIn) {
            setEnabled(catalog.id, catalog.enabled)
            return
        }
        dataStore.edit { preferences ->
            val current = CatalogSettingsCodec.decodeUserCatalogs(preferences[Keys.USER_CATALOGS])
            val updated = if (current.any { it.id == catalog.id }) {
                current.map { if (it.id == catalog.id) catalog else it }
            } else {
                current + catalog
            }
            preferences[Keys.USER_CATALOGS] = CatalogSettingsCodec.encodeUserCatalogs(updated)
        }
    }

    /** Deletes a user catalogue; built-in catalogues are only disabled. */
    suspend fun deleteCatalog(id: String) {
        if (BuiltInCatalogs.find(id) != null) {
            setEnabled(id, false)
            return
        }
        dataStore.edit { preferences ->
            val current = CatalogSettingsCodec.decodeUserCatalogs(preferences[Keys.USER_CATALOGS])
            preferences[Keys.USER_CATALOGS] = CatalogSettingsCodec.encodeUserCatalogs(current.filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            if (BuiltInCatalogs.find(id) != null) {
                val disabled = preferences[Keys.DISABLED_BUILT_INS].orEmpty()
                preferences[Keys.DISABLED_BUILT_INS] = if (enabled) disabled - id else disabled + id
            } else {
                val current = CatalogSettingsCodec.decodeUserCatalogs(preferences[Keys.USER_CATALOGS])
                preferences[Keys.USER_CATALOGS] = CatalogSettingsCodec.encodeUserCatalogs(
                    current.map { if (it.id == id) it.copy(enabled = enabled) else it }
                )
            }
        }
    }
}
