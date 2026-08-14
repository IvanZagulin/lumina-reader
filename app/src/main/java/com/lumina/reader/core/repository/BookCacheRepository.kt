package com.lumina.reader.core.repository

import android.util.LruCache
import com.lumina.reader.core.model.ParsedBook

object BookCacheRepository {
    // Keep up to 5 recently parsed books in memory for instant 0ms reader opening
    private val cache = LruCache<String, ParsedBook>(5)

    fun get(filePath: String): ParsedBook? {
        return cache.get(filePath)
    }

    fun put(filePath: String, book: ParsedBook) {
        cache.put(filePath, book)
    }

    fun remove(filePath: String) {
        cache.remove(filePath)
    }
}
