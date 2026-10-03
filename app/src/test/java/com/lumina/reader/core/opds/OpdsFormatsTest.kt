package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPDS formats and catalogue credentials (stage 7 code; split from OpdsUrlsTest, which moved to :shared). */
class OpdsFormatsTest {

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
    fun credentialsOnlyGoToTheCatalogueOwnHosts() {
        val catalog = OpdsCatalogConfig(
            id = "user:1",
            name = "Моя библиотека",
            url = "https://books.example.org/opds",
            username = "reader",
            password = "secret",
            mirrorBaseUrls = listOf("https://mirror.example.net")
        )
        val expected = mapOf("Authorization" to "Basic cmVhZGVyOnNlY3JldA==")
        assertEquals(expected, catalog.authHeaders())
        assertEquals(expected, catalog.authHeadersFor("https://books.example.org/covers/1.jpg"))
        assertEquals(expected, catalog.authHeadersFor("https://mirror.example.net/covers/1.jpg"))
        assertTrue(catalog.authHeadersFor("https://cdn.other.org/covers/1.jpg").isEmpty())
        assertTrue(catalog.authHeadersFor(null).isEmpty())
        assertTrue(catalog.copy(username = "").authHeadersFor("https://books.example.org/x").isEmpty())
    }
}
