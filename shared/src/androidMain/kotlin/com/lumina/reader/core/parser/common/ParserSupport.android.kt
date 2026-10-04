package com.lumina.reader.core.parser.common

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * [TextSupport.readCapped] for java.io streams, as the Android archive readers
 * have always used it: all bytes of [input], or null once there are more than [maxBytes].
 */
internal fun TextSupport.readCapped(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
