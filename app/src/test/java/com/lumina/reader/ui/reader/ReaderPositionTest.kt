package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ReadingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionTest {

    private val chapters = listOf(
        Chapter(index = 0, title = "One", paragraphs = listOf("a", "b")),
        Chapter(index = 1, title = "Two", paragraphs = listOf("Первый абзац главы", "c"))
    )

    @Test
    fun restoresOutOfRangePositionToLastAvailableText() {
        val restored = restoreReaderPosition(chapters, chapterIndex = 12, paragraphIndex = 42, charOffset = 3)
        assertEquals(ReaderPosition(chapterIndex = 1, paragraphIndex = 1, charOffset = 1), restored.position)
        assertFalse(restored.atChapterEnd)
    }

    @Test
    fun keepsCharacterOffsetInsideTheParagraph() {
        val restored = restoreReaderPosition(chapters, chapterIndex = 1, paragraphIndex = 0, charOffset = 7)
        assertEquals(ReaderPosition(1, 0, 7), restored.position)
        val clamped = restoreReaderPosition(chapters, chapterIndex = 1, paragraphIndex = 0, charOffset = 500)
        assertEquals("Первый абзац главы".length, clamped.position.charOffset)
        assertEquals(0, restoreReaderPosition(chapters, 1, 0, -4).position.charOffset)
    }

    @Test
    fun legacyEndOfChapterMarkerOpensTheLastPage() {
        val restored = restoreReaderPosition(chapters, chapterIndex = 0, paragraphIndex = Int.MAX_VALUE, charOffset = 0)
        assertTrue(restored.atChapterEnd)
        assertEquals(ReaderPosition(0, 1, 0), restored.position)
    }

    @Test
    fun pdfChapterWithoutParagraphsRestoresToItsStart() {
        val pdf = listOf(Chapter(index = 0, title = "Страница 1", paragraphs = emptyList(), pdfPageNumber = 0))
        assertEquals(ReaderPosition(0, 0, 0), restoreReaderPosition(pdf, 0, 5, 9).position)
    }

    @Test
    fun anchorsOrderByParagraphThenCharacter() {
        assertTrue(TextAnchor(1, 50) < TextAnchor(2, 0))
        assertTrue(TextAnchor(2, 3) < TextAnchor(2, 4))
        assertTrue(TextAnchor(999, 999) < TextAnchor.CHAPTER_END)
        assertEquals(TextAnchor(0, 0), TextAnchor.START)
    }

    @Test
    fun navigationRequestAnchor() {
        assertEquals(TextAnchor(3, 7), NavigationRequest(1, 0, 3, 7).anchor)
        assertEquals(TextAnchor.CHAPTER_END, NavigationRequest(2, 0, 3, 7, toChapterEnd = true).anchor)
    }

    @Test
    fun visibleRangeIncludesStartAndExcludesEnd() {
        val range = VisibleRange(2, TextAnchor(4, 10), TextAnchor(6, 0))
        assertTrue(range.contains(2, 4, 10))
        assertTrue(range.contains(2, 5, 900))
        assertFalse(range.contains(2, 6, 0))
        assertFalse(range.contains(2, 4, 9))
        assertFalse(range.contains(3, 5, 0))
    }

    @Test
    fun wordsAreCountedOncePerSession() {
        val tracker = SessionWordTracker()
        val text = "Раз два  три\nчетыре пять"
        assertEquals(3, tracker.count(0, 0, text, 0, 12))
        // The same page again, and an overlapping range, add only new words.
        assertEquals(0, tracker.count(0, 0, text, 0, 12))
        assertEquals(2, tracker.count(0, 0, text, 4, text.length))
        // Another paragraph or chapter is counted separately.
        assertEquals(5, tracker.count(1, 0, text, 0, text.length))
        tracker.clear()
        assertEquals(5, tracker.count(0, 0, text, 0, text.length))
    }

    @Test
    fun wordBelongsToThePageWhereItStarts() {
        val text = "один два четыре"
        // A page ending inside "четыре" owns it, because the word starts there...
        assertEquals(3, countWords(text, 0, 11))
        // ...so the next page, which starts inside it, does not count it again.
        assertEquals(0, countWords(text, 11, text.length))
        assertEquals(1, countWords(text, 9, text.length))
        assertEquals(0, countWords("   ", 0, 3))
    }

    @Test
    fun readingSpeedNeedsHistory() {
        assertEquals(DEFAULT_WORDS_PER_MINUTE, averageWordsPerMinute(emptyList()))
        assertEquals(DEFAULT_WORDS_PER_MINUTE, averageWordsPerMinute(listOf(stats(seconds = 60, words = 300))))
        assertEquals(250, averageWordsPerMinute(listOf(stats(600, 2_000), stats(600, 3_000))))
        // Implausible values are kept in range.
        assertEquals(1_200, averageWordsPerMinute(listOf(stats(120, 100_000))))
    }

    @Test
    fun minutesLeftRoundUp() {
        assertEquals(0, estimateMinutesLeft(0, 200))
        assertEquals(1, estimateMinutesLeft(1, 200))
        assertEquals(3, estimateMinutesLeft(401, 200))
    }

    @Test
    fun remainingWordsStartAtTheCharacter() {
        val plain = listOf("один два три", "четыре пять", "", "шесть")
        val counts = IntArray(plain.size) { countWords(plain[it]) }
        assertEquals(6, wordsRemainingInChapter(plain, counts, 0, 0))
        assertEquals(5, wordsRemainingInChapter(plain, counts, 0, 5))
        assertEquals(4, wordsRemainingInChapter(plain, counts, 0, 6))
        assertEquals(1, wordsRemainingInChapter(plain, counts, 3, 0))
        assertEquals(0, wordsRemainingInChapter(emptyList(), IntArray(0), 0, 0))
    }

    @Test
    fun bookmarkSnippetStartsAtThePosition() {
        val chapter = Chapter(
            index = 4,
            title = "Пять",
            paragraphs = listOf(
                "[IMG:pic.png]",
                ParagraphMarkup.emphasis("Начало") + " второго абзаца",
                "x".repeat(300)
            )
        )
        assertEquals("Начало второго абзаца", bookmarkSnippet(chapter, 0, 0, isPdf = false))
        assertEquals("второго абзаца", bookmarkSnippet(chapter, 1, 7, isPdf = false))
        val long = bookmarkSnippet(chapter, 2, 0, isPdf = false, maxLength = 20)
        assertEquals("x".repeat(20) + "…", long)
        assertEquals("Страница 5", bookmarkSnippet(chapter, 0, 0, isPdf = true))
        val pdfPage = Chapter(index = 2, title = "Страница 3", paragraphs = emptyList(), pdfPageNumber = 2)
        assertEquals("Страница 3", bookmarkSnippet(pdfPage, 0, 0, isPdf = false))
    }

    @Test
    fun illustrationsHaveNoPlainText() {
        assertEquals("", paragraphPlainText("[IMG:a.jpg]"))
        assertEquals("курсив", paragraphPlainText(ParagraphMarkup.emphasis("курсив")))
    }

    private fun stats(seconds: Long, words: Int) = ReadingStats(
        bookId = 1,
        sessionDurationSeconds = seconds,
        wordsReadCount = words
    )
}
