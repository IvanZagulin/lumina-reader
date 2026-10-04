package com.lumina.reader.ui.transition

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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

    /** Registers the bounds of this element as the slot [key] of [bookId]. */
    fun slotModifier(key: String, bookId: Long): Modifier
}

/** Provided by the app shell; null outside it (previews, sheets in other windows, iOS for now). */
val LocalBookSlotHost = staticCompositionLocalOf<BookSlotHost?> { null }

/** [BookSlotHost.slotModifier] of [host], or nothing without a host. */
fun Modifier.bookSlot(host: BookSlotHost?, key: String, bookId: Long): Modifier =
    if (host == null) this else this then host.slotModifier(key, bookId)
