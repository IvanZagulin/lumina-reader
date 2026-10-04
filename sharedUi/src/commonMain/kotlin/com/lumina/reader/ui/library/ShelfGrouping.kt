package com.lumina.reader.ui.library

import androidx.compose.runtime.Immutable
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.preferences.LibrarySort

/** Kinds of shelves on the «Полки» view, in display order (spec §5.2). */
enum class ShelfKind { READING, NEW, SERIES, CUSTOM, MAIN, FINISHED }

/**
 * One shelf of the library. [readCount] / [totalCount] count finished books
 * of a series (the header shows «x из n прочитано»); other shelves report
 * 0 / size.
 */
@Immutable
data class ShelfSection(
    val key: String,
    val kind: ShelfKind,
    val title: String,
    val books: List<Book>,
    val readCount: Int,
    val totalCount: Int
)

/** The book of the «Продолжить чтение» card; [resume] is false for «Начните новую книгу». */
@Immutable
data class HeroPick(val book: Book, val resume: Boolean)

/** Counts for the header subtitle «34 книги · 3 читаю · 12 прочитано». */
@Immutable
data class LibrarySummary(val total: Int, val reading: Int, val finished: Int)

internal const val NEW_SHELF_LIMIT = 12

private fun Book.isInProgress(): Boolean = !isDone() && currentProgressPercent > 0f

private fun Book.isUntouched(): Boolean = !isDone() && currentProgressPercent <= 0f

/** The book's shelf, normalised; a blank collection means the main shelf. */
fun Book.shelfName(): String =
    normalizeShelfName(collection).ifBlank { LibraryPreferences.MAIN_SHELF }

private fun isMainShelf(name: String): Boolean =
    name.equals(LibraryPreferences.MAIN_SHELF, ignoreCase = true)

/**
 * The most recently read unfinished book, or else the newest untouched one;
 * null when there is nothing left to read.
 */
fun pickHeroBook(all: List<Book>): HeroPick? {
    all.filter(Book::isInProgress).maxByOrNull(Book::lastReadTimestamp)?.let { return HeroPick(it, resume = true) }
    all.filter { !it.isDone() }.maxByOrNull(Book::id)?.let { return HeroPick(it, resume = false) }
    return null
}

/**
 * Groups the library into shelves (spec §5.2). The default view keeps the
 * owner's decision "unread books on the main screen": finished books only
 * appear on series shelves and on the collapsed «Прочитано» shelf.
 *
 * - «Сейчас читаю»: in progress, minus the hero book, last read first.
 * - «Новые поступления»: untouched, newest 12 (duplicates other shelves on purpose).
 * - One shelf per series (normalised name), finished books included, in series
 *   order; series with books in progress first, then by name.
 * - Custom shelves: unfinished books without a series; user shelves render
 *   even while empty. Sorted by name, books last read first.
 * - «Основная полка»: unfinished main-shelf books without a series, started first.
 * - «Прочитано»: finished books without a series, most recently finished first.
 *
 * [customShelfNames] are the user's shelves (the main shelf is ignored).
 */
