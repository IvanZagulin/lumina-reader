package com.lumina.reader.core.text

import com.lumina.reader.core.text.charset.ByteToCharDecoder
import okio.BufferedSource

/**
 * A source of characters, the common counterpart of java.io.Reader.read:
 * fills `buffer[offset until offset + length]` and returns how many
 * characters it stored, or -1 at the end of the input.
 */
fun interface CharReader {
    fun read(buffer: CharArray, offset: Int, length: Int): Int
}

/** Reads a string, like java.io.StringReader. */
class StringCharReader(private val text: String) : CharReader {
    private var position = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= text.length) return -1
        val count = minOf(length, text.length - position)
        for (i in 0 until count) buffer[offset + i] = text[position + i]
        position += count
        return count
    }
}

/**
 * Decodes [source] on the fly with [decoder], like an InputStreamReader over
 * a stream: large books are never held as one byte array. The source is not
 * closed here.
 */
class DecodingCharReader(
    private val source: BufferedSource,
    private val decoder: ByteToCharDecoder,
    chunkBytes: Int = DEFAULT_CHUNK_BYTES
) : CharReader {
    private val bytes = ByteArray(chunkBytes)
    private val chars = StringBuilder()
    private var charPosition = 0
    private var finished = false

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (charPosition >= chars.length) {
            if (finished) return -1
            chars.setLength(0)
            charPosition = 0
            val read = source.read(bytes, 0, bytes.size)
            if (read < 0) {
                decoder.flush(chars)
                finished = true
            } else {
                decoder.decode(bytes, 0, read, chars)
            }
        }
        val count = minOf(length, chars.length - charPosition)
        chars.toCharArray(buffer, offset, charPosition, charPosition + count)
        charPosition += count
        return count
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 64 * 1024
    }
}
