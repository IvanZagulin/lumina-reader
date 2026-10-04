package com.lumina.reader.core.library

import com.lumina.reader.core.text.charset.TextCharset

/**
 * `java.net.URLDecoder.decode(String, String)` in common code: the iPhone
 * app's [decodeUrlComponent] (Android keeps the JDK class). The algorithm is
 * the JDK's: "+" is a space; every run of `%XX` escapes is collected into
 * bytes and decoded in one go with the charset (malformed bytes become
 * U+FFFD); every other character is kept. Like `Integer.parseInt`, an escape
 * may carry a sign ("%+5" is byte 5, "%-0" byte 0) and any Unicode hex digit.
 *
 * Throws [IllegalArgumentException] for a truncated or non-hexadecimal escape,
 * a negative value, and an empty or unknown charset name.
 */
internal object UrlDecoding {

    fun decode(value: String, charset: String): String {
        require(charset.isNotEmpty()) { "URLDecoder: empty string enc parameter" }
        val textCharset = requireNotNull(TextCharset.forName(charset)) { "Unsupported charset: $charset" }
        val length = value.length
        val out = StringBuilder(if (length > 500) length / 2 else length)
        var needToChange = false
        var bytes: ByteArray? = null
        var i = 0
        while (i < length) {
            var c = value[i]
            when (c) {
                '+' -> {
                    out.append(' ')
                    i++
                    needToChange = true
                }
                '%' -> {
                    // (length - i) / 3 bounds the bytes of this and every later run.
                    val buffer = bytes ?: ByteArray((length - i) / 3).also { bytes = it }
                    var pos = 0
                    while (i + 2 < length && c == '%') {
                        val byte = parseEscape(value, i + 1)
                        require(byte >= 0) { "URLDecoder: Illegal hex characters in escape (%) pattern - negative value" }
                        buffer[pos++] = byte.toByte()
                        i += 3
                        if (i < length) c = value[i]
                    }
                    require(i >= length || c != '%') { "URLDecoder: Incomplete trailing escape (%) pattern" }
                    out.append(textCharset.decode(buffer, 0, pos))
                    needToChange = true
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return if (needToChange) out.toString() else value
    }

    /** `Integer.parseInt(value, start, start + 2, 16)`. */
    private fun parseEscape(value: String, start: Int): Int {
        val first = value[start]
        val second = value[start + 1]
        return when (first) {
            '-' -> -hexDigit(second)
            '+' -> hexDigit(second)
            else -> hexDigit(first) * 16 + hexDigit(second)
        }
    }

    /** `Character.digit(c, 16)`, failing for anything that is not a hexadecimal digit. */
    private fun hexDigit(c: Char): Int =
        c.digitToIntOrNull(16)
            ?: throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - \"$c\"")
}
