package com.lumina.reader.core.preferences

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lumina.reader.core.opds.CatalogSettingsCodec
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.basicCredentials
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shelves list and the user's OPDS catalogues were stored as JSON written
 * by Gson before the settings moved to common code (kotlinx.serialization).
 * Values stored by the old code must read back identically, and what the new
 * code writes must still be read by the old code (an older APK installed over
 * a newer one). Gson is still on :app's classpath, so it is the oracle here.
 */
class GsonCompatibilityTest {

    private val gson = Gson()

    /** The class Gson used to store a catalogue (CatalogSettingsCodec before the move). */
    private data class GsonStoredCatalog(
        @SerializedName("id") val id: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("username") val username: String? = null,
        @SerializedName("password") val password: String? = null,
        @SerializedName("enabled") val enabled: Boolean? = null
    )

    private val catalogs = listOf(
        OpdsCatalogConfig(
            id = "user:2f1c7a52-0d0e-4c11-9a43-5d1a8d6f0b7e",
            name = "Домашняя <библиотека> & 'книги'",
            url = "https://example.org/opds?lang=ru&sort=new",
            username = "иван",
            password = "p@ss=word\"\\/\n\t ",
            enabled = false
        ),
        OpdsCatalogConfig(id = "user:2", name = "Без пароля", url = "http://192.168.1.2:8080/opds")
    )

    @Test
    fun catalogsStoredByGsonDecodeIdentically() {
        val stored = gson.toJson(catalogs.map { it.toGson() })
        assertEquals(catalogs, CatalogSettingsCodec.decodeUserCatalogs(stored))
    }

    @Test
    fun gsonOmittedNullsAndUnknownKeysStillDecode() {
        // Gson leaves null fields out.
        val stored = gson.toJson(listOf(GsonStoredCatalog(url = "https://example.org/opds")))
        assertEquals("""[{"url":"https://example.org/opds"}]""", stored)
        val decoded = CatalogSettingsCodec.decodeUserCatalogs(stored).single()
        assertEquals("example.org", decoded.name)
        assertEquals(true, decoded.enabled)
        assertEquals("", decoded.username)

        val withExtra = """[{"url":"https://a.example/opds","name":"A","future":{"x":[1,2]},"enabled":true}]"""
        assertEquals("A", CatalogSettingsCodec.decodeUserCatalogs(withExtra).single().name)
    }

    @Test
    fun catalogsWrittenNowAreReadByTheOldGsonCode() {
        val written = CatalogSettingsCodec.encodeUserCatalogs(catalogs)
        val readByGson = gson.fromJson(written, Array<GsonStoredCatalog?>::class.java).toList()
        assertEquals(catalogs.map { it.toGson() }, readByGson)
    }

    @Test
    fun shelvesStoredByGsonDecodeIdentically() {
        val shelves = listOf("Фантастика", "Учёба & работа", "<Новое>", "Сказки 'народов' = мира", "Tab\tшкаф")
        val stored = gson.toJson(shelves.toTypedArray())
        assertEquals(
            listOf("Фантастика", "Учёба & работа", "<Новое>", "Сказки 'народов' = мира", "Tab шкаф"),
            LibraryPreferences.decodeShelves(stored)
        )
        assertEquals(emptyList<String>(), LibraryPreferences.decodeShelves("null"))
        assertEquals(emptyList<String>(), LibraryPreferences.decodeShelves(""))
        assertEquals(LibraryPreferences.DEFAULT_SHELVES, LibraryPreferences.decodeShelves("{broken"))
    }

    @Test
    fun shelvesWrittenNowAreReadByTheOldGsonCode() {
        val shelves = listOf("Фантастика", "Учёба & работа", "<Новое>", "\"Цитаты\"")
        val written = LibraryPreferences.encodeShelves(shelves)
        assertEquals(shelves, gson.fromJson(written, Array<String?>::class.java).toList())
    }

    @Test
    fun basicCredentialsMatchOkHttp() {
        listOf(
            "me" to "секрет",
            "иван" to "p@ss:word",
            "" to "",
            "user" to "😀 emoji"
        ).forEach { (user, password) ->
            assertEquals(
                Credentials.basic(user, password, Charsets.UTF_8),
                basicCredentials(user, password)
            )
        }
    }

    private fun OpdsCatalogConfig.toGson() = GsonStoredCatalog(
        id = id,
        name = name,
        url = url,
        username = username,
        password = password,
        enabled = enabled
    )
}
