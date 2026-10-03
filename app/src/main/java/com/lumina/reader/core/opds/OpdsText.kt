package com.lumina.reader.core.opds

/** Turns OPDS text constructs (plain, escaped HTML, XHTML) into readable plain text. */
object OpdsText {
    private val BLOCK_BREAK = Regex("(?i)<\\s*(br|/p|p|/div|div|/li|li|/h[1-6]|h[1-6]|tr|/tr)\\b[^>]*>")
    private val TAG = Regex("<[^>]*>")
    private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")
    private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")
    private val SPACES = Regex("[ \\t\\u00A0\\u2007\\u202F]+")
    private val SPACE_AROUND_NEWLINE = Regex(" *\\n *")
    private val MANY_NEWLINES = Regex("\\n{2,}")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘",
        "rsquo" to "’", "copy" to "©", "shy" to "", "middot" to "·", "bull" to "•"
    )

    /** Strips tags (keeping line breaks for block elements) and decodes entities. */
    fun htmlToPlain(html: String): String {
        val withBreaks = html.replace(BLOCK_BREAK, "\n")
        val noTags = withBreaks.replace(TAG, "")
        return normalize(decodeEntities(noTags))
    }

    /** Collapses runs of spaces and blank lines (paragraphs end with one line break), trims. */
    fun normalize(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(SPACES, " ")
            .replace(SPACE_AROUND_NEWLINE, "\n")
            .replace(MANY_NEWLINES, "\n")
            .trim()

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val numeric = NUMERIC_ENTITY.replace(text) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        return NAMED_ENTITY.replace(numeric) { match ->
            NAMED[match.groupValues[1].lowercase()] ?: match.value
        }
    }
}
