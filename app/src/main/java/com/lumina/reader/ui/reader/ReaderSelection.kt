package com.lumina.reader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Keeps text selection in the reader dismissible.
 *
 * Compose leaves a finger selection on screen until it is copied, because the
 * reader's own tap handling (page turns, menu) never clears it. Wrapping the
 * platform text toolbar tells us when a selection is shown, so the next tap
 * only removes it instead of turning the page.
 */
@Stable
internal class ReaderSelectionState(
    private val platformToolbar: TextToolbar
) : TextToolbar {

    /** Selection containers are keyed by this value; bumping it drops the selection. */
    var resetKey by mutableIntStateOf(0)
        private set

    private var toolbarShown = false
    private var selectedAtGestureStart = false

    val hasSelection: Boolean get() = toolbarShown

    override val status: TextToolbarStatus get() = platformToolbar.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        toolbarShown = true
        platformToolbar.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        toolbarShown = false
        platformToolbar.hide()
    }

    /**
     * Remembers whether a selection existed when the finger went down. The
     * selection itself may react to the same tap before the reader sees it.
     */
    fun onGestureStart() {
        selectedAtGestureStart = toolbarShown
    }

    /** Clears the selection and returns true if this tap belonged to it. */
    fun dismissOnTap(): Boolean {
        if (!selectedAtGestureStart && !toolbarShown) return false
        clear()
        return true
    }

    fun clear() {
        selectedAtGestureStart = false
        if (toolbarShown) hide()
        resetKey++
    }
}

/** Records the selection state at the start of every gesture, without consuming it. */
internal fun Modifier.trackSelectionGestures(selection: ReaderSelectionState): Modifier =
    pointerInput(selection) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            selection.onGestureStart()
        }
    }
