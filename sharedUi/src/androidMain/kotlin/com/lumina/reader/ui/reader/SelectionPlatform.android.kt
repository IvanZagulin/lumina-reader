package com.lumina.reader.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSelectionClipboard(): SelectionClipboard? {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.let(::AndroidSelectionClipboard)
    }
}

/**
 * Saves the user's clip, lets the selection copy itself, reads the copy and
 * restores the saved clip (or clears the copy when there was none, API 28+).
 */
private class AndroidSelectionClipboard(private val manager: ClipboardManager) : SelectionClipboard {

    override fun readThrough(copy: () -> Unit): String? {
        val previous: ClipData? = try {
            manager.primaryClip
        } catch (error: SecurityException) {
            null
        }
        copy()
        val copied = try {
            manager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        } catch (error: SecurityException) {
            null
        }
        try {
            if (previous != null) {
                manager.setPrimaryClip(previous)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            }
        } catch (error: Exception) {
            // Restoring is best effort; the selection itself was read.
        }
        return copied
    }
}
