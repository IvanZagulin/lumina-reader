package com.lumina.reader.core.text

/** Percent-encoding for URLs in common code. */
object PercentCodec {

    private const val HEX = "0123456789ABCDEF"

    /**
     * Encodes [text] as a query or path component: exactly
     * `URLEncoder.encode(text, "UTF-8").replace("+", "%20")`. Letters, digits
     * and `-_.*` stay; everything else is UTF-8 with upper-case `%XX`, a space
     * is `%20`, and a lone surrogate becomes `%3F` ("?", as the JDK's
     * UTF-8 encoder writes it).
     */
    fun encodeComponent(text: String): String {
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '*' ->
                    out.append(c)
                c == ' ' -> out.append("%20")
                c.code < 0x80 -> appendByte(out, c.code)
                c.code < 0x800 -> {
                    appendByte(out, 0xC0 or (c.code shr 6))
                    appendByte(out, 0x80 or (c.code and 0x3F))
                }
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                    appendByte(out, 0xF0 or (codePoint shr 18))
                    appendByte(out, 0x80 or ((codePoint shr 12) and 0x3F))
                    appendByte(out, 0x80 or ((codePoint shr 6) and 0x3F))
                    appendByte(out, 0x80 or (codePoint and 0x3F))
                    i++
                }
                c.isSurrogate() -> appendByte(out, '?'.code)
                else -> {
                    appendByte(out, 0xE0 or (c.code shr 12))
                    appendByte(out, 0x80 or ((c.code shr 6) and 0x3F))
                    appendByte(out, 0x80 or (c.code and 0x3F))
                }
            }
            i++
        }
        return out.toString()
    }

    private fun appendByte(out: StringBuilder, value: Int) {
        out.append('%').append(HEX[(value shr 4) and 0xF]).append(HEX[value and 0xF])
    }
}
