package com.lumina.reader.core.library

import okio.Path
import okio.Sink
import okio.Source

actual object StreamCopier {
    actual fun copy(
        source: Source,
        sink: Sink,
        maxBytes: Long,
        tooLargeMessage: String,
        beforeChunk: (() -> Unit)?,
        onProgress: ((Long) -> Unit)?
    ): CopyResult = PortableStreams.copy(source, sink, maxBytes, tooLargeMessage, beforeChunk, onProgress)

    actual fun sha256(path: Path): String? = PortableStreams.sha256(path)

    actual fun readHeader(path: Path, count: Int): ByteArray = PortableStreams.readHeader(path, count)
}

internal actual fun localizedMessageOf(error: Throwable): String? = error.message

/** Kotlin/Native cannot recover from running out of memory, so there is nothing to catch. */
internal actual fun isOutOfMemory(error: Throwable): Boolean = false
