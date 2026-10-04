package com.lumina.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

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
actual fun luminaHttpClient(timeouts: HttpTimeouts): HttpClient = HttpClient(Darwin) {
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
    engine {
        configureSession {
            URLCache = null
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            HTTPCookieStorage = null
            HTTPShouldSetCookies = false
        }
    }
}
