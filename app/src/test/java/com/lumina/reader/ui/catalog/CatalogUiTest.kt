package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUiTest {

    private fun nav(title: String, url: String = "https://flibusta.is/opds/x") =
        OpdsEntry.Navigation(key = "$title|$url", title = title, url = url)

    private val fb2 = OpdsAcquisition("https://flibusta.is/b/1/fb2", BookFormat.FB2_ZIP, sizeBytes = 1_200_000)
    private val epub = OpdsAcquisition("https://flibusta.is/b/1/epub", BookFormat.EPUB)
    private val pdf = OpdsAcquisition("https://flibusta.is/b/1/pdf", BookFormat.PDF)
    private val book = OpdsEntry.Publication(
        key = "b1",
        title = "Дюна",
        authors = listOf("Фрэнк Герберт"),
        acquisitions = listOf(epub, pdf, fb2)
    )

    @Test
    fun navigationKindsFromTitleAndUrl() {
        assertEquals(OpdsNavKind.AUTHOR, CatalogUi.navKind(nav("Авторы", "https://flibusta.is/opds/authorsindex")))
        assertEquals(OpdsNavKind.AUTHOR, CatalogUi.navKind(nav("Герберт Фрэнк", "https://flibusta.is/opds/author/123")))
        assertEquals(OpdsNavKind.SERIES, CatalogUi.navKind(nav("Серии")))
        assertEquals(OpdsNavKind.SERIES, CatalogUi.navKind(nav("Дюна", "https://flibusta.is/opds/sequencebooks/5")))
        assertEquals(OpdsNavKind.GENRE, CatalogUi.navKind(nav("Жанры")))
        assertEquals(OpdsNavKind.LETTER, CatalogUi.navKind(nav("Б")))
        assertEquals(OpdsNavKind.OTHER, CatalogUi.navKind(nav("Новинки")))
    }

    @Test
    fun alphabetFeedNeedsFourShortFolders() {
        assertTrue(CatalogUi.isAlphabetFeed(listOf(nav("А"), nav("Б"), nav("В"), nav("Га"))))
        assertFalse(CatalogUi.isAlphabetFeed(listOf(nav("А"), nav("Б"), nav("В"))))
        assertFalse(CatalogUi.isAlphabetFeed(listOf(nav("А"), nav("Б"), nav("В"), nav("Новинки"))))
        assertFalse(CatalogUi.isAlphabetFeed(listOf(nav("А"), nav("Б"), nav("В"), book)))
    }

    @Test
    fun seriesAndMetaLines() {
        assertEquals("Дюна · №3", CatalogUi.seriesLine("Дюна", 3))
        assertEquals("Дюна", CatalogUi.seriesLine("Дюна", null))
        assertEquals("Дюна", CatalogUi.seriesLine("Дюна", 0))
        assertEquals(listOf("Русский", "1965", "1,1 МБ"), CatalogUi.metaParts("ru", "1965-01-01", "1,1 МБ"))
        assertEquals(listOf("EO"), CatalogUi.metaParts("eo", "unknown", null))
        assertEquals(emptyList<String>(), CatalogUi.metaParts(" ", null, null))
    }

    @Test
    fun authorLinkIsFoundByTitleOrHref() {
        val byTitle = book.copy(relatedLinks = listOf(OpdsLink("https://x/opds/s/1", title = "Все книги автора Герберт")))
        assertEquals("https://x/opds/s/1", CatalogUi.authorLink(byTitle)?.href)
        val byHref = book.copy(relatedLinks = listOf(OpdsLink("https://x/opds/author/7", title = "Ещё")))
        assertEquals("https://x/opds/author/7", CatalogUi.authorLink(byHref)?.href)
        assertNull(CatalogUi.authorLink(book.copy(relatedLinks = listOf(OpdsLink("https://x/opds/sequence/2", title = "Серия")))))
    }

    @Test
    fun monogramAndClothAreStable() {
        assertEquals("Ф", CatalogUi.monogram("флибуста"))
        assertEquals("C", CatalogUi.monogram("  «CoolLib»"))
        assertEquals("?", CatalogUi.monogram("  "))
        val index = CatalogUi.clothIndex("Flibusta")
        assertEquals(index, CatalogUi.clothIndex("flibusta"))
        assertTrue(index in 0 until CatalogUi.CLOTH_COUNT)
    }

    @Test
    fun breadcrumbsFollowThePageStack() {
        val catalog = OpdsCatalogConfig(id = "c", name = "Флибуста", url = "https://flibusta.is/opds")
        val pages = listOf(
            CatalogPage(id = 1, catalog = catalog, title = "", url = catalog.url),
            CatalogPage(id = 2, catalog = catalog, title = "Авторы", url = "u2"),
            CatalogPage(id = 3, catalog = catalog, title = "Б", url = "u3")
        )
        assertEquals(listOf(1L to "Флибуста", 2L to "Авторы", 3L to "Б"), CatalogUi.breadcrumbs(pages))
    }

    @Test
    fun chipStatesFollowTheDownload() {
        assertEquals(ChipState.Idle, ChipState.of(null, null))
        assertEquals(ChipState.InLibrary(4), ChipState.of(null, 4))
        assertEquals(ChipState.Queued, ChipState.of(DownloadState.Queued, null))
        assertEquals(37, (ChipState.of(DownloadState.Running(37, 100), null) as ChipState.Running).percent)
        assertNull((ChipState.of(DownloadState.Running(37, null), null) as ChipState.Running).fraction)
        assertEquals(ChipState.Importing, ChipState.of(DownloadState.Running(1, 1, isImporting = true), null))
        assertEquals(ChipState.Done(9), ChipState.of(DownloadState.Completed(9, "x"), null))
        assertEquals(ChipState.InLibrary(9), ChipState.of(DownloadState.Completed(9, "x", alreadyInLibrary = true), null))
        assertEquals(ChipState.Failed("Нет сети"), ChipState.of(DownloadState.Failed("Нет сети"), 4))
    }

    @Test
    fun formatChipsPutTheBestFormatFirst() {
        val chips = FormatChips.of(book, emptyMap(), libraryBookId = null)
        assertEquals(listOf(fb2, epub, pdf), chips.items.map { it.first })
        assertEquals(fb2.url, chips.bestUrl)
        assertTrue(chips.items.all { it.second == ChipState.Idle })
        assertFalse(chips.checked)
        assertNull(chips.failure)
    }

    @Test
    fun formatChipsShowTheLibraryCopyUntilSomethingIsDownloaded() {
        val inLibrary = FormatChips.of(book, emptyMap(), libraryBookId = 5)
        assertEquals(ChipState.InLibrary(5), inLibrary.items.first().second)
        assertNull(inLibrary.bestUrl)
        assertTrue(inLibrary.checked)

        val downloading = FormatChips.of(book, mapOf(epub.url to DownloadState.Failed("Ошибка")), libraryBookId = 5)
        assertEquals(ChipState.Idle, downloading.items.first().second)
        assertEquals("Ошибка", downloading.failure)
        assertEquals(fb2.url, downloading.bestUrl)
        assertFalse(downloading.checked)

        val done = FormatChips.of(book, mapOf(pdf.url to DownloadState.Completed(8, "Дюна")), libraryBookId = null)
        assertTrue(done.checked)
        assertEquals(ChipState.Done(8), done.items.last().second)
    }

    @Test
    fun libraryIndexMatchesTitleAndAnAuthorName() {
        val index = LibraryIndex.build(
            listOf(
                LibraryIndex.Entry(1, "«Дюна»", "Фрэнк Герберт"),
                LibraryIndex.Entry(2, "Дюна", "Другой Автор"),
                LibraryIndex.Entry(3, "Пикник на обочине", "Неизвестный автор")
            )
        )
        assertEquals(1L, index.find("Дюна", listOf("Герберт Фрэнк")))
        assertEquals(2L, index.find("ДЮНА", listOf("Автор Другой")))
        assertNull(index.find("Дюна", listOf("Кто-то Ещё")))
        assertEquals(3L, index.find("Пикник на обочине", listOf("Аркадий Стругацкий")))
        assertNull(index.find("Солярис", listOf("Станислав Лем")))
        // Without authors only an unambiguous title matches.
        assertNull(index.find("Дюна", emptyList()))
        assertEquals(3L, index.find("Пикник на обочине", emptyList()))
        assertTrue(LibraryIndex.Empty.isEmpty)
    }

    @Test
    fun batchCountsForSeriesDownloads() {
        val keys = listOf("a", "b", "c")
        val downloads = mapOf("a" to DownloadState.Completed(1, "A"), "b" to DownloadState.Running(1, 2))
        assertEquals(2 to 1, CatalogUi.batchCounts(keys, downloads))
        assertEquals(0 to 0, CatalogUi.batchCounts(keys, emptyMap()))
    }
}
