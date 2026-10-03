@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.lumina.reader.core.text.charset

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.*
import platform.Foundation.*

/**
 * iOS: the common decoders of [CharsetRegistry] (the encodings books and
 * feeds use: UTF-8/16, windows-125x, KOI8, CP866, ISO-8859, Mac Cyrillic),
 * then any other IANA name CoreFoundation knows, decoded by Foundation.
 */
actual object PlatformCharsets {

    actual fun canonicalName(name: String): String? {
        CharsetRegistry.canonicalName(name)?.let { return it }
        // Foundation-only charsets keep the name they were asked for.
        return if (foundationEncoding(name) != null) name else null
    }

    actual fun decoderFor(name: String): ByteToCharDecoder? {
        CharsetRegistry.decoderFor(name)?.let { return it }
        val encoding = foundationEncoding(name) ?: return null
        return FoundationDecoder(encoding)
    }

    actual fun decodeWhole(bytes: ByteArray, offset: Int, length: Int, name: String): String? {
        CharsetRegistry.decoderFor(name)?.let { return it.decodeAll(bytes, offset, length) }
        val encoding = foundationEncoding(name) ?: return null
        return decodeWithFoundation(bytes, offset, length, encoding)
    }
}

/** The NSStringEncoding of an IANA charset name, or null when CoreFoundation does not know it. */
private fun foundationEncoding(name: String): ULong? {
    if (name.isEmpty() || name.any { it.code !in 0x21..0x7E }) return null
    val cfName = CFStringCreateWithCString(null, name, kCFStringEncodingASCII) ?: return null
    try {
        val encoding = CFStringConvertIANACharSetNameToEncoding(cfName)
        // kCFStringEncodingInvalidId is 0xFFFFFFFF.
        if (encoding == UInt.MAX_VALUE) return null
        return CFStringConvertEncodingToNSStringEncoding(encoding)
    } finally {
        CFRelease(cfName)
    }
}

/**
 * Foundation has no replacing decoder, so text in such a charset is decoded
 * whole; when the bytes are not valid in it, the text is read as UTF-8 with
 * replacement characters rather than lost.
 */
private fun decodeWithFoundation(bytes: ByteArray, offset: Int, length: Int, encoding: ULong): String {
    if (length == 0) return ""
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(offset), length = length.toULong())
    }
    return NSString.create(data = data, encoding = encoding)?.toString()
        ?: Utf8Decoder().decodeAll(bytes, offset, length)
}

/** Collects the bytes and decodes them at [flush] (Foundation cannot decode in pieces). */
private class FoundationDecoder(private val encoding: ULong) : ByteToCharDecoder {
    private var buffer = ByteArray(0)
    private var size = 0

    override fun decode(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        if (size + length > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length, 64 * 1024))
        }
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    override fun flush(out: StringBuilder) {
        out.append(decodeWithFoundation(buffer, 0, size, encoding))
        buffer = ByteArray(0)
        size = 0
    }
}
