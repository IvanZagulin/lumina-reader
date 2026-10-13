package com.lumina.reader.core.network

import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.OpdsUrls
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
    fun appliesOnlyToTheDomainsItIsPaidFor() {
        assertTrue(EmbeddedProxy.appliesTo("https://flibusta.is/opds"))
        assertTrue(EmbeddedProxy.appliesTo("https://static.flibusta.is:443/b.fb2/x.zip"))
        assertTrue(EmbeddedProxy.appliesTo("https://flibusta.site/opds/search?searchTerm=x"))
        assertFalse(EmbeddedProxy.appliesTo("https://iknigi.net/opds"))
        assertFalse(EmbeddedProxy.appliesTo("https://api.example.com/flibusta"))
    }

    @Test
    fun aHostThatMerelyContainsTheNameIsNotProxied() {
        // The proxy is paid for by the app's owner: a catalogue a user adds must
        // not run through it just because its host reads like the real one.
        assertFalse(EmbeddedProxy.appliesTo("https://flibusta-mirror.example.com/opds"))
        assertFalse(EmbeddedProxy.appliesTo("https://notflibusta.is.example.com/opds"))
        assertFalse(EmbeddedProxy.appliesTo("https://flibusta.is.evil.test/opds"))
        assertFalse(EmbeddedProxy.appliesTo("not a url"))
    }

    @Test
    fun everyProxiedDomainBelongsToABuiltInCatalogue() {
        // The list is the one place to extend when another catalogue gets blocked;
        // this keeps it from drifting into domains the app never talks to.
        val known = BuiltInCatalogs.all
            .flatMap { it.mirrorBaseUrls + it.url }
            .mapNotNull { OpdsUrls.host(it) }
            .toSet()
        EmbeddedProxy.proxiedDomains.forEach { domain ->
            assertTrue(domain in known, "$domain is not a host of any built-in catalogue")
        }
    }
}
