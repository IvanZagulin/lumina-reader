package com.lumina.reader.ui.reader.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaMotion

/** Beyond this many entries only the path to the current chapter starts expanded (§7.4). */
const val TOC_COLLAPSE_THRESHOLD = 60

/** True when entry [index] has nested entries right after it. */
fun tocHasChildren(items: List<TocItem>, index: Int): Boolean =
    index + 1 < items.size && items[index + 1].level > items[index].level

/**
 * The entry for the reader's position: the last one that starts at or
 * before ([chapterIndex], [paragraphIndex]); -1 when the position lies
 * before every entry.
 */
fun currentTocIndex(items: List<TocItem>, chapterIndex: Int, paragraphIndex: Int): Int {
    var result = -1
    items.forEachIndexed { index, item ->
        val starts = item.chapterIndex < chapterIndex ||
            (item.chapterIndex == chapterIndex && item.paragraphIndex <= paragraphIndex)
        if (starts) result = index
    }
    return result
}

/** Indices of the entries that contain entry [index] (its parents, outermost first). */
fun tocAncestors(items: List<TocItem>, index: Int): List<Int> {
    if (index !in items.indices) return emptyList()
    val result = ArrayList<Int>()
    var level = items[index].level
    for (i in index - 1 downTo 0) {
        if (items[i].level < level) {
            result += i
            level = items[i].level
        }
    }
    return result.reversed()
}

/** Expanded entries when the sheet opens: everything, or only the path to [current] for long lists. */
fun defaultTocExpanded(items: List<TocItem>, current: Int): Set<Int> =
    if (items.size <= TOC_COLLAPSE_THRESHOLD) {
        items.indices.filter { tocHasChildren(items, it) }.toSet()
    } else {
        tocAncestors(items, current).toSet()
    }

/** Indices shown when entries outside [expanded] hide their children. */
fun visibleTocIndices(items: List<TocItem>, expanded: Set<Int>): List<Int> {
    val result = ArrayList<Int>(items.size)
    var hiddenBelowLevel = Int.MAX_VALUE
    items.forEachIndexed { index, item ->
        if (item.level > hiddenBelowLevel) return@forEachIndexed
        hiddenBelowLevel = Int.MAX_VALUE
        result += index
        if (tocHasChildren(items, index) && index !in expanded) hiddenBelowLevel = item.level
    }
    return result
}

/**
 * «Оглавление» (§7.4): rows of at least 48dp indented by level (capped at
 * four), guide lines for nesting, collapsible parents, the current entry
 * marked «Вы здесь» and scrolled into view, entries already read dimmed.
 */
@Composable
fun TocList(
    items: List<TocItem>,
    currentIndex: Int,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    onItemClick: (TocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(items) { mutableStateOf(defaultTocExpanded(items, currentIndex)) }
    val visible = remember(items, expanded) { visibleTocIndices(items, expanded) }
    val listState = rememberLazyListState()
    LaunchedEffect(items) {
        val position = visible.indexOf(currentIndex)
        if (position >= 0) {
            val target = (position - 2).coerceAtLeast(0)
            if (reducedMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(visible, key = { index -> "toc_$index" }) { index ->
            val item = items[index]
            TocRow(
                item = item,
                isCurrent = index == currentIndex,
                isRead = currentIndex >= 0 && index < currentIndex,
                hasChildren = tocHasChildren(items, index),
                isExpanded = index in expanded,
                colors = colors,
                onClick = { onItemClick(item) },
                onToggle = {
                    expanded = if (index in expanded) expanded - index else expanded + index
                }
            )
        }
    }
}

@Composable
private fun TocRow(
    item: TocItem,
    isCurrent: Boolean,
    isRead: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val level = item.level.coerceIn(0, 4)
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = LuminaMotion.snappy(),
        label = "tocChevron"
    )
    val guide = colors.content.copy(alpha = 0.10f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) colors.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { selected = isCurrent }
            .drawBehind {
                // Nesting guides, one hairline per level.
                for (l in 1..level) {
                    val x = (16.dp.toPx() * l) - 4.dp.toPx()
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                if (isCurrent) {
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(12.dp + 16.dp * level))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .graphicsLayer { alpha = if (isRead && !isCurrent) 0.6f else 1f }
        ) {
            Text(
                text = item.title,
                fontSize = when (level) {
                    0 -> 15.sp
                    1 -> 14.sp
                    else -> 13.sp
                },
                fontWeight = if (level == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isCurrent -> colors.content
                    level >= 2 -> colors.muted
                    else -> colors.content
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (isCurrent) {
                Text(
                    text = "Вы здесь",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent
                )
            }
        }
        if (hasChildren) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                    tint = colors.muted,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevron }
                )
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

/** Typography of empty states in the navigation sheet. */
@Composable
fun NavigationEmptyState(text: String, colors: ReaderChromeColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
