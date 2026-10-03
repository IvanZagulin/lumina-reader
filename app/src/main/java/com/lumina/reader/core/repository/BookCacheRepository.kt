package com.lumina.reader.core.repository

import android.util.LruCache
import com.lumina.reader.core.model.ParsedBook
import java.io.File

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

    /** Upper bound for the estimated size of all cached books. */
    const val MAX_CACHE_BYTES = 48 * 1024 * 1024

    private data class Entry(
        val lastModified: Long,
        val length: Long,
        val parserVersion: Int,
        val sizeBytes: Int,
        val book: ParsedBook
    )

    private val cache = object : LruCache<String, Entry>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Entry): Int = value.sizeBytes
    }

    fun get(filePath: String): ParsedBook? {
        val file = File(filePath)
        val key = file.absolutePath
        val entry = cache.get(key) ?: return null
        if (!entry.matches(file)) {
            cache.remove(key)
            return null
        }
        return entry.book
    }

    fun put(filePath: String, book: ParsedBook) {
        val file = File(filePath)
        val key = file.absolutePath
        val size = estimateParsedBookBytes(book)
        if (size > MAX_CACHE_BYTES / 2) {
            // A huge book would evict everything else and still barely fit.
            cache.remove(key)
            return
        }
        cache.put(
            key,
            Entry(
                lastModified = file.lastModified(),
                length = file.length(),
                parserVersion = PARSER_CACHE_VERSION,
                sizeBytes = size,
                book = book
            )
        )
    }

    fun remove(filePath: String) {
        cache.remove(File(filePath).absolutePath)
    }

    private fun Entry.matches(file: File): Boolean =
        parserVersion == PARSER_CACHE_VERSION &&
            lastModified == file.lastModified() &&
            length == file.length()
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
