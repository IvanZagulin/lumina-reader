package com.lumina.reader.core.opds

import com.lumina.reader.core.network.HttpTimeouts
import com.lumina.reader.core.network.luminaHttpClient
import com.lumina.reader.platform.AppInfo
import com.lumina.reader.platform.PlatformKind
import io.ktor.client.HttpClient
import com.lumina.reader.core.network.NetworkProxy
import com.lumina.reader.core.network.ProxySettings
import com.lumina.reader.platform.PlatformLock

/**
 * Shared HTTP clients of the catalogues. The download client has no overall
 * call timeout so large books are not cut off, only connect/read timeouts that
 * detect a stalled connection. On Android both run on OkHttp with the same
 * connection pool and dispatcher, as before Ktor (see [luminaHttpClient]).
 */
object OpdsHttp {
    /** "LuminaReader/1.2 (Android; OPDS)" as always on Android; "(iOS; OPDS)" on the iPhone. */
    val USER_AGENT: String =
        "LuminaReader/1.2 (" + (if (AppInfo.platform == PlatformKind.IOS) "iOS" else "Android") + "; OPDS)"

    const val ACCEPT_FEED = "application/atom+xml;profile=opds-catalog, application/atom+xml, application/xml;q=0.9, */*;q=0.8"

    private val feedTimeouts = HttpTimeouts(connectMillis = 10_000, readMillis = 20_000, writeMillis = 20_000, callMillis = 30_000)
    private val downloadTimeouts = HttpTimeouts(connectMillis = 15_000, readMillis = 60_000, writeMillis = 60_000, callMillis = 0)

    private val lock = PlatformLock()
    private var feed = HashMap<ProxySettings?, HttpClient>()
    private var download = HashMap<ProxySettings?, HttpClient>()

    /**
     * The client for feeds from [url]. Clients are fixed at creation, so there is one per
     * proxy in use (none, the user's, the built-in one for Flibusta), made when first needed.
     */
    fun feedClientFor(url: String): HttpClient = lock.withLock {
        val proxy = NetworkProxy.forUrl(url)
        feed.getOrPut(proxy) { luminaHttpClient(feedTimeouts, proxy) }
    }

    fun downloadClientFor(url: String): HttpClient = lock.withLock {
        val proxy = NetworkProxy.forUrl(url)
        download.getOrPut(proxy) { luminaHttpClient(downloadTimeouts, proxy) }
    }
}
