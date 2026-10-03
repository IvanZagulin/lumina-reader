package com.lumina.reader.core.text.charset

/**
 * A character set the platform can decode, identified by its canonical name
 * ("UTF-8", "windows-1251"). Common code uses it where JVM code used
 * java.nio.charset.Charset; two instances are equal when their names are, as
 * Charset.equals does.
 *
 * Decoding goes through [PlatformCharsets]: java.nio on Android, so Android
 * produces exactly the text it did before; the common decoders of
 * [CharsetRegistry] (plus a Foundation fallback) on iOS.
 */
class TextCharset private constructor(val name: String) {

    /** Like `String(bytes, offset, length, charset)`: malformed input becomes U+FFFD. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        PlatformCharsets.decodeWhole(bytes, offset, length, name)
            ?: throw IllegalStateException("Charset $name is not supported")

    /** A fresh streaming decoder for this charset. */
    fun newDecoder(): ByteToCharDecoder =
        PlatformCharsets.decoderFor(name) ?: throw IllegalStateException("Charset $name is not supported")

    override fun equals(other: Any?): Boolean = other is TextCharset && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        val UTF_8: TextCharset = TextCharset("UTF-8")

        /** Byte-order-mark aware UTF-16 (big-endian without a mark), like Charsets.UTF_16. */
        val UTF_16: TextCharset = TextCharset("UTF-16")
        val UTF_16BE: TextCharset = TextCharset("UTF-16BE")
        val UTF_16LE: TextCharset = TextCharset("UTF-16LE")
        val ISO_8859_1: TextCharset = TextCharset("ISO-8859-1")
        val US_ASCII: TextCharset = TextCharset("US-ASCII")

        /**
         * The charset called [name] (canonical name or alias, any case), or null
         * when the platform does not support it; like `Charset.forName` without
         * the exceptions. The name is not trimmed.
         */
        fun forName(name: String): TextCharset? =
            PlatformCharsets.canonicalName(name)?.let { canonical ->
                when (canonical) {
                    UTF_8.name -> UTF_8
                    UTF_16.name -> UTF_16
                    UTF_16BE.name -> UTF_16BE
                    UTF_16LE.name -> UTF_16LE
                    ISO_8859_1.name -> ISO_8859_1
                    US_ASCII.name -> US_ASCII
                    else -> TextCharset(canonical)
                }
            }
    }
}

/**
 * Charset support of the platform. Android: java.nio (`Charset.forName`,
 * `String(bytes, charset)`, CharsetDecoder with REPLACE), unchanged from the
 * JVM code. iOS: [CharsetRegistry], then CoreFoundation's IANA names.
 */
expect object PlatformCharsets {
    /** Canonical name of the charset called [name], or null when unsupported. */
    fun canonicalName(name: String): String?

    /** A fresh streaming decoder that replaces malformed input with U+FFFD, or null when unsupported. */
    fun decoderFor(name: String): ByteToCharDecoder?

    /** `bytes[offset until offset + length]` decoded in one go, or null when unsupported. */
    fun decodeWhole(bytes: ByteArray, offset: Int, length: Int, name: String): String?
}
