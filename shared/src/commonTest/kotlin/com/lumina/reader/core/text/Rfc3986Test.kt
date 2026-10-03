package com.lumina.reader.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Rfc3986Test {

    private val base = "http://a/b/c/d;p?q"

    @Test
    fun normalExamplesOfSection541() {
        val cases = mapOf(
            "g:h" to "g:h", "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g", "//g" to "http://g", "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s", "g#s" to "http://a/b/c/g#s", "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x", "g;x" to "http://a/b/c/g;x", "g;x?y#s" to "http://a/b/c/g;x?y#s",
            "" to "http://a/b/c/d;p?q", "." to "http://a/b/c/", "./" to "http://a/b/c/", ".." to "http://a/b/",
            "../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/", "../../" to "http://a/",
            "../../g" to "http://a/g"
        )
        for ((reference, expected) in cases) assertEquals(expected, Rfc3986.resolve(base, reference), reference)
    }

    @Test
    fun abnormalExamplesOfSection542() {
        val cases = mapOf(
            "../../../g" to "http://a/g", "../../../../g" to "http://a/g", "/./g" to "http://a/g",
            "/../g" to "http://a/g", "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g", "./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/",
            "g/./h" to "http://a/b/c/g/h", "g/../h" to "http://a/b/c/h", "g;x=1/./y" to "http://a/b/c/g;x=1/y",
            "g;x=1/../y" to "http://a/b/c/y", "g?y/./x" to "http://a/b/c/g?y/./x", "g?y/../x" to "http://a/b/c/g?y/../x",
            "g#s/./x" to "http://a/b/c/g#s/./x", "g#s/../x" to "http://a/b/c/g#s/../x", "http:g" to "http:g"
        )
        for ((reference, expected) in cases) assertEquals(expected, Rfc3986.resolve(base, reference), reference)
    }

    @Test
    fun opdsStyleReferences() {
        assertEquals("https://host/x", Rfc3986.resolve("https://host", "x"))
        assertEquals("https://host/a%20b.epub", Rfc3986.resolve("https://host/", "a%20b.epub"))
        assertEquals("https://flibusta.is/opds/new/0/1", Rfc3986.resolve("https://flibusta.is/opds/new/0/new", "1"))
        assertEquals("https://h/книги/1", Rfc3986.resolve("https://h/книги/", "1"))
        assertNull(Rfc3986.resolve("https://host/", "a b"))
        assertNull(Rfc3986.resolve("https://host/", "a%zz"))
        assertNull(Rfc3986.resolve("relative/base", "x"))
        assertNull(Rfc3986.resolve("https://host/", "x#a#b"))
    }

    @Test
    fun parsesComponents() {
        val url = assertNotNull(Rfc3986.parse("https://user@Host.example:8443/a/b%20c?x=1&y#frag"))
        assertEquals("https", url.scheme)
        assertEquals("user@Host.example:8443", url.authority)
        assertEquals("Host.example", url.host)
        assertEquals(8443, url.port)
        assertEquals("/a/b%20c", url.path)
        assertEquals("x=1&y", url.query)
        assertEquals("frag", url.fragment)
        assertEquals("https://user@Host.example:8443/a/b%20c?x=1&y#frag", url.toString())

        val plain = assertNotNull(Rfc3986.parse("https://host"))
        assertEquals("", plain.path)
        assertEquals(-1, plain.port)
        assertNull(plain.query)

        val ipv6 = assertNotNull(Rfc3986.parse("http://[::1]:8080/x"))
        assertEquals("[::1]", ipv6.host)
        assertEquals(8080, ipv6.port)

        assertNull(assertNotNull(Rfc3986.parse("/only/path")).host)
        assertNull(Rfc3986.parse("1http://x"))
        assertNull(Rfc3986.parse("http://host:80a/"))
        assertNull(Rfc3986.parse("http://host/<x>"))
        assertNull(Rfc3986.parse("http://host/a\tb"))
        assertNull(Rfc3986.parse("http://host/[x]"))
    }

    @Test
    fun removesDotSegments() {
        assertEquals("/a/g", Rfc3986.removeDotSegments("/a/b/c/./../../g"))
        assertEquals("mid/6", Rfc3986.removeDotSegments("mid/content=5/../6"))
        assertEquals("/", Rfc3986.removeDotSegments("/.."))
        assertEquals("", Rfc3986.removeDotSegments("../.."))
    }
}
