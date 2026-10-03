package com.lumina.reader.core.parser.common

import com.lumina.reader.core.text.charset.TextCharset
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.use

/**
 * Character-set detection shared by the text based parsers.
 *
 * Charsets are [TextCharset]s, decoded by java.nio on Android (the parsers
 * still in :app turn them into the same java.nio.charset.Charset as before
 * with toJavaCharset) and by the common decoders on iOS.
 */
object TextEncoding {

    val WINDOWS_1251: TextCharset by lazy {
        charsetOrNull("windows-1251") ?: TextCharset.UTF_8
    }

    class Bom(val charset: TextCharset, val length: Int)

    private val xmlDeclEncoding =
        Regex("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']", RegexOption.IGNORE_CASE)
    private val metaCharset =
        Regex("<meta[^>]+?charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE)

    fun charsetOrNull(name: String?): TextCharset? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return TextCharset.forName(trimmed)
    }

    /** Byte-order mark at the start of [bytes] (only the first [length] bytes are looked at). */
    fun detectBom(bytes: ByteArray, length: Int = bytes.size): Bom? {
        fun at(i: Int): Int = if (i < length && i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Bom(TextCharset.UTF_8, 3)
            at(0) == 0xFE && at(1) == 0xFF -> Bom(TextCharset.UTF_16BE, 2)
            at(0) == 0xFF && at(1) == 0xFE -> Bom(TextCharset.UTF_16LE, 2)
            else -> null
        }
    }

