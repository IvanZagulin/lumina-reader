package com.lumina.reader.ui.transition

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Registers this book's on-screen bounds under [key] (`"shelf:<section>:<id>"`,
 * `"grid:…"`, `"list:<id>"` or `"hero"`) so the transition can start from it
 * and fly back to it. Registration is a plain map put on every global
 * position change; the entry is removed when the node detaches.
 */
fun Modifier.bookTransitionSlot(state: BookTransitionState?, key: String, bookId: Long): Modifier =
    if (state == null) this else this then BookTransitionSlotElement(state, key, bookId)

private data class BookTransitionSlotElement(
    val state: BookTransitionState,
    val key: String,
    val bookId: Long
) : ModifierNodeElement<BookTransitionSlotNode>() {
    override fun create(): BookTransitionSlotNode = BookTransitionSlotNode(state, key, bookId)

    override fun update(node: BookTransitionSlotNode) {
        node.update(state, key, bookId)
    }
}

private class BookTransitionSlotNode(
    private var state: BookTransitionState,
    private var key: String,
    private var bookId: Long
) : Modifier.Node(), GlobalPositionAwareModifierNode {

    /** Last coordinates, so a changed key or book (e.g. a new hero book) re-registers at once. */
    private var lastCoordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        state.registerSlot(key, bookId, coordinates, this)
    }

    override fun onDetach() {
        state.unregisterSlot(key, this)
        lastCoordinates = null
    }

    fun update(newState: BookTransitionState, newKey: String, newBookId: Long) {
        if (newState !== state || newKey != key) state.unregisterSlot(key, this)
        state = newState
        key = newKey
        bookId = newBookId
        val coordinates = lastCoordinates
        if (isAttached && coordinates != null && coordinates.isAttached) {
            state.registerSlot(key, bookId, coordinates, this)
        }
    }
}
