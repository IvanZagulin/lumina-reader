package com.lumina.reader.core.library

import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.SYSTEM
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use

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

/**
 * Copies book content with a size cap while hashing it, and reads files for
 * format detection and duplicate checks.
 *
 * Android's actual also keeps the java.io overloads the app has always used
 * (`copy(InputStream, OutputStream, …)`, `sha256(File)`, `readHeader(File)`),
 * and answers the [Path] calls with them; iOS uses [PortableStreams].
 */
expect object StreamCopier {
    /**
     * Copies [source] to [sink] while hashing the content. Stops with an
     * [ImportException] carrying [tooLargeMessage] as soon as more than
     * [maxBytes] arrive, so an endless or oversized stream never fills the disk.
     * [beforeChunk] runs before every read (used for cancellation checks) and
     * [onProgress] after every written chunk with the running total. Neither
     * stream is closed; [sink] is flushed.
     */
    fun copy(
        source: Source,
        sink: Sink,
        maxBytes: Long,
        tooLargeMessage: String = ImportLimits.TOO_LARGE_MESSAGE,
        beforeChunk: (() -> Unit)? = null,
        onProgress: ((Long) -> Unit)? = null
    ): CopyResult

    /** SHA-256 of a whole file, or null when it cannot be read. */
    fun sha256(path: Path): String?

    /** Up to [count] leading bytes of the file at [path] (fewer when the file is shorter). */
    fun readHeader(path: Path, count: Int = ImportLimits.HEADER_BYTES): ByteArray
}

/** [StreamCopier] on Okio, for platforms without java.io. */
internal object PortableStreams {
    private const val BUFFER_SIZE = 64L * 1024

    fun copy(
        source: Source,
        sink: Sink,
        maxBytes: Long,
        tooLargeMessage: String,
        beforeChunk: (() -> Unit)?,
        onProgress: ((Long) -> Unit)?
    ): CopyResult {
        // The hashing sink passes every byte on to [sink].
        val hashing = HashingSink.sha256(sink)
        val buffer = Buffer()
        var total = 0L
        while (true) {
            beforeChunk?.invoke()
            val read = source.read(buffer, BUFFER_SIZE)
            if (read < 0L) break
            if (read == 0L) continue
            total += read
            if (total > maxBytes) throw ImportException(tooLargeMessage)
            hashing.write(buffer, read)
            onProgress?.invoke(total)
        }
        hashing.flush()
        return CopyResult(total, hashing.hash.hex())
    }

    fun sha256(path: Path, fileSystem: FileSystem = FileSystem.SYSTEM): String? = try {
        fileSystem.source(path).use { source ->
            copy(source, blackholeSink(), Long.MAX_VALUE, ImportLimits.TOO_LARGE_MESSAGE, null, null).sha256
        }
    } catch (e: Exception) {
        null
    }

    fun readHeader(path: Path, count: Int, fileSystem: FileSystem = FileSystem.SYSTEM): ByteArray =
        fileSystem.source(path).buffer().use { source ->
            val header = Buffer()
            while (header.size < count) {
                if (source.read(header, count - header.size) < 0L) break
            }
            header.readByteArray()
        }
}

/** `Throwable.localizedMessage` on Android (as the import messages always used), `message` elsewhere. */
internal expect fun localizedMessageOf(error: Throwable): String?

/** True for an out-of-memory error the import reports instead of crashing (Android's OutOfMemoryError). */
internal expect fun isOutOfMemory(error: Throwable): Boolean
