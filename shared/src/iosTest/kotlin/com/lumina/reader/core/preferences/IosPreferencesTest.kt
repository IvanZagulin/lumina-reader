package com.lumina.reader.core.preferences

import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.platform.Ids
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosPreferencesTest {

    private val directory = NSTemporaryDirectory() + "lumina-prefs-" + Ids.randomUuid()
    private val suiteName = "com.lumina.reader.tests." + Ids.randomUuid()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suiteName)
    }

    @Test
    fun dataStoreRoundTripUsesTheAndroidFileNames() = runBlocking {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        val reader = ReaderPreferences(IosPreferenceStores.create(PreferenceFiles.READER, directory))
        reader.updateSettings { it.copy(fontSizeSp = 22, theme = ReadingTheme.SEPIA) }
        val settings = reader.settingsFlow.first()
        assertEquals(22, settings.fontSizeSp)
        assertEquals(ReadingTheme.SEPIA, settings.theme)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$directory/reader_settings.preferences_pb"))

        val library = LibraryPreferences(IosPreferenceStores.create(PreferenceFiles.LIBRARY, directory))
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, library.customShelves.first())
        library.updateCustomShelves { it + "Новая" }
        assertEquals(LibraryPreferences.DEFAULT_SHELVES + "Новая", library.customShelves.first())

        val reminders = ReminderPreferences(IosPreferenceStores.create(PreferenceFiles.REMINDERS, directory))
        reminders.setTime(hour = 25, minute = 61)
        assertEquals(ReminderSettings(enabled = true, hour = 23, minute = 59), reminders.current())
    }

    @Test
    fun userDefaultsStoreKeepsUiChoices() {
        val defaults = NSUserDefaults(suiteName = suiteName)
        val store = NSUserDefaultsUiStore(defaults)
        assertNull(store.getString("library_view"))
        assertTrue(store.getBoolean("shelf_captions", default = true))
        assertFalse(store.getBoolean("shelf_captions", default = false))

        val preferences = AppUiPreferences(store)
        preferences.setLibraryView(LibraryViewMode.LIST)
        preferences.setShelfCaptions(true)
        assertEquals("LIST", store.getString("library_view"))
        assertTrue(store.getBoolean("shelf_captions", default = false))
        assertEquals(LibraryViewMode.LIST, AppUiPreferences(store).libraryView.value)
    }
}
