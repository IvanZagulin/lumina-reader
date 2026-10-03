package com.lumina.reader.core.library

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** What the central directory of an archive says about its content. */
data class ZipSummary(
    val entryNames: List<String>,
    /** Sum of the uncompressed sizes the entries declare (unknown sizes count as 0). */
    val declaredUncompressedBytes: Long
)

/**
 * Reads archives defensively: entry count is capped, names that are not valid
 * UTF-8 fall back to ISO-8859-1 (only the extension matters to us) and an
 * unpacked entry is cut off at a byte limit, so a zip bomb cannot fill the
 * storage.
 */
object ZipInspector {

    fun inspect(file: File, maxEntries: Int = ImportLimits.MAX_ZIP_ENTRIES): ZipSummary =
        readZip(file) { zip ->
            val names = ArrayList<String>()
            var declared = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                names += entry.name
                if (names.size > maxEntries) {
                    throw ImportException("В архиве слишком много файлов")
                }
                if (entry.size > 0) declared += entry.size
            }
            ZipSummary(names, declared)
        }

    /**
     * Unpacks [entryName] from [file] into [dest] and returns its size and
     * SHA-256. Fails with [ImportException] when more than [maxBytes] would be
     * written.
     */
    fun extractEntry(file: File, entryName: String, dest: File, maxBytes: Long): CopyResult =
        readZip(file) { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw ImportException("В архиве не найден файл книги")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(dest).use { output ->
                    StreamCopier.copy(
                        input = input,
                        output = output,
                        maxBytes = maxBytes,
                        tooLargeMessage = "Книга в архиве слишком большая после распаковки"
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
            throw ImportException("Архив повреждён или не читается", e)
        }
    }
}
