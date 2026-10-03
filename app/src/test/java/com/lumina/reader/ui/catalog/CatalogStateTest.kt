package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.CatalogSettingsCodec
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.catalogFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogStateTest {

    private val fb2 = OpdsAcquisition("https://flibusta.is/b/1/fb2", BookFormat.FB2_ZIP)
    private val epub = OpdsAcquisition("https://flibusta.is/b/1/epub", BookFormat.EPUB)
    private val book = OpdsEntry.Publication(key = "b1", title = "Книга", acquisitions = listOf(fb2, epub))

    @Test
    fun rowShowsTheMostRelevantDownload() {
        assertNull(publicationDownload(book, emptyMap()))

        val failedAndRunning = mapOf(
            fb2.url to DownloadState.Failed("Ошибка"),
            epub.url to DownloadState.Running(10, 100)
        )
        assertEquals(epub, publicationDownload(book, failedAndRunning)?.acquisition)

        val failedAndDone = mapOf(
            fb2.url to DownloadState.Failed("Ошибка"),
            epub.url to DownloadState.Completed(7, "Книга")
        )
        assertEquals(DownloadState.Completed(7, "Книга"), publicationDownload(book, failedAndDone)?.state)

        val onlyFailed = mapOf(fb2.url to DownloadState.Failed("Ошибка"))
        assertEquals(DownloadState.Failed("Ошибка"), publicationDownload(book, onlyFailed)?.state)
    }

    @Test
    fun nextPagesNeverDuplicateListKeys() {
        val first = listOf(nav("a"), nav("b"))
        val next = listOf(nav("b"), nav("c"), nav("c"))
        val merged = appendUniqueEntries(first, next)
        assertEquals(listOf("a", "b", "c"), merged.map { it.key })
        assertEquals(merged.size, merged.map { it.key }.toSet().size)
    }

    @Test
    fun globalSearchCollectsDownloadableBooksOfAllSections() {
        val catalog = BuiltInCatalogs.all.first()
        val noFormats = OpdsEntry.Publication(key = "b2", title = "DjVu", unsupportedFormats = listOf("DJVU"))
        val search = GlobalSearchState(
            query = "q",
            scope = CatalogSearchScope.SERIES,
            sections = listOf(
                CatalogSearchSection(catalog, entries = listOf(book, noFormats, nav("x")), isLoading = false),
                CatalogSearchSection(BuiltInCatalogs.all[1], isLoading = true)
            )
        )
        assertTrue(search.isLoading)
        assertEquals(3, search.totalResults)
        assertEquals(listOf(catalog to book), search.downloadablePublications)
    }

    @Test
    fun stateTitlesFollowTheNavigation() {
        val catalog = BuiltInCatalogs.all.first()
        assertEquals("Каталоги OPDS", CatalogUiState().title)
        assertEquals("Поиск во всех каталогах", CatalogUiState().searchPlaceholder)
        val browsing = CatalogUiState(pages = listOf(CatalogPage(id = 1, catalog = catalog, title = "Новинки", url = catalog.url)))
        assertEquals("Новинки", browsing.title)
        assertEquals("Поиск в «Flibusta»", browsing.searchPlaceholder)
    }

    @Test
    fun userCatalogsRoundTripThroughJson() {
        val user = OpdsCatalogConfig(
            id = "user:1",
            name = "Домашний",
            url = "http://192.168.1.2:8080/opds",
            username = "me",
            password = "секрет",
            enabled = false
        )
        val decoded = CatalogSettingsCodec.decodeUserCatalogs(
            CatalogSettingsCodec.encodeUserCatalogs(listOf(user, BuiltInCatalogs.all.first()))
        )
        assertEquals(listOf(user), decoded)
        assertTrue(decoded.single().hasCredentials)
        assertEquals("Basic bWU60YHQtdC60YDQtdGC", decoded.single().authHeaders()["Authorization"])
    }

    @Test
    fun decodingToleratesBrokenOrPartialJson() {
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs(null).isEmpty())
        assertTrue(CatalogSettingsCodec.decodeUserCatalogs("not json").isEmpty())
        val partial = CatalogSettingsCodec.decodeUserCatalogs("""[{"url":"https://example.org/opds"},{"name":"no url"},null]""")
        assertEquals(1, partial.size)
        assertEquals("example.org", partial.single().name)
        assertTrue(partial.single().enabled)
        assertFalse(partial.single().builtIn)
    }

    @Test
    fun builtInsCanOnlyBeDisabled() {
        val merged = CatalogSettingsCodec.merge(
            builtIns = BuiltInCatalogs.all,
            disabledBuiltInIds = setOf(BuiltInCatalogs.FLIBUSTA_ID),
            userCatalogs = emptyList()
        )
        assertEquals(BuiltInCatalogs.all.map { it.id }, merged.map { it.id })
        assertFalse(merged.first { it.id == BuiltInCatalogs.FLIBUSTA_ID }.enabled)
        assertTrue(merged.filter { it.id != BuiltInCatalogs.FLIBUSTA_ID }.all { it.enabled })
    }

    @Test
    fun urlsAreMatchedToTheirCatalogueIncludingMirrors() {
        val catalogs = BuiltInCatalogs.all
        assertEquals(BuiltInCatalogs.FLIBUSTA_ID, catalogs.catalogFor("https://flibusta.site/b/1/fb2")?.id)
        assertNull(catalogs.catalogFor("https://unknown.example/b/1"))
        // Built-in Flibusta keeps the legacy scoped search base.
        assertEquals("https://flibusta.is", catalogs.first().siteBaseUrl)
    }

    private fun nav(key: String) = OpdsEntry.Navigation(key = key, title = key, url = "https://x/$key")
}
