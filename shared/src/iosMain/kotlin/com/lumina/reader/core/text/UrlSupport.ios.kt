package com.lumina.reader.core.text

actual fun parseUrl(url: String): UrlParts? {
    val reference = Rfc3986.parse(url) ?: return null
    return UrlParts(
        scheme = reference.scheme,
        host = reference.host,
        port = reference.port,
        rawPath = reference.path,
        rawQuery = reference.query
    )
}

actual fun resolveUrl(base: String, reference: String): String? = Rfc3986.resolve(base, reference)
