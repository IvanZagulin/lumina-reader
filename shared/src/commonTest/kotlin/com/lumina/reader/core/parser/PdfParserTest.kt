package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.pdf.PdfParser
import com.lumina.reader.core.parser.pdf.PdfSummary
import okio.Buffer
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page and TOC layout of a PDF book; the platform engine is replaced by a fake. */
class PdfParserTest {

    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    @Test
    fun onePagePerChapterAndEveryFifthPageInTheToc() {
        val book = PdfParser { PdfSummary(pageCount = 12, coverBytes = cover) }.parse("/books/Атлас.pdf".toPath())
        assertEquals(BookFormat.PDF, book.format)
        assertEquals("Атлас", book.title)
        assertEquals("PDF Документ", book.author)
        assertEquals("Файл формата PDF (12 стр.)", book.description)
        assertContentEquals(cover, book.coverBytes)
        assertEquals((1..12).map { "Страница $it" }, book.chapters.map { it.title })
        assertEquals((0 until 12).toList(), book.chapters.map { it.pdfPageNumber })
        assertTrue(book.chapters.all { it.paragraphs.isEmpty() })
        assertEquals(listOf(0, 5, 10, 11), book.tableOfContents.map { it.chapterIndex })
        assertEquals("pdf_page_5", book.tableOfContents[1].id)
    }

    @Test
    fun unreadableOrEmptyDocumentGivesOneExplainingPage() {
        for (summary in listOf(null, PdfSummary(pageCount = 0, coverBytes = null))) {
            val book = PdfParser { summary }.parse("/books/broken.pdf".toPath())
            assertEquals("broken", book.title)
            assertEquals(listOf("Не удалось открыть PDF документ"), book.chapters.single().paragraphs)
            assertEquals("Страница 1", book.chapters.single().title)
            assertEquals(emptyList(), book.tableOfContents)
            assertNull(book.coverBytes)
        }
    }

    @Test
    fun sourceOverloadTakesTheTitleFromTheFileName() {
        var inspected: ByteArray? = null
        val parser = PdfParser { path ->
            inspected = okio.FileSystem.SYSTEM.read(path) { readByteArray() }
            PdfSummary(pageCount = 1, coverBytes = null)
        }
        val book = parser.parse(Buffer().write("%PDF-1.4".utf8()), "Отчёт.pdf")
        assertEquals("Отчёт", book.title)
        assertContentEquals("%PDF-1.4".utf8(), inspected)
    }
}
