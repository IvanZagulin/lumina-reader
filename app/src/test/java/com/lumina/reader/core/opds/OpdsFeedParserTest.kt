package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parser runs on the shared markup tokenizer (no Android XML parser any
 * more), so these are plain JVM tests; they use only common APIs and can move
 * to :shared commonTest with the parser.
 */
class OpdsFeedParserTest {

    private val parser = OpdsFeedParser()

    private fun parse(xml: String, url: String = "https://flibusta.is/opds/new/0") =
        parser.parseFeed(xml.encodeToByteArray(), url)

    private val rootFeed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/"
              xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>tag:root</id>
          <title>Flibusta catalog</title>
          <link href="/opds" rel="start" type="application/atom+xml;profile=opds-catalog"/>
          <link href="/opds-opensearch.xml" rel="search" type="application/opensearchdescription+xml"/>
          <link href="/opds/search?searchTerms={searchTerms}" rel="search" type="application/atom+xml"/>
          <link href="/opds/new/1" rel="next" type="application/atom+xml;profile=opds-catalog"/>
          <entry>
            <id>tag:root:authors</id>
            <title>По авторам</title>
            <content type="text">Поиск книг по авторам</content>
            <link href="authors" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <id>tag:book:12345</id>
            <title>Ведьмак</title>
            <author><name>Анджей Сапковский</name><uri>/a/1</uri></author>
            <author><name>Второй Автор</name></author>
            <category term="sf_fantasy" label="Фэнтези"/>
            <dc:language>ru</dc:language>
            <dc:issued>1990</dc:issued>
            <content type="text/html">&lt;p&gt;Первая &lt;b&gt;книга&lt;/b&gt; цикла&lt;/p&gt;&lt;br/&gt;Серия: Ведьмак #1&lt;br/&gt;Размер: 1,5 Мб</content>
            <link href="/a/1" rel="related" type="application/atom+xml" title="Все книги автора Анджей Сапковский"/>
            <link href="/opds/sequencebooks/77" rel="related" type="application/atom+xml" title="Все книги серии &quot;Ведьмак&quot;"/>
            <link href="/b/12345/fb2" rel="http://opds-spec.org/acquisition/open-access" type="application/fb2+zip"/>
            <link href="/b/12345/epub" rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" length="2048"/>
            <link href="/b/12345/mobi" rel="http://opds-spec.org/acquisition/open-access" type="application/x-mobipocket-ebook"/>
            <link href="/b/12345/buy" rel="http://opds-spec.org/acquisition/buy" type="application/epub+zip"/>
            <link href="/i/45/12345/cover.jpg" rel="http://opds-spec.org/image" type="image/jpeg"/>
            <link href="/i/45/12345/thumb.jpg" rel="http://opds-spec.org/image/thumbnail" type="image/jpeg"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun separatesNavigationFromPublications() {
        val feed = parse(rootFeed)
        assertEquals("Flibusta catalog", feed.title)
        assertEquals(2, feed.entries.size)

        val navigation = feed.entries[0] as OpdsEntry.Navigation
        assertEquals("По авторам", navigation.title)
        assertEquals("https://flibusta.is/opds/new/authors", navigation.url)
        assertEquals("Поиск книг по авторам", navigation.summary)

        val book = feed.entries[1] as OpdsEntry.Publication
        assertEquals("tag:book:12345", book.key)
        assertEquals(listOf("Анджей Сапковский", "Второй Автор"), book.authors)
        assertEquals(listOf("Фэнтези"), book.categories)
        assertEquals("ru", book.language)
        assertEquals("1990", book.issued)
    }

    @Test
    fun readsFeedLevelNextSearchAndStartLinks() {
        val feed = parse(rootFeed)
        assertEquals("https://flibusta.is/opds/new/1", feed.nextUrl)
        assertEquals("https://flibusta.is/opds", feed.startUrl)
        // A ready Atom template is preferred over an OpenSearch description.
        assertEquals("https://flibusta.is/opds/search?searchTerms={searchTerms}", feed.searchLink?.href)
        assertTrue(feed.searchLink!!.isSearchTemplate)
    }

