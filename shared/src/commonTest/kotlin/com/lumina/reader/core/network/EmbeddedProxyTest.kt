package com.lumina.reader.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedProxyTest {
    @Test
    fun parsesLoginPasswordHostAndPort() {
        val proxy = EmbeddedProxy.parse("user:p:ss@203.0.113.7:6499")!!
        assertEquals("203.0.113.7", proxy.host)
        assertEquals(6499, proxy.port)
        assertEquals("user", proxy.username)
        assertEquals("p:ss", proxy.password)
        assertEquals(ProxyType.HTTP, proxy.type)
        assertTrue(proxy.usable)
    }

    @Test
    fun parsesAProxyWithoutLogin() {
        val proxy = EmbeddedProxy.parse("http://203.0.113.7:3128")!!
        assertEquals("203.0.113.7", proxy.host)
        assertEquals("", proxy.username)
    }

    @Test
    fun emptyOrBrokenSpecMeansNoProxy() {
        assertNull(EmbeddedProxy.parse(""))
        assertNull(EmbeddedProxy.parse("   "))
        assertNull(EmbeddedProxy.parse("user:pass@host"))
        assertNull(EmbeddedProxy.parse("user:pass@host:99999"))
    }

    @Test
    fun appliesOnlyToFlibustaAddresses() {
        assertTrue(EmbeddedProxy.appliesTo("https://flibusta.is/opds"))
        assertTrue(EmbeddedProxy.appliesTo("https://static.flibusta.is:443/b.fb2/x.zip"))
        assertFalse(EmbeddedProxy.appliesTo("https://iknigi.net/opds"))
        assertFalse(EmbeddedProxy.appliesTo("https://api.example.com/flibusta"))
    }
}
