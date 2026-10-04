package com.lumina.reader.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Library-wide settings: user shelves (which exist even while empty) and
 * one-time flags, in the DataStore file [PreferenceFiles.LIBRARY]. On Android
 * `LibraryPreferences(context)` (androidMain) gives the process-wide store.
 */
class LibraryPreferences(private val dataStore: DataStore<Preferences>) {

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

        /**
         * The shelves are stored as a JSON array of strings (written by Gson
         * before the move to common code; the format is the same).
         */
        private val shelvesSerializer = ListSerializer(String.serializer().nullable).nullable
        private val format = Json

        fun encodeShelves(shelves: List<String>): String =
            format.encodeToString(shelvesSerializer, shelves)

        /**
         * Shelves from the stored JSON: the defaults when nothing was stored
         * or the value is unreadable, an empty list for a stored `null` or a
         * blank value (as Gson read them); null entries are dropped, names
         * trimmed with inner whitespace collapsed, blanks and
         * case-insensitive duplicates removed.
         */
        fun decodeShelves(json: String?): List<String> {
            if (json == null) return DEFAULT_SHELVES
            if (json.isBlank()) return emptyList()
            val parsed: List<String?> = try {
                format.decodeFromString(shelvesSerializer, json) ?: return emptyList()
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
