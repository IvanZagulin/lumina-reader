package com.lumina.reader.core.zip

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.openZip

/**
 * Read access to the file entries of a zip archive.
 *
 * Android reads books with java.util.zip exactly as before (see the Android
 * actuals of the parsers); this is the portable reader the iPhone app uses,
 * built on Okio's zip file system. Compared with java.util.zip it decodes
 * entry names as UTF-8 only (a name in a legacy code page keeps replacement
 * characters), lists entries sorted by path rather than in archive order, keeps
 * the last of two entries with the same name, and has no streaming fallback for
 * archives whose central directory is broken.
 */
internal interface ZipArchive : AutoCloseable {
    /** Names of the file entries, "/"-separated and without a leading slash. */
    val names: List<String>

    /** Uncompressed size [name] declares, or -1 when unknown. */
    fun size(name: String): Long

    /** The uncompressed content of [name]; the caller closes the source. */
    fun open(name: String): Source
}

/** Opens [path] with Okio's zip file system; throws an IOException when it is not a readable zip. */
internal fun openOkioZipArchive(path: Path, fileSystem: FileSystem = FileSystem.SYSTEM): ZipArchive =
    OkioZipArchive(fileSystem.openZip(path))

private class OkioZipArchive(private val zip: FileSystem) : ZipArchive {
    private val paths = LinkedHashMap<String, Path>()
    private val sizes = HashMap<String, Long>()

    init {
        // Directories (explicit or implied by a file's path) have children; a
        // file has none, so an empty directory entry is listed like an empty
        // file. Sizes are read lazily: they need the entry's local header.
        for (path in zip.listRecursively(ROOT)) {
            if (zip.listOrNull(path)?.isNotEmpty() == true) continue
            val name = path.toString().removePrefix("/")
            if (name.isNotEmpty() && !paths.containsKey(name)) paths[name] = path
        }
    }

    override val names: List<String> get() = paths.keys.toList()

    override fun size(name: String): Long {
        val path = paths[name] ?: return -1L
        return sizes.getOrPut(name) {
            try {
                val metadata = zip.metadataOrNull(path)
                if (metadata == null || metadata.isDirectory) -1L else metadata.size ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
    }

    override fun open(name: String): Source {
        val path = paths[name] ?: throw okio.FileNotFoundException("no such entry: $name")
        return zip.source(path)
    }

    override fun close() {
        // Okio's zip file system opens the archive for each read; nothing stays open.
    }

    private companion object {
        val ROOT = "/".toPath()
    }
}
