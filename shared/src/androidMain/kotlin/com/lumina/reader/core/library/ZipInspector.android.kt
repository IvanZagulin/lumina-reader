package com.lumina.reader.core.library

import okio.Path
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

internal actual fun inspectZip(path: Path, maxEntries: Int): ZipSummary =
    JavaZipInspector.inspect(path.toFile(), maxEntries)

internal actual fun extractZipEntry(path: Path, entryName: String, dest: Path, maxBytes: Long): CopyResult =
    JavaZipInspector.extractEntry(path.toFile(), entryName, dest.toFile(), maxBytes)

/**
 * java.util.zip, exactly as ZipInspector read archives before the move to
 * common code: names that are not valid UTF-8 fall back to ISO-8859-1 (only
 * the extension matters to us), entries come in archive order.
 */
internal object JavaZipInspector {

    fun inspect(file: File, maxEntries: Int = ImportLimits.MAX_ZIP_ENTRIES): ZipSummary =
        readZip(file) { zip ->
            val names = ArrayList<String>()
            var declared = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                names += entry.name
                if (names.size > maxEntries) {
                    throw ImportException(ZipInspector.TOO_MANY_ENTRIES)
                }
                if (entry.size > 0) declared += entry.size
            }
            ZipSummary(names, declared)
        }

    fun extractEntry(file: File, entryName: String, dest: File, maxBytes: Long): CopyResult =
        readZip(file) { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw ImportException(ZipInspector.ENTRY_NOT_FOUND)
            zip.getInputStream(entry).use { input ->
                FileOutputStream(dest).use { output ->
                    StreamCopier.copy(
                        input = input,
                        output = output,
                        maxBytes = maxBytes,
                        tooLargeMessage = ZipInspector.ENTRY_TOO_LARGE
                    )
                }
            }
        }

    private fun <T> readZip(file: File, block: (ZipFile) -> T): T {
        return try {
            try {
                ZipFile(file, Charsets.UTF_8).use(block)
            } catch (e: IllegalArgumentException) {
                // Entry names written in a legacy code page are not valid UTF-8.
                ZipFile(file, Charsets.ISO_8859_1).use(block)
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(ZipInspector.UNREADABLE, e)
        }
    }
}
