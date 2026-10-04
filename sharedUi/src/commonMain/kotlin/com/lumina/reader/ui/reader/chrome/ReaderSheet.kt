package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaShape

/** The 36×4dp drag handle of reader sheets, in the text colour at 20 %. */
@Composable
fun ReaderSheetHandle(colors: ReaderChromeColors) {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(colors.content.copy(alpha = 0.20f), RoundedCornerShape(2.dp))
            .clearAndSetSemantics { }
    )
}

/**
 * A bottom sheet in reader colours (§7.3, §7.4): never the app surface, so
 * the page underneath and the sheet read as one object. Stock components
 * inside take the reader colour scheme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderModalSheet(
    colors: ReaderChromeColors,
    onDismiss: () -> Unit,
    scrimAlpha: Float = 0.15f,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.content,
        scrimColor = Color.Black.copy(alpha = scrimAlpha),
        shape = LuminaShape.Sheet,
        tonalElevation = 0.dp,
        dragHandle = { ReaderSheetHandle(colors) }
    ) {
        ReaderMaterialTheme(colors) {
            // Dragging a list past its END hands the leftover to the sheet, which tries to
            // move, snaps back and moves again: the contents jump up and down. The leftover
            // of a scroll toward the end stops here. Scrolling toward the start is left
            // alone, so a swipe down from the top of a list still closes the sheet.
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.nestedScroll(KeepEndOfListInSheet),
                content = content
            )
        }
    }
}

/**
 * Swallows what a list could not scroll when the finger moves up (toward the end
 * of the list), so it never reaches the sheet's own drag; see [ReaderModalSheet].
 */
private val KeepEndOfListInSheet = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}
