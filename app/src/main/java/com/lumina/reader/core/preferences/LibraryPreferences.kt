package com.lumina.reader.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore(name = "library_settings")

/** Library-wide settings: user shelves (which exist even while empty) and one-time flags. */
class LibraryPreferences(context: Context) {
    private val dataStore = context.applicationContext.libraryDataStore

    private object Keys {
        val CUSTOM_SHELVES = stringPreferencesKey("custom_shelves_json")
        val WELCOME_BOOK_SEEDED = booleanPreferencesKey("welcome_book_seeded")
    }

    /** User shelves in creation order. Until the user changes them, the classic defaults. */
    val customShelves: Flow<List<String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> decodeShelves(preferences[Keys.CUSTOM_SHELVES]) }
        .distinctUntilChanged()

    suspend fun setCustomShelves(shelves: List<String>) {
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_SHELVES] = encodeShelves(shelves)
        }
    }

    /** Applies [transform] to the current shelf list atomically. */
    suspend fun updateCustomShelves(transform: (List<String>) -> List<String>) {
        dataStore.edit { preferences ->
            val current = decodeShelves(preferences[Keys.CUSTOM_SHELVES])
            preferences[Keys.CUSTOM_SHELVES] = encodeShelves(transform(current))
        }
    }

    suspend fun isWelcomeBookSeeded(): Boolean =
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[Keys.WELCOME_BOOK_SEEDED] ?: false

    suspend fun markWelcomeBookSeeded() {
        dataStore.edit { preferences -> preferences[Keys.WELCOME_BOOK_SEEDED] = true }
    }

    companion object {
        const val MAIN_SHELF = "Основная"
        val DEFAULT_SHELVES = listOf("Избранное", "Учеба", "Художественная")

        private val gson = Gson()

        internal fun encodeShelves(shelves: List<String>): String = gson.toJson(shelves.toTypedArray())

        internal fun decodeShelves(json: String?): List<String> {
            if (json == null) return DEFAULT_SHELVES
            val parsed: Array<String?> = try {
                gson.fromJson(json, Array<String?>::class.java) ?: return emptyList()
            } catch (e: Exception) {
                return DEFAULT_SHELVES
            }
            return parsed.filterNotNull()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
        }
    }
}