fun groupIntoShelves(all: List<Book>, heroBookId: Long?, customShelfNames: List<String>): List<ShelfSection> {
    val sections = ArrayList<ShelfSection>()

    val reading = all.filter { it.isInProgress() && it.id != heroBookId }
        .sortedByDescending(Book::lastReadTimestamp)
    if (reading.isNotEmpty()) {
        sections += ShelfSection("reading", ShelfKind.READING, "Сейчас читаю", reading, 0, reading.size)
    }

    val fresh = all.filter(Book::isUntouched).sortedByDescending(Book::id).take(NEW_SHELF_LIMIT)
    if (fresh.isNotEmpty()) {
        sections += ShelfSection("new", ShelfKind.NEW, "Новые поступления", fresh, 0, fresh.size)
    }

    val series = all.filter { it.seriesName.isNotBlank() }
        .groupBy { normalizeShelfName(it.seriesName).lowercase() }
        .filterKeys { it.isNotBlank() }
        .map { (key, books) ->
            val ordered = books.sortedForSeries()
            ShelfSection(
                key = "series:$key",
                kind = ShelfKind.SERIES,
                // The spelling of the most recently read book (library order), not of book 1.
                title = normalizeShelfName(books.first().seriesName),
                books = ordered,
                readCount = ordered.count(Book::isDone),
                totalCount = ordered.size
            )
        }
        .sortedWith(
            compareByDescending<ShelfSection> { section -> section.books.any(Book::isInProgress) }
                .thenBy { it.title.lowercase() }
        )
    sections += series

    val looseUnfinished = all.filter { it.seriesName.isBlank() && !it.isDone() }
    val userShelves = customShelfNames.map(::normalizeShelfName)
        .filter { it.isNotBlank() && !isMainShelf(it) }
    val shelvesOnBooks = looseUnfinished.map { it.shelfName() }.filterNot(::isMainShelf)
    val customNames = (userShelves + shelvesOnBooks).distinctBy(String::lowercase)
    val custom = customNames.map { name ->
        val books = looseUnfinished.filter { it.shelfName().equals(name, ignoreCase = true) }
            .sortedByDescending(Book::lastReadTimestamp)
        ShelfSection("custom:${name.lowercase()}", ShelfKind.CUSTOM, name, books, 0, books.size)
    }.sortedBy { it.title.lowercase() }
    sections += custom

    val main = all.filter { it.seriesName.isBlank() && isMainShelf(it.shelfName()) }.sortedForUnread()
    if (main.isNotEmpty()) {
        sections += ShelfSection("main", ShelfKind.MAIN, "Основная полка", main, 0, main.size)
    }

    val finished = all.filter { it.isDone() && it.seriesName.isBlank() }
        .sortedByDescending { it.completedAt ?: it.lastReadTimestamp }
    if (finished.isNotEmpty()) {
        sections += ShelfSection("finished", ShelfKind.FINISHED, "Прочитано", finished, finished.size, finished.size)
    }
    return sections
}

fun summarizeLibrary(all: List<Book>): LibrarySummary = LibrarySummary(
    total = all.size,
    reading = all.count(Book::isInProgress),
    finished = all.count(Book::isDone)
)

/** «34 книги · 3 читаю · 12 прочитано» (zero parts after the total are left out). */
fun LibrarySummary.subtitle(): String = buildString {
    append(total).append(' ').append(russianPlural(total, "книга", "книги", "книг"))
    if (reading > 0) append(" · ").append(reading).append(" читаю")
    if (finished > 0) append(" · ").append(finished).append(" прочитано")
}

/** Russian plural form for [n]: 1 книга, 2 книги, 5 книг, 11 книг, 21 книга. */
fun russianPlural(n: Int, one: String, few: String, many: String): String {
    val mod100 = kotlin.math.abs(n) % 100
    val mod10 = mod100 % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** Order of the flat (bookcase / list) views. «Недавние» keeps books in progress first. */
fun List<Book>.sortedForView(sort: LibrarySort): List<Book> = when (sort) {
    LibrarySort.RECENT -> sortedWith(
        compareByDescending<Book> { it.isInProgress() }.thenByDescending(Book::lastReadTimestamp)
    )
    LibrarySort.TITLE -> sortedWith(compareBy(CaseInsensitiveOrder, Book::title))
    LibrarySort.AUTHOR -> sortedWith(
        compareBy(CaseInsensitiveOrder, Book::author).thenBy(CaseInsensitiveOrder, Book::title)
    )
    LibrarySort.ADDED -> sortedByDescending(Book::id)
    LibrarySort.PROGRESS -> sortedWith(
        compareByDescending(Book::currentProgressPercent).thenBy(CaseInsensitiveOrder, Book::title)
    )
}

/** Plate text engraved on the plank: «ВЕДЬМАК · 3/7» for series, «ФАНТАСТИКА · 5» for custom shelves. */
fun ShelfSection.plateText(): String? = when (kind) {
    ShelfKind.SERIES -> "${title.uppercase()} · $readCount/$totalCount"
    ShelfKind.CUSTOM -> if (books.isEmpty()) null else "${title.uppercase()} · ${books.size}"
    else -> null
}
