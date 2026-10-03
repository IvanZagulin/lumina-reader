package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat

/** A link of an OPDS feed or entry with [href] already resolved to an absolute URL. */
data class OpdsLink(
    val href: String,
    val rel: String? = null,
    val type: String? = null,
    val title: String? = null,
    val length: Long? = null
) {
    val isOpenSearchDescription: Boolean
        get() = type?.contains("opensearchdescription", ignoreCase = true) == true

    val isSearchTemplate: Boolean
        get() = href.contains("{searchTerms", ignoreCase = true)
}

/** One downloadable file of a publication in a format the reader supports. */
data class OpdsAcquisition(
    val url: String,
    val format: BookFormat,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
) {
    val label: String
        get() = OpdsFormats.label(format)
}

data class OpdsSeries(val name: String, val index: Int? = null)

sealed interface OpdsEntry {
    /** Stable identity inside a feed (the Atom id, or a link when the id is missing). */
    val key: String
    val title: String

    /** A folder: opens another feed (authors, series, genres, new books...). */
    data class Navigation(
        override val key: String,
        override val title: String,
        val url: String,
        val summary: String = "",
        val thumbnailUrl: String? = null
    ) : OpdsEntry

    /** A book. [acquisitions] only lists supported formats (may be empty). */
    data class Publication(
        override val key: String,
        override val title: String,
        val authors: List<String> = emptyList(),
        val summary: String = "",
        val thumbnailUrl: String? = null,
        val coverUrl: String? = null,
        val series: OpdsSeries? = null,
        val acquisitions: List<OpdsAcquisition> = emptyList(),
        /** Links to other feeds: "all books of the author", "the series"... */
        val relatedLinks: List<OpdsLink> = emptyList(),
        val categories: List<String> = emptyList(),
        val language: String? = null,
        val issued: String? = null,
        val sizeBytes: Long? = null,
        /** Labels of offered formats the reader cannot open (MOBI, DjVu...). */
        val unsupportedFormats: List<String> = emptyList()
    ) : OpdsEntry {
        val authorLine: String
            get() = authors.joinToString(", ")

        val preferredAcquisition: OpdsAcquisition?
            get() = OpdsFormats.preferred(acquisitions)
    }
}

data class OpdsFeed(
    val title: String,
    /** Final URL of the feed (after redirects); relative links were resolved against it. */
    val url: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String? = null,
    val searchLink: OpdsLink? = null,
    val startUrl: String? = null,
    val upUrl: String? = null
) {
    val publications: List<OpdsEntry.Publication>
        get() = entries.filterIsInstance<OpdsEntry.Publication>()

    val navigation: List<OpdsEntry.Navigation>
        get() = entries.filterIsInstance<OpdsEntry.Navigation>()
}

/** What the global search looks for; [legacyType] is Flibusta's `searchType`. */
enum class OpdsSearchType(val legacyType: String) {
    BOOKS("books"),
    AUTHORS("authors"),
    SERIES("sequences")
}

/** Raised when a response is not an OPDS/Atom feed. */
class OpdsFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
