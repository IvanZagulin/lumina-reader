package com.lumina.reader.core.text

/** Unicode code point helpers for common code (java.lang.Character.toChars and friends). */
object Codepoints {
    const val MAX_CODE_POINT: Int = 0x10FFFF

    fun isValid(codePoint: Int): Boolean = codePoint in 0..MAX_CODE_POINT

    /**
     * The UTF-16 form of [codePoint], like `String(Character.toChars(codePoint))`:
     * one char below U+10000 (lone surrogates included), a surrogate pair above.
     */
    fun toString(codePoint: Int): String {
        require(isValid(codePoint)) { "Not a valid Unicode code point: $codePoint" }
        if (codePoint < 0x10000) return codePoint.toChar().toString()
        val v = codePoint - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    /** Appends the UTF-16 form of [codePoint] to [out], like StringBuilder.appendCodePoint. */
    fun append(out: StringBuilder, codePoint: Int): StringBuilder {
        require(isValid(codePoint)) { "Not a valid Unicode code point: $codePoint" }
        if (codePoint < 0x10000) return out.append(codePoint.toChar())
        val v = codePoint - 0x10000
        return out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
    }
}
