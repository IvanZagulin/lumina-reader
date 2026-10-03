package com.lumina.reader.ui.catalog

import androidx.compose.runtime.Immutable
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.normalizeTitleForMatch
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsLink

/*
 * Pure presentation helpers of the catalogue screens (spec §7.9). No Compose
 * UI and no Android here, so everything is unit tested.
 */

/** What an OPDS folder is about; picks its icon. */
enum class OpdsNavKind { AUTHOR, SERIES, GENRE, LETTER, OTHER }

object CatalogUi {

    /** Classifies a folder by its title and URL (Flibusta-style feeds and common OPDS servers). */
    fun navKind(entry: OpdsEntry.Navigation): OpdsNavKind {
        val title = entry.title.trim()
        if (title.isNotEmpty() && title.length <= 2) return OpdsNavKind.LETTER
        val lowerTitle = title.lowercase()
        val path = entry.url.lowercase().substringBefore('?')
        return when {
            "/author" in path || lowerTitle.startsWith("автор") || lowerTitle.startsWith("author") -> OpdsNavKind.AUTHOR
            "/sequence" in path || "/series" in path || lowerTitle.startsWith("сери") || lowerTitle.startsWith("series") ->
                OpdsNavKind.SERIES
            "/genre" in path || lowerTitle.startsWith("жанр") || lowerTitle.startsWith("genre") -> OpdsNavKind.GENRE
            else -> OpdsNavKind.OTHER
        }
    }

    /**
     * True for alphabet feeds ("А", "Б", … or "Ба", "Бе", …): only folders,
     * at least four of them, every title at most two characters.
     */
    fun isAlphabetFeed(entries: List<OpdsEntry>): Boolean =
        entries.size >= 4 && entries.all { it is OpdsEntry.Navigation && it.title.trim().length in 1..2 }

    /** «Серия · №3» or the series name alone. */
    fun seriesLine(name: String, index: Int?): String =
        if (index != null && index > 0) "$name · №$index" else name

    /** The link that opens "all books of the author", if the entry has one. */
    fun authorLink(publication: OpdsEntry.Publication): OpdsLink? =
        publication.relatedLinks.firstOrNull { link ->
            val title = link.title?.lowercase().orEmpty()
            "автор" in title || "author" in title || "/author" in link.href.lowercase()
        }

    /** «Язык · Год · Размер» parts of the details sheet. */
    fun metaParts(language: String?, issued: String?, sizeLabel: String?): List<String> = listOfNotNull(
        language?.trim()?.takeIf { it.isNotEmpty() }?.let(::languageName),
        issued?.trim()?.take(4)?.takeIf { year -> year.length == 4 && year.all { it.isDigit() } },
        sizeLabel
    )

    private fun languageName(code: String): String = when (code.lowercase().substringBefore('-')) {
        "ru" -> "Русский"
        "en" -> "Английский"
        "uk" -> "Украинский"
        "be" -> "Белорусский"
        "de" -> "Немецкий"
        "fr" -> "Французский"
        "es" -> "Испанский"
        "it" -> "Итальянский"
        else -> code.uppercase()
    }

    /** First letter for a catalogue monogram ("Флибуста" -> "Ф"). */
    fun monogram(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** Index into the 8 cloth colours, stable for a name (same formula as generated covers). */
    fun clothIndex(seed: String): Int = seed.lowercase().hashCode().mod(CLOTH_COUNT)

    const val CLOTH_COUNT = 8

    /**
     * Breadcrumbs of the browsing stack: page id and title, the last one is
     * the current level.
     */
    fun breadcrumbs(pages: List<CatalogPage>): List<Pair<Long, String>> =
        pages.map { page -> page.id to page.title.ifBlank { page.catalog.name } }

    /** "Скачать все 7 · FB2" progress: started and completed downloads among [keys]. */
    fun batchCounts(keys: List<String>, downloads: Map<String, DownloadState>): Pair<Int, Int> {
        val started = keys.count { downloads[it] != null }
        val done = keys.count { downloads[it] is DownloadState.Completed }
        return started to done
    }
}

/** Download control state of one format (spec §7.9 table). */
@Immutable
sealed interface ChipState {
    data object Idle : ChipState
    data object Queued : ChipState
    data class Running(val fraction: Float?) : ChipState {
        val percent: Int? get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    }
    data object Importing : ChipState
    data class Done(val bookId: Long) : ChipState
    data class InLibrary(val bookId: Long) : ChipState
    data class Failed(val message: String) : ChipState

