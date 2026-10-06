package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParagraphMarkup.InlineKind
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.epub.EpubParser
import com.lumina.reader.core.text.TestEncoders
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** EPUB books of the parser tests; the JVM parity test parses them with both container readers. */
internal object EpubFixtures {

    val coverJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(),
        'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1
    )
    val picturePng = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()
    )

    fun zip(entries: Map<String, ByteArray>): ByteArray = TestZip.build(entries)

    val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    val opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
            <dc:title>Тестовая книга</dc:title>
            <dc:creator opf:role="aut">Иван Петров</dc:creator>
            <dc:creator>Пётр Сидоров</dc:creator>
            <dc:creator opf:role="trl">Переводчик Переводов</dc:creator>
            <dc:description>&lt;p&gt;Описание &lt;b&gt;книги&lt;/b&gt;&lt;/p&gt;</dc:description>
            <meta name="cover" content="cover-img"/>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch1" href="Text/Chapter%201.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/>
            <item id="notes" href="Text/notes.xhtml" media-type="application/xhtml+xml"/>
            <item id="a-cover-xhtml" href="Text/cover-info.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover-img" href="Images/cover.jpg" media-type="image/jpeg"/>
            <item id="pic" href="Images/pic.png" media-type="image/png"/>
            <item id="font" href="Fonts/font.ttf" media-type="font/ttf"/>
          </manifest>
          <spine>
            <itemref idref="cover-page" linear="no"/>
            <itemref idref="ch1"/>
            <itemref idref="ch2"/>
            <itemref idref="notes"/>
          </spine>
        </package>
    """.trimIndent()

    val nav = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>Оглавление</title></head>
        <body>
          <nav epub:type="landmarks"><ol><li><a href="Text/cover.xhtml">Обложка</a></li></ol></nav>
          <nav epub:type="toc"><ol>
            <li><a href="Text/Chapter%201.xhtml">Глава первая</a>
              <ol><li><a href="Text/Chapter%201.xhtml#sec2">Раздел 2</a></li></ol>
            </li>
            <li><a href="Text/chapter2.xhtml">Глава вторая</a></li>
            <li><a href="Text/notes.xhtml">Примечания</a></li>
          </ol></nav>
        </body></html>
    """.trimIndent()

    val chapter1 = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>Тестовая книга</title><style type="text/css">p { color: red; }</style></head>
        <body class="calibre">
        <h1 class="title">Глава первая</h1>
        <p class="calibre1">Первый&nbsp;абзац с <em>курсивом</em> и <strong>жирным</strong>&hellip; &laquo;кавычки&raquo; &amp;lt; &#x1F600; &#8212; &#151; тест&shy;слово.</p>
        <p>Строка один<br/>Строка два</p>
        <p>Текст со сноской<a epub:type="noteref" href="notes.xhtml#n1">1</a> и ещё<sup><a href="#fn2">[2]</a></sup>.</p>
        <p>* * *</p>
        <h2 id="sec2">Раздел 2</h2>
        <blockquote><p>Эпиграф</p></blockquote>
        <table><tr><td>A</td><td>B</td></tr></table>
        <p><img src="../Images/pic.png" alt="pic"/></p>
        <hr/>
        <div class="poem"><p>Стих один</p></div>
        <aside epub:type="footnote" id="fn2"><p>Вторая сноска.</p></aside>
        </body></html>
    """.trimIndent()

    val chapter2 = """
        <html><head><title>Тестовая книга</title></head>
        <body><p>Вторая глава: R&amp;D.</p></body></html>
    """.trimIndent()

    val notes = """
        <html><head><title>Примечания</title></head>
        <body><h1>Примечания</h1>
        <p id="n1"><a href="Chapter%201.xhtml#r1">1</a> Текст первой сноски.</p>
        </body></html>
    """.trimIndent()

    val coverPage = """
        <html><body><svg xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../Images/cover.jpg"/></svg></body></html>
    """.trimIndent()

    fun sampleBook(): ByteArray = zip(
        linkedMapOf(
            "mimetype" to "application/epub+zip".utf8(),
            "META-INF/container.xml" to container.utf8(),
            "OEBPS/content.opf" to opf.utf8(),
            "OEBPS/nav.xhtml" to nav.utf8(),
            "OEBPS/Text/cover.xhtml" to coverPage.utf8(),
            "OEBPS/Text/Chapter 1.xhtml" to chapter1.utf8(),
            "OEBPS/Text/chapter2.xhtml" to chapter2.utf8(),
            "OEBPS/Text/notes.xhtml" to notes.utf8(),
            "OEBPS/Text/cover-info.xhtml" to "<html><body><p>x</p></body></html>".utf8(),
            "OEBPS/Images/cover.jpg" to coverJpeg,
            "OEBPS/Images/pic.png" to picturePng,
            "OEBPS/Fonts/font.ttf" to ByteArray(1024)
        )
    )

    fun naturalOrderBook(): ByteArray = zip(
        linkedMapOf(
            "text/chapter10.html" to "<html><body><p>Десять</p></body></html>".utf8(),
            "text/chapter2.html" to "<html><body><p>Два</p></body></html>".utf8(),
            "text/chapter1.html" to "<html><body><p>Один</p></body></html>".utf8()
        )
    )

    fun ncxBook(): ByteArray {
        val opf2 = """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Книга</dc:title></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <item id="b" href="b.html" media-type="application/xhtml+xml"/>
                <item id="c" href="c.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <ncx><navMap>
              <navPoint id="p1"><navLabel><text>Часть 1</text></navLabel><content src="a.html"/>
                <navPoint id="p2"><navLabel><text>Глава 1</text></navLabel><content src="a.html#g1"/></navPoint>
              </navPoint>
              <navPoint id="p3"><navLabel><text>Глава 2</text></navLabel><content src="b.html"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        return zip(
            linkedMapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf").utf8(),
                "content.opf" to opf2.utf8(),
                "toc.ncx" to ncx.utf8(),
                "a.html" to "<html><head><title>Книга</title></head><body><h1>Часть 1</h1><p>Вступление.</p><h2 id=\"g1\">Глава 1</h2><p>Текст.</p></body></html>".utf8(),
                "b.html" to "<html><head><title>Книга</title></head><body><p>Без заголовка.</p></body></html>".utf8(),
                "c.html" to "<html><head><title>Книга</title></head><body><h2>Эпилог<br/>Через год</h2><p>Конец.</p></body></html>".utf8()
            )
        )
    }

    fun legacyEncodingBook(): ByteArray {
        val legacy = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><html><body><p>Привет, мир</p></body></html>"
        val broken = "<html><body><p>Один<p>Два &foo; 3 < 5<div><b>жирный</p></div><p class=unquoted>Три"
        return zip(
            linkedMapOf(
                "a.html" to TestEncoders.windows1251(legacy),
                "b.html" to broken.utf8()
            )
        )
    }

    fun longDocumentBook(): ByteArray {
        val paragraph = "<p>" + "слово ".repeat(1000) + "</p>"
        val html = "<html><body><h1>Большая</h1>" + paragraph.repeat(150) + "</body></html>"
        return zip(linkedMapOf("big.html" to html.utf8()))
    }

    fun oversizedImageBook(): ByteArray {
        val huge = ByteArray(16 * 1024 * 1024)
        coverJpeg.copyInto(huge)
        return zip(
            linkedMapOf(
                "a.html" to "<html><body><p>Текст</p><img src=\"huge.jpg\"/><img src=\"small.png\"/></body></html>".utf8(),
                "huge.jpg" to huge,
                "small.png" to picturePng
            )
        )
    }

    val longLine = "Длинная строка прозы, которую конвертер отделил тегом br вместо отдельного абзаца, " +
        "как это часто бывает."

    fun hiddenNotesBook(notesFirst: Boolean = false, notesInSpine: Boolean = true): ByteArray {
        val chapterRef = "<itemref idref=\"ch\"/>"
        val notesRef = if (notesInSpine) "<itemref idref=\"notes\" linear=\"no\"/>" else ""
        val spine = if (notesFirst) notesRef + chapterRef else chapterRef + notesRef
        val opf3 = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Книга</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/>
                <item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>$spine</spine>
            </package>
        """.trimIndent()
        val nav3 = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
              <li><a href="ch.xhtml">Глава</a><ol><li><a href="ch.xhtml#s2">Раздел</a></li></ol></li>
            </ol></nav></body></html>
        """.trimIndent()
        val ch = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>Вводный текст без абзаца.<h2 id="s2">Раздел</h2>
            <p>Проза<a href="notes.xhtml#n1" class="calibre5"><sup class="calibre6">1</sup></a> дальше.</p>
            <div>$longLine<br/>$longLine</div>
            <aside epub:type="footnote" id="lonely"><p>Видимая врезка.</p></aside>
            </body></html>
        """.trimIndent()
        val notesDoc = "<html><body><div id=\"n1\"><p>1. Текст сноски из скрытого файла.</p></div></body></html>"
        return zip(
            linkedMapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf").utf8(),
                "content.opf" to opf3.utf8(),
                "nav.xhtml" to nav3.utf8(),
                "ch.xhtml" to ch.utf8(),
                "notes.xhtml" to notesDoc.utf8()
            )
        )
    }

    /** Every fixture, by name. */
    fun all(): List<Pair<String, ByteArray>> = listOf(
        "sample" to sampleBook(),
        "naturalOrder" to naturalOrderBook(),
        "ncx" to ncxBook(),
        "legacyEncoding" to legacyEncodingBook(),
        "longDocument" to longDocumentBook(),
        "oversizedImage" to oversizedImageBook(),
        "hiddenNotes" to hiddenNotesBook(),
        "stored" to storedBook()
    )

    fun storedBook(): ByteArray = TestZip.build(
        linkedMapOf("Text/one.xhtml" to "<html><body><p>Без сжатия</p></body></html>".utf8()),
        deflate = false
    )
}

class EpubParserTest {

    private fun parseBytes(bytes: ByteArray): ParsedBook =
        TestFiles.withFile(bytes, ".epub") { EpubParser().parse(it) }

    @Test
    fun metadataAuthorsAndCover() {
        val book = parseBytes(EpubFixtures.sampleBook())
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals("Тестовая книга", book.title)
        assertEquals("Иван Петров, Пётр Сидоров", book.author)
        assertEquals("Описание книги", book.description)
        assertContentEquals(EpubFixtures.coverJpeg, book.coverBytes)
    }

    @Test
    fun oneChapterPerSpineItemWithTocTitlesAndNoJunk() {
        val book = parseBytes(EpubFixtures.sampleBook())
        // cover.xhtml is linear="no" and not in the TOC; the percent-encoded href is resolved.
        assertEquals(listOf("Глава первая", "Глава вторая", "Примечания"), book.chapters.map { it.title })
        val first = book.chapters[0].paragraphs
        assertTrue(first.isNotEmpty())
        assertFalse(first[0].startsWith(">"))
        assertTrue(first.none { it.contains("calibre") || it.contains("color: red") })
        // The heading used as the title is not repeated as the first paragraph.
        assertTrue(ParagraphMarkup.plainText(first[0]).startsWith("Первый"))
        assertTrue(book.chapters.none { it.title.contains("часть") })
    }

    @Test
    fun entitiesAndInlineFormatting() {
        val book = parseBytes(EpubFixtures.sampleBook())
        val raw = book.chapters[0].paragraphs[0]
        val parsed = ParagraphMarkup.parse(raw)
        val text = parsed.text
        // &nbsp; stays a no-break space.
        assertTrue(text.startsWith("Первый абзац с курсивом и жирным…"), text)
        assertTrue(text.contains("«кавычки»"))
        assertTrue(text.contains("&lt;"), "no double decoding")
        assertTrue(text.contains("😀"), "supplementary code point")
        assertTrue(text.contains("— —"))
        assertTrue(text.contains("тестслово"), "soft hyphen removed")
        val emphasis = parsed.spans.single { it.kind == InlineKind.EMPHASIS }
        assertEquals("курсивом", text.substring(emphasis.start, emphasis.end))
        val strong = parsed.spans.single { it.kind == InlineKind.STRONG }
        assertEquals("жирным", text.substring(strong.start, strong.end))
        assertEquals("Вторая глава: R&D.", ParagraphMarkup.plainText(book.chapters[1].paragraphs[0]))
    }

    @Test
    fun lineBreaksBlocksTablesImagesAndSeparators() {
        val book = parseBytes(EpubFixtures.sampleBook())
        val paragraphs = book.chapters[0].paragraphs
        val one = paragraphs.indexOfFirst { ParagraphMarkup.plainText(it) == "Строка один" }
        assertTrue(one >= 0)
        assertEquals(BlockStyle.VERSE, ParagraphMarkup.blockStyle(paragraphs[one]))
        assertEquals("Строка два", ParagraphMarkup.plainText(paragraphs[one + 1]))
        assertEquals(BlockStyle.VERSE, ParagraphMarkup.blockStyle(paragraphs[one + 1]))

        val separators = paragraphs.filter {
            ParagraphMarkup.blockStyle(it) == BlockStyle.SUBTITLE && ParagraphMarkup.plainText(it) == "* * *"
        }
        assertEquals(2, separators.size) // "* * *" paragraph and <hr>

        assertTrue(paragraphs.any { ParagraphMarkup.blockStyle(it) == BlockStyle.HEADING && ParagraphMarkup.plainText(it) == "Раздел 2" })
        assertTrue(paragraphs.any { ParagraphMarkup.blockStyle(it) == BlockStyle.EPIGRAPH && ParagraphMarkup.plainText(it) == "Эпиграф" })
        assertTrue(paragraphs.any { ParagraphMarkup.blockStyle(it) == BlockStyle.VERSE && ParagraphMarkup.plainText(it) == "Стих один" })
        assertTrue(paragraphs.any { it == "A | B" })

        val imageId = "OEBPS/Images/pic.png"
        assertTrue(paragraphs.contains("[IMG:$imageId]"))
        assertContentEquals(EpubFixtures.picturePng, book.images[imageId])
        assertFalse(book.images.keys.any { it.endsWith(".ttf") }, "fonts are never loaded")
    }

    @Test
    fun footnotesAreReferencedAndCollected() {
        val book = parseBytes(EpubFixtures.sampleBook())
        val paragraphs = book.chapters[0].paragraphs
        val withNotes = paragraphs.single { ParagraphMarkup.plainText(it).startsWith("Текст со сноской") }
        val parsed = ParagraphMarkup.parse(withNotes)
        val refs = parsed.spans.filter { it.kind == InlineKind.NOTE_REF }
        assertEquals(2, refs.size)
        assertEquals("1", parsed.text.substring(refs[0].start, refs[0].end))
        assertEquals("2", parsed.text.substring(refs[1].start, refs[1].end))
        assertEquals("Текст первой сноски.", book.footnotes[refs[0].noteId])
        assertEquals("Вторая сноска.", book.footnotes[refs[1].noteId])
        // The <aside> footnote is not part of the reading flow.
        assertTrue(paragraphs.none { ParagraphMarkup.plainText(it).contains("Вторая сноска") })
        // The notes document stays a chapter, and its back-link is plain text.
        val notesChapter = book.chapters[2].paragraphs
        assertTrue(notesChapter.any { ParagraphMarkup.plainText(it).contains("Текст первой сноски.") })
        assertTrue(notesChapter.none { it.contains(ParagraphMarkup.NOTE_START) })
    }

    @Test
    fun tableOfContentsFromNavWithLevelsAndAnchors() {
        val book = parseBytes(EpubFixtures.sampleBook())
        val toc = book.tableOfContents
        assertEquals(listOf("Глава первая", "Раздел 2", "Глава вторая", "Примечания"), toc.map { it.title })
        assertEquals(listOf(0, 1, 0, 0), toc.map { it.level })
        assertEquals(listOf(0, 0, 1, 2), toc.map { it.chapterIndex })
        val section = toc[1]
        val target = book.chapters[0].paragraphs[section.paragraphIndex]
        assertEquals("Раздел 2", ParagraphMarkup.plainText(target))
        assertEquals(BlockStyle.HEADING, ParagraphMarkup.blockStyle(target))
    }

    @Test
    fun sourceOverloadUsesFileName() {
        val bytes = EpubFixtures.zip(
            linkedMapOf(
                "chapter1.html" to "<html><body><p>Один</p></body></html>".utf8()
            )
        )
        val book = EpubParser().parse(Buffer().write(bytes), "Моя книга.epub")
        assertEquals("Моя книга", book.title)
        assertEquals("Один", book.chapters.single().paragraphs.single())
    }

    @Test
    fun withoutSpineFilesAreSortedNaturally() {
        val book = parseBytes(EpubFixtures.naturalOrderBook())
        assertEquals(listOf("Один", "Два", "Десять"), book.chapters.map { it.paragraphs.single() })
    }

    @Test
    fun ncxTableOfContentsAndHeadingTitles() {
        val book = parseBytes(EpubFixtures.ncxBook())
        assertEquals(listOf("Часть 1", "Глава 2", "Эпилог. Через год"), book.chapters.map { it.title })
        assertEquals("Конец.", book.chapters[2].paragraphs.single())
        assertEquals(listOf(0, 1, 0), book.tableOfContents.map { it.level })
        val g1 = book.tableOfContents[1]
        assertEquals("Глава 1", ParagraphMarkup.plainText(book.chapters[g1.chapterIndex].paragraphs[g1.paragraphIndex]))
    }

    @Test
    fun malformedXhtmlAndLegacyEncodingDoNotLoseText() {
        val book = parseBytes(EpubFixtures.legacyEncodingBook())
        assertEquals("Привет, мир", book.chapters[0].paragraphs.single())
        val texts = book.chapters[1].paragraphs.map { ParagraphMarkup.plainText(it) }
        assertEquals(listOf("Один", "Два &foo; 3 < 5", "жирный", "Три"), texts)
    }

    @Test
    fun veryLongDocumentIsSplitIntoParts() {
        val book = parseBytes(EpubFixtures.longDocumentBook())
        assertTrue(book.chapters.size >= 2)
        assertEquals("Большая (часть 1)", book.chapters[0].title)
        assertEquals("Большая (часть 2)", book.chapters[1].title)
        assertEquals(150, book.chapters.sumOf { it.paragraphs.size })
    }

    @Test
    fun oversizedImagesAreSkipped() {
        val book = parseBytes(EpubFixtures.oversizedImageBook())
        assertEquals(listOf("Текст", "[IMG:small.png]"), book.chapters.single().paragraphs)
        assertNotNull(book.images["small.png"])
        assertFalse(book.images.containsKey("huge.jpg"))
    }

    @Test
    fun hiddenNotesFileSuperscriptLinksProseBreaksAndAnchors() {
        val book = parseBytes(EpubFixtures.hiddenNotesBook())
        // The linear="no" notes file is not a chapter, but its note is collected.
        assertEquals(listOf("Глава"), book.chapters.map { it.title })
        val paragraphs = book.chapters[0].paragraphs
        assertEquals("Вводный текст без абзаца.", paragraphs[0])

        // The anchor of a block that follows loose text points at the block itself.
        val section = book.tableOfContents.single { it.title == "Раздел" }
        assertEquals("Раздел", ParagraphMarkup.plainText(paragraphs[section.paragraphIndex]))

        // <a><sup>1</sup></a> is a footnote reference.
        val parsed = ParagraphMarkup.parse(paragraphs.single { ParagraphMarkup.plainText(it).startsWith("Проза") })
        val ref = parsed.spans.single { it.kind == InlineKind.NOTE_REF }
        assertEquals("Текст сноски из скрытого файла.", book.footnotes[ref.noteId])

        // Prose lines separated by <br> keep the normal paragraph style.
        val prose = paragraphs.filter { ParagraphMarkup.plainText(it) == EpubFixtures.longLine }
        assertEquals(2, prose.size)
        assertTrue(prose.all { ParagraphMarkup.blockStyle(it) == BlockStyle.NORMAL })

        // An <aside> footnote nobody links to stays in the text.
        assertTrue(paragraphs.any { ParagraphMarkup.plainText(it) == "Видимая врезка." })
    }

    @Test
    fun hiddenNotesAreIndependentOfSpineOrderAndMembership() {
        val expected = parseBytes(EpubFixtures.hiddenNotesBook())
        for (bytes in listOf(
            EpubFixtures.hiddenNotesBook(notesFirst = true),
            EpubFixtures.hiddenNotesBook(notesInSpine = false)
        )) {
            val book = parseBytes(bytes)
            assertEquals(expected.footnotes, book.footnotes)
            assertEquals(expected.chapters, book.chapters)
            assertEquals(expected.tableOfContents, book.tableOfContents)
        }
    }

    @Test
    fun storedEntriesAreRead() {
        val book = parseBytes(EpubFixtures.storedBook())
        assertEquals(listOf("Без сжатия"), book.chapters.single().paragraphs)
    }

    @Test
    fun notAZipGivesAnExplanationInsteadOfAnError() {
        val book = TestFiles.withFile("просто текст".utf8(), ".epub") { EpubParser().parse(it, "Сломанная.epub") }
        assertEquals("Сломанная", book.title)
        assertEquals(listOf("Не удалось извлечь текст из книги"), book.chapters.single().paragraphs)
    }
}
