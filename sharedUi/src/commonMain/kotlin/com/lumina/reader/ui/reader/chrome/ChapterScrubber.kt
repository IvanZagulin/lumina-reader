package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.lumina.reader.ui.theme.rememberLuminaHaptics
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.roundToInt

/** What the drag bubble shows for a position. */
@Immutable
data class ScrubberLabel(
    val title: String,
    val detail: String
)

/** Chapter ticks are drawn only for books with at most this many chapters. */
const val MAX_SCRUBBER_TICKS = 60

/**
 * Where each chapter starts as a fraction of the book text. Chapters without
 * text share the book evenly when the whole book has none.
 */
fun chapterStartFractions(chapterLengths: IntArray): FloatArray {
    val count = chapterLengths.size
    if (count == 0) return FloatArray(0)
    val total = chapterLengths.sumOf { it.coerceAtLeast(0).toLong() }
    if (total <= 0L) return FloatArray(count) { it.toFloat() / count }
    var before = 0L
    return FloatArray(count) { index ->
        val start = before.toFloat() / total
        before += chapterLengths[index].coerceAtLeast(0)
        start
    }
}

/** The chapter that contains [fraction] of the book. */
fun chapterAtFraction(starts: FloatArray, fraction: Float): Int {
    if (starts.isEmpty()) return 0
    var low = 0
    var high = starts.lastIndex
    var result = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (starts[mid] <= fraction) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** «42 процента, глава 7» for TalkBack. */
fun scrubberStateDescription(fraction: Float, chapterNumber: Int): String {
    val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
    return "${percent} ${percentWord(percent)}, глава $chapterNumber"
}

fun percentWord(value: Int): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> "процентов"
        mod10 == 1 -> "процент"
        mod10 in 2..4 -> "процента"
        else -> "процентов"
    }
}

/**
 * Book-level slider of the bottom bar (§4.2): a 4dp track, chapter ticks, a
 * ringed thumb and a bubble with the chapter while dragging. Positions are
 * fractions of the book; [onJump] fires when the finger lifts.
 */
@Composable
fun ChapterScrubber(
    value: Float,
    chapterStarts: FloatArray,
    colors: ReaderChromeColors,
    labelFor: (Float) -> ScrubberLabel,
    stateDescription: String,
    onJump: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberLuminaHaptics()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val latestValue by rememberUpdatedState(value)
    val latestJump by rememberUpdatedState(onJump)
    val showTicks = chapterStarts.size in 2..MAX_SCRUBBER_TICKS

    Box(
        modifier = modifier
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                contentDescription = "Положение в книге"
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress(label = "Перейти") { target ->
                    latestJump(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(chapterStarts) {
                val inset = 10.dp.toPx()
                fun fractionAt(x: Float): Float {
                    val width = (size.width - 2 * inset).coerceAtLeast(1f)
                    return ((x - inset) / width).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var current = fractionAt(down.position.x)
                    var chapter = chapterAtFraction(chapterStarts, current)
                    dragFraction = current
                    var lifted = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            lifted = true
                            break
                        }
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            current = fractionAt(change.position.x)
                            dragFraction = current
                            val nowChapter = chapterAtFraction(chapterStarts, current)
                            if (nowChapter != chapter) {
                                chapter = nowChapter
                                if (showTicks) haptics.clock()
                            }
                        }
                    }
                    dragFraction = null
                    if (lifted) latestJump(current)
                }
            }
            .drawBehind {
                val inset = 10.dp.toPx()
                val trackHeight = 4.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(1f)
                val centerY = size.height / 2f
                val fraction = (dragFraction ?: latestValue).coerceIn(0f, 1f)
                val x = inset + width * fraction
                drawRoundRect(
                    color = colors.content.copy(alpha = 0.16f),
                    topLeft = Offset(inset, centerY - trackHeight / 2f),
                    size = Size(width, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f)
                )
                drawRoundRect(
                    color = colors.accent,
                    topLeft = Offset(inset, centerY - trackHeight / 2f),
                    size = Size(x - inset, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f)
                )
                if (showTicks) {
                    val tickWidth = 2.dp.toPx()
                    val tickHeight = 6.dp.toPx()
                    for (i in 1 until chapterStarts.size) {
                        val tickX = inset + width * chapterStarts[i]
                        drawRect(
                            color = colors.content.copy(alpha = 0.30f),
                            topLeft = Offset(tickX - tickWidth / 2f, centerY - tickHeight / 2f),
                            size = Size(tickWidth, tickHeight)
                        )
                    }
                }
                val radius = 10.dp.toPx()
                drawCircle(color = colors.surface, radius = radius, center = Offset(x, centerY))
                drawCircle(
                    color = colors.accent,
                    radius = radius - 1.5.dp.toPx(),
                    center = Offset(x, centerY),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
    ) {
        val dragging = dragFraction
        if (dragging != null) {
            val label = labelFor(dragging)
            val inset = with(density) { 10.dp.toPx() }
            val thumbX = (inset + (widthPx - 2 * inset).coerceAtLeast(1f) * dragging).roundToInt()
            val provider = remember(thumbX) { BubblePositionProvider(thumbX) }
            Popup(popupPositionProvider = provider) {
                ReaderInverseCapsule(colors = colors, shape = LuminaShape.Card) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inverseContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = label.detail,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp,
                            color = colors.inverseContent.copy(alpha = 0.72f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Places the drag bubble centred over the thumb, 8dp above the scrubber, inside the window. */
private class BubblePositionProvider(private val thumbX: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val gap = 16
        val x = (anchorBounds.left + thumbX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
