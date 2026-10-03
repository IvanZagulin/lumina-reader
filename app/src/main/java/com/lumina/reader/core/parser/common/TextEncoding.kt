package com.lumina.reader.core.parser.common

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/** Character-set detection shared by the text based parsers. */
internal object TextEncoding {

    val WINDOWS_1251: Charset by lazy {
        charsetOrNull("windows-1251") ?: Charsets.UTF_8
    }

    class Bom(val charset: Charset, val length: Int)

    private val xmlDeclEncoding =
        Regex("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']", RegexOption.IGNORE_CASE)
    private val metaCharset =
        Regex("<meta[^>]+?charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE)

    fun charsetOrNull(name: String?): Charset? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            Charset.forName(trimmed)
        } catch (e: Exception) {
            null
        }
    }

    /** Byte-order mark at the start of [bytes] (only the first [length] bytes are looked at). */
    fun detectBom(bytes: ByteArray, length: Int = bytes.size): Bom? {
        fun at(i: Int): Int = if (i < length && i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Bom(Charsets.UTF_8, 3)
            at(0) == 0xFE && at(1) == 0xFF -> Bom(Charsets.UTF_16BE, 2)
            at(0) == 0xFF && at(1) == 0xFE -> Bom(Charsets.UTF_16LE, 2)
            else -> null
        }
    }

    /**
     * Encoding announced by the document itself: the XML declaration, an HTML
     * `<meta charset>`, or the byte pattern of BOM-less UTF-16 `<?`.
     */
    fun declaredCharset(head: ByteArray, length: Int = head.size): Charset? {
        val n = minOf(length, head.size)
        if (n >= 4) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            if (b0 == 0x3C && b1 == 0 && b2 == 0x3F && b3 == 0) return Charsets.UTF_16LE
            if (b0 == 0 && b1 == 0x3C && b2 == 0 && b3 == 0x3F) return Charsets.UTF_16BE
        }
        val ascii = String(head, 0, minOf(n, 4096), Charsets.ISO_8859_1)
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
    fun detect(bytes: ByteArray, fallback: Charset = WINDOWS_1251): Charset {
        detectBom(bytes)?.let { return it.charset }
        declaredCharset(bytes)?.let { return it }
        return if (looksLikeUtf8(bytes)) Charsets.UTF_8 else fallback
    }

    /** Decodes [bytes] with [detect], dropping a byte-order mark. */
    fun decode(bytes: ByteArray, fallback: Charset = WINDOWS_1251): String {
        val bom = detectBom(bytes)
        if (bom != null) return String(bytes, bom.length, bytes.size - bom.length, bom.charset)
        val text = String(bytes, detect(bytes, fallback))
        return if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    /**
     * Streaming variant of [detect] for large files that can be opened twice:
     * looks at the head first and only scans the whole stream when the
     * document does not declare its encoding.
     */
    fun detect(open: () -> InputStream, fallback: Charset = WINDOWS_1251): Charset {
        val head = open().use { readHead(it, 4096) }
        detectBom(head)?.let { bom ->
            // The generic UTF-16 decoder consumes the BOM itself.
            return if (bom.charset == Charsets.UTF_8) Charsets.UTF_8 else Charsets.UTF_16
        }
        declaredCharset(head)?.let { return it }
        val validator = Utf8Validator()
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                validator.update(buffer, 0, read)
                if (validator.isHopeless) break
            }
        }
        return if (validator.isMostlyValid) Charsets.UTF_8 else fallback
    }

    private fun readHead(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(max)
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val read = input.read(buffer, 0, max - total)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
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
internal class Base64StreamDecoder(private val maxBytes: Int) {
    private val out = ByteArrayOutputStream()
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
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
                if (out.size() > maxBytes) {
                    overflow = true
                    return
                }
            }
        }
    }

    /** Decoded bytes, or null when nothing was decoded or the size cap was hit. */
    fun result(): ByteArray? = if (overflow || out.size() == 0) null else out.toByteArray()
}
