package com.lumina.reader.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadStatesTest {

    private val url = "https://example.org/b/1/fb2"

    private fun reduceAll(vararg events: DownloadEvent): Map<String, DownloadState> =
        events.fold(emptyMap()) { states, event -> DownloadStates.reduce(states, event) }

    @Test
    fun fullSuccessfulLifecycle() {
        val states = reduceAll(
            DownloadEvent.Enqueued(url),
            DownloadEvent.Progress(url, 50, 200),
            DownloadEvent.Importing(url),
            DownloadEvent.Succeeded(url, bookId = 42, title = "Книга", alreadyInLibrary = false)
        )
        assertEquals(DownloadState.Completed(42, "Книга"), states[url])
    }

    @Test
    fun progressReportsFractionAndPercent() {
        val states = reduceAll(DownloadEvent.Enqueued(url), DownloadEvent.Progress(url, 50, 200))
        val running = states[url] as DownloadState.Running
        assertEquals(0.25f, running.fraction!!, 0.0001f)
        assertEquals(25, running.percent)
        assertFalse(running.isImporting)

        val unknownSize = DownloadStates.reduce(states, DownloadEvent.Progress(url, 70, null))[url] as DownloadState.Running
        assertNull(unknownSize.fraction)
        assertNull(DownloadState.Running(5, 0).fraction)
    }

    @Test
    fun importingKeepsTheTransferredSize() {
        val states = reduceAll(
            DownloadEvent.Enqueued(url),
            DownloadEvent.Progress(url, 200, 200),
            DownloadEvent.Importing(url)
        )
        assertEquals(DownloadState.Running(200, 200, isImporting = true), states[url])
    }

    @Test
    fun enqueueIsIgnoredWhileActiveButAllowedAfterFailure() {
        val running = reduceAll(DownloadEvent.Enqueued(url), DownloadEvent.Progress(url, 1, 10))
        assertSame(running, DownloadStates.reduce(running, DownloadEvent.Enqueued(url)))
        assertFalse(DownloadStates.canStart(running[url]))

        val failed = DownloadStates.reduce(running, DownloadEvent.Failed(url, "Сервер недоступен"))
        assertEquals(DownloadState.Failed("Сервер недоступен"), failed[url])
        assertTrue(DownloadStates.canStart(failed[url]))
        assertEquals(DownloadState.Queued, DownloadStates.reduce(failed, DownloadEvent.Enqueued(url))[url])
    }

    @Test
    fun lateEventsDoNotOverwriteFinishedOrClearedDownloads() {
        val completed = reduceAll(
            DownloadEvent.Enqueued(url),
            DownloadEvent.Succeeded(url, 1, "Книга", alreadyInLibrary = true)
        )
        assertSame(completed, DownloadStates.reduce(completed, DownloadEvent.Progress(url, 5, 10)))
        assertSame(completed, DownloadStates.reduce(completed, DownloadEvent.Failed(url, "x")))
        assertEquals(DownloadState.Completed(1, "Книга", alreadyInLibrary = true), completed[url])

        val cleared = DownloadStates.reduce(completed, DownloadEvent.Cleared(url))
        assertTrue(cleared.isEmpty())
        assertTrue(DownloadStates.reduce(cleared, DownloadEvent.Progress(url, 1, 2)).isEmpty())
    }

    @Test
    fun keysAreIndependent() {
        val other = "https://example.org/b/2/epub"
        val states = reduceAll(
            DownloadEvent.Enqueued(url),
            DownloadEvent.Enqueued(other),
            DownloadEvent.Failed(other, "Ошибка"),
            DownloadEvent.Progress(url, 3, null)
        )
        assertEquals(DownloadState.Running(3, null), states[url])
        assertEquals(DownloadState.Failed("Ошибка"), states[other])
    }

    @Test
    fun throttleLetsFirstFinalAndSpacedUpdatesThrough() {
        val throttle = ProgressThrottle(minIntervalMillis = 1000, minPercentStep = 10)
        assertTrue(throttle.shouldEmit(0, 1000, nowMillis = 0))
        assertFalse(throttle.shouldEmit(10, 1000, nowMillis = 100))
        assertTrue(throttle.shouldEmit(150, 1000, nowMillis = 200)) // +15 %
        assertFalse(throttle.shouldEmit(160, 1000, nowMillis = 300))
        assertTrue(throttle.shouldEmit(170, 1000, nowMillis = 1300)) // interval elapsed
        assertTrue(throttle.shouldEmit(1000, 1000, nowMillis = 1301)) // finished
        assertFalse(throttle.shouldEmit(1000, 1000, nowMillis = 1302))
    }

    @Test
    fun formatsSizesInRussian() {
        assertEquals("512 Б", formatByteSize(512))
        assertEquals("2 КБ", formatByteSize(2048))
        assertEquals("1,5 МБ", formatByteSize(1536L * 1024))
        assertEquals("1 МБ", describeProgress(1024L * 1024, null).replace(",0", ""))
        assertEquals("512 КБ из 1,0 МБ · 50%", describeProgress(512L * 1024, 1024L * 1024))
    }
}
