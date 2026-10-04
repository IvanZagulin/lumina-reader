package com.lumina.reader.ui.reader.selection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.HighlightPalette
import com.lumina.reader.ui.theme.LuminaMotion
import kotlin.math.roundToInt

/**
 * Where the selection menu goes (§7.5): [gap] above the selection, or below
 * it when there is less than its height plus [minTop] above; always at
 * least [margin] from the container edges.
 */
fun selectionMenuPosition(
    anchor: Rect,
    menu: IntSize,
    container: IntSize,
    margin: Int,
    gap: Int,
    minTop: Int
): IntOffset {
    val maxX = (container.width - menu.width - margin).coerceAtLeast(margin)
    val x = (anchor.center.x - menu.width / 2f).roundToInt().coerceIn(margin, maxX)
    val above = anchor.top - gap - menu.height
    val y = if (anchor.top >= menu.height + minTop) {
        above.roundToInt()
    } else {
        (anchor.bottom + gap).roundToInt()
    }
    val maxY = (container.height - menu.height - margin).coerceAtLeast(margin)
    return IntOffset(x, y.coerceIn(margin, maxY))
}

/** An action of the second row of the menu. */
class SelectionMenuAction(
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The selection menu (§7.5): a two-row inverse card. Row 1: five highlight
 * colours and «Заметка»; row 2: text actions. It sits inside a full-size
 * overlay of the reading area and places itself next to [anchor] (in the
 * overlay's coordinates).
 */
@Composable
fun SelectionMenu(
    anchor: Rect,
    colors: ReaderChromeColors,
    selectedColorHex: String?,
    noteLabel: String,
    actions: List<SelectionMenuAction>,
    reducedMotion: Boolean,
    onColor: (HighlightPalette.Swatch) -> Unit,
    onNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appear = remember(anchor) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(anchor) {
        if (!reducedMotion) {
            appear.animateTo(1f, tween(durationMillis = 140, easing = LuminaMotion.EmphasizedDecelerate))
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val position = selectionMenuPosition(
                        anchor = anchor,
                        menu = IntSize(placeable.width, placeable.height),
                        container = IntSize(constraints.maxWidth, constraints.maxHeight),
                        margin = 8.dp.roundToPx(),
                        gap = 12.dp.roundToPx(),
                        minTop = 56.dp.roundToPx()
                    )
                    placeable.place(position)
                }
            }
    ) {
        Surface(
            color = colors.inverseBg,
            contentColor = colors.inverseContent,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
        ) {
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Row(
                    modifier = Modifier.height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HighlightPalette.all.forEach { swatch ->
                        ColorDot(
                            swatch = swatch,
                            selected = selectedColorHex != null && swatch == HighlightPalette.fromHex(selectedColorHex),
                            ringColor = colors.inverseContent,
                            onClick = { onColor(swatch) }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(24.dp)
                            .background(colors.inverseContent.copy(alpha = 0.20f))
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = noteLabel, onClick = onNote),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = noteLabel,
                            tint = colors.inverseContent
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions.forEach { action ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClick = action.onClick)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = action.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (action.isDestructive) colors.error else colors.inverseContent,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(
    swatch: HighlightPalette.Swatch,
    selected: Boolean,
    ringColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Выделить: ${swatch.label}"
                role = Role.RadioButton
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .then(
                    if (selected) Modifier.border(2.dp, ringColor, CircleShape).padding(3.dp) else Modifier
                )
                .clip(CircleShape)
                .background(swatch.color),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color(0xFF2A211B),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
