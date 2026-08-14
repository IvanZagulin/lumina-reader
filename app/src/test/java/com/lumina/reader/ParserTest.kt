package com.lumina.reader

import com.lumina.reader.core.bionic.BionicReadingHelper
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.fb2.fb2ChapterTitle
import com.lumina.reader.core.parser.txt.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ParserTest {

    @Test
    fun testBookFormatDetection() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("war_and_peace.epub"))
        assertEquals(BookFormat.FB2, BookFormat.fromFileName("master_and_margarita.fb2"))
        assertEquals(BookFormat.FB2_ZIP, BookFormat.fromFileName("dune.fb2.zip"))
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("notes.txt"))
    }

    @Test
    fun testTxtParser() {
        val sampleText = """
Глава 1. Начало путешествия

Это первый абзац замечательной книги.
Он содержит интересную мысль.

Глава 2. Продолжение

Это второй абзац книги.
Здесь разворачиваются основные события.
        """.trimIndent()

        val parser = TxtParser()
        val stream = ByteArrayInputStream(sampleText.toByteArray(Charsets.UTF_8))
        val parsed = parser.parse(stream, "test_book.txt")

        assertEquals("test_book", parsed.title)
        assertTrue(parsed.chapters.isNotEmpty())
        assertEquals(2, parsed.chapters.size)
        assertEquals("Глава 1. Начало путешествия", parsed.chapters[0].title)
        assertEquals("Глава 2. Продолжение", parsed.chapters[1].title)
    }

    @Test
    fun untitledFb2SectionsUseChapterTitles() {
        val generatedTitles = listOf(
            fb2ChapterTitle("", chapterIndex = 0),
            fb2ChapterTitle("   ", chapterIndex = 1)
        )

        assertEquals(listOf("Глава 1", "Глава 2"), generatedTitles)
        assertTrue(generatedTitles.none { it.startsWith("Раздел") })
        assertEquals("Пролог", fb2ChapterTitle("Пролог", chapterIndex = 2))
    }

    @Test
    fun testBionicReadingHelper() {
        val input = "Lumina Reader флагманская читалка"
        val annotated = BionicReadingHelper.transform(input)
        assertNotNull(annotated)
        assertEquals(input, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
