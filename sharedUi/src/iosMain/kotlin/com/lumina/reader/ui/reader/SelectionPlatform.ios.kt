package com.lumina.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberSelectionClipboard(): SelectionClipboard? = remember { IosSelectionClipboard() }

/**
 * Reads the selection through the general pasteboard.
 *
 * Unlike Android, the user's previous items are not saved and restored:
 * reading content another app put on the pasteboard shows the iOS 16+
 * "Allow Paste" alert, which would pop up on every highlight. Text this app
 * wrote itself is read silently, so only the copy is read, and the
 * pasteboard is cleared afterwards so the quote does not stay behind (as on
 * Android when there was nothing to restore). [UIPasteboard.changeCount]
 * tells whether the copy wrote anything at all.
 */
private class IosSelectionClipboard : SelectionClipboard {

    override fun readThrough(copy: () -> Unit): String? {
        val pasteboard = UIPasteboard.generalPasteboard
        val before = pasteboard.changeCount
        copy()
        if (pasteboard.changeCount == before) return null
        val copied = pasteboard.string
        pasteboard.items = emptyList<Any>()
        return copied
    }
}
