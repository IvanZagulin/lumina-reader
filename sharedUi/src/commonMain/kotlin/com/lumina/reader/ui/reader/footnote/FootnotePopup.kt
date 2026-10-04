package com.lumina.reader.ui.reader.footnote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.PlatformBackHandler
import com.lumina.reader.ui.reader.ReaderTextColors
import com.lumina.reader.ui.reader.ReaderTypography
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.renderParagraph
import com.lumina.reader.ui.theme.LuminaMotion
import kotlin.math.roundToInt

/** Where the footnote card goes and whether its caret points up (card below the marker). */
data class FootnotePlacement(
    val offset: IntOffset,
    val below: Boolean,
    /** Caret x inside the card. */
    val caretX: Int
)

/**
 * The card prefers the space under the marker; when that is too small it
 * goes above. It stays [margin] inside the container.
 */
fun footnotePlacement(
    anchor: Offset,
    card: IntSize,
    container: IntSize,
    margin: Int,
    gap: Int
): FootnotePlacement {
    val maxX = (container.width - card.width - margin).coerceAtLeast(margin)
    val x = (anchor.x - card.width / 2f).roundToInt().coerceIn(margin, maxX)
    val spaceBelow = container.height - anchor.y - gap - margin
    val below = spaceBelow >= card.height || anchor.y < container.height / 2f
    val rawY = if (below) anchor.y + gap else anchor.y - gap - card.height
    val maxY = (container.height - card.height - margin).coerceAtLeast(margin)
    val y = rawY.roundToInt().coerceIn(margin, maxY)
    val caretX = (anchor.x - x).roundToInt().coerceIn(16, (card.width - 16).coerceAtLeast(16))
    return FootnotePlacement(IntOffset(x, y), below, caretX)
}

/**
 * Footnote popup (§7.6): an anchored card in the reader surface colour with
 * a caret, the note rendered with the book's markup in the reading font,
 * scrollable up to 40 % of the screen. A tap outside or Back closes it;
 * nothing underneath reacts to that tap (the page does not turn).
 */
@Composable
fun FootnotePopup(
    noteId: String,
    noteNumber: String,
    text: String?,
    anchor: Offset,
    colors: ReaderChromeColors,
    typography: ReaderTypography,
    reducedMotion: Boolean,
    onNoteClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)
    val appear = remember(noteId) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(noteId) {
        if (!reducedMotion) {
            appear.animateTo(1f, tween(durationMillis = 160, easing = LuminaMotion.EmphasizedDecelerate))
        }
    }
    val noteTypography = remember(typography) {
        typography.copy(
            fontSizeSp = maxOf(14, (typography.fontSizeSp * 0.9f).roundToInt()),
            firstLineIndentEm = 0f,
            bionic = false,
            justify = false
        )
    }
    val textColors = remember(colors) {
        ReaderTextColors(text = colors.content, noteRef = colors.accent, searchMatch = colors.accent)
    }
    val holder = remember { PlacementHolder() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .layout { measurable, constraints ->
                val maxHeight = (constraints.maxHeight * 0.4f).roundToInt()
                val maxWidth = minOf(320.dp.roundToPx(), constraints.maxWidth - 16.dp.roundToPx())
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxWidth.coerceAtLeast(0), maxHeight = maxHeight)
                )
                val place = footnotePlacement(
                    anchor = anchor,
                    card = IntSize(placeable.width, placeable.height),
                    container = IntSize(constraints.maxWidth, constraints.maxHeight),
                    margin = 8.dp.roundToPx(),
                    gap = 12.dp.roundToPx()
                )
                holder.value = place
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(place.offset)
                }
            }
    ) {
        Surface(
            color = colors.surface,
            contentColor = colors.content,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.content.copy(alpha = 0.12f)),
            shadowElevation = if (colors.isDark) 0.dp else 8.dp,
            modifier = Modifier
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = scaleX
                    val place = holder.value
                    transformOrigin = if (place == null || size.width <= 0f) {
                        TransformOrigin.Center
                    } else {
                        TransformOrigin(place.caretX / size.width, if (place.below) 0f else 1f)
                    }
                }
                .caret(colors) { holder.value }
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    // Taps inside the card stay in the card.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "ПРИМЕЧАНИЕ $noteNumber".trim(),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.muted
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (text == null) {
                    Text("Текст примечания не найден", color = colors.muted, fontSize = 14.sp)
                } else {
                    val paragraphs = remember(text) { text.split('\n').filter { it.isNotBlank() } }
                    paragraphs.forEachIndexed { index, raw ->
                        val rendered = remember(raw, noteTypography, textColors) {
                            renderParagraph(raw, noteTypography, textColors, onNoteClick = onNoteClick)
                        }
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        BasicText(text = rendered.text, style = rendered.style)
                    }
                }
            }
        }
    }
}

/** Placement computed during layout, read while drawing (not state: layout precedes drawing). */
private class PlacementHolder {
    var value: FootnotePlacement? = null
}

/** A 12×6dp caret on the card edge that faces the marker. */
private fun Modifier.caret(colors: ReaderChromeColors, placement: () -> FootnotePlacement?): Modifier =
    drawBehind {
        val place = placement() ?: return@drawBehind
        val half = 6.dp.toPx()
        val height = 6.dp.toPx()
        val x = place.caretX.toFloat()
        val path = Path().apply {
            if (place.below) {
                moveTo(x - half, 0f)
                lineTo(x, -height)
                lineTo(x + half, 0f)
            } else {
                moveTo(x - half, size.height)
                lineTo(x, size.height + height)
                lineTo(x + half, size.height)
            }
            close()
        }
        drawPath(path, colors.surface)
    }