    @Test
    fun keepsOnlySupportedFreeFormatsAndResolvesUrls() {
        val book = parse(rootFeed).entries[1] as OpdsEntry.Publication
        assertEquals(listOf(BookFormat.FB2_ZIP, BookFormat.EPUB), book.acquisitions.map { it.format })
        assertEquals("https://flibusta.is/b/12345/fb2", book.acquisitions[0].url)
        assertEquals(2048L, book.acquisitions[1].sizeBytes)
        assertEquals(listOf("MOBI"), book.unsupportedFormats)
        assertEquals(BookFormat.FB2_ZIP, book.preferredAcquisition?.format)
        assertEquals("https://flibusta.is/i/45/12345/thumb.jpg", book.thumbnailUrl)
        assertEquals("https://flibusta.is/i/45/12345/cover.jpg", book.coverUrl)
    }

    @Test
    fun extractsPlainSummarySeriesSizeAndRelatedFeeds() {
        val book = parse(rootFeed).entries[1] as OpdsEntry.Publication
        assertTrue(book.summary.startsWith("Первая книга цикла"))
        assertTrue(!book.summary.contains("<"))
        assertEquals(OpdsSeries("Ведьмак", 1), book.series)
        // The preferred (FB2) file has no length attribute: the size comes from the description.
        assertEquals(1_572_864L, book.sizeBytes)
        assertEquals(
            listOf("https://flibusta.is/a/1", "https://flibusta.is/opds/sequencebooks/77"),
            book.relatedLinks.map { it.href }
        )
    }

