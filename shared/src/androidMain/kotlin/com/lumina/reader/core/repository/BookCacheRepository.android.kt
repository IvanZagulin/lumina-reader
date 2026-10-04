package com.lumina.reader.core.repository

import java.io.File

internal actual val PLATFORM_BOOK_CACHE_BYTES: Int = 48 * 1024 * 1024

/** java.io.File, as the cache has always checked book files on Android. */
internal actual object BookFileStamps {
    actual fun absolutePath(path: String): String = File(path).absolutePath

    actual fun lastModified(absolutePath: String): Long = File(absolutePath).lastModified()

    actual fun length(absolutePath: String): Long = File(absolutePath).length()
}
