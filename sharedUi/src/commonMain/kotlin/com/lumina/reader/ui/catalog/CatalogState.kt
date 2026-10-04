package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsLink
import com.lumina.reader.core.opds.OpdsSearchType

enum class CatalogSearchScope(val title: String, val searchType: OpdsSearchType) {
    BOOKS("Книги", OpdsSearchType.BOOKS),
    AUTHORS("Авторы", OpdsSearchType.AUTHORS),
    SERIES("Серии", OpdsSearchType.SERIES)
}

/** One level of the browsing stack inside a catalogue. */
data class CatalogPage(
    val id: Long,
    val catalog: OpdsCatalogConfig,
    val title: String,
    /** Feed URL (for search pages: the URL of the loaded results, if any). */
    val url: String,
    val entries: List<OpdsEntry> = emptyList(),
    val nextUrl: String? = null,
    /** The feed's own search link; inherited from the parent page when the feed has none. */
    val searchLink: OpdsLink? = null,
    /** Non-null for a page with search results inside this catalogue. */
    val searchQuery: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    /** False until the first load succeeded (an empty folder is loaded too). */
    val isLoaded: Boolean = false
) {
    val publications: List<OpdsEntry.Publication>
        get() = entries.filterIsInstance<OpdsEntry.Publication>()

    val downloadablePublications: List<OpdsEntry.Publication>
        get() = publications.filter { it.acquisitions.isNotEmpty() }
}

/** Results of one catalogue in the global search. */
data class CatalogSearchSection(
    val catalog: OpdsCatalogConfig,
    val entries: List<OpdsEntry> = emptyList(),
    val nextUrl: String? = null,
    val feedUrl: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

data class GlobalSearchState(
    val query: String,
    val scope: CatalogSearchScope,
    val sections: List<CatalogSearchSection>
) {
    val isLoading: Boolean
        get() = sections.any { it.isLoading }

    val totalResults: Int
        get() = sections.sumOf { it.entries.size }

    /** Every downloadable publication with its catalogue ("Скачать все"). */
    val downloadablePublications: List<Pair<OpdsCatalogConfig, OpdsEntry.Publication>>
        get() = sections.flatMap { section ->
            section.entries.filterIsInstance<OpdsEntry.Publication>()
                .filter { it.acquisitions.isNotEmpty() }
                .map { section.catalog to it }
        }
}

/** The book whose details sheet is open. */
data class SelectedPublication(
    val catalog: OpdsCatalogConfig,
    val publication: OpdsEntry.Publication
)

data class CatalogUiState(
    /** Enabled catalogues, shown on the start screen and used by the global search. */
    val catalogs: List<OpdsCatalogConfig> = emptyList(),
    val pages: List<CatalogPage> = emptyList(),
    val query: String = "",
    val scope: CatalogSearchScope = CatalogSearchScope.BOOKS,
    val globalSearch: GlobalSearchState? = null,
    val selected: SelectedPublication? = null
) {
    val currentPage: CatalogPage?
        get() = pages.lastOrNull()

    val isBrowsing: Boolean
        get() = pages.isNotEmpty()

    val title: String
        get() = currentPage?.title ?: if (globalSearch != null) "Поиск по каталогам" else "Каталоги OPDS"

    val searchPlaceholder: String
        get() = currentPage?.let { "Поиск в «${it.catalog.name}»" } ?: "Поиск во всех каталогах"
}

/** What a catalogue row shows for a publication's downloads. */
data class PublicationDownload(
    val acquisition: OpdsAcquisition,
    val state: DownloadState
)

/**
 * The download state most relevant for a publication: a running download
 * first, then a completed one, then a failure; null when nothing was started.
 */
fun publicationDownload(
    publication: OpdsEntry.Publication,
    downloads: Map<String, DownloadState>
): PublicationDownload? {
    val known = publication.acquisitions.mapNotNull { acquisition ->
        downloads[acquisition.url]?.let { PublicationDownload(acquisition, it) }
    }
    return known.firstOrNull { it.state.isActive }
        ?: known.firstOrNull { it.state is DownloadState.Completed }
        ?: known.firstOrNull { it.state is DownloadState.Failed }
}

/** Appends a next page, skipping entries already shown (stable unique list keys). */
fun appendUniqueEntries(existing: List<OpdsEntry>, more: List<OpdsEntry>): List<OpdsEntry> {
    val keys = existing.mapTo(HashSet()) { it.key }
    return existing + more.filter { keys.add(it.key) }
}
