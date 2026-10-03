package com.lumina.reader.core.text.charset

/**
 * UTF-8 decoder with the replacement rules of the JDK's decoder
 * (sun.nio.cs.UTF_8, also used by `String(bytes, UTF_8)`):
 *
 * - each malformed piece becomes one U+FFFD; how many bytes it covers follows
 *   the JDK (e.g. `E0 80` gives two, `F0 90 41` one plus "A");
 * - an encoded surrogate (`ED A0..BF xx`) is one U+FFFD for all three bytes;
 * - a sequence cut off by the end of the input is one U+FFFD.
 *
 * Utf8DecoderParityTest compares it with java.nio over generated input.
 */
internal class Utf8Decoder : ByteToCharDecoder {
    /** Start of a sequence that the previous chunk ended in the middle of (at most 3 bytes). */
    private val pending = ByteArray(3)
    private var pendingCount = 0

    override fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        val end = offset + length
        var start = offset
        if (pendingCount > 0) {
            // Finish the pending sequence with up to four new bytes; whatever starts
            // inside the pending bytes is then decidable or the chunk was all used.
            val take = minOf(length, 4)
            val combined = ByteArray(pendingCount + take)
            pending.copyInto(combined, 0, 0, pendingCount)
            bytes.copyInto(combined, pendingCount, offset, offset + take)
            val stop = decodeLoop(combined, 0, combined.size, out)
            if (stop < pendingCount) {
                keepPending(combined, stop, combined.size)
                return
            }
            start = offset + (stop - pendingCount)
            pendingCount = 0
        }
        val stop = decodeLoop(bytes, start, end, out)
        keepPending(bytes, stop, end)
    }

    override fun flush(out: StringBuilder) {
        if (pendingCount > 0) {
            val stop = decodeLoop(pending, 0, pendingCount, out)
            if (stop < pendingCount) out.append(REPLACEMENT_CHAR)
            pendingCount = 0
        }
    }

    private fun keepPending(source: ByteArray, from: Int, to: Int) {
        val count = to - from
        check(count <= pending.size) { "incomplete UTF-8 sequence longer than 3 bytes" }
        source.copyInto(pending, 0, from, to)
        pendingCount = count
    }

    /**
     * Decodes `src[from until to]` and returns where an incomplete sequence
     * starts (that needs more input), or [to] when everything was decoded.
     */
    private fun decodeLoop(src: ByteArray, from: Int, to: Int, out: StringBuilder): Int {
        var sp = from
        while (sp < to) {
            val b1 = src[sp].toInt() and 0xFF
            when {
                b1 < 0x80 -> {
                    out.append(b1.toChar())
                    sp++
                }
                b1 in 0xC2..0xDF -> {
                    if (to - sp < 2) return sp
                    val b2 = src[sp + 1].toInt() and 0xFF
                    if (isNotContinuation(b2)) {
                        out.append(REPLACEMENT_CHAR)
                        sp += 1
                    } else {
                        out.append((((b1 and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                        sp += 2
                    }
                }
                b1 in 0xE0..0xEF -> {
                    val remaining = to - sp
                    if (remaining < 3) {
                        if (remaining > 1 && isMalformed3Prefix(b1, src[sp + 1].toInt() and 0xFF)) {
                            out.append(REPLACEMENT_CHAR)
                            sp += 1
                            continue
                        }
                        return sp
                    }
                    val b2 = src[sp + 1].toInt() and 0xFF
                    val b3 = src[sp + 2].toInt() and 0xFF
                    if (isMalformed3Prefix(b1, b2) || isNotContinuation(b3)) {
                        out.append(REPLACEMENT_CHAR)
                        sp += if (isMalformed3Prefix(b1, b2)) 1 else 2
                        continue
                    }
                    val c = (((b1 and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar()
                    out.append(if (c.isSurrogate()) REPLACEMENT_CHAR else c)
                    sp += 3
                }
                b1 in 0xF0..0xF7 -> {
                    val remaining = to - sp
                    if (remaining < 4) {
                        if (b1 > 0xF4 || remaining > 1 && isMalformed4Prefix(b1, src[sp + 1].toInt() and 0xFF)) {
                            out.append(REPLACEMENT_CHAR)
                            sp += 1
                            continue
                        }
                        if (remaining > 2 && isNotContinuation(src[sp + 2].toInt() and 0xFF)) {
                            out.append(REPLACEMENT_CHAR)
                            sp += 2
                            continue
                        }
                        return sp
                    }
                    val b2 = src[sp + 1].toInt() and 0xFF
                    val b3 = src[sp + 2].toInt() and 0xFF
                    val b4 = src[sp + 3].toInt() and 0xFF
                    val codePoint = ((b1 and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)
                    if (isNotContinuation(b2) || isNotContinuation(b3) || isNotContinuation(b4) ||
                        codePoint !in 0x10000..0x10FFFF
                    ) {
                        out.append(REPLACEMENT_CHAR)
                        sp += when {
                            b1 > 0xF4 || isMalformed4Prefix(b1, b2) -> 1
                            isNotContinuation(b3) -> 2
                            else -> 3
                        }
                        continue
                    }
                    val v = codePoint - 0x10000
                    out.append((0xD800 + (v shr 10)).toChar())
                    out.append((0xDC00 + (v and 0x3FF)).toChar())
                    sp += 4
                }
                else -> {
                    // 0x80..0xC1 (stray continuation, overlong lead) and 0xF8..0xFF.
                    out.append(REPLACEMENT_CHAR)
                    sp++
                }
            }
        }
        return sp
    }

    private fun isNotContinuation(b: Int): Boolean = (b and 0xC0) != 0x80

    /** First two bytes of a three-byte sequence that can never complete (overlong `E0 80..9F` or no continuation). */
    private fun isMalformed3Prefix(b1: Int, b2: Int): Boolean =
        (b1 == 0xE0 && (b2 and 0xE0) == 0x80) || isNotContinuation(b2)

    /** First two bytes of a four-byte sequence that can never complete (overlong, above U+10FFFF, no continuation). */
    private fun isMalformed4Prefix(b1: Int, b2: Int): Boolean =
        (b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 and 0xF0) != 0x80) || isNotContinuation(b2)
}
