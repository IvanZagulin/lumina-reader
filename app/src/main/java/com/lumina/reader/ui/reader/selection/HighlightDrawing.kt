package com.lumina.reader.ui.reader.selection

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.reader.HighlightSpan
import com.lumina.reader.ui.reader.OffsetRange
import com.lumina.reader.ui.theme.HighlightPalette

/**
 * What is drawn behind one paragraph: saved highlights, the search match the
 * reader jumped to and the sentence being read aloud.
 */
@Immutable
internal data class ParagraphMarks(
    val highlights: List<HighlightSpan> = emptyList(),
    val searchMatch: OffsetRange? = null,
    val ttsSentence: OffsetRange? = null
) {
    val isEmpty: Boolean get() = highlights.isEmpty() && searchMatch == null && ttsSentence == null

    companion object {
        val None = ParagraphMarks()
    }
}

/** Colours of the marks for the current reading theme. */
@Immutable
internal data class MarkColors(
    val isDarkTheme: Boolean,
    val accent: Color
) {
    /** Highlight fill alpha of the palette: 0.38 on light pages, 0.28 on dark ones. */
    val highlightAlpha: Float get() = HighlightPalette.fillAlpha(isDarkTheme)
    val ttsFill: Color get() = accent.copy(alpha = 0.22f)
}

/**
 * Line boxes covering [start, end) of [layout], one per line, slightly
 * narrower than the line height so neighbouring lines do not merge.
 */
internal fun markRects(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
    val length = layout.layoutInput.text.length
    val from = start.coerceIn(0, length)
    val to = end.coerceIn(from, length)
    if (to <= from || layout.lineCount == 0) return emptyList()
    val firstLine = layout.getLineForOffset(from)
    val lastLine = layout.getLineForOffset(to - 1)
    val rects = ArrayList<Rect>(lastLine - firstLine + 1)
    for (line in firstLine..lastLine) {
        val lineStart = maxOf(from, layout.getLineStart(line))
        val lineEnd = minOf(to, layout.getLineEnd(line, visibleEnd = true))
        if (lineEnd <= lineStart) continue
        val first = layout.getBoundingBox(lineStart)
        val last = layout.getBoundingBox(lineEnd - 1)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val inset = (bottom - top) * 0.06f
        rects += Rect(
            left = minOf(first.left, last.left),
            top = top + inset,
            right = maxOf(first.right, last.right),
            bottom = bottom - inset
        )
    }
    return rects
}

/**
 * Draws [marks] behind the text whose layout [layout] returns. Must come
 * after any modifier that shifts the text (the page slice window) so the
 * coordinates match the layout. Rectangles are computed once per layout;
 * [searchAlpha] is read only while drawing, so the search flash does not
 * recompose anything.
 */
internal fun Modifier.paragraphMarks(
    marks: ParagraphMarks,
    colors: MarkColors,
    layout: () -> TextLayoutResult?,
    searchAlpha: () -> Float
): Modifier {
    if (marks.isEmpty) return this
    return drawWithCache {
        val result = layout()
        if (result == null) {
            onDrawBehind { }
        } else {
            val radius = CornerRadius(3.dp.toPx())
            val tts = marks.ttsSentence?.let { markRects(result, it.start, it.end) }.orEmpty()
            val highlights = marks.highlights.map { span ->
                span.color.copy(alpha = colors.highlightAlpha) to markRects(result, span.start, span.end)
            }
            val noteDots = marks.highlights
                .filter { it.hasNote }
                .mapNotNull { span -> markRects(result, span.start, span.end).lastOrNull() }
            val search = marks.searchMatch?.let { markRects(result, it.start, it.end) }.orEmpty()
            val dotRadius = 3.dp.toPx()
            val ttsFill = colors.ttsFill
            onDrawBehind {
                for (rect in tts) {
                    drawRoundRect(ttsFill, rect.topLeft, rect.size, radius)
                }
                for ((color, rects) in highlights) {
                    for (rect in rects) drawRoundRect(color, rect.topLeft, rect.size, radius)
                }
                if (search.isNotEmpty()) {
                    val searchFill = colors.accent.copy(alpha = searchAlpha().coerceIn(0f, 1f))
                    for (rect in search) drawRoundRect(searchFill, rect.topLeft, rect.size, radius)
                }
                for (rect in noteDots) {
                    val x = minOf(rect.right + dotRadius, size.width - dotRadius)
                    drawCircle(colors.accent, dotRadius, Offset(x, rect.top + dotRadius))
                }
            }
        }
    }
}
