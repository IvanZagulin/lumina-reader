package com.lumina.reader.core.parser

import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.txt.TxtLayout
import com.lumina.reader.core.parser.txt.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxtParserTest {

    private fun parse(bytes: ByteArray): ParsedBook = TxtParser().parse(ByteArrayInputStream(bytes), "book.txt")

    private fun parse(text: String): ParsedBook = parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun strictHeadingDetection() {
        listOf(
            "Глава 1", "ГЛАВА ПЕРВАЯ", "Глава 12. Возвращение", "Часть II", "Chapter 3", "CHAPTER ONE",
            "Пролог", "Эпилог", "Prologue", "Том третий", "# Начало", "## Подглава"
        ).forEach { assertNotNull(it, TxtLayout.heading(it)) }

        listOf(
            "Глава семьи молча кивнул.", "Том вошёл в комнату", "Part of the problem", "12", "1.",
            "Глава 1 была скучной, сказал он.", "ПРОСТО ЗАГЛАВНЫЕ БУКВЫ", "Прологом служила песня",
            "Chapter civil war"
        ).forEach { assertNull(it, TxtLayout.heading(it)) }
    }

    @Test
    fun falsePositivesDoNotSplitChapters() {
        val text = """
            Глава семьи молча кивнул.

            Том вошёл в комнату и сел.

            Part of the problem was the weather.

            12

            Конец истории.
        """.trimIndent()
        val book = parse(text)
        assertEquals(1, book.chapters.size)
        assertEquals("Часть 1", book.chapters[0].title)
        assertEquals(5, book.chapters[0].paragraphs.size)
    }

    @Test
    fun headingsCreateChaptersAndLevels() {
        val text = """
            Предисловие автора к книге.

            Часть I

            Глава 1. Начало

            Первый абзац.

            ГЛАВА ВТОРАЯ

            Второй абзац.
            * * *
            После разрыва.
        """.trimIndent()
        val book = parse(text)
        assertEquals(listOf("Введение", "Глава 1. Начало", "ГЛАВА ВТОРАЯ"), book.chapters.map { it.title })
        assertEquals(
            listOf("Введение" to 0, "Часть I" to 0, "Глава 1. Начало" to 1, "ГЛАВА ВТОРАЯ" to 1),
            book.tableOfContents.map { it.title to it.level }
        )
        assertEquals(listOf(0, 1, 1, 2), book.tableOfContents.map { it.chapterIndex })
        val last = book.chapters[2].paragraphs
        assertEquals("Второй абзац.", last[0])
        assertEquals(BlockStyle.SUBTITLE, ParagraphMarkup.blockStyle(last[1]))
        assertEquals("После разрыва.", last[2])
    }

    @Test
    fun onePhysicalLinePerParagraph() {
        val longLine = "Это длинный абзац, который занимает одну строку файла и продолжается довольно долго, " +
            "чтобы быть явно длиннее ширины переноса."
        val text = (1..20).joinToString("\n") { "$it. $longLine" }
        val paragraphs = parse(text).chapters.flatMap { it.paragraphs }
        assertEquals(20, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("1. Это"))
    }

    @Test
    fun indentedHardWrappedTextIsJoined() {
        val text = """
            |    Первый абзац начинается с отступа и продолжается
            |на следующей строке без отступа, как это принято в
            |старых текстовых файлах библиотеки.
            |    Второй абзац тоже начинается с отступа и переносится
            |на следующую строку, где и заканчивается спокойно.
            |    Третий абзац, короткий, но тоже с отступом в начале
            |и продолжением на второй строке этого файла.
            |    Четвёртый абзац завершает пример текста с отступами
            |и тоже переносится на вторую строку файла книги.
        """.trimMargin()
        assertEquals(TxtLayout.Mode.INDENTED, TxtLayout.detectMode(text.lines()))
        val paragraphs = parse(text).chapters.single().paragraphs
        assertEquals(4, paragraphs.size)
        assertEquals(
            "Первый абзац начинается с отступа и продолжается на следующей строке без отступа, " +
                "как это принято в старых текстовых файлах библиотеки.",
            paragraphs[0]
        )
    }

    @Test
    fun blankSeparatedHardWrappedText() {
        val block = "Строка текста, перенесённая на фиксированной ширине,\nпродолжается здесь и заканчивается тут."
        val text = List(6) { block }.joinToString("\n\n")
        val paragraphs = parse(text).chapters.single().paragraphs
        assertEquals(6, paragraphs.size)
        assertEquals(block.replace("\n", " "), paragraphs[0])
    }

    @Test
    fun utf8WithEmojiAndBom() {
        val text = "Привет 😀 мир\n\nВторой абзац"
        val book = parse(text)
        assertEquals("Привет 😀 мир", book.chapters[0].paragraphs[0])

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals("Привет 😀 мир", parse(withBom).chapters[0].paragraphs[0])

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals("Привет 😀 мир", parse(utf16).chapters[0].paragraphs[0])

        val cp1251 = "Обычный текст в кодировке Windows".toByteArray(Charset.forName("windows-1251"))
        assertEquals("Обычный текст в кодировке Windows", parse(cp1251).chapters[0].paragraphs[0])
    }

    @Test
    fun textWithoutHeadingsIsSplitIntoParts() {
        val paragraph = "Предложение для проверки разбиения длинного текста на части. ".repeat(10).trim()
        val text = List(200) { paragraph }.joinToString("\n\n")
        val book = parse(text)
        assertTrue(book.chapters.size >= 3)
        assertEquals("Часть 1", book.chapters[0].title)
        assertTrue(book.chapters.all { chapter -> chapter.paragraphs.sumOf { it.length } >= 8_000 })
        assertTrue(book.chapters.all { chapter -> chapter.paragraphs.sumOf { it.length } <= 40_000 })
        assertEquals(200, book.chapters.sumOf { it.paragraphs.size })
    }

    @Test
    fun fileOverloadReadsAndCloses() {
        val file = File.createTempFile("txt_test_", ".txt")
        try {
            file.writeText("Глава 1\n\nТекст.", Charsets.UTF_8)
            val book = TxtParser().parse(file)
            assertEquals("Глава 1", book.chapters.single().title)
            assertFalse(book.chapters.single().paragraphs.isEmpty())
            assertTrue(file.delete())
        } finally {
            file.delete()
        }
    }

    @Test
    fun sentencesThatStartWithAKeywordAreNotHeadings() {
        listOf("Часть первая была скучной", "Book I am reading", "Глава вторая началась тихо")
            .forEach { assertNull(it, TxtLayout.heading(it)) }
        listOf("Часть первая Начало", "Part I The Beginning", "Глава 3 возвращение")
            .forEach { assertNotNull(it, TxtLayout.heading(it)) }
    }

    @Test
    fun hugeChapterAfterASingleHeadingIsSplit() {
        val paragraph = "Длинное предложение для проверки разбиения большой главы на части. ".repeat(10).trim()
        val text = "Пролог\n\n" + List(250) { paragraph }.joinToString("\n\n")
        val book = parse(text)
        assertTrue(book.chapters.size >= 4)
        assertEquals("Пролог (часть 1)", book.chapters[0].title)
        assertEquals("Пролог (часть 2)", book.chapters[1].title)
        assertEquals(250, book.chapters.sumOf { it.paragraphs.size })
        assertTrue(book.chapters.all { chapter -> chapter.paragraphs.sumOf { it.length } <= 40_000 })
        val toc = book.tableOfContents
        assertEquals("Пролог", toc[0].title)
        assertEquals(book.chapters.indices.toList(), toc.map { it.chapterIndex })
        assertEquals(listOf(0) + List(book.chapters.size - 1) { 1 }, toc.map { it.level })
    }

    @Test
    fun shortLinesOfVaryingLengthStayOneParagraphPerLine() {
        val lines = listOf(
            "— Привет!", "— Как дела?", "Он улыбнулся и посмотрел в окно, где шёл дождь.", "— Хорошо.",
            "Она кивнула.", "— Пойдём гулять?", "Дождь стучал по крыше всё сильнее и сильнее, не переставая.",
            "— Нет.", "Тишина.", "— Почему?"
        )
        assertEquals(TxtLayout.Mode.LINE_PER_PARAGRAPH, TxtLayout.detectMode(lines))
        assertEquals(lines, parse(lines.joinToString("\n")).chapters.single().paragraphs)
    }

    @Test
    fun utf8WithAStrayByteIsNotDecodedAsWindows1251() {
        val bytes = "Привет, мир! ".repeat(50).toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) +
            " Ещё текст".toByteArray(Charsets.UTF_8)
        val paragraph = parse(bytes).chapters.single().paragraphs.single()
        assertTrue(paragraph, paragraph.startsWith("Привет, мир!"))
        assertTrue(paragraph, paragraph.endsWith("Ещё текст"))
    }
}
