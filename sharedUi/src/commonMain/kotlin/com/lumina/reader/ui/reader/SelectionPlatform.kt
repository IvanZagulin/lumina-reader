package com.lumina.reader.ui.reader

import androidx.compose.runtime.Composable

/**
 * The system clipboard as the reader's selection needs it.
 *
 * Compose's SelectionContainer still does not expose the selected range; it
 * only hands the text toolbar a "copy" callback. The reader captures a
 * selection by running that copy and reading the clipboard back, and each
 * platform has its own rules for doing that without leaving the quote behind
 * or disturbing what the user had copied.
 */
fun interface SelectionClipboard {
    /**
     * Runs [copy] (the selection's own Copy, which writes the system
     * clipboard) and returns the copied text, or null when nothing could be
     * read. The clipboard is put back as far as the platform allows.
     */
    fun readThrough(copy: () -> Unit): String?
}

/**
 * The platform clipboard for [SelectionClipboard], or null when there is none
 * (the selection menu then reports that the text could not be read).
 */
@Composable
expect fun rememberSelectionClipboard(): SelectionClipboard?
