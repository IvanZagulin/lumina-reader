package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.ui.reader.navigation.TOC_COLLAPSE_THRESHOLD
import com.lumina.reader.ui.reader.navigation.currentTocIndex
import com.lumina.reader.ui.reader.navigation.defaultTocExpanded
import com.lumina.reader.ui.reader.navigation.highlightsMarkdown
import com.lumina.reader.ui.reader.navigation.quoteShareText
import com.lumina.reader.ui.reader.navigation.relativeDateLabel
import com.lumina.reader.ui.reader.navigation.tabTitle
import com.lumina.reader.ui.reader.navigation.tocAncestors
import com.lumina.reader.ui.reader.navigation.tocHasChildren
import com.lumina.reader.ui.reader.navigation.visibleTocIndices
import com.lumina.reader.ui.reader.search.groupSearchResults
import com.lumina.reader.ui.reader.search.russianPlural
import com.lumina.reader.ui.reader.search.searchPositionLabel
import com.lumina.reader.ui.reader.search.searchSummary
import com.lumina.reader.ui.reader.selection.AskAiMode
import com.lumina.reader.ui.reader.selection.MAX_AI_QUOTE_LENGTH
import com.lumina.reader.ui.reader.selection.askAiMessages
import com.lumina.reader.ui.reader.tts.formatSpeed
import com.lumina.reader.ui.reader.tts.minutesUntil
import com.lumina.reader.ui.reader.tts.nextTtsSpeed
import com.lumina.reader.ui.reader.tts.snapToStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReaderNavigationLogicTest {

    private val toc = listOf(
        TocItem("p1", "Часть 1", chapterIndex = 0, level = 0),
        TocItem("c1", "Глава 1", chapterIndex = 0, level = 1),
        TocItem("c2", "Глава 2", chapterIndex = 1, level = 1),
        TocItem("s1", "Сцена", chapterIndex = 1, level = 2, paragraphIndex = 40),
        TocItem("p2", "Часть 2", chapterIndex = 2, level = 0),
        TocItem("c3", "Глава 3", chapterIndex = 2, level = 1)
    )

    @Test
    fun currentEntryIsTheLastOneStartedBeforeThePosition() {
        assertEquals(1, currentTocIndex(toc, chapterIndex = 0, paragraphIndex = 5))
        assertEquals(2, currentTocIndex(toc, chapterIndex = 1, paragraphIndex = 10))
        assertEquals(3, currentTocIndex(toc, chapterIndex = 1, paragraphIndex = 40))
        assertEquals(5, currentTocIndex(toc, chapterIndex = 9, paragraphIndex = 0))
        assertEquals(-1, currentTocIndex(toc.drop(2), chapterIndex = 0, paragraphIndex = 0))
    }

    @Test
    fun nestingAndCollapsing() {
        assertTrue(tocHasChildren(toc, 0))
        assertTrue(tocHasChildren(toc, 2))
        assertFalse(tocHasChildren(toc, 1))
        assertFalse(tocHasChildren(toc, 5))
        assertEquals(listOf(0, 2), tocAncestors(toc, 3))
        assertEquals(emptyList<Int>(), tocAncestors(toc, 0))

        // Short lists open fully.
        assertEquals(setOf(0, 2, 4), defaultTocExpanded(toc, current = 3))
        assertEquals(toc.indices.toList(), visibleTocIndices(toc, setOf(0, 2, 4)))
        // Collapsed parents hide their subtree.
        assertEquals(listOf(0, 4, 5), visibleTocIndices(toc, setOf(4)))
        assertEquals(listOf(0, 1, 2, 4, 5), visibleTocIndices(toc, setOf(0, 4)))
        assertEquals(listOf(0, 4), visibleTocIndices(toc, emptySet()))

        // Long lists open only the way to the current entry.
        val long = List(TOC_COLLAPSE_THRESHOLD + 5) { i ->
            TocItem("i$i", "Пункт $i", chapterIndex = i / 2, level = if (i % 2 == 0) 0 else 1)
        }
        assertEquals(setOf(10), defaultTocExpanded(long, current = 11))
    }

    @Test
    fun datesReadNaturally() {
        val zone = TimeZone.getTimeZone("Europe/Moscow")
        val now = Calendar.getInstance(zone).apply { set(2026, Calendar.OCTOBER, 3, 18, 0) }.timeInMillis
        val hour = 60 * 60 * 1000L
        assertEquals("сегодня", relativeDateLabel(now - 2 * hour, now, zone))
        assertEquals("вчера", relativeDateLabel(now - 24 * hour, now, zone))
        val september = Calendar.getInstance(zone).apply { set(2026, Calendar.SEPTEMBER, 12, 10, 0) }.timeInMillis
        assertEquals("12 сент.", relativeDateLabel(september, now, zone))
        val lastYear = Calendar.getInstance(zone).apply { set(2025, Calendar.MAY, 1, 10, 0) }.timeInMillis
        assertEquals("1 мая 2025", relativeDateLabel(lastYear, now, zone))
        assertEquals("Закладки 4", tabTitle("Закладки", 4))
        assertEquals("Цитаты", tabTitle("Цитаты", 0))
    }

    @Test
    fun quotesExportToMarkdown() {
        val markdown = highlightsMarkdown(
            bookTitle = "Сад",
            author = "Автор",
            highlights = listOf(
                highlight(chapter = 1, paragraph = 2, text = "Вторая", note = "мысль"),
                highlight(chapter = 0, paragraph = 1, text = "Первая\nстрока")
            ),
            chapterTitle = { "Глава ${it + 1}" }
        )
        val expected = "# Сад\n_Автор_\n\n## Глава 1\n\n> Первая\n> строка\n\n## Глава 2\n\n> Вторая\n\nмысль\n"
        assertEquals(expected, markdown)
        assertEquals("«Цитата» — Сад, Автор", quoteShareText(" Цитата ", "Сад", "Автор"))
        assertEquals("«Цитата»", quoteShareText("Цитата", "", ""))
    }

    @Test
    fun searchResultsAreSummarisedAndGrouped() {
        val results = listOf(result(0, 1), result(0, 4), result(2, 0), result(3, 3), result(3, 5))
        val groups = groupSearchResults(results)
        assertEquals(listOf(0, 2, 3), groups.map { it.chapterIndex })
        assertEquals(listOf(2, 1, 2), groups.map { it.results.size })
        assertEquals("5 совпадений в 3 главах", searchSummary(results, truncated = false))
        assertEquals("1 совпадение в 1 главе", searchSummary(results.take(1), truncated = false))
        assertEquals("Первые 2 совпадения в 1 главе", searchSummary(results.take(2), truncated = true))
        assertEquals("совпадений", russianPlural(12, "совпадение", "совпадения", "совпадений"))
        assertEquals("совпадение", russianPlural(21, "совпадение", "совпадения", "совпадений"))
        assertEquals("3 из 37", searchPositionLabel(2, 37))
        assertEquals("— из 37", searchPositionLabel(-1, 37))
    }

    @Test
    fun readAloudControls() {
        assertEquals(1.25f, nextTtsSpeed(1f))
        assertEquals(0.75f, nextTtsSpeed(2f))
        // An unusual speed snaps to the nearest step first.
        assertEquals(1.5f, nextTtsSpeed(1.6f))
        assertEquals("1,25×", formatSpeed(1.25f))
        assertEquals("1×", formatSpeed(1f))
        assertEquals("0,75×", formatSpeed(0.75f))
        assertEquals(15, minutesUntil(endsAt = 15 * 60_000L, now = 0L))
        assertEquals(1, minutesUntil(endsAt = 10_000L, now = 0L))
        assertEquals(0, minutesUntil(endsAt = 0L, now = 5L))
        assertEquals(1.25f, snapToStep(1.31f, 0.25f, 0.5f), 1e-4f)
        assertEquals(1.1f, snapToStep(1.12f, 0.1f, 0.5f), 1e-4f)
    }

    @Test
    fun askAiSendsAShortRussianPrompt() {
        val messages = askAiMessages(AskAiMode.EXPLAIN, "  Мысль изречённая есть ложь.  ", "Silentium", "Тютчев")
        assertEquals(listOf("system", "user"), messages.map { it.role })
        val user = messages[1].content
        assertTrue(user.startsWith("Объясни"))
        assertTrue(user.contains("Книга: Silentium, Тютчев"))
        assertTrue(user.endsWith("«Мысль изречённая есть ложь.»"))
        val long = askAiMessages(AskAiMode.TRANSLATE, "а".repeat(MAX_AI_QUOTE_LENGTH + 500), "", "")[1].content
        assertFalse(long.contains("Книга:"))
        assertTrue(long.length < MAX_AI_QUOTE_LENGTH + 200)
    }

    private fun highlight(chapter: Int, paragraph: Int, text: String, note: String? = null) = ReadingHighlight(
        bookId = 1,
        chapterIndex = chapter,
        selectedText = text,
        note = note,
        paragraphIndex = paragraph,
        startOffset = 0,
        endOffset = text.length
    )

    private fun result(chapter: Int, paragraph: Int) = SearchResult(
        chapterIndex = chapter,
        chapterTitle = "Глава ${chapter + 1}",
        paragraphIndex = paragraph,
        matchStart = 0,
        matchEnd = 2,
        snippet = "сад",
        snippetMatchStart = 0,
        snippetMatchEnd = 2
    )
}
