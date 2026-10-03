package com.lumina.reader.ui.reader.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors

/**
 * Up to four equal segments, each a 24dp icon drawn on a canvas plus a tiny
 * label (§4.2). Generic over the option type.
 */
@Composable
internal fun <T> IconSegmentRow(
    options: List<T>,
    selected: T,
    colors: ReaderChromeColors,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    drawIcon: DrawScope.(option: T, color: Color) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(16.dp))
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val tint = if (isSelected) colors.accent else colors.content.copy(alpha = 0.72f)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(if (isSelected) colors.selectedBg else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(modifier = Modifier.size(24.dp)) { drawIcon(option, tint) }
                Text(
                    text = label(option),
                    fontSize = 10.sp,
                    color = if (isSelected) colors.accent else colors.muted,
                    maxLines = 1
                )
            }
        }
    }
}

/** Three text lines whose gap grows with [spacing] (1.2 … 2.0). */
internal fun DrawScope.drawLineSpacingIcon(spacing: Float, color: Color) {
    val stroke = 2.dp.toPx()
    val gap = size.height * 0.16f * spacing
    val total = 2 * gap
    val top = (size.height - total) / 2f
    for (i in 0 until 3) {
        val y = top + i * gap
        drawLine(color, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), stroke)
    }
}

/** A page outline with text lines inset by [margin] (12 … 44 dp). */
internal fun DrawScope.drawMarginIcon(margin: Int, color: Color) {
    val stroke = 1.5.dp.toPx()
    val pageLeft = size.width * 0.18f
    val pageWidth = size.width * 0.64f
    drawRect(
        color = color,
        topLeft = Offset(pageLeft, size.height * 0.08f),
        size = Size(pageWidth, size.height * 0.84f),
        style = Stroke(width = stroke)
    )
    val inset = pageWidth * (0.08f + 0.22f * ((margin - 12).coerceIn(0, 32) / 32f))
    for (i in 0 until 3) {
        val y = size.height * (0.32f + i * 0.18f)
        drawLine(color, Offset(pageLeft + inset, y), Offset(pageLeft + pageWidth - inset, y), stroke)
    }
}
