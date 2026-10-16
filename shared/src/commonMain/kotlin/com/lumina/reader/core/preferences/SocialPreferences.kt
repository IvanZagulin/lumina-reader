package com.lumina.reader.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Membership of the reading circle and what this device shares.
 *
 * [sharingEnabled] is off until the reader turns it on, and nothing leaves the
 * device before that: what a person reads is theirs. [hiddenBookKeys] keeps
 * single books out of the circle while sharing is otherwise on.
 */
data class SocialSettings(
    /** Base address of the circle's server ("https://social.example.ru"). */
    val serverUrl: String = "",
    /** Bearer token this device received when it redeemed an invite. */
    val token: String = "",
    val userId: String = "",
    val displayName: String = "",
    val sharingEnabled: Boolean = false,
    /** Keys of books ([com.lumina.reader.core.social.SharedShelf.bookKey]) kept private. */
    val hiddenBookKeys: Set<String> = emptySet(),
) {
    /** True once this device has joined a circle and can talk to it. */
    val joined: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/**
 * Stored in the DataStore file [PreferenceFiles.SOCIAL]; on Android
 * `SocialPreferences(context)` (androidMain) gives the process-wide store.
 */
class SocialPreferences(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SHARING_ENABLED = booleanPreferencesKey("sharing_enabled")
        val HIDDEN_BOOKS = stringSetPreferencesKey("hidden_book_keys")
    }

    val settings: Flow<SocialSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            SocialSettings(
                serverUrl = preferences[Keys.SERVER_URL].orEmpty(),
                token = preferences[Keys.TOKEN].orEmpty(),
                userId = preferences[Keys.USER_ID].orEmpty(),
                displayName = preferences[Keys.DISPLAY_NAME].orEmpty(),
                sharingEnabled = preferences[Keys.SHARING_ENABLED] ?: false,
                hiddenBookKeys = preferences[Keys.HIDDEN_BOOKS].orEmpty(),
            )
        }
        .distinctUntilChanged()

    /** Remembers the circle this device just joined. Sharing stays off until asked for. */
    suspend fun saveMembership(serverUrl: String, userId: String, token: String, displayName: String) {
        dataStore.edit { preferences ->
            preferences[Keys.SERVER_URL] = serverUrl.trim().trimEnd('/')
            preferences[Keys.USER_ID] = userId
            preferences[Keys.TOKEN] = token
            preferences[Keys.DISPLAY_NAME] = displayName.trim()
        }
    }

    /**
     * Forgets the circle. The token is dropped and sharing switched off, so a
     * device that left stops sending anything; clearing what the server already
     * holds is the caller's job (push an empty shelf first).
     */
    suspend fun leave() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.TOKEN)
            preferences.remove(Keys.USER_ID)
            preferences.remove(Keys.SERVER_URL)
            preferences.remove(Keys.DISPLAY_NAME)
            preferences[Keys.SHARING_ENABLED] = false
        }
    }

    suspend fun setSharingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SHARING_ENABLED] = enabled }
    }

    suspend fun setBookHidden(bookKey: String, hidden: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[Keys.HIDDEN_BOOKS].orEmpty()
            preferences[Keys.HIDDEN_BOOKS] = if (hidden) current + bookKey else current - bookKey
        }
    }
}
