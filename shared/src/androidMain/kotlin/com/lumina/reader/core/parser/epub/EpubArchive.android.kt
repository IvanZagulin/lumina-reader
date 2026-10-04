package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.parser.common.ParserLimits
import com.lumina.reader.core.parser.common.TextSupport
import com.lumina.reader.core.parser.common.readCapped
import okio.Path
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// The Android EPUB container code, unchanged from :app: java.util.zip reads
// every book exactly as it did before the parsers moved to common code.

internal actual fun openEpubArchive(path: Path): EpubArchive {
    val file = path.toFile()
    return try {
        ZipFileArchive(file)
    } catch (e: Exception) {
        FileInputStream(file).use { StreamedArchive(it) }
    }
}

/** Lazy access through [ZipFile]: only the entries that are asked for are inflated. */
internal class ZipFileArchive(file: File) : EpubArchive {
    private val zip = ZipFile(file)
    private val entries = LinkedHashMap<String, ZipEntry>()
    private val lowerIndex = HashMap<String, String>()

    init {
        try {
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                val name = EpubPaths.normalizeEntryName(entry.name)
                if (!entries.containsKey(name)) {
                    entries[name] = entry
                    lowerIndex.putIfAbsent(name.lowercase(), name)
                }
            }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    override val entryNames: List<String> get() = entries.keys.toList()

    override fun resolveName(path: String): String? =
        if (entries.containsKey(path)) path else lowerIndex[path.lowercase()]

    override fun size(name: String): Long = entries[name]?.size ?: -1L

    override fun read(path: String, maxBytes: Long): ByteArray? {
        val name = resolveName(path) ?: return null
        val entry = entries[name] ?: return null
        if (entry.size > maxBytes) return null
        return try {
            zip.getInputStream(entry).use { TextSupport.readCapped(it, maxBytes) }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        zip.close()
    }
}

/**
 * Fallback for archives [ZipFile] refuses (e.g. odd entry names): reads the
 * stream once, keeping only the entries a reader can use, each with a size cap.
 */
internal class StreamedArchive(input: InputStream) : EpubArchive {
    private val data = LinkedHashMap<String, ByteArray>()
    private val lowerIndex = HashMap<String, String>()

    init {
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = EpubPaths.normalizeEntryName(entry.name)
                    val cap = capFor(name)
                    if (cap <= 0 || total >= MAX_TOTAL) continue
                    val bytes = TextSupport.readCapped(zip, cap.toLong()) ?: continue
                    total += bytes.size
                    if (!data.containsKey(name)) {
                        data[name] = bytes
                        lowerIndex.putIfAbsent(name.lowercase(), name)
                    }
                }
            }
        } catch (e: Exception) {
            // Keep whatever was read before the damaged part.
        }
    }

    private fun capFor(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xhtml", "html", "htm", "xml", "opf", "ncx", "svg" -> ParserLimits.MAX_DOCUMENT_BYTES
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> ParserLimits.MAX_IMAGE_BYTES
            else -> 0
        }
    }

    override val entryNames: List<String> get() = data.keys.toList()

    override fun resolveName(path: String): String? =
        if (data.containsKey(path)) path else lowerIndex[path.lowercase()]

    override fun size(name: String): Long = data[name]?.size?.toLong() ?: -1L

    override fun read(path: String, maxBytes: Long): ByteArray? {
        val name = resolveName(path) ?: return null
        val bytes = data[name] ?: return null
        return if (bytes.size > maxBytes) null else bytes
    }

    override fun close() {
        data.clear()
    }

    private companion object {
        const val MAX_TOTAL = 256L * 1024 * 1024
    }
}
