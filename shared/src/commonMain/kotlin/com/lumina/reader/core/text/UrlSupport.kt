package com.lumina.reader.core.text

/** Components of a URL as java.net.URI reports them (raw, not percent-decoded). */
class UrlParts(
    val scheme: String?,
    val host: String?,
    /** -1 when the URL has no port. */
    val port: Int,
    val rawPath: String?,
    val rawQuery: String?
)

/**
 * Parses [url] like `java.net.URI(url)`; null when it is not a valid URI.
 * Android: java.net.URI (unchanged behaviour); iOS: [Rfc3986].
 */
expect fun parseUrl(url: String): UrlParts?

/**
 * [reference] resolved against [base]; null when either is not a valid URI.
 * Android: `java.net.URI(base).resolve(reference)` (RFC 2396), after giving a
 * base without a path the path "/"; iOS: [Rfc3986.resolve]. Callers escape
 * spaces and similar characters first, and handle empty, query-only and
 * fragment-only references themselves (RFC 2396 resolves those differently).
 */
expect fun resolveUrl(base: String, reference: String): String?
