package com.lumina.reader.core.network

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A real request through luminaHttpClient on the JVM, against a server in this
 * process. The build pins OkHttp to 4.12.0 while Ktor's engine was built against
 * 5.x; this runs the engine on that combination — a plain answer, a redirect with
 * the final URL, an error status and a body of several chunks — so a binary
 * incompatibility shows up here and not on a user's phone.
 */
class LuminaHttpSmokeTest {

    private fun withServer(block: suspend (base: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { ex ->
            val body = "Привет, OPDS".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/ok")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/big") { ex ->
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            ex.sendResponseHeaders(200, 0) // chunked
            ex.responseBody.use { out -> repeat(20) { out.write(chunk) } }
        }
        server.start()
        try {
            runBlocking { block("http://127.0.0.1:${server.address.port}") }
        } finally {
            server.stop(0)
        }
    }

    private val timeouts = HttpTimeouts(connectMillis = 5_000, readMillis = 5_000, writeMillis = 5_000, callMillis = 20_000)

    @Test
    fun aPlainAnswerIsReadWithItsCharset() = withServer { base ->
        luminaHttpClient(timeouts).use { client ->
            val response = client.get("$base/ok")
            assertEquals(200, response.status.value)
            assertEquals("Привет, OPDS", response.readBodyText())
        }
    }

    @Test
    fun aRedirectIsFollowedAndTheFinalUrlReported() = withServer { base ->
        luminaHttpClient(timeouts).use { client ->
            val response = client.get("$base/moved")
            assertEquals(200, response.status.value)
            assertEquals("$base/ok", response.finalUrl)
        }
    }

    @Test
    fun anErrorStatusIsAnAnswerNotAnException() = withServer { base ->
        luminaHttpClient(timeouts).use { client ->
            assertEquals(404, client.get("$base/missing").status.value)
        }
    }

    @Test
    fun aChunkedBodyIsReadWhole() = withServer { base ->
        luminaHttpClient(timeouts).use { client ->
            val bytes = client.get("$base/big").readBodyBytes()
            assertEquals(20 * 64 * 1024, bytes?.size)
        }
    }

    @Test
    fun aProxyReceivesTheRequestWithItsLogin() {
        // The "proxy" is a server that answers any absolute-form request (what an HTTP
        // proxy is sent) and records the login it was given.
        var seenAuth: String? = null
        var seenPath: String? = null
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext("/") { ex ->
            seenPath = ex.requestURI.toString()
            seenAuth = ex.requestHeaders.getFirst("Proxy-Authorization")
            if (seenAuth == null) {
                ex.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"test\"")
                ex.sendResponseHeaders(407, -1)
            } else {
                val body = "via proxy".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        proxy.start()
        try {
            runBlocking {
                val settings = ProxySettings(
                    enabled = true, type = ProxyType.HTTP, host = "127.0.0.1",
                    port = proxy.address.port, username = "me", password = "secret"
                )
                luminaHttpClient(timeouts, settings).use { client ->
                    val response = client.get("http://catalogue.invalid/opds")
                    assertEquals(200, response.status.value)
                    assertEquals("via proxy", response.readBodyText())
                }
            }
            assertEquals("http://catalogue.invalid/opds", seenPath)
            assertEquals("Basic bWU6c2VjcmV0", seenAuth) // "me:secret"
        } finally {
            proxy.stop(0)
        }
    }
}
