package com.lumina.reader.core.download

/**
 * Short Russian explanation of a failure of the platform's network stack, or
 * null when [error] is not one it recognises. `describeNetworkError` asks for
 * every exception of the cause chain, after the common cases (import errors,
 * HTTP statuses, Ktor's timeouts).
 *
 * The exception types are platform ones: java.net / javax.net.ssl on Android
 * (OkHttp throws them, as before Ktor), NSURLErrorDomain errors of
 * NSURLSession on iOS (Ktor's Darwin engine wraps them).
 */
expect fun platformNetworkErrorMessage(error: Throwable): String?
