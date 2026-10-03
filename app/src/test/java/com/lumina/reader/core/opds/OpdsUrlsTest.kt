package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsUrlsTest {

    @Test
    fun resolvesRelativeReferencesLikeABrowser() {
        val base = "https://flibusta.is/opds/new/0/new"
        assertEquals("https://flibusta.is/b/1/fb2", OpdsUrls.resolve(base, "/b/1/fb2"))
        assertEquals("https://flibusta.is/opds/new/0/1", OpdsUrls.resolve(base, "1"))
        assertEquals("https://flibusta.is/opds/x", OpdsUrls.resolve(base, "../../x"))
        assertEquals("https://cdn.example.org/a.jpg", OpdsUrls.resolve(base, "//cdn.example.org/a.jpg"))
        assertEquals("http://other.org/feed", OpdsUrls.resolve(base, "http://other.org/feed"))
        assertEquals("https://host/x", OpdsUrls.resolve("https://host", "x"))
        assertEquals("https://host/a%20b.epub", OpdsUrls.resolve("https://host/", "a b.epub"))
        assertEquals("https://host/opds?page=2", OpdsUrls.resolve("https://host/opds?page=1", "?page=2"))
    }

    @Test
    fun resolvesTemplatesWithoutBreakingPlaceholders() {
        assertEquals(
            "https://host/opds/search?q={searchTerms}",
            OpdsUrls.resolveTemplate("https://host/opds/osd.xml", "search?q={searchTerms}")
        )
    }

    @Test
    fun buildsMirrorCandidatesForTheSameSiteOnly() {
        val mirrors = listOf("https://flibusta.is", "https://flibusta.site")
        assertEquals(
            listOf("https://flibusta.is/b/1/fb2?x=1", "https://flibusta.site/b/1/fb2?x=1"),
            OpdsUrls.candidates("https://flibusta.is/b/1/fb2?x=1", mirrors)
        )
        assertEquals(
            listOf("https://cdn.other.org/cover.jpg"),
            OpdsUrls.candidates("https://cdn.other.org/cover.jpg", mirrors)
        )
        assertEquals(listOf("https://example.org/feed"), OpdsUrls.candidates("https://example.org/feed", emptyList()))
    }

    @Test
    fun normalisesUserTypedCatalogAddresses() {
        assertEquals("https://example.org/opds", OpdsUrls.normalizeUserUrl("  example.org/opds "))
        assertEquals("http://192.168.1.5:8080/opds", OpdsUrls.normalizeUserUrl("http://192.168.1.5:8080/opds"))
        assertNull(OpdsUrls.normalizeUserUrl("ftp://example.org"))
        assertNull(OpdsUrls.normalizeUserUrl("not a url"))
        assertNull(OpdsUrls.normalizeUserUrl(""))
        assertEquals("https://host:8443", OpdsUrls.origin("https://host:8443/a/b?c"))
    }

    @Test
    fun expandsOpenSearchTemplates() {
        assertEquals(
            "https://h/s?q=%D0%BC%D0%B8%D1%80&start=1&n=",
            OpenSearch.expand("https://h/s?q={searchTerms}&start={startPage}&n={count?}", "мир")
        )
        assertEquals("https://h/s/a%26b", OpenSearch.expand("https://h/s/{searchTerms}", "a&b"))
        assertTrue(OpenSearch.hasSearchTerms("x?q={searchTerms?}"))
        assertFalse(OpenSearch.hasSearchTerms("x?q={query}"))
        assertEquals(
            "b?q={searchTerms}",
            OpenSearch.chooseTemplate(
                listOf(
                    "text/html" to "a?q={searchTerms}",
                    "application/atom+xml;profile=opds-catalog" to "b?q={searchTerms}"
                )
            )
        )
        assertNull(OpenSearch.chooseTemplate(listOf("application/atom+xml" to "no-terms")))
    }

    @Test
    fun classifiesAcquisitionLinks() {
        assertEquals(BookFormat.FB2_ZIP, OpdsFormats.formatOf("application/fb2+zip", "/b/1/fb2"))
        assertEquals(BookFormat.FB2_ZIP, OpdsFormats.formatOf(null, "/b/1/fb2"))
        assertEquals(BookFormat.FB2, OpdsFormats.formatOf("application/x-fictionbook+xml", "/get?id=1"))
        assertEquals(BookFormat.EPUB, OpdsFormats.formatOf("application/epub+zip", "/b/1/epub"))
        assertEquals(BookFormat.PDF, OpdsFormats.formatOf("application/pdf", "/b/1/download"))
        assertEquals(BookFormat.TXT, OpdsFormats.formatOf("text/plain; charset=utf-8", "/b/1"))
        assertNull(OpdsFormats.formatOf("application/x-mobipocket-ebook", "/b/1/mobi"))
        assertNull(OpdsFormats.formatOf("application/txt+zip", "/b/1/txt"))

        assertTrue(OpdsFormats.isAcquisitionLink("http://opds-spec.org/acquisition/open-access", "application/epub+zip"))
        assertTrue(OpdsFormats.isAcquisitionLink(null, "application/epub+zip"))
        assertFalse(OpdsFormats.isAcquisitionLink("http://opds-spec.org/acquisition/buy", "application/epub+zip"))
        assertFalse(OpdsFormats.isAcquisitionLink("related", "application/atom+xml"))
        assertFalse(OpdsFormats.isAcquisitionLink(null, "application/atom+xml;profile=opds-catalog"))
    }

    @Test
    fun prefersFb2ThenEpub() {
        val acquisitions = listOf(
            OpdsAcquisition("p", BookFormat.PDF),
            OpdsAcquisition("e", BookFormat.EPUB),
            OpdsAcquisition("f", BookFormat.FB2_ZIP)
        )
        assertEquals("f", OpdsFormats.preferred(acquisitions)?.url)
        assertEquals("e", OpdsFormats.preferred(acquisitions.take(2))?.url)
        assertNull(OpdsFormats.preferred(emptyList()))
    }

    @Test
    fun convertsHtmlToPlainText() {
        assertEquals(
            "Строка 1\nСтрока «2» & ещё",
            OpdsText.htmlToPlain("<p>Строка&nbsp;1</p><br/><p>Строка &laquo;2&raquo; &amp; ещё</p>")
        )
        assertEquals("A — B", OpdsText.decodeEntities("A &#8212; B"))
        assertEquals("x", OpdsText.normalize("  x \n\n\n "))
    }
}