    companion object {
        /**
         * Maps a download state (or none) to the chip. [libraryBookId] is a
         * book already on the shelf with the same title and author.
         */
        fun of(download: DownloadState?, libraryBookId: Long?): ChipState = when (download) {
            null -> if (libraryBookId != null) InLibrary(libraryBookId) else Idle
            DownloadState.Queued -> Queued
            is DownloadState.Running -> if (download.isImporting) Importing else Running(download.fraction)
            is DownloadState.Completed ->
                if (download.alreadyInLibrary) InLibrary(download.bookId) else Done(download.bookId)
            is DownloadState.Failed -> Failed(download.message)
        }
    }
}

/** Download controls of one publication: one chip per format, best format first. */
@Immutable
data class FormatChips(
    val items: List<Pair<OpdsAcquisition, ChipState>>,
    /** URL of the format drawn tonal-filled (null when the book is already on the shelf). */
    val bestUrl: String?,
    /** Reason of the first failed download, shown under the chips. */
    val failure: String?,
    /** The book is on the shelf (downloaded now or before): the cover gets a ✓ badge. */
    val checked: Boolean
) {
    companion object {
        fun of(
            publication: OpdsEntry.Publication,
            downloads: Map<String, DownloadState>,
            libraryBookId: Long?
        ): FormatChips {
            val acquisitions = publication.acquisitions
            val best = bestAcquisition(acquisitions)
            val anyStarted = acquisitions.any { downloads[it.url] != null }
            val showLibrary = !anyStarted && libraryBookId != null
            val ordered = if (best == null) acquisitions else listOf(best) + acquisitions.filter { it !== best }
            val items = ordered.map { acquisition ->
                val state = if (showLibrary && acquisition === best) {
                    ChipState.InLibrary(libraryBookId!!)
                } else {
                    ChipState.of(downloads[acquisition.url], null)
                }
                acquisition to state
            }
            return FormatChips(
                items = items,
                bestUrl = if (showLibrary) null else best?.url,
                failure = items.firstNotNullOfOrNull { (it.second as? ChipState.Failed)?.message },
                checked = items.any { it.second is ChipState.Done || it.second is ChipState.InLibrary }
            )
        }
    }
}

/** The format that gets the filled chip: FB2 (zip), then EPUB, PDF, TXT. */
fun bestAcquisition(acquisitions: List<OpdsAcquisition>): OpdsAcquisition? =
    com.lumina.reader.core.opds.OpdsFormats.preferred(acquisitions)

/**
 * Library books by normalised title, with their authors' name tokens, to
 * show «В библиотеке · Открыть» for books that are already on the shelf.
 */
@Immutable
class LibraryIndex private constructor(
    private val byTitle: Map<String, List<Pair<Set<String>, Long>>>
) {
    data class Entry(val id: Long, val title: String, val author: String)

    /** Book id with the same title and at least one common author name, or null. */
    fun find(title: String, authors: List<String>): Long? {
        val candidates = byTitle[normalizeTitleForMatch(title)] ?: return null
        val wanted = authors.flatMapTo(HashSet()) { tokens(it) }
        if (wanted.isEmpty()) return candidates.singleOrNull()?.second
        return candidates.firstOrNull { (bookTokens, _) ->
            bookTokens.isEmpty() || bookTokens.any { it in wanted }
        }?.second
    }

    val isEmpty: Boolean
        get() = byTitle.isEmpty()

    companion object {
        val Empty = LibraryIndex(emptyMap())

        private const val UNKNOWN_AUTHOR = "неизвестный автор"

        fun build(books: List<Entry>): LibraryIndex {
            if (books.isEmpty()) return Empty
            val map = HashMap<String, MutableList<Pair<Set<String>, Long>>>()
            books.forEach { book ->
                val key = normalizeTitleForMatch(book.title)
                if (key.isEmpty()) return@forEach
                val authorTokens = if (book.author.trim().lowercase() == UNKNOWN_AUTHOR) emptySet() else tokens(book.author)
                map.getOrPut(key) { mutableListOf() } += authorTokens to book.id
            }
            return LibraryIndex(map)
        }

        /** Name words of 3+ letters, so initials do not match everything. */
        private fun tokens(name: String): Set<String> =
            normalizeTitleForMatch(name).split(' ').filterTo(HashSet()) { it.length >= 3 }
    }
}
