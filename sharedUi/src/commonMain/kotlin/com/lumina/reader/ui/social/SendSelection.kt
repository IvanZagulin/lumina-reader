package com.lumina.reader.ui.social

import androidx.compose.runtime.Immutable
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.social.SharedBook
import com.lumina.reader.core.social.SharedShelf
import com.lumina.reader.core.social.TransferOutbox

/**
 * One book as the send sheet shows it.
 *
 * [alreadyTheirs] and [tooLarge] are the two things worth knowing before
 * ticking a box: one makes the upload pointless, the other makes it fail. Both
 * are known here, so neither has to be discovered the hard way.
 */
@Immutable
data class SendableBook(
    val book: Book,
    /** The recipient's shelf already has this one, by [SharedShelf.bookKey]. */
    val alreadyTheirs: Boolean,
    /** Past the server's per-file cap, so it cannot be sent at all. */
    val tooLarge: Boolean
) {
    val id: Long get() = book.id
    val selectable: Boolean get() = !tooLarge
}

/**
 * Books of one series, or the loose ones when [series] is null.
 *
 * A series is a group rather than a word in a subtitle because sending one is
 * the common case: the header selects the whole of it at once, and the volumes
 * stay in reading order instead of being scattered by title.
 */
@Immutable
data class SendGroup(val series: String?, val books: List<SendableBook>) {
    val selectableIds: Set<Long> get() = books.filter { it.selectable }.map { it.id }.toSet()
}

/**
 * Arranges the library for the send sheet: series first as groups in reading
 * order, then everything else by title, with [query] matching a title, an
 * author or a series name.
 *
 * Pure on purpose - the ordering and the matching are the part worth testing,
 * and neither needs a screen.
 */
fun groupForSending(
    books: List<Book>,
    theirBookKeys: Set<String>,
    query: String = "",
    maxBytes: Long = TransferOutbox.MAX_SEND_BYTES
): List<SendGroup> {
    val needle = query.trim().lowercase()
    val matching = books
        .filter { it.title.isNotBlank() }
        .filter { needle.isEmpty() || it.matches(needle) }
        .map { book ->
            SendableBook(
                book = book,
                alreadyTheirs = SharedShelf.bookKey(book.title, book.author) in theirBookKeys,
                tooLarge = book.fileSizeBytes > maxBytes
            )
        }

    val series = matching
        .filter { it.book.seriesName.isNotBlank() }
        .groupBy { it.book.seriesName.trim() }
        .map { (name, volumes) ->
            SendGroup(
                series = name,
                // Numbered volumes in order; unnumbered ones after them, by title.
                books = volumes.sortedWith(
                    compareBy<SendableBook> { it.book.seriesOrder <= 0 }
                        .thenBy { it.book.seriesOrder }
                        .thenBy { it.book.title.lowercase() }
                )
            )
        }
        .sortedBy { it.series?.lowercase() }

    val loose = matching
        .filter { it.book.seriesName.isBlank() }
        .sortedBy { it.book.title.lowercase() }

    return if (loose.isEmpty()) series else series + SendGroup(null, loose)
}

private fun Book.matches(needle: String): Boolean =
    title.lowercase().contains(needle) ||
        author.lowercase().contains(needle) ||
        seriesName.lowercase().contains(needle)

/** A friend's shelf as the feed shows it: one series, or the loose books. */
@Immutable
data class ShelfGroup(val series: String?, val books: List<SharedBook>)

/**
 * The viewing counterpart of [groupForSending]: series as groups in reading
 * order, loose books after them, most recently touched first.
 *
 * Without this a friend's shelf is a flat list in which three volumes of one
 * story sit apart from each other, which was the whole complaint.
 */
fun groupSharedShelf(books: List<SharedBook>): List<ShelfGroup> {
    val series = books
        .filter { !it.seriesName.isNullOrBlank() }
        .groupBy { it.seriesName!!.trim() }
        .map { (name, volumes) ->
            ShelfGroup(
                series = name,
                books = volumes.sortedWith(
                    compareBy<SharedBook> { it.seriesOrder <= 0 }
                        .thenBy { it.seriesOrder }
                        .thenBy { it.title.lowercase() }
                )
            )
        }
        .sortedBy { it.series?.lowercase() }

    val loose = books
        .filter { it.seriesName.isNullOrBlank() }
        .sortedByDescending { it.updatedAt }

    return if (loose.isEmpty()) series else series + ShelfGroup(null, loose)
}

/** «1,4 МБ» / «870 КБ» — enough to tell a quick send from a slow one. */
fun formatBookSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> {
        val mb = bytes.toDouble() / (1024 * 1024)
        // One decimal below ten megabytes, where the difference is worth seeing.
        if (mb < 10) "${(mb * 10).toLong() / 10}," + "${(mb * 10).toLong() % 10} МБ" else "${mb.toLong()} МБ"
    }
}