    /**
     * Encoding announced by the document itself: the XML declaration, an HTML
     * `<meta charset>`, or the byte pattern of BOM-less UTF-16 `<?`.
     */
    fun declaredCharset(head: ByteArray, length: Int = head.size): TextCharset? {
        val n = minOf(length, head.size)
        if (n >= 4) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            if (b0 == 0x3C && b1 == 0 && b2 == 0x3F && b3 == 0) return TextCharset.UTF_16LE
            if (b0 == 0 && b1 == 0x3C && b2 == 0 && b3 == 0x3F) return TextCharset.UTF_16BE
        }
        val ascii = TextCharset.ISO_8859_1.decode(head, 0, minOf(n, HEAD_BYTES))
        xmlDeclEncoding.find(ascii)?.let { match ->
            charsetOrNull(match.groupValues[1])?.let { return it }
        }
        metaCharset.find(ascii)?.let { match ->
            charsetOrNull(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** Strict check: every byte belongs to a well-formed UTF-8 sequence. */
    fun isValidUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        val validator = Utf8Validator()
        validator.update(bytes, offset, length)
        return validator.isValid
    }

    /**
     * True when [bytes] should be read as UTF-8: valid, or valid apart from a
     * few stray bytes among many multi-byte characters. Windows-1251 text never
     * passes, because its Cyrillic letters do not form UTF-8 sequences.
     */
    fun looksLikeUtf8(bytes: ByteArray): Boolean {
        val validator = Utf8Validator()
        validator.update(bytes, 0, bytes.size)
        return validator.isMostlyValid
    }

    /**
     * Charset of a whole in-memory document: BOM, then the document's own
     * declaration, then UTF-8 when the bytes are (almost entirely) valid
     * UTF-8, else [fallback].
     */
    fun detect(bytes: ByteArray, fallback: TextCharset = WINDOWS_1251): TextCharset {
        detectBom(bytes)?.let { return it.charset }
        declaredCharset(bytes)?.let { return it }
        return if (looksLikeUtf8(bytes)) TextCharset.UTF_8 else fallback
    }

    /** Decodes [bytes] with [detect], dropping a byte-order mark. */
    fun decode(bytes: ByteArray, fallback: TextCharset = WINDOWS_1251): String {
        val bom = detectBom(bytes)
        if (bom != null) return bom.charset.decode(bytes, bom.length, bytes.size - bom.length)
        val text = detect(bytes, fallback).decode(bytes)
        return if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    /**
     * Streaming variant of [detect] for large files that can be opened twice:
     * looks at the head first and only scans the whole source when the
     * document does not declare its encoding. Android's parsers use the
     * InputStream variant (androidMain), which reads exactly as before.
     */
    fun detect(open: () -> Source, fallback: TextCharset = WINDOWS_1251): TextCharset {
        val head = open().buffer().use { readHead(it) }
        headCharset(head)?.let { return it }
        val validator = Utf8Validator()
        open().buffer().use { input ->
            val chunk = ByteArray(SCAN_CHUNK_BYTES)
            while (true) {
                val read = readChunk(input, chunk)
                if (read <= 0) break
                validator.update(chunk, 0, read)
                if (validator.isHopeless) break
            }
        }
        return if (validator.isMostlyValid) TextCharset.UTF_8 else fallback
    }

    /** What the first [HEAD_BYTES] of a stream say: a BOM or a declaration. */
    internal fun headCharset(head: ByteArray): TextCharset? {
        detectBom(head)?.let { bom ->
            // The generic UTF-16 decoder consumes the BOM itself.
            return if (bom.charset == TextCharset.UTF_8) TextCharset.UTF_8 else TextCharset.UTF_16
        }
        return declaredCharset(head)
    }

    private fun readHead(input: BufferedSource): ByteArray {
        input.request(HEAD_BYTES.toLong())
        val buffer: Buffer = input.buffer
        return buffer.readByteArray(minOf(buffer.size, HEAD_BYTES.toLong()))
    }

    /** Fills [chunk] as far as the source allows; -1 at the end. */
    private fun readChunk(input: BufferedSource, chunk: ByteArray): Int {
        var total = 0
        while (total < chunk.size) {
            val read = input.read(chunk, total, chunk.size - total)
            if (read < 0) break
            total += read
        }
        return if (total == 0) -1 else total
    }

    internal const val HEAD_BYTES = 4096
    internal const val SCAN_CHUNK_BYTES = 64 * 1024
}

/**
 * Incremental strict UTF-8 validator (rejects overlong forms and surrogates,
 * accepts 4-byte sequences). A sequence cut off by the end of the input is
 * tolerated, so a truncated file is still recognised as UTF-8.
 *
 * Invalid bytes are counted rather than ending the scan, so a UTF-8 text
 * with a few stray bytes (e.g. two files glued together) can still be told
 * apart from a legacy single-byte encoding ([isMostlyValid]).
 */
internal class Utf8Validator {
    private var need = 0
    private var lo = 0x80
    private var hi = 0xBF

    /** Bytes that do not fit UTF-8. */
    var errors: Long = 0L
        private set

    /** Completed multi-byte sequences (non-ASCII characters). */
    var sequences: Long = 0L
        private set

    val isValid: Boolean get() = errors == 0L

    /** Valid, or a few stray bytes among plenty of well-formed non-ASCII characters. */
    val isMostlyValid: Boolean
        get() = errors == 0L || (sequences >= MIN_SEQUENCES && errors * MAX_ERROR_RATIO <= sequences)

    /** True once the input clearly is not UTF-8, so a caller may stop scanning. */
    val isHopeless: Boolean
        get() = errors >= HOPELESS_ERRORS && errors * MAX_ERROR_RATIO > sequences

    fun update(bytes: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val x = bytes[i].toInt() and 0xFF
            if (need > 0) {
                if (x in lo..hi) {
                    lo = 0x80
                    hi = 0xBF
                    need--
                    if (need == 0) sequences++
                    i++
                    continue
                }
                // Broken sequence: count it and read this byte again as a lead byte.
                errors++
                need = 0
                lo = 0x80
                hi = 0xBF
            }
            when {
                x < 0x80 -> Unit
                x in 0xC2..0xDF -> start(1, 0x80, 0xBF)
                x == 0xE0 -> start(2, 0xA0, 0xBF)
                x in 0xE1..0xEC -> start(2, 0x80, 0xBF)
                x == 0xED -> start(2, 0x80, 0x9F)
                x in 0xEE..0xEF -> start(2, 0x80, 0xBF)
                x == 0xF0 -> start(3, 0x90, 0xBF)
                x in 0xF1..0xF3 -> start(3, 0x80, 0xBF)
                x == 0xF4 -> start(3, 0x80, 0x8F)
                else -> errors++
            }
            i++
        }
    }

    private fun start(continuation: Int, low: Int, high: Int) {
        need = continuation
        lo = low
        hi = high
    }

    private companion object {
        const val MIN_SEQUENCES = 32L
        const val MAX_ERROR_RATIO = 100L
        const val HOPELESS_ERRORS = 1024L
    }
}

/**
 * Base64 decoder fed with text chunks, so large embedded images never exist
 * as one giant string. Characters outside the alphabet (line breaks, spaces,
 * padding) are skipped. Decoding stops once more than [maxBytes] were produced.
 */
class Base64StreamDecoder(private val maxBytes: Int) {
    private val out = Buffer()
    private var buffer = 0
    private var bits = 0

    var overflow: Boolean = false
        private set

    fun feed(text: CharSequence) {
        if (overflow) return
        for (ch in text) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> -1
            }
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.writeByte((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
                if (out.size > maxBytes) {
                    overflow = true
                    return
                }
            }
        }
    }

    /** Decoded bytes, or null when nothing was decoded or the size cap was hit. */
    fun result(): ByteArray? = if (overflow || out.size == 0L) null else out.snapshot().toByteArray()
}
