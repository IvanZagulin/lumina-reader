package com.lumina.reader.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.ui.transition.OpenAnimation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings written by the app before its settings classes moved to :shared
 * must be read back after the move: the same DataStore files
 * (filesDir/datastore/<name>.preferences_pb, as Context.preferencesDataStore
 * created them), the same keys, and the same SharedPreferences file for the
 * UI choices.
 *
 * Everything runs in one test on purpose: Context.preferencesDataStore keeps
 * one DataStore per file for the whole process, bound to the first context
 * that used it, while Robolectric gives every test a fresh files directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenceFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsOfTheShippedAppSurviveTheMove() = runBlocking {
        assertEquals(
            listOf("reader_settings", "library_settings", "opds_catalogs", "reminder_settings"),
            PreferenceFiles.dataStores
        )
        assertEquals("lumina_ui", PreferenceFiles.UI)

        // Files as the shipped app left them (key names copied from it).
        seed("reader_settings") {
            it[intPreferencesKey("font_size_sp")] = 24
            it[stringPreferencesKey("reading_theme")] = "SEPIA"
            it[floatPreferencesKey("tts_speed")] = 1.3f
            it[stringPreferencesKey("page_turn_animation")] = "CURL"
            it[intPreferencesKey("paragraph_spacing_dp")] = 12
        }
        seed("library_settings") {
            it[stringPreferencesKey("custom_shelves_json")] =
                Gson().toJson(arrayOf("Фантастика", "Учёба & работа"))
            it[booleanPreferencesKey("welcome_book_seeded")] = true
        }
        seed("opds_catalogs") {
            it[stringPreferencesKey("user_catalogs_json")] =
                """[{"id":"user:1","name":"Дом","url":"https://example.org/opds?a=1&b=2",""" +
                """"username":"me","password":"секрет","enabled":false}]"""
            it[stringSetPreferencesKey("disabled_builtin_ids")] = setOf("builtin:coollib")
        }
        seed("reminder_settings") {
            it[booleanPreferencesKey("reminder_enabled")] = false
            it[intPreferencesKey("reminder_hour")] = 7
            it[intPreferencesKey("reminder_minute")] = 45
        }
        context.getSharedPreferences("lumina_ui", Context.MODE_PRIVATE).edit()
            .putString("open_animation", "FAST")
            .putBoolean("shelf_captions", true)
            .putString("library_view", "LIST")
            .putString("library_sort", "AUTHOR")
            .commit()

        val reader = ReaderPreferences(context).settingsFlow.first()
        assertEquals(24, reader.fontSizeSp)
        assertEquals(ReadingTheme.SEPIA, reader.theme)
        assertEquals(1.3f, reader.ttsSpeed, 0.0001f)
        assertEquals(PageTurnAnimation.CURL, reader.pageTurnAnimation)
        // The legacy dp spacing is still migrated on read (12 dp at 24 sp = 0.5 em -> 0.35).
        assertEquals(0.35f, reader.paragraphSpacingEm, 0.0001f)

        val library = LibraryPreferences(context)
        assertEquals(listOf("Фантастика", "Учёба & работа"), library.customShelves.first())
        assertTrue(library.isWelcomeBookSeeded())

        val catalogs = CatalogPreferences(context).catalogs.first()
        val user = catalogs.single { it.id == "user:1" }
        assertEquals("Дом", user.name)
        assertEquals("https://example.org/opds?a=1&b=2", user.url)
        assertEquals("me", user.username)
        assertEquals("секрет", user.password)
        assertFalse(user.enabled)
        assertFalse(catalogs.single { it.id == "builtin:coollib" }.enabled)
        assertTrue(catalogs.single { it.id == "builtin:flibusta" }.enabled)

        assertEquals(ReminderSettings(enabled = false, hour = 7, minute = 45), ReminderPreferences(context).current())

        val ui = AppUiPreferences.get(context)
        assertEquals(OpenAnimation.FAST, ui.openAnimation.value)
        assertTrue(ui.shelfCaptions.value)
        assertEquals(LibraryViewMode.LIST, ui.libraryView.value)
        assertEquals(LibrarySort.AUTHOR, ui.librarySort.value)

        // New writes land in the same files.
        ReaderPreferences(context).updateSettings { it.copy(fontSizeSp = 26) }
        assertEquals(26, ReaderPreferences(context).settingsFlow.first().fontSizeSp)
        library.setCustomShelves(listOf("Новая"))
        assertEquals(listOf("Новая"), LibraryPreferences(context).customShelves.first())
        ReminderPreferences(context).setTime(hour = 21, minute = 30)
        assertEquals(21, ReminderPreferences(context).current().hour)
        val files = File(context.filesDir, "datastore").list().orEmpty().toSet()
        assertTrue(
            files.toString(),
            files.containsAll(
                listOf(
                    "reader_settings.preferences_pb",
                    "library_settings.preferences_pb",
                    "opds_catalogs.preferences_pb",
                    "reminder_settings.preferences_pb"
                )
            )
        )
        ui.setLibraryView(LibraryViewMode.BOOKCASE)
        val stored = context.getSharedPreferences("lumina_ui", Context.MODE_PRIVATE)
        assertEquals("BOOKCASE", stored.getString("library_view", null))
    }

    /**
     * Writes [name] where `Context.preferencesDataStore(name)` keeps it, with a
     * separate DataStore that is closed (scope cancelled) before the app's
     * own instance opens the file.
     */
    private suspend fun seed(name: String, block: (MutablePreferences) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(context.filesDir, "datastore/$name.preferences_pb")
        }
        store.edit { block(it) }
        job.cancelAndJoin()
    }
}
