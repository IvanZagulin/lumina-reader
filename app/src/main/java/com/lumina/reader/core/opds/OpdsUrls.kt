package com.lumina.reader.core.opds

import java.net.URI
import java.net.URLEncoder

/** URL helpers shared by feed loading, search and downloads. */
object OpdsUrls {

    /**
     * Resolves [href] against [base] like a browser does. Handles absolute,
     * protocol-relative, root-relative and relative references, and a base
     * without a path ("https://host" + "x" -> "https://host/x").
     */
    fun resolve(base: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.isEmpty()) return base
        // java.net.URI follows RFC 2396 here and would drop the last path segment.
        if (trimmed.startsWith("?")) return base.substringBefore('#').substringBefore('?') + trimmed
        if (trimmed.startsWith("#")) return base.substringBefore('#') + trimmed
        return try {
            val baseUri = URI(base)
            val normalizedBase = if (baseUri.rawPath.isNullOrEmpty()) URI("$base/") else baseUri
            normalizedBase.resolve(escapeForUri(trimmed)).toString()
        } catch (e: Exception) {
            val origin = origin(base).orEmpty()
            when {
                trimmed.startsWith("//") -> (scheme(base) ?: "https") + ":" + trimmed
                trimmed.startsWith("/") -> origin + trimmed
                else -> base.substringBeforeLast('/') + "/" + trimmed
            }
        }
    }

    /**
     * Resolves an OpenSearch template. Its `{placeholders}` are not legal in a
     * URI, so they are protected during resolution.
     */
    fun resolveTemplate(base: String, template: String): String {
        val protectedTemplate = template.replace("{", OPEN_MARK).replace("}", CLOSE_MARK)
        return resolve(base, protectedTemplate).replace(OPEN_MARK, "{").replace(CLOSE_MARK, "}")
    }

    /** "https://host:8080/a/b" -> "https://host:8080". */
    fun origin(url: String): String? = try {
        val uri = URI(url)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            null
        } else {
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }
    } catch (e: Exception) {
        null
    }

    fun host(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    /**
     * The URL itself followed by the same path on every mirror, so a blocked
     * domain falls back to a working one.
     */
    fun candidates(url: String, mirrorBaseUrls: List<String>): List<String> {
        val pathAndQuery = try {
            val uri = URI(url)
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$path$query"
        } catch (e: Exception) {
            url
        }

        val result = mutableListOf<String>()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            result += url
        }
        val ownHost = host(url)
        mirrorBaseUrls.forEach { mirror ->
            val mirrorBase = mirror.trimEnd('/')
            // Only rewrite URLs that belong to the mirrored site (or are relative).
            val belongs = ownHost == null || mirrorBaseUrls.any { host(it) == ownHost }
            if (belongs) {
                result += if (pathAndQuery.startsWith("/")) "$mirrorBase$pathAndQuery" else "$mirrorBase/$pathAndQuery"
            }
        }
        return result.distinct()
    }

    /** Percent-encodes a search query; spaces become %20 (valid in paths and queries). */
    fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /** Adds `https://` when the user typed a bare host; null for unusable input. */
    fun normalizeUserUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val scheme = scheme(withScheme)?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return if (host(withScheme).isNullOrBlank()) null else withScheme
    }

    private fun scheme(url: String): String? = try {
        URI(url).scheme
    } catch (e: Exception) {
        null
    }

    /** Escapes characters that java.net.URI rejects but servers put in hrefs. */
    private fun escapeForUri(value: String): String {
        val builder = StringBuilder(value.length)
        for (char in value) {
            when {
                char == ' ' -> builder.append("%20")
                char == '|' -> builder.append("%7C")
                char == '"' -> builder.append("%22")
                char == '<' -> builder.append("%3C")
                char == '>' -> builder.append("%3E")
                char == '^' -> builder.append("%5E")
                char == '`' -> builder.append("%60")
                char == '{' -> builder.append("%7B")
                char == '}' -> builder.append("%7D")
                char == '\\' -> builder.append("%5C")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private const val OPEN_MARK = "__lumina_open__"
    private const val CLOSE_MARK = "__lumina_close__"
}
