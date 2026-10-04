package com.lumina.reader.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.roundToInt

/**
 * The four top-level destinations of the dock (spec §3.3). The routes are
 * the Android NavHost's (`Screen.Library.route` and so on, also listed in
 * `Screen.TopLevelRoutes`), spelled out so the dock does not depend on the
 * Android navigation graph.
 */
enum class DockDestination(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "Полка", Icons.Rounded.AutoStories),
    CATALOG("catalog", "Каталоги", Icons.Rounded.TravelExplore),
    ASSISTANT("ai_chat", "Помощник", Icons.Rounded.AutoAwesome),
    STATS("stats", "Статистика", Icons.Rounded.Insights);

    companion object {
        fun forRoute(route: String?): DockDestination? = entries.firstOrNull { it.route == route }
    }
}

private val MinItemWidth = 64.dp
private val MaxItemWidth = 76.dp
/** Screen margins (2 × 12 dp), the gap and the «+» circle next to the pill. */
private val DockFixedWidth = 24.dp + 8.dp + 56.dp

/** Item width that fills a phone's width so the labels never touch each other. */
@Composable
private fun rememberDockItemWidth(itemCount: Int): Dp {
    val screenWidth = shellScreenWidthDp().dp
    return remember(screenWidth, itemCount) {
        ((screenWidth - DockFixedWidth) / itemCount.coerceAtLeast(1)).coerceIn(MinItemWidth, MaxItemWidth)
    }
}
private val ExpandedHeight = 64.dp
private val CollapsedHeight = 52.dp
private val DockShadow = Color(0x4D2A1608)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The floating dock (spec §3.3): a pill with «Полка», «Каталоги», «Помощник»,
 * «Статистика» and a separate «+» circle that adds a book from a file. The
 * selected item sits on a `primaryContainer` pill that slides with
 * `snappy()`. [collapse] (0..1, from [DockScrollState]) shrinks the capsule
 * to 52 dp and fades the labels; it is read only in layout/draw lambdas.
 * [destinations] are the items shown, in order: all four on Android; a
 * platform whose catalogue, assistant or statistics screens are not there
 * yet shows only the ones it has.
 */
@Composable
fun LuminaDock(
    selected: DockDestination?,
    onSelect: (DockDestination) -> Unit,
    onAddBook: () -> Unit,
    collapse: () -> Float,
    libraryBadge: Int,
    modifier: Modifier = Modifier,
    destinations: List<DockDestination> = DockDestination.entries
) {
    val scheme = MaterialTheme.colorScheme
    val selectedIndex = if (selected == null) -1 else destinations.indexOf(selected)
    val indicatorIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = LuminaMotion.snappy(),
        label = "dock-indicator"
    )
    val indicatorColor = scheme.primaryContainer
    val showIndicator = selectedIndex >= 0
    val itemWidth = rememberDockItemWidth(destinations.size)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val height = lerp(ExpandedHeight.toPx(), CollapsedHeight.toPx(), collapse()).roundToInt()
                    val width = (itemWidth * destinations.size).roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }
                .shadow(8.dp, LuminaShape.Pill, clip = false, ambientColor = DockShadow, spotColor = DockShadow)
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.96f), LuminaShape.Pill)
                .border(1.dp, scheme.outlineVariant, LuminaShape.Pill)
                .clip(LuminaShape.Pill)
                .drawBehind {
                    if (!showIndicator) return@drawBehind
                    val item = itemWidth.toPx()
                    val pillWidth = 56.dp.toPx()
                    val pillHeight = 32.dp.toPx()
                    val iconCenter = lerp(23.dp.toPx(), size.height / 2f, collapse())
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(indicatorIndex * item + (item - pillWidth) / 2f, iconCenter - pillHeight / 2f),
                        size = Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2f)
                    )
                }
                .selectableGroup()
        ) {
            Row {
                destinations.forEach { destination ->
                    DockItem(
                        destination = destination,
                        width = itemWidth,
                        selected = destination == selected,
                        badge = if (destination == DockDestination.LIBRARY) libraryBadge else 0,
                        collapse = collapse,
                        onClick = { onSelect(destination) }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = DockShadow, spotColor = DockShadow)
                .background(scheme.primary, CircleShape)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAddBook)
                .semantics { contentDescription = "Добавить книгу из файла" },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onPrimary)
        }
    }
}

@Composable
private fun DockItem(
    destination: DockDestination,
    width: Dp,
    selected: Boolean,
    badge: Int,
    collapse: () -> Float,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val labelColor = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    val badgeScale = animateFloatAsState(
        targetValue = if (badge > 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "dock-badge"
    )
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
            .semantics {
                contentDescription = if (badge > 0) "${destination.label}, новых книг: $badge" else destination.label
            }
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val top = lerp(11.dp.toPx(), 14.dp.toPx(), collapse())
                    IntOffset(0, top.roundToInt())
                }
                .size(24.dp)
        )
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .offset { IntOffset(0, 38.dp.roundToPx()) }
                .graphicsLayer { alpha = 1f - collapse() }
                .clearAndSetSemantics { }
        )
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(14.dp.roundToPx(), 5.dp.roundToPx()) }
                    .graphicsLayer {
                        scaleX = badgeScale.value
                        scaleY = badgeScale.value
                    }
                    .size(16.dp)
                    .background(scheme.primary, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9+" else "+$badge",
                    color = scheme.onPrimary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
