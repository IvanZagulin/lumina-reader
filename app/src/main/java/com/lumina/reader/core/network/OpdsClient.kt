package com.lumina.reader.core.network

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.opds.BuiltInCatalogs
import com.lumina.reader.core.opds.FoundPublication
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsRepository
import com.lumina.reader.core.opds.OpdsSearchType

/**
 * Flat search result kept for callers of the original client. New code uses
 * [OpdsRepository] and [com.lumina.reader.core.opds.OpdsEntry] directly.
 */
data class OpdsBook(
    val title: String,
    val author: String,
    val downloadUrlEpub: String?,
    val downloadUrlFb2: String?
)

/**
 * Compatibility facade over [OpdsRepository]: global book search across the
 * given catalogues (the built-in ones by default). Downloads go through
 * [com.lumina.reader.core.library.BookImporter], which streams to disk.
 */
class OpdsClient(
    private val catalogs: List<OpdsCatalogConfig> = BuiltInCatalogs.all,
    private val repository: OpdsRepository = OpdsRepository()
) {
    /** [searchType] is Flibusta's legacy value: "books", "authors" or "sequences". */
    suspend fun searchBooks(query: String, searchType: String = "books"): List<OpdsBook> {
        val enabled = catalogs.filter { it.enabled }
        val type = OpdsSearchType.entries.firstOrNull { it.legacyType == searchType } ?: OpdsSearchType.BOOKS
        val found = if (type == OpdsSearchType.BOOKS) {
            repository.findPublications(query, enabled)
        } else {
            repository.searchAll(enabled, query, type).flatMap { result ->
                result.feed?.publications.orEmpty().map { FoundPublication(result.catalog, it) }
            }
        }
        return found
            .map { item ->
                val publication = item.publication
                OpdsBook(
                    title = publication.title,
                    author = publication.authorLine,
                    downloadUrlEpub = publication.acquisitions.firstOrNull { it.format == BookFormat.EPUB }?.url,
                    downloadUrlFb2 = publication.acquisitions.firstOrNull {
                        it.format == BookFormat.FB2_ZIP || it.format == BookFormat.FB2
                    }?.url
                )
            }
            .filter { it.downloadUrlEpub != null || it.downloadUrlFb2 != null }
            .distinctBy { listOf(it.title.trim().lowercase(), it.author.trim().lowercase()).joinToString("|") }
    }
}
