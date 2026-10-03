package com.lumina.reader.ui.downloads

import com.lumina.reader.core.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUiMapperTest {

    private val a = "https://flibusta.is/b/1/fb2"
    private val b = "https://flibusta.is/b/2/epub"
    private val c = "https://example.org/files/Dune%20Messiah.fb2.zip"

    @Test
    fun rowPhasesFollowTheState() {
        assertEquals(DownloadPhase.QUEUED, DownloadUiMapper.row(a, DownloadState.Queued, null).phase)
        assertEquals(DownloadPhase.RUNNING, DownloadUiMapper.row(a, DownloadState.Running(10, 100), null).phase)
        assertEquals(
            DownloadPhase.IMPORTING,
            DownloadUiMapper.row(a, DownloadState.Running(100, 100, isImporting = true), null).phase
        )
        assertEquals(DownloadPhase.DONE, DownloadUiMapper.row(a, DownloadState.Completed(5, "Дюна"), null).phase)
        assertEquals(
            DownloadPhase.IN_LIBRARY,
            DownloadUiMapper.row(a, DownloadState.Completed(5, "Дюна", alreadyInLibrary = true), null).phase
        )
        assertEquals(DownloadPhase.FAILED, DownloadUiMapper.row(a, DownloadState.Failed("Нет сети"), null).phase)
    }

    @Test
    fun fractionOnlyWhileTransferringWithKnownSize() {
        assertEquals(0.25f, DownloadUiMapper.row(a, DownloadState.Running(25, 100), null).fraction!!, 0.0001f)
        assertEquals(25, DownloadUiMapper.row(a, DownloadState.Running(25, 100), null).percent)
        assertNull(DownloadUiMapper.row(a, DownloadState.Running(25, null), null).fraction)
        assertNull(DownloadUiMapper.row(a, DownloadState.Running(100, 100, isImporting = true), null).fraction)
        assertNull(DownloadUiMapper.row(a, DownloadState.Queued, null).fraction)
    }

    @Test
    fun titlePrefersCompletedThenMetaThenUrl() {
        val meta = DownloadMeta(title = "Дюна", author = "Фрэнк Герберт", formatLabel = "FB2")
        assertEquals("Дюна", DownloadUiMapper.row(a, DownloadState.Queued, meta).title)
        assertEquals("Фрэнк Герберт", DownloadUiMapper.row(a, DownloadState.Queued, meta).author)
        assertEquals("Дюна (2021)", DownloadUiMapper.row(a, DownloadState.Completed(1, "Дюна (2021)"), meta).title)
        assertEquals("Dune Messiah", DownloadUiMapper.row(c, DownloadState.Queued, null).title)
        assertEquals("Книга", DownloadUiMapper.row(a, DownloadState.Queued, null).title)
        assertEquals(1L, DownloadUiMapper.row(a, DownloadState.Completed(1, "x"), null).bookId)
        assertEquals("Нет сети", DownloadUiMapper.row(a, DownloadState.Failed("Нет сети"), null).errorMessage)
    }

    @Test
    fun titleFromUrlSkipsOpaqueSegments() {
        assertNull(DownloadUiMapper.titleFromUrl("https://flibusta.is/b/123/fb2"))
        assertNull(DownloadUiMapper.titleFromUrl("https://host/get/12345"))
        assertNull(DownloadUiMapper.titleFromUrl("https://host/"))
        assertEquals("War and Peace", DownloadUiMapper.titleFromUrl("https://host/War_and_Peace.epub?x=1"))
        assertEquals("Мастер", DownloadUiMapper.titleFromUrl("https://host/%D0%9C%D0%B0%D1%81%D1%82%D0%B5%D1%80.fb2.zip"))
    }

    @Test
    fun statusTexts() {
        assertEquals("В очереди", DownloadUiMapper.statusText(DownloadState.Queued))
        assertEquals("Подключение…", DownloadUiMapper.statusText(DownloadState.Running(0, null)))
        assertEquals("Обработка…", DownloadUiMapper.statusText(DownloadState.Running(5, 5, isImporting = true)))
        assertEquals("На полке", DownloadUiMapper.statusText(DownloadState.Completed(1, "x")))
        assertEquals("Уже на полке", DownloadUiMapper.statusText(DownloadState.Completed(1, "x", true)))
        assertEquals("Ошибка", DownloadUiMapper.statusText(DownloadState.Failed("Ошибка")))
        assertTrue(DownloadUiMapper.statusText(DownloadState.Running(512, 1024)).endsWith("50%"))
    }

    @Test
    fun rowsPutActiveFirstThenNewestFinished() {
        val downloads = linkedMapOf<String, DownloadState>(
            a to DownloadState.Completed(1, "A"),
            b to DownloadState.Running(1, 10),
            c to DownloadState.Failed("x"),
            "d" to DownloadState.Queued
        )
        val keys = DownloadUiMapper.rows(downloads, emptyMap()).map { it.key }
        assertEquals(listOf(b, "d", c, a), keys)
    }

    @Test
    fun newlyFinishedOnlyReportsTransitionsFromActive() {
        val before = mapOf(a to DownloadState.Running(5, 10), b to DownloadState.Queued, c to DownloadState.Failed("old"))
        val after = mapOf(
            a to DownloadState.Completed(1, "A"),
            b to DownloadState.Running(1, null),
            c to DownloadState.Failed("old")
        )
        assertEquals(listOf(a), DownloadUiMapper.newlyFinished(before, after))
        assertEquals(emptyList<String>(), DownloadUiMapper.newlyFinished(after, after))
        // A cancelled download disappears: nothing to announce.
        assertEquals(emptyList<String>(), DownloadUiMapper.newlyFinished(before, emptyMap()))
    }

    @Test
    fun batchGrowsWhileActiveAndResetsWhenIdle() {
        var batch = DownloadUiMapper.nextBatch(emptySet(), mapOf(a to DownloadState.Queued))
        assertEquals(setOf(a), batch)
        batch = DownloadUiMapper.nextBatch(batch, mapOf(a to DownloadState.Completed(1, "A"), b to DownloadState.Queued))
        assertEquals(setOf(a, b), batch)
        // Cancelled downloads leave the batch.
        batch = DownloadUiMapper.nextBatch(batch + c, mapOf(a to DownloadState.Completed(1, "A"), b to DownloadState.Queued))
        assertEquals(setOf(a, b), batch)
        batch = DownloadUiMapper.nextBatch(batch, mapOf(a to DownloadState.Completed(1, "A"), b to DownloadState.Completed(2, "B")))
        assertTrue(batch.isEmpty())
    }

    @Test
    fun islandProgressForOneAndSeveralDownloads() {
        assertNull(DownloadUiMapper.progress(emptyList(), emptySet()))

        val single = DownloadUiMapper.rows(mapOf(a to DownloadState.Running(42, 100)), mapOf(a to DownloadMeta("Дюна")))
        val one = DownloadUiMapper.progress(single, setOf(a))!!
        assertEquals(1, one.activeCount)
        assertEquals(42, one.percent)
        assertEquals("Загрузка «Дюна»", one.label)

        val downloads = linkedMapOf<String, DownloadState>(
            a to DownloadState.Completed(1, "A"),
            b to DownloadState.Running(50, 100),
            c to DownloadState.Queued
        )
        val several = DownloadUiMapper.progress(DownloadUiMapper.rows(downloads, emptyMap()), setOf(a, b, c))!!
        assertEquals(2, several.activeCount)
        assertEquals(1, several.doneInBatch)
        assertEquals(3, several.batchSize)
        assertEquals(0.5f, several.fraction!!, 0.0001f)
        assertEquals("Загружаются 2 книги · 1/3", several.label)
    }

    @Test
    fun announcementsForFinishedStatesOnly() {
        assertNull(DownloadUiMapper.announcementFor(a, DownloadState.Queued, null))
        assertNull(DownloadUiMapper.announcementFor(a, null, null))
        assertTrue(DownloadUiMapper.announcementFor(a, DownloadState.Completed(1, "A"), null) is IslandAnnouncement.Success)
        assertTrue(DownloadUiMapper.announcementFor(a, DownloadState.Failed("x"), null) is IslandAnnouncement.Failure)
    }

    @Test
    fun russianPlurals() {
        assertEquals("книга", pluralBooks(1))
        assertEquals("книги", pluralBooks(3))
        assertEquals("книг", pluralBooks(5))
        assertEquals("книг", pluralBooks(12))
        assertEquals("книга", pluralBooks(21))
    }
}
