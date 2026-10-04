package com.lumina.reader.core.repository

import okio.FileMetadata
import okio.FileSystem
import okio.Path.Companion.toPath

/** Parsed books cost the same memory on iPhone, which has less of it to spare. */
internal actual val PLATFORM_BOOK_CACHE_BYTES: Int = 32 * 1024 * 1024

/** Okio's file metadata; a missing or unreadable file reads as 0, like java.io.File. */
internal actual object BookFileStamps {
    actual fun absolutePath(path: String): String {
        val file = path.toPath()
        if (file.isAbsolute) return file.toString()
        return try {
            (FileSystem.SYSTEM.canonicalize(".".toPath()) / file).toString()
        } catch (e: Exception) {
            file.toString()
        }
    }

    actual fun lastModified(absolutePath: String): Long = metadata(absolutePath)?.lastModifiedAtMillis ?: 0L

    actual fun length(absolutePath: String): Long = metadata(absolutePath)?.takeIf { it.isRegularFile }?.size ?: 0L

    private fun metadata(path: String): FileMetadata? = try {
        FileSystem.SYSTEM.metadataOrNull(path.toPath())
    } catch (e: Exception) {
        null
    }
}
