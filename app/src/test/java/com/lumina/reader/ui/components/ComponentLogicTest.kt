package com.lumina.reader.ui.components

import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComponentLogicTest {

    private val eps = 0.001f

    @Test
    fun thicknessFollowsFileSizeWithPdfFixed() {
        assertEquals(2f, bookThicknessDp(100_000, BookFormat.EPUB), eps)
        assertEquals(3f, bookThicknessDp(1_200_000, BookFormat.FB2), eps)
        assertEquals(6f, bookThicknessDp(50_000_000, BookFormat.TXT), eps)
        assertEquals(4f, bookThicknessDp(50_000_000, BookFormat.PDF), eps)
    }

    @Test
    fun coverModelOfABook() {
        val book = Book(id = 5, title = "Дюна", author = "Герберт", filePath = "/b/5.fb2", coverPath = "/c/5.jpg")
        val model = book.toCoverModel()
        assertEquals(5L, model.bookId)
        assertEquals("book:5", model.key)
        assertEquals(File("/c/5.jpg"), model.image)
        assertNull(book.copy(coverPath = " ").toCoverModel().image)
    }

    @Test
    fun clothIsDeterministicAndCaseInsensitive() {
        val a = ClothPalette.indexFor("Мастер и Маргарита", "Булгаков")
        val b = ClothPalette.indexFor("МАСТЕР И МАРГАРИТА", "булгаков")
        assertEquals(a, b)
        assertTrue(a in 0 until ClothPalette.all.size)
        assertEquals(8, ClothPalette.all.size)
        // Only Охра uses debossed (dark) ink.
        assertEquals(listOf("Охра"), ClothPalette.all.filterNot(Cloth::isFoil).map(Cloth::name))
        repeat(200) { i ->
            assertTrue(ClothPalette.indexFor("Книга $i", "Автор ${i * 7}") in 0 until 8)
        }
    }

    @Test
    fun generatedTitleSizeByLength() {
        assertEquals(15f, generatedTitleSizeDp("Дюна", 96f), eps)
        assertEquals(13f, generatedTitleSizeDp("Мастер и Маргарита", 96f), eps)
        assertEquals(11.5f, generatedTitleSizeDp("Гарри Поттер и философский камень", 96f), eps)
        assertEquals(10.5f, generatedTitleSizeDp("Удивительное путешествие Нильса с дикими гусями", 96f), eps)
        assertEquals(18.75f, generatedTitleSizeDp("Дюна", 120f), eps)
    }

    @Test
    fun coverflowFractionIsCentredOnTheViewport() {
        // Viewport -20..340 (content padding 20 dp), centre 160, half width 180.
        assertEquals(0f, coverflowFraction(itemOffset = 112, itemSize = 96, viewportStart = -20, viewportEnd = 340), eps)
        assertEquals(-1f, coverflowFraction(itemOffset = -200, itemSize = 96, viewportStart = -20, viewportEnd = 340), eps)
        assertEquals(0.5f, coverflowFraction(itemOffset = 202, itemSize = 96, viewportStart = -20, viewportEnd = 340), eps)
        assertEquals(1f, coverflowFraction(itemOffset = 900, itemSize = 96, viewportStart = -20, viewportEnd = 340), eps)
        assertEquals(0f, coverflowFraction(itemOffset = 10, itemSize = 96, viewportStart = 0, viewportEnd = 0), eps)
    }

    @Test
    fun bookcaseColumns() {
        assertEquals(3 to 96f, bookcaseLayout(360f, 96f))
        assertEquals(3 to 96f, bookcaseLayout(412f, 96f))
        assertEquals(5 to 120f, bookcaseLayout(700f, 120f))
        // Three columns always; covers shrink on a very narrow screen.
        val (columns, width) = bookcaseLayout(320f, 96f)
        assertEquals(3, columns)
        assertTrue(3 * width + 2 * 14f + 40f <= 320.01f)
    }

    @Test
    fun paletteSampleSizeBringsCoverUnder96Px() {
        assertEquals(1, paletteSampleSize(80, 96, 96))
        assertEquals(16, paletteSampleSize(600, 900, 96))
        assertEquals(1, paletteSampleSize(0, 0, 96))
        val sample = paletteSampleSize(1200, 1800, 96)
        assertTrue(1800 / sample <= 96)
        assertTrue(1800 / (sample / 2) > 96)
    }

    @Test
    fun grainIsSeededAndFaint() {
        val first = PaperGrain.pixels()
        val second = PaperGrain.pixels()
        assertEquals(PaperGrain.SIZE * PaperGrain.SIZE, first.size)
        assertTrue(first.contentEquals(second))
        assertTrue(first.all { (it ushr 24) in 0..18 })
        assertTrue(first.any { (it ushr 24) > 0 })
        val rgbs = first.map { it and 0xFFFFFF }.toSet()
        assertEquals(setOf(0x000000, 0xFFFFFF), rgbs)
    }

    @Test
    fun shelfBookUiAndDescription() {
        val book = Book(
            id = 3,
            title = "Ведьмак",
            author = "Сапковский",
            filePath = "/b/3.fb2",
            currentProgressPercent = 42.4f,
            isFavorite = true,
            seriesName = "Ведьмак",
            seriesOrder = 3
        )
        val ui = book.toShelfBookUi()
        assertEquals(0.424f, ui.progress, eps)
        assertEquals(3, ui.seriesNumber)
        assertEquals(false, ui.isNew)
        assertEquals(
            "«Ведьмак», Сапковский, прочитано 42%, в избранном, книга 3 в серии",
            shelfBookDescription(ui)
        )
        val finished = book.copy(isCompleted = true, isFavorite = false, seriesName = "").toShelfBookUi()
        assertNull(finished.seriesNumber)
        assertEquals("«Ведьмак», Сапковский, прочитана", shelfBookDescription(finished))
        assertTrue(book.copy(currentProgressPercent = 0f).toShelfBookUi().isNew)
    }

    @Test
    fun downloadChipStatesMapFromImporterStates() {
        assertEquals(DownloadChipState.Idle, (null as DownloadState?).toChipState())
        assertEquals(DownloadChipState.Queued, DownloadState.Queued.toChipState())
        assertEquals(DownloadChipState.Running(0.5f), DownloadState.Running(50, 100).toChipState())
        assertEquals(DownloadChipState.Running(null), DownloadState.Running(50, null).toChipState())
        assertEquals(DownloadChipState.Importing, DownloadState.Running(100, 100, isImporting = true).toChipState())
        assertEquals(DownloadChipState.Done(9), DownloadState.Completed(9, "Книга").toChipState())
        assertEquals(
            DownloadChipState.InLibrary(9),
            DownloadState.Completed(9, "Книга", alreadyInLibrary = true).toChipState()
        )
        assertEquals(DownloadChipState.Failed("нет сети"), DownloadState.Failed("нет сети").toChipState())
    }

    @Test
    fun newlyShelvedCountsOnlyFreshBooks() {
        val before = mapOf(
            "a" to DownloadState.Running(10, 100),
            "b" to DownloadState.Completed(1, "Б"),
            "c" to DownloadState.Queued,
            "d" to DownloadState.Running(5, null)
        )
        val after = mapOf(
            "a" to DownloadState.Completed(2, "А"),
            "b" to DownloadState.Completed(1, "Б"),
            "c" to DownloadState.Completed(3, "В", alreadyInLibrary = true),
            "d" to DownloadState.Failed("ошибка"),
            "e" to DownloadState.Completed(4, "Г")
        )
        assertEquals(setOf("a", "e"), newlyShelvedKeys(before, after))
        assertEquals(emptySet<String>(), newlyShelvedKeys(after, after))
    }

    @Test
    fun onlyBooksAddedLaterDropOntoTheShelf() {
        val arrivals = ShelfArrivals()
        // The first look at a row animates nothing.
        arrivals.update(listOf(1L, 2L), animate = true)
        assertFalse(arrivals.consume(1L))
        // A new book drops once; known ones never do.
        arrivals.update(listOf(3L, 1L, 2L), animate = true)
        assertTrue(arrivals.consume(3L))
        assertFalse(arrivals.consume(3L))
        assertFalse(arrivals.consume(2L))
        // Reduced motion records the book without queueing the drop.
        arrivals.update(listOf(4L, 3L, 1L, 2L), animate = false)
        assertFalse(arrivals.consume(4L))
        // A row restored from saved state already knows its books.
        val restored = ShelfArrivals(listOf(1L, 2L))
        restored.update(listOf(5L, 1L, 2L), animate = true)
        assertTrue(restored.consume(5L))
        assertFalse(restored.consume(1L))
    }
}
