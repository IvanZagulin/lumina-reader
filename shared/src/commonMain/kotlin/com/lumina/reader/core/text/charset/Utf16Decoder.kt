package com.lumina.reader.core.text.charset

/**
 * UTF-16 decoder with the rules of the JDK's sun.nio.cs.UnicodeDecoder:
 *
 * - [Mode.BIG_ENDIAN] / [Mode.LITTLE_ENDIAN] ("UTF-16BE"/"UTF-16LE") never
 *   consume a byte-order mark: a leading U+FEFF is an ordinary character;
 * - [Mode.DETECT] ("UTF-16") consumes a leading FE FF or FF FE mark and
 *   otherwise reads big-endian;
 * - after that, U+FEFF and U+FFFE are ordinary characters (JDK 21);
 * - a lone low surrogate becomes U+FFFD; a high surrogate followed by
 *   anything but a low surrogate becomes one U+FFFD for both units;
 * - an odd trailing byte or an unfinished pair at the end is one U+FFFD.
 */
internal class Utf16Decoder(private val mode: Mode) : ByteToCharDecoder {

    enum class Mode { BIG_ENDIAN, LITTLE_ENDIAN, DETECT }

    private var bigEndian = mode != Mode.LITTLE_ENDIAN
    private var orderKnown = mode != Mode.DETECT
    private var oddByte = -1
    private var pendingHigh = -1

    override fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            i++
            if (oddByte < 0) {
                oddByte = b
                continue
            }
            val first = oddByte
            oddByte = -1
            unit(first, b, out)
        }
    }

    private fun unit(b1: Int, b2: Int, out: StringBuilder) {
        if (!orderKnown) {
            orderKnown = true
            val asBig = (b1 shl 8) or b2
            if (asBig == BYTE_ORDER_MARK) {
                bigEndian = true
                return
            }
            if (asBig == REVERSED_MARK) {
                bigEndian = false
                return
            }
            bigEndian = true
        }
        val c = if (bigEndian) (b1 shl 8) or b2 else (b2 shl 8) or b1
        val high = pendingHigh
        if (high >= 0) {
            pendingHigh = -1
            if (c in 0xDC00..0xDFFF) {
                out.append(high.toChar())
                out.append(c.toChar())
            } else {
                out.append(REPLACEMENT_CHAR)
            }
            return
        }
        when (c) {
            in 0xD800..0xDBFF -> pendingHigh = c
            in 0xDC00..0xDFFF -> out.append(REPLACEMENT_CHAR)
            else -> out.append(c.toChar())
        }
    }

    override fun flush(out: StringBuilder) {
        if (oddByte >= 0 || pendingHigh >= 0) out.append(REPLACEMENT_CHAR)
        oddByte = -1
        pendingHigh = -1
        bigEndian = mode != Mode.LITTLE_ENDIAN
        orderKnown = mode != Mode.DETECT
    }

    private companion object {
        const val BYTE_ORDER_MARK = 0xFEFF
        const val REVERSED_MARK = 0xFFFE
    }
}
