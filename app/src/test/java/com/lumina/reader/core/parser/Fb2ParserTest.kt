package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParagraphMarkup.InlineKind
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.fb2.Fb2Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fb2ParserTest {

    private val coverBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    )
    private val pictureBytes = ByteArray(3000) { (it * 7 % 251).toByte() }

    private fun base64Lines(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).chunked(76).joinToString("\n")

    private fun parse(text: String, charset: Charset = Charsets.UTF_8, name: String = "book.fb2"): ParsedBook =
        Fb2Parser().parse(ByteArrayInputStream(text.toByteArray(charset)), name)

    private fun plain(raw: String) = ParagraphMarkup.plainText(raw)

    private val sample by lazy {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description>
          <title-info>
            <author><first-name>Илья</first-name><last-name>Ильф</last-name></author>
            <author><first-name>Евгений</first-name><last-name>Петров</last-name></author>
            <book-title>Двенадцать&nbsp;стульев</book-title>
            <annotation><p>Аннотация книги.</p></annotation>
            <sequence name="Остап" number="1"/>
            <coverpage><image l:href="#cover.jpg"/></coverpage>
          </title-info>
          <document-info><author><nickname>bookmaker</nickname></author></document-info>
        </description>
        <body>
          <title><p>Двенадцать стульев</p></title>
          <section>
            <title><p>Часть первая</p><p>Старгородский лев</p></title>
            <epigraph><p>Эпиграф части</p><text-author>Автор</text-author></epigraph>
            <section>
              <title><p>Глава 1</p></title>
              <p>Первый абзац
              с переносом строки.</p>
              <p>Текст со сноской<a l:href="#n1" type="note">[1]</a>.</p>
              <subtitle>* * *</subtitle>
              <poem>
                <title><p>Стихотворение</p></title>
                <stanza><v>Строка 1</v><v>Строка 2</v></stanza>
                <stanza><v>Строка 3</v></stanza>
              </poem>
              <cite><p>Цитата</p><text-author>Кто-то</text-author></cite>
              <table><tr><td>A</td><td>B</td></tr></table>
              <p><emphasis>курсив</emphasis> и <strong>жирный</strong></p>
              <image l:href="#pic.png"/>
              <section>
                <title><p>Подраздел</p></title>
                <p>Текст подраздела.</p>
              </section>
            </section>
            <section>
              <p>Безымянная глава.</p>
            </section>
          </section>
        </body>
        <body name="notes">
          <title><p>Примечания</p></title>
          <section id="n1"><title><p>1</p></title><p>Текст <emphasis>сноски</emphasis>.</p></section>
        </body>
        <binary id="cover.jpg" content-type="image/jpeg">${base64Lines(coverBytes)}</binary>
        <binary id="pic.png" content-type="image/png">${base64Lines(pictureBytes)}</binary>
        </FictionBook>
        """.trimIndent()
    }

    @Test
    fun metadata() {
        val book = parse(sample)
        assertEquals(BookFormat.FB2, book.format)
        assertEquals("Двенадцать стульев", book.title)
        assertEquals("Ильф Илья, Петров Евгений", book.author)
        assertEquals("Аннотация книги.", book.description)
        assertEquals("Остап", book.seriesName)
        assertEquals(1, book.seriesOrder)
        assertArrayEquals(coverBytes, book.coverBytes)
        assertArrayEquals(pictureBytes, book.images["pic.png"])
    }

    @Test
    fun chaptersFollowSectionStructure() {
        val book = parse(sample)
        val titles = book.chapters.map { it.title }
        assertEquals(3, titles.size)
        assertEquals("Часть первая. Старгородский лев", titles[0])
        assertEquals("Глава 1", titles[1])
        assertTrue(titles[2].startsWith("Глава"))
        assertTrue(titles.none { it.contains("Примечания") || it.contains("Стихотворение") || it.contains(" - ") })

        val part = book.chapters[0].paragraphs
        assertEquals(BlockStyle.EPIGRAPH, ParagraphMarkup.blockStyle(part[0]))
        assertEquals("Эпиграф части", plain(part[0]))
        assertEquals(BlockStyle.TEXT_AUTHOR, ParagraphMarkup.blockStyle(part[1]))

        assertEquals(listOf("Безымянная глава."), book.chapters[2].paragraphs)

        val toc = book.tableOfContents
        assertEquals(
            listOf("Часть первая. Старгородский лев", "Глава 1", "Подраздел", titles[2]),
            toc.map { it.title }
        )
        assertEquals(listOf(0, 1, 2, 1), toc.map { it.level })
        assertEquals(listOf(0, 1, 1, 2), toc.map { it.chapterIndex })
        val sub = toc[2]
        val heading = book.chapters[1].paragraphs[sub.paragraphIndex]
        assertEquals(BlockStyle.HEADING, ParagraphMarkup.blockStyle(heading))
        assertEquals("Подраздел", plain(heading))
    }

    @Test
    fun blockElementsAreMapped() {
        val paragraphs = parse(sample).chapters[1].paragraphs
        assertEquals("Первый абзац с переносом строки.", paragraphs[0])

        fun styled(style: BlockStyle) = paragraphs.filter { ParagraphMarkup.blockStyle(it) == style }.map { plain(it) }
        assertEquals(listOf("* * *", "Стихотворение"), styled(BlockStyle.SUBTITLE))
        assertEquals(listOf("Строка 1", "Строка 2", "Строка 3"), styled(BlockStyle.VERSE))
        val v2 = paragraphs.indexOfFirst { plain(it) == "Строка 2" }
        assertEquals("", paragraphs[v2 + 1]) // stanza gap
        assertEquals(listOf("Цитата"), styled(BlockStyle.EPIGRAPH))
        assertEquals(listOf("Кто-то"), styled(BlockStyle.TEXT_AUTHOR))
        assertTrue(paragraphs.contains("A | B"))
        assertTrue(paragraphs.contains("[IMG:pic.png]"))

        val formatted = ParagraphMarkup.parse(paragraphs.single { plain(it) == "курсив и жирный" })
        assertEquals(listOf(InlineKind.EMPHASIS, InlineKind.STRONG), formatted.spans.map { it.kind })
    }

    @Test
    fun notesBecomeFootnotesNotChapters() {
        val book = parse(sample)
        val raw = book.chapters[1].paragraphs.single { plain(it).startsWith("Текст со сноской") }
        val parsed = ParagraphMarkup.parse(raw)
        val ref = parsed.spans.single { it.kind == InlineKind.NOTE_REF }
        assertEquals("1", parsed.text.substring(ref.start, ref.end))
        assertEquals("n1", ref.noteId)
        assertEquals("Текст сноски.", plain(book.footnotes.getValue("n1")))
        assertTrue(book.chapters.none { chapter -> chapter.paragraphs.any { plain(it).contains("Текст сноски") } })
    }

    @Test
    fun untitledSubsectionsInheritTitleOrBecomeSceneBreaks() {
        val text = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook><body>
              <section><title><p>Пролог</p></title><section><p>Текст пролога.</p></section></section>
              <section><title><p>Глава 1</p></title><p>Начало.</p><section><p>Сцена два.</p></section></section>
            </body></FictionBook>"""
        val book = parse(text)
        assertEquals(listOf("Пролог", "Глава 1"), book.chapters.map { it.title })
        assertEquals(listOf("Пролог", "Глава 1"), book.tableOfContents.map { it.title })
        assertEquals(listOf("Начало.", "", "Сцена два."), book.chapters[1].paragraphs)
    }

    @Test
    fun legacyEncodingsAreDetected() {
        val cp1251 = Charset.forName("windows-1251")
        val declared = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook><body><section><title><p>Глава</p></title><p>Ёлка и щука</p></section></body></FictionBook>"""
        assertEquals("Ёлка и щука", parse(declared, cp1251).chapters[0].paragraphs[0])

        val undeclared = """<FictionBook><body><section><p>Без декларации</p></section></body></FictionBook>"""
        assertEquals("Без декларации", parse(undeclared, cp1251).chapters[0].paragraphs[0])

        val utf16 = "﻿<?xml version=\"1.0\" encoding=\"UTF-16\"?><FictionBook><body><section><p>Юникод</p></section></body></FictionBook>"
        assertEquals("Юникод", parse(utf16, Charsets.UTF_16LE).chapters[0].paragraphs[0])
    }

    @Test
    fun damagedFileKeepsParsedText() {
        val truncated = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook><body><section><title><p>Глава 1</p></title><p>Целый абзац.</p><p>Оборван"""
        val book = parse(truncated)
        val paragraphs = book.chapters.single().paragraphs
        assertEquals("Целый абзац.", paragraphs[0])
        assertEquals(Fb2Parser.DAMAGED_NOTE, plain(paragraphs.last()))
    }

    @Test
    fun largeImagesAreNotTruncated() {
        val big = ByteArray(4 * 1024 * 1024).also { Random(42).nextBytes(it) }
        val text = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns:xlink="http://www.w3.org/1999/xlink"><body><section><p>Текст</p><image xlink:href="#big"/></section></body>
            <binary id="big" content-type="image/jpeg">${base64Lines(big)}</binary></FictionBook>"""
        val book = parse(text)
        assertArrayEquals(big, book.images["big"])
        assertEquals(listOf("Текст", "[IMG:big]"), book.chapters.single().paragraphs)
    }

    @Test
    fun zippedFb2() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("book.fb2"))
            zip.write(sample.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val file = File.createTempFile("fb2_test_", ".fb2_zip")
        try {
            file.writeBytes(out.toByteArray())
            val book = Fb2Parser().parse(file)
            assertEquals(BookFormat.FB2_ZIP, book.format)
            assertEquals(3, book.chapters.size)
        } finally {
            file.delete()
        }
        val fromStream = Fb2Parser().parse(ByteArrayInputStream(out.toByteArray()), "book.fb2.zip")
        assertEquals("Двенадцать стульев", fromStream.title)
        assertFalse(fromStream.chapters.isEmpty())
    }
}
