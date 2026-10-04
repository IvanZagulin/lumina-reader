package com.lumina.reader.core.library

import okio.Path

/** What the central directory of an archive says about its content. */
data class ZipSummary(
    val entryNames: List<String>,
    /** Sum of the uncompressed sizes the entries declare (unknown sizes count as 0). */
    val declaredUncompressedBytes: Long
)

/**
 * Reads archives defensively: entry count is capped and an unpacked entry is
 * cut off at a byte limit, so a zip bomb cannot fill the storage. Any other
 * failure becomes "Архив повреждён или не читается".
 *
 * Android reads with java.util.zip exactly as before (names that are not valid
 * UTF-8 fall back to ISO-8859-1; entries in archive order). iOS reads with
 * Okio's zip file system: names are UTF-8 only and listed sorted by path,
 * which only matters when an archive holds two FB2 files.
 */
object ZipInspector {

    fun inspect(path: Path, maxEntries: Int = ImportLimits.MAX_ZIP_ENTRIES): ZipSummary =
        inspectZip(path, maxEntries)

    /**
     * Unpacks [entryName] from the archive at [path] into [dest] and returns
     * its size and SHA-256. Fails with [ImportException] when more than
     * [maxBytes] would be written.
     */
    fun extractEntry(path: Path, entryName: String, dest: Path, maxBytes: Long): CopyResult =
        extractZipEntry(path, entryName, dest, maxBytes)

    internal const val TOO_MANY_ENTRIES = "В архиве слишком много файлов"
    internal const val ENTRY_NOT_FOUND = "В архиве не найден файл книги"
    internal const val ENTRY_TOO_LARGE = "Книга в архиве слишком большая после распаковки"
    internal const val UNREADABLE = "Архив повреждён или не читается"
}

/** [ZipInspector.inspect] on this platform. */
internal expect fun inspectZip(path: Path, maxEntries: Int): ZipSummary

/** [ZipInspector.extractEntry] on this platform. */
internal expect fun extractZipEntry(path: Path, entryName: String, dest: Path, maxBytes: Long): CopyResult
