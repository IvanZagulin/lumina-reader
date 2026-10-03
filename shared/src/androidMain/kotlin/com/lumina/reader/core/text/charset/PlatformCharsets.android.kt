package com.lumina.reader.core.text.charset

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * java.nio, exactly as the Android code used it before the move: names resolve
 * with Charset.forName, whole arrays decode with `String(bytes, charset)` and
 * streams with a REPLACE decoder (what InputStreamReader does).
 */
actual object PlatformCharsets {

    actual fun canonicalName(name: String): String? = javaCharsetOrNull(name)?.name()

    actual fun decoderFor(name: String): ByteToCharDecoder? = javaCharsetOrNull(name)?.let(::NioDecoder)

    actual fun decodeWhole(bytes: ByteArray, offset: Int, length: Int, name: String): String? =
        javaCharsetOrNull(name)?.let { String(bytes, offset, length, it) }

    private fun javaCharsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (e: Exception) {
        null
    }
}

/** The java.nio charset of [this] (for InputStreamReader and other JVM APIs). */
fun TextCharset.toJavaCharset(): Charset = Charset.forName(name)

/** Streaming adapter over a java.nio decoder, with the bookkeeping of sun.nio.cs.StreamDecoder. */
private class NioDecoder(charset: Charset) : ByteToCharDecoder {
    private val decoder: CharsetDecoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val chars: CharBuffer = CharBuffer.allocate(CHAR_BUFFER_SIZE)
    private var leftover: ByteArray = EMPTY

    override fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        val input = if (leftover.isEmpty()) {
            ByteBuffer.wrap(bytes, offset, length)
        } else {
            ByteBuffer.wrap(leftover + bytes.copyOfRange(offset, offset + length))
        }
        run(input, endOfInput = false, out)
        leftover = if (input.hasRemaining()) ByteArray(input.remaining()).also(input::get) else EMPTY
    }

    override fun flush(out: StringBuilder) {
        run(ByteBuffer.wrap(leftover), endOfInput = true, out)
        while (true) {
            val result = decoder.flush(chars)
            drain(out)
            if (!result.isOverflow) break
        }
        leftover = EMPTY
        decoder.reset()
    }

    private fun run(input: ByteBuffer, endOfInput: Boolean, out: StringBuilder) {
        while (true) {
            val result = decoder.decode(input, chars, endOfInput)
            drain(out)
            if (!result.isOverflow) break
        }
    }

    private fun drain(out: StringBuilder) {
        chars.flip()
        out.append(chars)
        chars.clear()
    }

    private companion object {
        const val CHAR_BUFFER_SIZE = 8192
        val EMPTY = ByteArray(0)
    }
}
