package com.lumina.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NSURLSession underneath (Ktor's Darwin engine). Ktor's engine turns off
 * NSURLSession's own redirect handling, so Ktor's redirect plugin follows
 * them; like OkHttp's `followSslRedirects(true)` on Android it also follows a
 * catalogue that moves from https to http, and [finalUrl] is the request URL
 * of the last hop.
 *
 * The session keeps no HTTP cache and no cookies, as the app's OkHttpClients
 * never had a Cache or a CookieJar: NSURLSession's default configuration would
 * otherwise answer a catalogue page from the shared URL cache (a Calibre
 * library showing yesterday's books) and replay a server's cookies.
 *
 * Timeouts: NSURLSession has no separate connect timeout; its per-request
 * timeout is an idle timer that also runs while connecting, so the read
 * timeout ([HttpTimeouts.readMillis], Ktor's socket timeout) covers a host that
 * does not answer as well. The call timeout becomes Ktor's request timeout;
 * 0 leaves the request unlimited, as for book downloads.
 *
 * Plain http:// catalogues (Calibre on a home network) need App Transport
 * Security exceptions in the app's Info.plist; without them NSURLSession
 * refuses the request (reported as a secure-connection error).
 */
actual fun luminaHttpClient(timeouts: HttpTimeouts, proxy: ProxySettings?): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    useDefaultTransformers = false
    followRedirects = true
    install(HttpRedirect) {
        allowHttpsDowngrade = true
    }
    install(HttpTimeout) {
        socketTimeoutMillis = timeouts.readMillis.takeIf { it > 0 }
        requestTimeoutMillis = timeouts.callMillis.takeIf { it > 0 }
    }
    val activeProxy = proxy?.takeIf { it.usable }
    engine {
        configureSession {
            URLCache = null
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            HTTPCookieStorage = null
            HTTPShouldSetCookies = false
            if (activeProxy != null) {
                connectionProxyDictionary = proxyDictionary(activeProxy)
                // NSURLSession sends this header to the proxy, also in the CONNECT that opens
                // an https tunnel (an HTTP proxy's login; SOCKS5 has no login here).
                if (activeProxy.hasCredentials && activeProxy.type == ProxyType.HTTP) {
                    HTTPAdditionalHeaders = mapOf<Any?, Any?>("Proxy-Authorization" to basicAuth(activeProxy))
                }
            }
        }
    }
}

/**
 * CFNetwork's proxy dictionary for the session. The keys are the documented
 * string values of the kCFNetworkProxies... constants, which iOS does not export
 * for HTTPS. HTTPS requests tunnel through an HTTP proxy with CONNECT.
 */
private fun proxyDictionary(proxy: ProxySettings): Map<Any?, Any?> {
    val host = proxy.host.trim()
    return if (proxy.type == ProxyType.SOCKS5) {
        mapOf("SOCKSEnable" to 1, "SOCKSProxy" to host, "SOCKSPort" to proxy.port)
    } else {
        mapOf(
            "HTTPEnable" to 1, "HTTPProxy" to host, "HTTPPort" to proxy.port,
            "HTTPSEnable" to 1, "HTTPSProxy" to host, "HTTPSPort" to proxy.port
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun basicAuth(proxy: ProxySettings): String =
    "Basic " + Base64.encode("${proxy.username}:${proxy.password}".encodeToByteArray())
