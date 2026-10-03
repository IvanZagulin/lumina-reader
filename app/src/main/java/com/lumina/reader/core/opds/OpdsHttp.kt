package com.lumina.reader.core.opds

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared HTTP clients. Both share one connection pool and dispatcher; the
 * download client has no overall call timeout so large books are not cut off,
 * only connect/read timeouts that detect a stalled connection.
 */
object OpdsHttp {
    const val USER_AGENT = "LuminaReader/1.2 (Android; OPDS)"
    const val ACCEPT_FEED = "application/atom+xml;profile=opds-catalog, application/atom+xml, application/xml;q=0.9, */*;q=0.8"

    val feedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        feedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Executes the call asynchronously; cancelling the coroutine cancels the HTTP
 * call. The caller must close the returned response.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (ignored: Throwable) {
        }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
}
