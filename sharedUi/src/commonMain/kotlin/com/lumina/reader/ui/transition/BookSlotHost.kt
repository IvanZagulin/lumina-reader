package com.lumina.reader.ui.transition

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.lumina.reader.ui.components.BookCoverModel

/**
 * What shelf books need from the book-opening transition: the book whose
 * shelf copy is hidden while the overlay draws it, and the modifier that
 * registers a book's on-screen slot under a key (`"shelf:<section>:<id>"`,
 * `"grid:…"`, …) so the transition can start from it and fly back to it.
 *
 * Implemented by the transition state of the app shell (:app's
 * `BookTransitionState`, until the transition moves here in stage 8d).
 */
@Stable
interface BookSlotHost {
    /** The book whose shelf slot is drawn transparent while the clone flies (snapshot state). */
    val hiddenBookId: Long?

    /**
     * The scale the live library draws at: it grows back to 1 while the reader
     * closes onto it, and is 1 at rest (snapshot state).
     */
    val libraryScale: Float
        get() = 1f

    /** Registers the bounds of this element as the slot [key] of [bookId]. */
    fun slotModifier(key: String, bookId: Long): Modifier

    /** A slot this book is currently shown in, if any; the flight starts there. */
    fun findSlotKey(bookId: Long): String? = null

    /**
     * Flies [cover] from [slotKey] into the reader and calls [navigate] at the
     * right moment. Without a host the caller just navigates.
     */
    fun open(cover: BookCoverModel, slotKey: String?, mode: OpenAnimation, navigate: () -> Unit) {
        navigate()
    }
}

/** Provided by the app shell; null outside it (previews, sheets in other windows, iOS for now). */
val LocalBookSlotHost = staticCompositionLocalOf<BookSlotHost?> { null }

/**
 * Slot key of the cover on the «Продолжить чтение» card — the one slot that is
 * not built from a section and a book id, so both the shared card and the app
 * shell's transition name it from here.
 */
const val HeroSlotKey = "hero"

/** [BookSlotHost.slotModifier] of [host], or nothing without a host. */
fun Modifier.bookSlot(host: BookSlotHost?, key: String, bookId: Long): Modifier =
    if (host == null) this else this then host.slotModifier(key, bookId)
