package com.lumina.reader.core.preferences

import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.CatalogSettingsCodec
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.basicCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The stored formats of the settings, on every platform (Android JVM and iOS). */
class SettingsCodecsTest {

    @Test
    fun fileNamesAreTheOnesInstalledAppsHave() {
        assertEquals("reader_settings", PreferenceFiles.READER)
        assertEquals("library_settings", PreferenceFiles.LIBRARY)
        assertEquals("opds_catalogs", PreferenceFiles.CATALOGS)
        assertEquals("reminder_settings", PreferenceFiles.REMINDERS)
        assertEquals("lumina_ui", PreferenceFiles.UI)
        assertEquals("reader_settings.preferences_pb", PreferenceFiles.dataStoreFileName(PreferenceFiles.READER))
    }

    @Test
    fun shelvesReadWhatGsonWrote() {
        // Gson escapes HTML characters; any JSON reader decodes them.
        assertEquals(
            listOf("Учёба & работа", "<Новое>"),
            LibraryPreferences.decodeShelves("""["Учёба & работа","<Новое>"]""")
        )
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, LibraryPreferences.decodeShelves(null))
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, LibraryPreferences.decodeShelves("not json"))
        assertEquals(emptyList(), LibraryPreferences.decodeShelves("null"))
        assertEquals(emptyList(), LibraryPreferences.decodeShelves("  "))
        assertEquals(listOf("А"), LibraryPreferences.decodeShelves("""[null," А ","а",""]"""))
        assertEquals("""["А","Б \"В\""]""", LibraryPreferences.encodeShelves(listOf("А", "Б \"В\"")))
    }

    @Test
    fun catalogsRoundTripAndKeepTheirStoredShape() {
        val user = OpdsCatalogConfig(
            id = "user:1",
            name = "Домашний",
            url = "http://192.168.1.2:8080/opds",
            username = "me",
            password = "секрет",
            enabled = false
        )
        val json = CatalogSettingsCodec.encodeUserCatalogs(listOf(user, BuiltInCatalogs.all.first()))
        assertEquals(
            """[{"id":"user:1","name":"Домашний","url":"http://192.168.1.2:8080/opds",""" +
                """"username":"me","password":"секрет","enabled":false}]""",
            json
        )
        assertEquals(listOf(user), CatalogSettingsCodec.decodeUserCatalogs(json))
        assertEquals("Basic bWU60YHQtdC60YDQtdGC", user.authHeaders()["Authorization"])
    }

    @Test
    fun brokenCatalogJsonIsDropped() {
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs(null).isEmpty())
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs("").isEmpty())
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs("not json").isEmpty())
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs("null").isEmpty())
        val partial = CatalogSettingsCodec.decodeUserCatalogs(
            """[{"url":" https://example.org/opds ","enabled":null,"extra":1},{"name":"no url"},null]"""
        ).single()
        assertEquals("https://example.org/opds", partial.url)
        assertEquals("example.org", partial.name)
        assertTrue(partial.enabled)
        assertTrue(partial.id.startsWith("user:"))
    }

    @Test
    fun basicCredentialsAreBase64OfUtf8() {
        assertEquals("Basic dXNlcjpwYXNz", basicCredentials("user", "pass"))
        assertEquals("Basic Og==", basicCredentials("", ""))
        assertEquals("Basic bWU60YHQtdC60YDQtdGC", basicCredentials("me", "секрет"))
    }
}
