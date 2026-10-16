package com.lumina.reader.core.social

import com.lumina.reader.core.library.normalizeTitleForMatch
import com.lumina.reader.core.model.Book
import kotlin.math.roundToInt

/**
 * What of the library is sent to the circle, and in what shape.
 *
 * This is the privacy boundary of the feature, so it is a pure function over
 * the books and the user's choices rather than something the network layer
 * decides: sharing off means an empty shelf, and a book the user hid is simply
 * not in the list. Nothing here reads a file path or anything else that would
 * say where a book came from - only what it is and how far the reader got.
 */
object SharedShelf {

    /**
     * Identity of a book inside a shelf. Two people reading the same book get
     * the same key, so a feed can tell "you are both reading this" without
     * anyone sharing a file. Built from the normalized title and author, the
     * same normalization the library uses to match series.
     */
    fun bookKey(title: String, author: String): String =
        normalizeTitleForMatch(title) + "|" + normalizeTitleForMatch(author)

    /**
     * The shelf to push. [sharingEnabled] off gives an empty list - the server
     * then holds nothing for this user, which is how "stop sharing" erases what
     * was shared. [hiddenKeys] are books the user keeps to themselves.
     */
    fun build(
        books: List<Book>,
        sharingEnabled: Boolean,
        hiddenKeys: Set<String> = emptySet(),
    ): List<SharedBook> {
        if (!sharingEnabled) return emptyList()
        return books
            .asSequence()
            .filter { it.title.isNotBlank() }
            .map { it.toSharedBook() }
            .filter { it.bookKey !in hiddenKeys }
            .distinctBy { it.bookKey }
            .toList()
    }

    private fun Book.toSharedBook(): SharedBook = SharedBook(
        bookKey = bookKey(title, author),
        title = title.trim(),
        author = author.trim(),
        rating = null,
        progress = currentProgressPercent.roundToInt().coerceIn(0, 100),
        status = statusOf(this),
        updatedAt = lastReadTimestamp,
    )

    /** Finished, started, or never opened - the three states the feed shows. */
    private fun statusOf(book: Book): String = when {
        book.isDone() -> ReadingStatus.DONE
        book.startedAt != null || book.currentProgressPercent > 0f -> ReadingStatus.READING
        else -> ReadingStatus.NOT_STARTED
    }
}