    @Test
    fun seriesComesFromRelatedLinkWhenContentHasNone() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Книга</title>
                <link href="/s/9" rel="related" type="application/atom+xml" title="Все книги серии «Хроники»"/>
                <link href="book.epub" rel="http://opds-spec.org/acquisition"/>
              </entry>
            </feed>
        """.trimIndent()
        val book = parse(xml, url = "https://example.org/catalog/feed.xml").entries.single() as OpdsEntry.Publication
        assertEquals(OpdsSeries("Хроники"), book.series)
        assertEquals("https://example.org/catalog/book.epub", book.acquisitions.single().url)
        // No id: the first acquisition link becomes the stable key.
        assertEquals("https://example.org/catalog/book.epub", book.key)
    }

    @Test
    fun handlesPrefixedAtomXhtmlContentAndCalibreSeries() {
        val xml = """
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:title>Calibre</atom:title>
              <atom:entry>
                <atom:title>Дюна</atom:title>
                <atom:content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Абзац один</p><p>Абзац два</p></div></atom:content>
                <calibre:series xmlns:calibre="http://calibre.kovidgoyal.net/2009/metadata" index="2.0">Дюна</calibre:series>
                <atom:link href="https://cdn.example.org/dune.fb2" rel="http://opds-spec.org/acquisition" type="text/fb2+xml"/>
              </atom:entry>
            </atom:feed>
        """.trimIndent()
        val feed = parse(xml)
        assertEquals("Calibre", feed.title)
        val book = feed.entries.single() as OpdsEntry.Publication
        assertEquals("Абзац один\nАбзац два", book.summary)
        assertEquals(OpdsSeries("Дюна", 2), book.series)
        assertEquals(BookFormat.FB2, book.acquisitions.single().format)
    }

    @Test
    fun entriesWithoutLinksAreSkippedAndBooksWithoutSupportedFormatsStayVisible() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>Пусто</title></entry>
              <entry>
                <id>djvu-only</id>
                <title>Скан</title>
                <link href="/b/1/djvu" rel="http://opds-spec.org/acquisition" type="image/vnd.djvu"/>
              </entry>
            </feed>
        """.trimIndent()
        val book = parse(xml).entries.single() as OpdsEntry.Publication
        assertTrue(book.acquisitions.isEmpty())
        assertEquals(listOf("DJVU"), book.unsupportedFormats)
        assertNull(book.preferredAcquisition)
    }

    @Test
    fun rejectsHtmlPages() {
        try {
            parse("<html><body>Captcha</body></html>")
            fail("HTML accepted as a feed")
        } catch (e: OpdsFormatException) {
            assertTrue(e.message!!.contains("веб-страницу"))
        }
    }

    @Test
    fun readsOpenSearchDescriptions() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <ShortName>Flibusta</ShortName>
              <Url type="text/html" template="https://flibusta.is/booksearch?ask={searchTerms}"/>
              <Url type="application/atom+xml" template="/opds/search?searchTerms={searchTerms}&amp;page={startPage?}"/>
            </OpenSearchDescription>
        """.trimIndent()
        val template = parser.parseOpenSearchTemplate(xml.encodeToByteArray(), "https://flibusta.is/opds-opensearch.xml")
        assertEquals("https://flibusta.is/opds/search?searchTerms={searchTerms}&page={startPage?}", template)
        assertEquals(
            "https://flibusta.is/opds/search?searchTerms=%D0%B2%D0%B5%D0%B4%D1%8C%D0%BC%D0%B0%D0%BA%201&page=",
            OpenSearch.expand(template!!, " ведьмак 1 ")
        )
    }

    @Test
    fun unclosedHtmlInsideContentDoesNotSwallowTheNextEntry() {
        // <br> without "/" is not XML. The cursor closes it with its parent; Android's
        // relaxed KXmlParser closed the innermost element on every end tag instead,
        // which lost the first entry's link and skipped the second entry.
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Первая</title>
                <content type="xhtml"><div>Строка<br>ещё</div></content>
                <link href="/b/1.epub" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
              </entry>
              <entry>
                <title>Вторая</title>
                <link href="/b/2.epub" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
              </entry>
              </stray>
            </feed>
        """.trimIndent()
        val books = parse(xml, url = "https://example.org/opds").entries.map { it as OpdsEntry.Publication }
        assertEquals(listOf("Первая", "Вторая"), books.map { it.title })
        assertEquals("Строка\nещё", books[0].summary)
        assertEquals("https://example.org/b/2.epub", books[1].acquisitions.single().url)
    }

    @Test
    fun decodesTheEncodingOfTheXmlDeclaration() {
        val head = """<?xml version="1.0" encoding="windows-1251"?><feed><title>""".encodeToByteArray()
        // "Каталог" in windows-1251.
        val title = intArrayOf(0xCA, 0xE0, 0xF2, 0xE0, 0xEB, 0xEE, 0xE3).map { it.toByte() }.toByteArray()
        val tail = "</title></feed>".encodeToByteArray()
        val feed = parser.parseFeed(head + title + tail, "https://example.org/opds")
        assertEquals("Каталог", feed.title)
    }

    @Test
    fun readsSizesOnlyWithAWholeUnit() {
        assertEquals(1_572_864L, OpdsFeedParser.sizeFromText("Размер: 1,5 Мб"))
        assertEquals(2048L, OpdsFeedParser.sizeFromText("Формат: fb2\nразмер: 2 KB"))
        assertEquals(300L, OpdsFeedParser.sizeFromText("РАЗМЕР : 300 байт"))
        // "Kbytes" is no unit of the list: neither "kb" nor "k" may run on into a word.
        assertNull(OpdsFeedParser.sizeFromText("Размер: 12 Kbytes"))
    }

    @Test
    fun matchesSeriesKeywordsInAnyCase() {
        assertEquals(OpdsSeries("Дюна", 3), OpdsFeedParser.seriesFromText("СЕРИЯ: Дюна №3"))
        assertEquals(
            OpdsSeries("Хроники"),
            OpdsFeedParser.seriesFromLinks(listOf(OpdsLink(href = "https://x/s/1", title = "Все КНИГИ СЕРИИ «Хроники»")))
        )
    }
}
