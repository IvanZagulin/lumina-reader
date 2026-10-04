package com.lumina.reader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/** Where a selection lies in the book: one paragraph, plain-text offsets. */
data class SelectionLocation(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * What the selection menu and the highlight menu do, implemented by the
 * reader screen. Colours are palette hex values (HighlightPalette).
 */
class ReaderSelectionActions(
    val onHighlight: (text: String, location: SelectionLocation, colorHex: String) -> Unit,
    val onNote: (text: String, location: SelectionLocation, colorHex: String) -> Unit,
    val onShare: (text: String) -> Unit,
    val onAskAi: (text: String) -> Unit,
    val onFind: (text: String) -> Unit,
    val onRecolorHighlight: (highlightId: Long, colorHex: String) -> Unit,
    val onEditHighlightNote: (highlightId: Long) -> Unit,
    val onDeleteHighlight: (highlightId: Long) -> Unit
)

/** Selected text read back from the selection, and where it is if it was found. */
data class CapturedSelection(
    val text: String,
    val location: SelectionLocation?
)

/** The paragraphs a viewer currently shows, for locating a selection. */
data class VisibleText(
    val chapterIndex: Int,
    val paragraphs: List<VisibleParagraphText>
)

/**
 * The reader's text toolbar.
 *
 * Compose 1.7's SelectionContainer does not expose the selected range. It
 * only hands the toolbar a "copy" callback, so capturing a selection copies
 * it, reads the clipboard through [SelectionClipboard] (which restores the
 * user's previous clipboard where the platform allows) and finds the text
 * among the paragraphs on screen.
 *
 * The state also keeps a selection dismissible: the next tap after a
 * selection only clears it instead of turning the page.
 */
@Stable
class ReaderSelectionState(
    private val platformToolbar: TextToolbar,
    private val clipboard: SelectionClipboard?
) : TextToolbar {

    /** Selection containers are keyed by this value; bumping it drops the selection. */
    var resetKey by mutableIntStateOf(0)
        private set

    /** Bounds of the current selection in root coordinates, while a menu should show. */
    var menuAnchor by mutableStateOf<Rect?>(null)
        private set

    /** Set by the active viewer: the paragraphs it shows right now. */
    var visibleTextProvider: (() -> VisibleText?)? = null

    private var copyAction: (() -> Unit)? = null
    private var selectedAtGestureStart = false

    val hasSelection: Boolean get() = menuAnchor != null

    override val status: TextToolbarStatus
        get() = if (menuAnchor != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        copyAction = onCopyRequested
        menuAnchor = rect
    }

    override fun hide() {
        menuAnchor = null
        copyAction = null
        platformToolbar.hide()
    }

    /** Copies the selection to the clipboard like the system "Copy" action. */
    fun copySelection() {
        val copy = copyAction
        copy?.invoke()
        clear()
    }

    /**
     * Reads the selected text without leaving it on the clipboard and locates
     * it on screen. Clears the selection.
     */
    fun captureSelection(): CapturedSelection? {
        val copy = copyAction ?: return null
        val text = clipboard?.readThrough(copy)
        clear()
        if (text.isNullOrBlank()) return null
        val visible = visibleTextProvider?.invoke()
        val location = visible?.let { shown ->
            locateSelection(text, shown.paragraphs)?.let { range ->
                SelectionLocation(shown.chapterIndex, range.paragraphIndex, range.start, range.end)
            }
        }
        return CapturedSelection(text, location)
    }

    /**
     * Remembers whether a selection existed when the finger went down. The
     * selection itself may react to the same tap before the reader sees it.
     */
    fun onGestureStart() {
        selectedAtGestureStart = menuAnchor != null
    }

    /** Clears the selection and returns true if this tap belonged to it. */
    fun dismissOnTap(): Boolean {
        if (!selectedAtGestureStart && menuAnchor == null) return false
        clear()
        return true
    }

    fun clear() {
        selectedAtGestureStart = false
        if (menuAnchor != null || copyAction != null) hide()
        resetKey++
    }
}

/** Records the selection state at the start of every gesture, without consuming it. */
fun Modifier.trackSelectionGestures(selection: ReaderSelectionState): Modifier =
    pointerInput(selection) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            selection.onGestureStart()
        }
    }
