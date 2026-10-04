package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.text.parseUrl
import com.lumina.reader.platform.Ids

/**
 * File naming for stored books. Books are kept as `books/<uuid>.<ext>` so a
 * name supplied by another app or a server can never point outside the books
 * directory or overwrite another book; the original name is only used as
 * metadata (a title fallback).
 */
object BookFileNames {
    private val STORED_NAME = Regex("^[A-Za-z0-9-]{1,64}\\.(epub|fb2|fb2\\.zip|pdf|txt)$")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
    private val BOOK_EXTENSIONS = listOf(".fb2.zip", ".fb2_zip", ".fbz", ".epub", ".fb2", ".pdf", ".txt", ".md", ".zip")

    fun extensionFor(format: BookFormat): String = when (format) {
        BookFormat.EPUB -> "epub"
        BookFormat.FB2 -> "fb2"
        BookFormat.FB2_ZIP -> "fb2.zip"
        BookFormat.PDF -> "pdf"
        BookFormat.TXT -> "txt"
    }

    fun newStoredName(format: BookFormat, id: String = Ids.randomUuid()): String =
        "$id.${extensionFor(format)}"

    /** True for names produced by [newStoredName]. */
    fun isSafeStoredName(name: String): Boolean = STORED_NAME.matches(name)

    /**
     * Cleans a display name from another app or a server: drops any directory
     * part, control characters and surrounding spaces. Returns null when
     * nothing meaningful remains.
     */
    fun sanitizeDisplayName(raw: String?): String? {
        if (raw == null) return null
        val lastSegment = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = lastSegment.replace(CONTROL_CHARS, "").trim().take(200)
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** "Толстой_Война_и_мир.fb2.zip" -> "Толстой Война и мир". */
    fun titleFromFileName(raw: String?): String? {
        val name = sanitizeDisplayName(raw) ?: return null
        val lower = name.lowercase()
        val extension = BOOK_EXTENSIONS.firstOrNull { lower.endsWith(it) }
        val base = if (extension != null) name.dropLast(extension.length) else name
        return base.replace('_', ' ').replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * File name from a Content-Disposition header. The RFC 5987 form
     * (`filename*=UTF-8''...`) wins over the plain `filename=` parameter.
     */
    fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (extended != null) {
            val value = extended.groupValues[1].trim().trim('"')
            val parts = value.split("'", limit = 3)
            if (parts.size == 3) {
                val charset = parts[0].ifBlank { "UTF-8" }
                val decoded = runCatching { decodeUrlComponent(parts[2].replace("+", "%2B"), charset) }.getOrNull()
                sanitizeDisplayName(decoded)?.let { return it }
            }
        }
        val plain = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)
            ?: return null
        val quoted = plain.groupValues[2]
        val value = if (quoted.isNotEmpty()) quoted else plain.groupValues[1].trim()
        return sanitizeDisplayName(value)
    }

    /** Last path segment of a URL, percent-decoded; null when there is none. */
    fun fileNameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = parseUrl(url)?.rawPath
            ?: url.substringBefore('?').substringBefore('#')
        val segment = path.trimEnd('/').substringAfterLast('/')
        if (segment.isBlank()) return null
        val decoded = runCatching { decodeUrlComponent(segment.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(segment)
        return sanitizeDisplayName(decoded)
    }
}

/**
 * `java.net.URLDecoder.decode(value, charset)`: "+" becomes a space, each run of
 * `%XX` escapes is decoded as bytes in [charset], other characters stay; throws
 * for a malformed escape or an unknown charset. Android: the JDK class, as
 * before; iOS: [UrlDecoding].
 */
expect fun decodeUrlComponent(value: String, charset: String): String

/** Picks the title and author stored for an imported book. */
object ImportMetadata {
    private val PLACEHOLDER_AUTHORS = setOf("", "неизвестный автор", "unknown", "unknown author", "автор неизвестен")

    /**
     * Parsers fall back to the file name for the title. Stored files are named
     * by a random id, so such a title is replaced by the catalog title or the
     * original file name.
     */
    fun resolveTitle(
        parsedTitle: String,
        storedFileName: String,
        fallbackTitle: String?,
        displayName: String?
    ): String {
        val storedBase = storedFileName.substringBefore('.')
        val parsed = parsedTitle.trim()
        val parsedIsUsable = parsed.isNotEmpty() &&
            (storedBase.isEmpty() || !parsed.contains(storedBase, ignoreCase = true))
        if (parsedIsUsable) return parsed
        return fallbackTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: BookFileNames.titleFromFileName(displayName)
            ?: "Без названия"
    }

    fun resolveAuthor(parsedAuthor: String, fallbackAuthor: String?): String {
        val parsed = parsedAuthor.trim()
        if (parsed.lowercase() !in PLACEHOLDER_AUTHORS) return parsed
        return fallbackAuthor?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.ifEmpty { "Неизвестный автор" }
    }
}
