package com.lumina.reader.core.library

import com.lumina.reader.core.zip.ZipArchive
import com.lumina.reader.core.zip.openOkioZipArchive
import okio.FileSystem
import okio.Path
import okio.use

// Okio's zip file system (see ZipArchive): UTF-8 names, sorted by path.

internal actual fun inspectZip(path: Path, maxEntries: Int): ZipSummary =
    readZip(path) { zip ->
        val names = zip.names
        if (names.size > maxEntries) throw ImportException(ZipInspector.TOO_MANY_ENTRIES)
        var declared = 0L
        for (name in names) {
            val size = zip.size(name)
            if (size > 0) declared += size
        }
        ZipSummary(names, declared)
    }

internal actual fun extractZipEntry(path: Path, entryName: String, dest: Path, maxBytes: Long): CopyResult =
    readZip(path) { zip ->
        if (entryName !in zip.names) throw ImportException(ZipInspector.ENTRY_NOT_FOUND)
        zip.open(entryName).use { source ->
            FileSystem.SYSTEM.sink(dest).use { sink ->
                StreamCopier.copy(
                    source = source,
                    sink = sink,
                    maxBytes = maxBytes,
                    tooLargeMessage = ZipInspector.ENTRY_TOO_LARGE
                )
            }
        }
    }

private inline fun <T> readZip(path: Path, block: (ZipArchive) -> T): T =
    try {
        openOkioZipArchive(path).use(block)
    } catch (e: ImportException) {
        throw e
    } catch (e: Exception) {
        throw ImportException(ZipInspector.UNREADABLE, e)
    }
