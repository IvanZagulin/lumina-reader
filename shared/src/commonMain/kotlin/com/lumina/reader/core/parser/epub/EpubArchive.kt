package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.parser.common.TextSupport
import com.lumina.reader.core.text.charset.TextCharset
import com.lumina.reader.core.zip.ZipArchive
import com.lumina.reader.core.zip.openOkioZipArchive
import okio.Buffer
import okio.Path
import okio.use

/** Read access to the entries of an EPUB container, with size caps. */
internal interface EpubArchive : AutoCloseable {
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
        val bytes = Buffer()
        val pending = StringBuilder()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                // String.toByteArray(Charsets.UTF_8) on the JVM.
                bytes.write(pending.toString().encodeToByteArray())
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
                    bytes.writeByte(value)
                    i += 3
                    continue
                }
            }
            pending.append(c)
            i++
        }
        flushPending()
        // String(bytes, Charsets.UTF_8) on Android; the common UTF-8 decoder
        // (the same replacement of malformed bytes) on iOS.
        return TextCharset.UTF_8.decode(bytes.readByteArray())
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

/**
 * Opens the EPUB at [path]. Android: java.util.zip's ZipFile with the capped
 * one-pass ZipInputStream fallback for archives ZipFile refuses, exactly as
 * before; iOS: [PortableEpubArchive.open].
 */
internal expect fun openEpubArchive(path: Path): EpubArchive

/**
 * Lazy access through a [ZipArchive]: only the entries that are asked for are
 * inflated, each with a size cap. The bookkeeping matches Android's
 * ZipFile-based archive: names are normalised, the first spelling of a name
 * wins, and lookups fall back to a case-insensitive match.
 */
internal class PortableEpubArchive(private val zip: ZipArchive) : EpubArchive {
    /** Normalised name -> the zip's own name. */
    private val entries = LinkedHashMap<String, String>()
    private val lowerIndex = HashMap<String, String>()

    init {
        for (raw in zip.names) {
            val name = EpubPaths.normalizeEntryName(raw)
            if (!entries.containsKey(name)) {
                entries[name] = raw
                val lower = name.lowercase()
                if (!lowerIndex.containsKey(lower)) lowerIndex[lower] = name
            }
        }
    }

    override val entryNames: List<String> get() = entries.keys.toList()

    override fun resolveName(path: String): String? =
        if (entries.containsKey(path)) path else lowerIndex[path.lowercase()]

    override fun size(name: String): Long = entries[name]?.let { zip.size(it) } ?: -1L

    override fun read(path: String, maxBytes: Long): ByteArray? {
        val name = resolveName(path) ?: return null
        val raw = entries[name] ?: return null
        if (zip.size(raw) > maxBytes) return null
        return try {
            zip.open(raw).use { TextSupport.readCapped(it, maxBytes) }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        zip.close()
    }

    companion object {
        /**
         * [path] through Okio's zip reader. An archive it cannot read becomes an
         * empty one (the parser then reports that no text could be extracted);
         * Android would try a one-pass stream read here, which Okio does not offer.
         */
        fun open(path: Path): EpubArchive = try {
            PortableEpubArchive(openOkioZipArchive(path))
        } catch (e: Exception) {
            EmptyEpubArchive
        }
    }
}

/** An archive without entries. */
internal object EmptyEpubArchive : EpubArchive {
    override val entryNames: List<String> get() = emptyList()

    override fun resolveName(path: String): String? = null

    override fun size(name: String): Long = -1L

    override fun read(path: String, maxBytes: Long): ByteArray? = null

    override fun close() = Unit
}
