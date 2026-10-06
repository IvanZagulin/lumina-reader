package com.lumina.reader.core.opds

import com.lumina.reader.core.download.HttpStatusException
import com.lumina.reader.core.network.HttpTimeouts
import com.lumina.reader.core.network.luminaHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpdsSearchRecoveryTest {
    private fun withRepository(
        initialStatus: Int,
        description: String = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="application/atom+xml" template="/search?q={searchTerms}"/>
            </OpenSearchDescription>""",
        block: suspend (OpdsRepository, OpdsCatalogConfig, OpdsLink, AtomicInteger) -> Unit
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/opds") { exchange ->
            val body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Test</title>
                <link rel="search" type="application/opensearchdescription+xml" href="/description"/>
                </feed>""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/description") { exchange ->
            val status = if (requests.incrementAndGet() == 1) initialStatus else 200
            val body = description.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val directory = Files.createTempDirectory("opds-recovery-").toString().toPath()
        val client = luminaHttpClient(HttpTimeouts(5_000, 5_000, 5_000, 10_000))
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val repository = OpdsRepository({ client }, feedCache = OpdsFeedCache(directory = directory))
            val catalog = OpdsCatalogConfig("test", "Test", "$base/opds")
            val link = OpdsLink("$base/description", rel = "search", type = "application/opensearchdescription+xml")
            runBlocking { block(repository, catalog, link, requests) }
        } finally {
            client.close()
            server.stop(0)
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun aFailedDescriptionCanBeRetriedOnTheSameRepository() = withRepository(503) { repository, catalog, link, calls ->
        assertFailsWith<HttpStatusException> { repository.templateForLink(link, catalog) }
        val expected = catalog.url.removeSuffix("/opds") + "/search?q={searchTerms}"
        assertEquals(expected, repository.templateForLink(link, catalog))
        assertEquals(expected, repository.templateForLink(link, catalog))
        assertEquals(2, calls.get())
    }

    @Test
    fun catalogSearchDoesNotCacheDescriptionFailuresEither() = withRepository(503) { repository, catalog, _, calls ->
        assertNull(repository.searchTemplate(catalog))
        val expected = catalog.url.removeSuffix("/opds") + "/search?q={searchTerms}"
        assertEquals(expected, repository.searchTemplate(catalog))
        assertEquals(expected, repository.searchTemplate(catalog))
        assertEquals(2, calls.get())
    }

    @Test
    fun aSuccessfulDescriptionWithoutSearchCanStillBeCached() = withRepository(
        200, """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>None</ShortName></OpenSearchDescription>"""
    ) { repository, catalog, _, calls ->
        assertNull(repository.searchTemplate(catalog))
        assertNull(repository.searchTemplate(catalog))
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationIsPropagatedAndNotCached() = runBlocking {
        var requests = 0
        val repository = OpdsRepository(clientProvider = {
            requests++
            throw CancellationException("cancelled request")
        })
        val link = OpdsLink("https://example.invalid/description", type = "application/opensearchdescription+xml")
        repeat(2) {
            assertFailsWith<CancellationException> { repository.templateForLink(link, null) }
        }
        assertEquals(2, requests)
    }
}
