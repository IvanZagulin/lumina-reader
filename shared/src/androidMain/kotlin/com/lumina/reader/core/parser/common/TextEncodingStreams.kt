package com.lumina.reader.core.parser.common

import com.lumina.reader.core.text.charset.TextCharset
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * [TextEncoding.detect] for java.io streams, read exactly as the Android
 * parsers did before the move to common code (a 4 KB head, then 64 KB reads
 * until the end or until the text clearly is not UTF-8).
 */
fun TextEncoding.detectStream(
    open: () -> InputStream,
    fallback: TextCharset = TextEncoding.WINDOWS_1251
): TextCharset {
    val head = open().use { readHead(it, TextEncoding.HEAD_BYTES) }
    TextEncoding.headCharset(head)?.let { return it }
    val validator = Utf8Validator()
    open().use { input ->
        val buffer = ByteArray(TextEncoding.SCAN_CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            validator.update(buffer, 0, read)
            if (validator.isHopeless) break
        }
    }
    return if (validator.isMostlyValid) TextCharset.UTF_8 else fallback
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
