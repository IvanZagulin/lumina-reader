package com.lumina.reader.core.repository

import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.platform.PlatformLock

/**
 * Recently parsed books kept in memory so reopening a book is instant.
 *
 * An entry is only returned while it still describes the file on disk: it is
 * keyed by the absolute path and validated against the file's modification
 * time, its length and [PARSER_CACHE_VERSION]. Bump the version whenever the
 * parsers change their output so books parsed by an older app version are
 * parsed again. The cache is bounded by an estimate of the memory the parsed
 * books take, not by their number.
 */
object BookCacheRepository {
    /** Increase whenever parser output changes in a way the reader depends on. */
    const val PARSER_CACHE_VERSION = 2

    /** Upper bound for the estimated size of all cached books (48 MB on Android, less on the iPhone). */
    val MAX_CACHE_BYTES: Int = PLATFORM_BOOK_CACHE_BYTES

    private class Entry(
        val lastModified: Long,
        val length: Long,
        val parserVersion: Int,
        val sizeBytes: Int,
        val book: ParsedBook
    )

    private val lock = PlatformLock()
    private val cache = LruMap<String, Entry>(MAX_CACHE_BYTES) { it.sizeBytes }

    fun get(filePath: String): ParsedBook? {
        val key = BookFileStamps.absolutePath(filePath)
        val entry = lock.withLock { cache[key] } ?: return null
        if (!entry.matches(key)) {
            lock.withLock { cache.remove(key) }
            return null
        }
        return entry.book
    }

    fun put(filePath: String, book: ParsedBook) {
        val key = BookFileStamps.absolutePath(filePath)
        val size = estimateParsedBookBytes(book)
        if (size > MAX_CACHE_BYTES / 2) {
            // A huge book would evict everything else and still barely fit.
            lock.withLock { cache.remove(key) }
            return
        }
        val entry = Entry(
            lastModified = BookFileStamps.lastModified(key),
            length = BookFileStamps.length(key),
            parserVersion = PARSER_CACHE_VERSION,
            sizeBytes = size,
            book = book
        )
        lock.withLock { cache.put(key, entry) }
    }

    fun remove(filePath: String) {
        val key = BookFileStamps.absolutePath(filePath)
        lock.withLock { cache.remove(key) }
    }

    private fun Entry.matches(path: String): Boolean =
        parserVersion == PARSER_CACHE_VERSION &&
            lastModified == BookFileStamps.lastModified(path) &&
            length == BookFileStamps.length(path)
}

/**
 * Approximate heap size of a parsed book: two bytes per character of text
 * plus the raw bytes of the images and the cover. Always at least 1.
 */
fun estimateParsedBookBytes(book: ParsedBook): Int {
    var chars = 0L
    chars += book.title.length + book.author.length + book.description.length
    for (chapter in book.chapters) {
        chars += chapter.title.length
        for (paragraph in chapter.paragraphs) chars += paragraph.length
    }
    for ((id, note) in book.footnotes) chars += id.length + note.length
    for (item in book.tableOfContents) chars += item.title.length + item.id.length
    var bytes = chars * 2
    for (image in book.images.values) bytes += image.size
    bytes += book.coverBytes?.size ?: 0
    return bytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

/** Size budget of [BookCacheRepository] on this platform. */
internal expect val PLATFORM_BOOK_CACHE_BYTES: Int

/**
 * What the cache checks about a book file. Android: java.io.File as before
 * (`absolutePath`, `lastModified()`, `length()`, 0 for a missing file); iOS:
 * Okio's file metadata.
 */
internal expect object BookFileStamps {
    fun absolutePath(path: String): String
    fun lastModified(absolutePath: String): Long
    fun length(absolutePath: String): Long
}

/**
 * The eviction policy of android.util.LruCache: entries are ordered from least
 * to most recently used (a read or a write counts as a use), and after every
 * put the least recently used entries are dropped until the total size is at
 * most [maxSize]. Not thread-safe; callers lock.
 *
 * Public, not internal: the reader's illustration cache (ReaderImageCache in
 * :app, later :sharedUi) replaced its LruCache with it too, and internal is
 * invisible across modules.
 */
class LruMap<K : Any, V : Any>(private val maxSize: Int, private val sizeOf: (V) -> Int) {
    /** Insertion order is the use order: a used entry is moved to the end. */
    private val map = LinkedHashMap<K, V>()

    var size: Long = 0L
        private set

    val keys: List<K> get() = map.keys.toList()

    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    fun put(key: K, value: V): V? {
        size += safeSizeOf(value)
        val previous = map.remove(key)
        map[key] = value
        if (previous != null) size -= safeSizeOf(previous)
        trimToSize()
        return previous
    }

    fun remove(key: K): V? {
        val previous = map.remove(key) ?: return null
        size -= safeSizeOf(previous)
        return previous
    }

    /** Drops every entry, as LruCache.evictAll() did. */
    fun clear() {
        map.clear()
        size = 0L
    }

    private fun trimToSize() {
        while (size > maxSize && map.isNotEmpty()) {
            val eldest = map.keys.first()
            val value = map.remove(eldest) ?: break
            size -= safeSizeOf(value)
        }
    }

    private fun safeSizeOf(value: V): Int {
        val result = sizeOf(value)
        check(result >= 0) { "Negative size: $value=$result" }
        return result
    }
}
