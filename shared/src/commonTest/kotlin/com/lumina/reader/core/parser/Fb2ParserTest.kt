package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParagraphMarkup.InlineKind
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.fb2.Fb2Parser
import com.lumina.reader.core.text.TestEncoders
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FB2 books of the parser tests; the JVM parity test parses them with both file readers. */
internal object Fb2Fixtures {

    val coverBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    )
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // Starts like a PNG: only raster images the reader can decode are kept.
    val pictureBytes = ByteArray(3000) { (it * 7 % 251).toByte() }.also { pngSignature.copyInto(it) }

    fun base64Lines(bytes: ByteArray): String =
        bytes.toByteString().base64().chunked(76).joinToString("\n")

    val sample by lazy {
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

    const val untitledSubsections = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook><body>
              <section><title><p>Пролог</p></title><section><p>Текст пролога.</p></section></section>
              <section><title><p>Глава 1</p></title><p>Начало.</p><section><p>Сцена два.</p></section></section>
            </body></FictionBook>"""

    const val declaredCp1251 = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook><body><section><title><p>Глава</p></title><p>Ёлка и щука</p></section></body></FictionBook>"""

    const val undeclared = """<FictionBook><body><section><p>Без декларации</p></section></body></FictionBook>"""

    const val utf16 = "﻿<?xml version=\"1.0\" encoding=\"UTF-16\"?><FictionBook><body><section><p>Юникод</p></section></body></FictionBook>"

    const val truncated = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook><body><section><title><p>Глава 1</p></title><p>Целый абзац.</p><p>Оборван"""

    val bigImage: ByteArray by lazy {
        Random(42).nextBytes(ByteArray(4 * 1024 * 1024)).also { coverBytes.copyInto(it) } // JPEG header
    }

    val largeImage by lazy {
        """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns:xlink="http://www.w3.org/1999/xlink"><body><section><p>Текст</p><image xlink:href="#big"/></section></body>
            <binary id="big" content-type="image/jpeg">${base64Lines(bigImage)}</binary></FictionBook>"""
    }

    val missingImages by lazy {
        """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><body>
            <section><title><p>Глава 1</p></title><image l:href="#gone"/><p>Начало.</p><image l:href="#svg"/><image l:href="#pic"/>
              <section><title><p>Подраздел</p></title><p>Текст.</p></section>
            </section></body>
            <binary id="svg" content-type="image/svg+xml">${base64Lines("<svg/>".utf8())}</binary>
            <binary id="pic" content-type="image/png">${base64Lines(pictureBytes)}</binary>
            </FictionBook>"""
    }

    fun zipped(): ByteArray = TestZip.build(
        linkedMapOf(
            "readme.txt" to "hello".utf8(),
            "book.fb2" to sample.utf8()
        )
    )

    /** Every fixture as file bytes with the file name it is parsed under. */
    fun all(): List<Pair<String, ByteArray>> = listOf(
        "sample.fb2" to sample.utf8(),
        "untitled.fb2" to untitledSubsections.utf8(),
        "cp1251.fb2" to TestEncoders.windows1251(declaredCp1251),
        "undeclared.fb2" to TestEncoders.windows1251(undeclared),
        "utf16.fb2" to TestEncoders.utf16(utf16, bigEndian = false),
        "truncated.fb2" to truncated.utf8(),
        "large.fb2" to largeImage.utf8(),
        "missing.fb2" to missingImages.utf8(),
        "book.fb2.zip" to zipped(),
        "renamed.fb2_zip" to zipped(),
        "noBook.fb2.zip" to TestZip.build(linkedMapOf("readme.txt" to "hello".utf8())),
        "empty.fb2" to ByteArray(0)
    )
}

class Fb2ParserTest {

    private fun parse(bytes: ByteArray, name: String = "book.fb2"): ParsedBook =
        Fb2Parser().parse(Buffer().write(bytes), name)

    private fun parse(text: String, name: String = "book.fb2"): ParsedBook = parse(text.utf8(), name)

    private fun plain(raw: String) = ParagraphMarkup.plainText(raw)

    private val sample get() = Fb2Fixtures.sample

    @Test
    fun metadata() {
        val book = parse(sample)
        assertEquals(BookFormat.FB2, book.format)
        assertEquals("Двенадцать стульев", book.title)
        assertEquals("Ильф Илья, Петров Евгений", book.author)
        assertEquals("Аннотация книги.", book.description)
        assertEquals("Остап", book.seriesName)
        assertEquals(1, book.seriesOrder)
        assertContentEquals(Fb2Fixtures.coverBytes, book.coverBytes)
        assertContentEquals(Fb2Fixtures.pictureBytes, book.images["pic.png"])
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
        val book = parse(Fb2Fixtures.untitledSubsections)
        assertEquals(listOf("Пролог", "Глава 1"), book.chapters.map { it.title })
        assertEquals(listOf("Пролог", "Глава 1"), book.tableOfContents.map { it.title })
        assertEquals(listOf("Начало.", "", "Сцена два."), book.chapters[1].paragraphs)
    }

    @Test
    fun legacyEncodingsAreDetected() {
        assertEquals("Ёлка и щука", parse(TestEncoders.windows1251(Fb2Fixtures.declaredCp1251)).chapters[0].paragraphs[0])
        assertEquals("Без декларации", parse(TestEncoders.windows1251(Fb2Fixtures.undeclared)).chapters[0].paragraphs[0])
        val utf16 = TestEncoders.utf16(Fb2Fixtures.utf16, bigEndian = false)
        assertEquals("Юникод", parse(utf16).chapters[0].paragraphs[0])
    }

    @Test
    fun koi8rDeclarationIsHonoured() {
        val text = """<?xml version="1.0" encoding="koi8-r"?>
            <FictionBook><body><section><title><p>Глава</p></title><p>Съешь ещё этих мягких булок</p></section></body></FictionBook>"""
        assertEquals("Съешь ещё этих мягких булок", parse(TestEncoders.koi8r(text)).chapters[0].paragraphs[0])
    }

    @Test
    fun damagedFileKeepsParsedText() {
        val book = parse(Fb2Fixtures.truncated)
        val paragraphs = book.chapters.single().paragraphs
        assertEquals("Целый абзац.", paragraphs[0])
        assertEquals(Fb2Parser.DAMAGED_NOTE, plain(paragraphs.last()))
    }

    @Test
    fun largeImagesAreNotTruncated() {
        val book = parse(Fb2Fixtures.largeImage)
        assertContentEquals(Fb2Fixtures.bigImage, book.images["big"])
        assertEquals(listOf("Текст", "[IMG:big]"), book.chapters.single().paragraphs)
    }

    @Test
    fun zippedFb2() {
        val bytes = Fb2Fixtures.zipped()
        val book = TestFiles.withFile(bytes, ".fb2_zip") { Fb2Parser().parse(it) }
        assertEquals(BookFormat.FB2_ZIP, book.format)
        assertEquals(3, book.chapters.size)
        val fromSource = parse(bytes, "book.fb2.zip")
        assertEquals("Двенадцать стульев", fromSource.title)
        assertFalse(fromSource.chapters.isEmpty())
    }

    @Test
    fun zipWithoutABookExplainsIt() {
        val bytes = TestZip.build(linkedMapOf("readme.txt" to "hello".utf8()))
        val book = parse(bytes, "book.fb2.zip")
        assertEquals(BookFormat.FB2_ZIP, book.format)
        assertEquals(listOf("В архиве не найден файл FB2"), book.chapters.single().paragraphs)
    }

    @Test
    fun unreadableZipGivesAnErrorChapter() {
        // The zip reader's own message follows the prefix (java.util.zip and Okio word it differently).
        val book = parse(sample, "book.fb2.zip")
        assertEquals(BookFormat.FB2_ZIP, book.format)
        assertEquals("book.fb2", book.title)
        assertTrue(book.chapters.single().paragraphs.single().startsWith("Не удалось прочитать файл: "))
    }

    @Test
    fun emptyFileIsReportedAsDamaged() {
        val book = parse(ByteArray(0))
        assertEquals(listOf("Текст не найден. Возможно, файл повреждён."), book.chapters.single().paragraphs)
    }

    @Test
    fun missingOrUnusableImagesAreDroppedAndTocFollows() {
        val book = parse(Fb2Fixtures.missingImages)
        val paragraphs = book.chapters.single().paragraphs
        assertEquals(listOf("Начало.", "[IMG:pic]", "Подраздел", "Текст."), paragraphs.map { plain(it) })
        assertFalse(book.images.containsKey("svg"))
        assertContentEquals(Fb2Fixtures.pictureBytes, book.images["pic"])
        val sub = book.tableOfContents.single { it.title == "Подраздел" }
        assertEquals(BlockStyle.HEADING, ParagraphMarkup.blockStyle(paragraphs[sub.paragraphIndex]))
        assertEquals("Подраздел", plain(paragraphs[sub.paragraphIndex]))
    }
}
