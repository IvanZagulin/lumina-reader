package com.lumina.reader.core.text.charset

/**
 * Streaming byte-to-character decoder. Bytes may be fed in chunks of any size:
 * a character split between two chunks is completed by the next call.
 * Malformed or unmappable input becomes U+FFFD, the way java.nio decoders
 * with CodingErrorAction.REPLACE (and `String(bytes, charset)`) handle it.
 */
interface ByteToCharDecoder {
    /** Decodes `bytes[offset until offset + length]`, appending the characters to [out]. */
    fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder)

    /** End of input: an incomplete trailing sequence becomes U+FFFD; the decoder can then be reused. */
    fun flush(out: StringBuilder)
}

/** Decodes a whole array with a fresh state of [decoder]. */
internal fun ByteToCharDecoder.decodeAll(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
    val out = StringBuilder(length)
    decode(bytes, offset, length, out)
    flush(out)
    return out.toString()
}

internal const val REPLACEMENT_CHAR: Char = '�'

/** Single-byte charset: ASCII below 0x80, [upper] for bytes 0x80..0xFF (see [SingleByteTables]). */
internal class SingleByteDecoder(private val upper: String) : ByteToCharDecoder {
    init {
        require(upper.length == 128) { "a table has 128 characters" }
    }

    override fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt()
            out.append(if (b >= 0) b.toChar() else upper[b + 128])
        }
    }

    override fun flush(out: StringBuilder) = Unit
}
