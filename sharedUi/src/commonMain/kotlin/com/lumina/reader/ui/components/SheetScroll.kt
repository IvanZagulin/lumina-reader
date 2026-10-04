package com.lumina.reader.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a list inside a bottom sheet from dragging the sheet.
 *
 * Whatever a list cannot scroll any more — at its end, or at its top — is handed
 * to the surrounding ModalBottomSheet, which tries to move, snaps back and moves
 * again, so the contents jump up and down. Here that leftover is absorbed. The
 * sheet still closes by its handle, by a swipe on its header or by a tap on the
 * scrim; only the list stops pulling it.
 */
fun Modifier.absorbListOverscroll(): Modifier = nestedScroll(AbsorbLeftovers)

private val AbsorbLeftovers = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}
