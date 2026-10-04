package com.lumina.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

/**
 * OkHttp underneath, set up as the app's own OkHttpClients were before Ktor,
 * so Android keeps its exact network behaviour: the same connect, read, write
 * and call timeouts, and redirects followed by OkHttp itself
 * (`followRedirects` + `followSslRedirects`: https -> http too, at most 20
 * hops, the Authorization header dropped when the host changes). Ktor's own
 * redirect plugin differs in those details, so it is off; [finalUrl] still
 * knows where the redirects ended through [LuminaOkHttpInterceptor].
 *
 * The timeouts go to OkHttp directly rather than through Ktor's HttpTimeout:
 * that keeps OkHttp's call timeout and its exceptions exactly as they were.
 */
actual fun luminaHttpClient(timeouts: HttpTimeouts, proxy: ProxySettings?): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    useDefaultTransformers = false
    followRedirects = false
    install(MarkMissingUserAgent)
    engine {
        config {
            // One dispatcher for every client, as the feed and download
            // clients shared one before: OkHttp's per-host limit then covers both.
            dispatcher(sharedDispatcher)
            connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
            readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
            writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
            callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
            followRedirects(true)
            followSslRedirects(true)
            if (proxy != null && proxy.usable) applyProxy(proxy)
        }
        addInterceptor(LuminaOkHttpInterceptor)
    }
}

/**
 * The pool is OkHttp's own default (no core threads, unbounded, 60 s idle), but
 * closing one client must not end it for the rest: Ktor's engine shuts down the
 * dispatcher's executor on close, and with one dispatcher for every client the
 * next request of any other client would then be rejected.
 */
private val sharedDispatcher: Dispatcher by lazy {
    val pool = ThreadPoolExecutor(
        0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { task -> Thread(task, "OkHttp Dispatcher").apply { isDaemon = false } }
    )
    Dispatcher(SurvivingExecutor(pool))
}

private class SurvivingExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
}

/**
 * Stands in for "no User-Agent" between Ktor and OkHttp. Ktor's engine fills
 * in its own User-Agent when a request has none; OkHttp would then send that
 * instead of its default ("okhttp/<version>"), which the AI requests (the only
 * ones that set none) always carried. Marking the gap with this value and
 * removing it in [LuminaOkHttpInterceptor] works whatever Ktor's default
 * text is in a given version.
 */
private const val NO_USER_AGENT = "x-lumina-no-user-agent"

/** Sets [NO_USER_AGENT] on requests whose caller set no User-Agent. */
private val MarkMissingUserAgent = createClientPlugin("LuminaMarkMissingUserAgent") {
    onRequest { request, _ ->
        if (request.headers[HttpHeaders.UserAgent] == null) {
            request.headers[HttpHeaders.UserAgent] = NO_USER_AGENT
        }
    }
}

/**
 * Application interceptor (it runs outside OkHttp's redirect and retry
 * handling):
 *
 * - removes the [NO_USER_AGENT] marker, so that OkHttp's bridge adds its own
 *   default User-Agent as it always did;
 * - reports the URL of the response OkHttp finally got in [FINAL_URL_HEADER];
 * - hands a failed call on as [OkHttpFailure].
 */
private object LuminaOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sent = if (request.header("User-Agent") == NO_USER_AGENT) {
            request.newBuilder().removeHeader("User-Agent").build()
        } else {
            request
        }
        val response = try {
            chain.proceed(sent)
        } catch (e: IOException) {
            throw OkHttpFailure(e)
        }
        return response.newBuilder()
            .header(FINAL_URL_HEADER, response.request.url.toString())
            .build()
    }
}

/**
 * Carries the exception of a failed OkHttp call past Ktor's OkHttp engine
 * unchanged. The engine rewrites what it receives: an exception with
 * suppressed ones is replaced by the first of those (OkHttp attaches every
 * failure it recovered from, so going offline right after a stale pooled
 * connection would report that connection's "unexpected end of stream"
 * instead of the UnknownHostException), and a SocketTimeoutException becomes
 * Ktor's own timeout exception with an English message. This wrapper has
 * neither; it keeps the original's message, which the AI chat shows as is,
 * and has the original as its cause, which describeNetworkError looks
 * through, so both read exactly as before Ktor.
 */
private class OkHttpFailure(cause: IOException) : IOException(cause.message, cause)

/**
 * Sends everything through [proxy]. The address is left unresolved, so the proxy
 * looks the host up itself (also what a blocked name needs). An HTTP proxy asks
 * for its login with a Proxy-Authorization challenge, which OkHttp answers; a
 * SOCKS5 proxy asks through the JVM's default authenticator, set only when a
 * login was typed in.
 */
private fun OkHttpClient.Builder.applyProxy(proxy: ProxySettings) {
    val type = if (proxy.type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
    proxy(Proxy(type, InetSocketAddress.createUnresolved(proxy.host.trim(), proxy.port)))
    if (!proxy.hasCredentials) return
    if (proxy.type == ProxyType.HTTP) {
        proxyAuthenticator { _, response ->
            // A second 407 means the login was refused: give up instead of looping.
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(proxy.username, proxy.password))
                    .build()
            }
        }
    } else {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true) {
                    PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                } else {
                    null
                }
        })
    }
}
