package com.lumina.reader

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.fb2.Fb2Parser
import com.lumina.reader.core.parser.fb2.fb2ChapterTitle
import com.lumina.reader.core.parser.txt.TxtParser
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Moved from :app (stage 6); the BionicReadingHelper test stays there (BionicReadingHelperTest, Robolectric).
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
        val parsed = parser.parse(Buffer().writeUtf8(sampleText), "test_book.txt")

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
    fun fb2NotesBodyDoesNotCreateReaderChapters() {
        val fb2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook>
              <body>
                <section><title><p>Глава 1</p></title><p>Основной текст.</p></section>
              </body>
              <body name="notes">
                <section><title><p>Сноска 1</p></title><p>Текст сноски.</p></section>
              </body>
            </FictionBook>
        """.trimIndent()

        val parsed = Fb2Parser().parse(Buffer().writeUtf8(fb2), "notes.fb2")

        assertEquals(1, parsed.chapters.size)
        assertEquals("Глава 1", parsed.chapters.single().title)
        assertEquals(listOf("Основной текст."), parsed.chapters.single().paragraphs)
        assertTrue(!parsed.chapters.single().title.contains("Сноска", ignoreCase = true))
        assertTrue(parsed.chapters.single().paragraphs.none { it.contains("сноски") })
    }
}
