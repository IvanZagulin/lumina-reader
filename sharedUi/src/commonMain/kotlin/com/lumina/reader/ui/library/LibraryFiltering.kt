package com.lumina.reader.ui.library

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.core.preferences.LibraryPreferences

// The library's shelf naming, ordering and filtering: pure functions over
// [Book], shared by the Android and iPhone library screens and their view
// models. Moved out of LibraryViewModel in stage 8b.

/**
 * Case-insensitive order of shelf, series, title and author names.
 * The stdlib's String.CASE_INSENSITIVE_ORDER is JVM-only, so common code
 * sorts with this comparator instead.
 */
val CaseInsensitiveOrder: Comparator<String> =
    Comparator { a, b -> a.compareTo(b, ignoreCase = true) }

data class ShelfSelection(
    val collection: String? = null,
    val seriesName: String? = null
)

fun normalizeShelfName(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")

val seriesBookComparator: Comparator<Book> = compareBy<Book>(
    { if (it.seriesOrder > 0) 0 else 1 },
    { if (it.seriesOrder > 0) it.seriesOrder else Int.MAX_VALUE },
    { it.title.lowercase() }
)

fun List<Book>.sortedForSeries(): List<Book> = sortedWith(seriesBookComparator)

/** Keeps every numbered series readable even on the main "All books" screen. */
fun List<Book>.sortedForLibrary(): List<Book> = sortedWith(
    compareBy<Book>(
        { it.seriesName.isBlank() },
        { it.seriesName.lowercase() },
        { if (it.seriesOrder > 0) 0 else 1 },
        { if (it.seriesOrder > 0) it.seriesOrder else Int.MAX_VALUE },
        { it.title.lowercase() }
    )
)

/**
 * Main screen order: books already started come first, most recently read on
 * top, followed by untouched books in the usual library order.
 */
fun List<Book>.sortedForUnread(): List<Book> {
    val (started, notStarted) = filterNot(Book::isDone)
        .partition { it.currentProgressPercent > 0f }
    return started.sortedByDescending(Book::lastReadTimestamp) + notStarted.sortedForLibrary()
}

/**
 * Shelf list for chips and dialogs: the main shelf, the user's shelves in
 * their order (they exist even while empty), then shelves that only exist on
 * books. Case-insensitive duplicates are removed.
 */
fun mergeShelfNames(customShelves: List<String>, shelvesOnBooks: List<String>): List<String> =
    (listOf(LibraryPreferences.MAIN_SHELF) +
        customShelves.map(::normalizeShelfName) +
        shelvesOnBooks.map(::normalizeShelfName).sortedWith(CaseInsensitiveOrder))
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)

/** The filtering and ordering behind the library tabs, search and shelves. */
fun filterLibraryBooks(
    allBooks: List<Book>,
    query: String,
    format: BookFormat?,
    status: ReadingStatus,
    shelf: ShelfSelection
): List<Book> {
    var filtered = when (status) {
        ReadingStatus.UNREAD -> allBooks.sortedForUnread()
        ReadingStatus.ALL -> allBooks.sortedForLibrary()
        ReadingStatus.READING -> allBooks
            .filter { it.currentProgressPercent > 0f && !it.isDone() }
            .sortedForLibrary()
        ReadingStatus.FAVORITES -> allBooks.filter { it.isFavorite }.sortedForLibrary()
        ReadingStatus.COMPLETED -> allBooks
            .filter(Book::isDone)
            .sortedForLibrary()
        ReadingStatus.COLLECTIONS -> when {
            shelf.seriesName != null -> allBooks
                .filter { it.seriesName.equals(shelf.seriesName, ignoreCase = true) }
                .sortedForSeries()
            shelf.collection != null -> allBooks
                // A book assigned to a series is shown only in that series.
                // Its former shelf is kept as a fallback for when the series is removed.
                .filter {
                    it.seriesName.isBlank() &&
                        normalizeShelfName(it.collection).equals(shelf.collection, ignoreCase = true)
                }
                .sortedWith(
                    compareBy<Book>(
                        { it.seriesName.lowercase() },
                        { if (it.seriesOrder > 0) it.seriesOrder else Int.MAX_VALUE },
                        { it.title.lowercase() }
                    )
                )
            else -> allBooks
        }
    }

    val trimmedQuery = query.trim()
    if (trimmedQuery.isNotEmpty()) {
        filtered = filtered.filter {
            it.title.contains(trimmedQuery, ignoreCase = true) ||
                it.author.contains(trimmedQuery, ignoreCase = true) ||
                it.collection.contains(trimmedQuery, ignoreCase = true) ||
                it.seriesName.contains(trimmedQuery, ignoreCase = true)
        }
    }

    if (format != null) {
        filtered = filtered.filter {
            if (format == BookFormat.FB2) {
                it.format == BookFormat.FB2 || it.format == BookFormat.FB2_ZIP
            } else {
                it.format == format
            }
        }
    }
    return filtered
}
