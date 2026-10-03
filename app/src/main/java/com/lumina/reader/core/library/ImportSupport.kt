package com.lumina.reader.core.library

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Hard limits that keep a single import from exhausting storage or memory. */
object ImportLimits {
    /** Largest book file the library accepts (downloaded or copied). */
    const val MAX_BOOK_BYTES: Long = 300L * 1024 * 1024

    /** Largest FB2 text unpacked from an `.fb2.zip` archive. */
    const val MAX_FB2_UNPACKED_BYTES: Long = 200L * 1024 * 1024

    /** Upper bound for the uncompressed size an EPUB archive declares. */
    const val MAX_EPUB_DECLARED_BYTES: Long = 1024L * 1024 * 1024

    /** Archives with more entries than this are rejected (zip bomb guard). */
    const val MAX_ZIP_ENTRIES = 10_000

    /** How many leading bytes are inspected to recognise a file format. */
    const val HEADER_BYTES = 8 * 1024

    const val TOO_LARGE_MESSAGE = "Файл слишком большой: можно добавить книгу до 300 МБ"
}

/** Thrown when an import cannot continue; [userMessage] is shown to the user as is. */
class ImportException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/** Number of bytes copied and their SHA-256 (lower-case hex). */
data class CopyResult(val bytes: Long, val sha256: String)

object StreamCopier {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Copies [input] to [output] while hashing the content. Stops with an
     * [ImportException] carrying [tooLargeMessage] as soon as more than
     * [maxBytes] arrive, so an endless or oversized stream never fills the disk.
     * [beforeChunk] runs before every read (used for cancellation checks) and
     * [onProgress] after every written chunk with the running total.
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        tooLargeMessage: String = ImportLimits.TOO_LARGE_MESSAGE,
        beforeChunk: (() -> Unit)? = null,
        onProgress: ((Long) -> Unit)? = null
    ): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            beforeChunk?.invoke()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw ImportException(tooLargeMessage)
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            onProgress?.invoke(total)
        }
        output.flush()
        return CopyResult(total, digest.digest().toHexString())
    }

    /** SHA-256 of a whole file, or null when it cannot be read. */
    fun sha256(file: File): String? = try {
        file.inputStream().use { input ->
            copy(input, DiscardingOutputStream, Long.MAX_VALUE).sha256
        }
    } catch (e: Exception) {
        null
    }

    /** Up to [count] leading bytes of [file] (fewer when the file is shorter). */
    fun readHeader(file: File, count: Int = ImportLimits.HEADER_BYTES): ByteArray {
        file.inputStream().use { input ->
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = input.read(buffer, filled, count - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == count) buffer else buffer.copyOf(filled)
        }
    }
}

internal fun ByteArray.toHexString(): String {
    val chars = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = digits[value ushr 4]
        chars[i * 2 + 1] = digits[value and 0x0F]
    }
    return String(chars)
}

/** `OutputStream.nullOutputStream()` needs API 33; this works on every version. */
private object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
