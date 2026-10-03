package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.parser.common.ParserLimits
import com.lumina.reader.core.parser.common.TextSupport
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Read access to the entries of an EPUB container, with size caps. */
internal interface EpubArchive : Closeable {
    /** Normalised names of all file entries, in archive order. */
    val entryNames: List<String>

    /** The archive's own spelling of [path] (exact, then case-insensitive), or null. */
    fun resolveName(path: String): String?

    /** Uncompressed size if known, else -1. */
    fun size(name: String): Long

    /** Entry bytes, or null if missing or larger than [maxBytes]. */
    fun read(path: String, maxBytes: Long): ByteArray?
}

internal object EpubPaths {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun normalizeEntryName(name: String): String = name.replace('\\', '/').trimStart('/')

    /** Folder of [path] with a trailing slash, or "" for the archive root. */
    fun dirOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) "" else path.substring(0, slash + 1)
    }

    fun isExternal(href: String): Boolean = scheme.containsMatchIn(href.trim())

    fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    /** Decodes %XX escapes as UTF-8 bytes; '+' is left alone. Malformed escapes stay literal. */
    fun percentDecode(text: String): String {
        if (text.indexOf('%') < 0) return text
        val bytes = ByteArrayOutputStream(text.length)
        val pending = StringBuilder()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                bytes.write(pending.toString().toByteArray(Charsets.UTF_8))
                pending.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    flushPending()
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            pending.append(c)
            i++
        }
        flushPending()
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Resolves [href] found in the document at [basePath] to an archive path
     * and an optional fragment. An empty path part means "the same document".
     */
    fun resolve(basePath: String, href: String): Pair<String, String?> {
        val trimmed = href.trim()
        val hash = trimmed.indexOf('#')
        val pathPart = if (hash >= 0) trimmed.substring(0, hash) else trimmed
        val fragment = if (hash >= 0) percentDecode(trimmed.substring(hash + 1)).takeIf { it.isNotEmpty() } else null
        val decoded = percentDecode(pathPart.substringBefore('?'))
        val path = when {
            decoded.isEmpty() -> basePath
            decoded.startsWith("/") -> normalize(decoded)
            else -> normalize(dirOf(basePath) + decoded)
        }
        return path to fragment
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
