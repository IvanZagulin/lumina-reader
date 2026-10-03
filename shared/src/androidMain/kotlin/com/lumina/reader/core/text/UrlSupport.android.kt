package com.lumina.reader.core.text

import java.net.URI

// java.net.URI, exactly as OpdsUrls used it before the move to common code.

actual fun parseUrl(url: String): UrlParts? = try {
    val uri = URI(url)
    UrlParts(uri.scheme, uri.host, uri.port, uri.rawPath, uri.rawQuery)
} catch (e: Exception) {
    null
}

actual fun resolveUrl(base: String, reference: String): String? = try {
    val baseUri = URI(base)
    val normalizedBase = if (baseUri.rawPath.isNullOrEmpty()) URI("$base/") else baseUri
    normalizedBase.resolve(reference).toString()
} catch (e: Exception) {
    null
}
