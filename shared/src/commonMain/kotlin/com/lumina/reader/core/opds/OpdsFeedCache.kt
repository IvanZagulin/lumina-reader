package com.lumina.reader.core.opds

import com.lumina.reader.platform.AppClock
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A day's memory of the OPDS feeds the user has opened.
 *
 * Opening a catalogue meant waiting for the site every time, and a site that is
 * slow (or blocked, so the request only ends on a timeout) made the whole screen
 * slow. A feed fetched less than [BROWSE_MAX_AGE_MILLIS] ago is read from disk
 * instead; searches are kept for [SEARCH_MAX_AGE_MILLIS], since their answers change
 * more often. When the site cannot be reached at all, the last copy is shown however
 * old it is — a catalogue from yesterday beats an error.
 *
 * The files live in the system temporary directory: it is a cache, the system may
 * clear it, and the next open simply fetches again.
 */
class OpdsFeedCache(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "lumina-opds-cache",
    private val now: () -> Long = AppClock::nowMillis
) {
    /** A cached feed: where it finally came from (relative links are resolved against it) and its bytes. */
    class Entry(val finalUrl: String, val body: ByteArray, val ageMillis: Long)

    /** The copy of [url], if there is one younger than [maxAgeMillis]. */
    fun read(url: String, maxAgeMillis: Long = Long.MAX_VALUE): Entry? = try {
        val path = pathOf(url)
        val modified = fileSystem.metadataOrNull(path)?.lastModifiedAtMillis
        if (modified == null) {
            null
        } else {
            val age = (now() - modified).coerceAtLeast(0L)
            if (age > maxAgeMillis) {
                null
            } else {
                fileSystem.read(path) {
                    val finalUrl = readUtf8LineStrict()
                    Entry(finalUrl, readByteArray(), age)
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    fun write(url: String, finalUrl: String, body: ByteArray) {
        if (body.size > MAX_ENTRY_BYTES || '\n' in finalUrl) return
        try {
            fileSystem.createDirectories(directory)
            val path = pathOf(url)
            val temp = path.parent!! / (path.name + ".tmp")
            fileSystem.write(temp) {
                writeUtf8(finalUrl)
                writeUtf8("\n")
                write(body)
            }
            fileSystem.atomicMove(temp, path)
            prune()
        } catch (e: Exception) {
            // A cache that cannot be written is just a cache that is empty.
        }
    }

    private fun prune() {
        val files = fileSystem.listOrNull(directory)?.filter { it.name.endsWith(".feed") } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { fileSystem.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
            .take(files.size - MAX_ENTRIES + PRUNE_SLACK)
            .forEach { fileSystem.delete(it, mustExist = false) }
    }

    private fun pathOf(url: String): Path = directory / "${fnv1a(url)}.feed"

    companion object {
        const val BROWSE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val SEARCH_MAX_AGE_MILLIS = 60L * 60 * 1000
        private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_ENTRIES = 300
        private const val PRUNE_SLACK = 40

        /** One cache for the process. */
        val shared: OpdsFeedCache by lazy { OpdsFeedCache() }

        /** Search answers change more often than the shelves of a catalogue. */
        fun maxAgeFor(url: String): Long {
            val lower = url.lowercase()
            val isSearch = "search" in lower || "searchterm=" in lower || "q=" in lower
            return if (isSearch) SEARCH_MAX_AGE_MILLIS else BROWSE_MAX_AGE_MILLIS
        }

        /** FNV-1a, 64 bit: a file name for a URL, the same on every platform. */
        internal fun fnv1a(text: String): String {
            var hash = 0xcbf29ce484222325uL
            for (byte in text.encodeToByteArray()) {
                hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
            }
            return hash.toString(16).padStart(16, '0')
        }
    }
}
