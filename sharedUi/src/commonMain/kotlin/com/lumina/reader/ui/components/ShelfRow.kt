package com.lumina.reader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.OnestFamily
import com.lumina.reader.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.collectLatest

/** Cover size on shelves: 96×144 on phones, 120×180 from 600 dp. */
@Immutable
data class ShelfBookSize(val width: Dp, val height: Dp)

@Composable
fun rememberShelfBookSize(): ShelfBookSize {
    val wide = screenWidthDp() >= 600
    return remember(wide) { if (wide) ShelfBookSize(120.dp, 180.dp) else ShelfBookSize(96.dp, 144.dp) }
}

/**
 * Width of the window in dp. Android: `Configuration.screenWidthDp` (as
 * before); iOS: the width of the Compose container.
 */
@Composable
internal expect fun screenWidthDp(): Int

/** Space above the covers inside a shelf row (room for the press lift). */
val ShelfTopPadding: Dp = 16.dp

/** Captions start below the plank and most of its shadow. */
private val CaptionGap: Dp = ShelfPlankMetrics.Total - 6.dp

/** Shelf rows show at most this many books, then an «Ещё N» card. */
const val SHELF_ROW_MAX_BOOKS = 14

/**
 * Columns of the «Шкаф» grid and the cover width that fits them:
 * `n = (W − 40 + 14) / (coverW + 14)`, at least 3; covers shrink when three
 * do not fit a very narrow screen.
 */
fun bookcaseLayout(screenWidthDp: Float, preferredCoverWidthDp: Float): Pair<Int, Float> {
    val gap = 14f
    val gutter = 40f
    val columns = ((screenWidthDp - gutter + gap) / (preferredCoverWidthDp + gap)).toInt().coerceAtLeast(3)
    val needed = columns * preferredCoverWidthDp + (columns - 1) * gap + gutter
    val width = if (needed <= screenWidthDp) {
        preferredCoverWidthDp
    } else {
        ((screenWidthDp - gutter - (columns - 1) * gap) / columns).coerceAtLeast(48f)
    }
    return columns to width
}

/** What a shelf header looks like. */
enum class ShelfHeaderStyle { PLAIN, SERIES, CUSTOM, COLLAPSIBLE }

/**
 * Shelf header (spec §4.1): name, «· count», «Все →»; series get a «СЕРИЯ» tag
 * and brass progress ticks; custom shelves a ⋯ menu (also on long press);
 * the «Прочитано» shelf a chevron that collapses it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    style: ShelfHeaderStyle = ShelfHeaderStyle.PLAIN,
    readCount: Int = 0,
    totalCount: Int = count,
    expanded: Boolean = true,
    onShowAll: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rowModifier = when {
        style == ShelfHeaderStyle.COLLAPSIBLE && onToggle != null -> Modifier.clickable(
            onClickLabel = if (expanded) "Свернуть полку" else "Развернуть полку",
            onClick = onToggle
        )
        style == ShelfHeaderStyle.CUSTOM && onRename != null -> Modifier.combinedClickable(
            onClick = { onShowAll?.invoke() },
            onLongClickLabel = "Изменить полку",
            onLongClick = { menuOpen = true }
        )
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(rowModifier)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (style == ShelfHeaderStyle.SERIES) {
                    Text(
                        text = "СЕРИЯ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.tertiaryContainer, LuminaShape.Pill)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics { heading() }
                )
                Text(
                    text = " · $count",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted
                )
            }
            if (style == ShelfHeaderStyle.SERIES && totalCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (totalCount <= 20) {
                        SeriesTicks(read = readCount, total = totalCount)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = "$readCount из $totalCount прочитано",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                }
            }
        }
        when {
            style == ShelfHeaderStyle.COLLAPSIBLE -> {
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier
                        .padding(12.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
            else -> {
                if (onShowAll != null) {
                    TextButton(onClick = onShowAll) {
                        Text("Все", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (style == ShelfHeaderStyle.CUSTOM && (onRename != null || onDelete != null)) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с полкой «$title»", tint = muted)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onRename != null) {
                                DropdownMenuItem(
                                    text = { Text("Переименовать") },
                                    onClick = {
                                        menuOpen = false
                                        onRename()
                                    }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Удалить полку", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** n ticks of 6×3 dp with 2 dp gaps; read ones in brass. */
@Composable
private fun SeriesTicks(read: Int, total: Int) {
    val brass = Lumina.colors.brass
    val rest = MaterialTheme.colorScheme.outlineVariant
    val tickWidth = 6.dp
    val gap = 2.dp
    Spacer(
        modifier = Modifier
            .size(width = tickWidth * total + gap * (total - 1).coerceAtLeast(0), height = 3.dp)
            .clearAndSetSemantics { }
            .drawBehind {
                val w = tickWidth.toPx()
                val g = gap.toPx()
                for (i in 0 until total) {
                    drawRoundRect(
                        color = if (i < read) brass else rest,
                        topLeft = Offset(i * (w + g), 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
            }
    )
}

/** Title (12/16, 2 lines) and author (11 sp, muted) under a cover. */
@Composable
fun BookCaption(book: ShelfBookUi, width: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(width)
            .clearAndSetSemantics { }
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = book.author,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A horizontally scrolling shelf (spec §4.1): a LazyRow of 3D books standing
 * on a plank, coverflow tilt and a subtle sway on fling (read in draw only),
 * optional captions, and an «Ещё N» card after [SHELF_ROW_MAX_BOOKS] books.
 */
@Composable
fun ShelfRow(
    sectionKey: String,
    books: List<ShelfBookUi>,
    size: ShelfBookSize,
    onOpen: (book: ShelfBookUi, slotKey: String) -> Unit,
    onLongPress: (ShelfBookUi) -> Unit,
    modifier: Modifier = Modifier,
    captions: Boolean = false,
    plateText: String? = null,
    showNewDot: Boolean = false,
    dimFinished: Boolean = false,
    dropNewArrivals: Boolean = false,
    onShowAll: () -> Unit = {}
) {
    val colors = Lumina.colors
    val rowState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val reducedMotion = rememberReducedMotion()
    val sway = remember { Animatable(0f) }
    val swayTarget = remember { mutableFloatStateOf(0f) }
    val swayConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                swayTarget.floatValue = (-consumed.x * 0.12f).coerceIn(-5f, 5f)
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                swayTarget.floatValue = 0f
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            sway.snapTo(0f)
        } else {
            snapshotFlow { swayTarget.floatValue }.collectLatest { target ->
                sway.animateTo(target, spring(dampingRatio = 0.45f, stiffness = 200f))
            }
        }
    }
    val swayValue: () -> Float = remember { { sway.value } }
    val visible = if (books.size > SHELF_ROW_MAX_BOOKS) books.take(SHELF_ROW_MAX_BOOKS) else books
    val more = books.size - visible.size

    // Books that arrive while the row is known drop onto the plank (spec §8 #5).
    val arrivals = rememberSaveable(saver = ShelfArrivals.Saver) { ShelfArrivals() }
    if (dropNewArrivals) arrivals.update(visible.map(ShelfBookUi::id), animate = !reducedMotion)
    val dropPx = with(LocalDensity.current) { 40.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shelfPlank(colors, ShelfTopPadding + size.height, sectionKey, plateText, textMeasurer, OnestFamily)
    ) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = ShelfTopPadding,
                bottom = if (captions) 12.dp else ShelfPlankMetrics.Total - 2.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(swayConnection)
        ) {
            items(items = visible, key = { "book:${it.id}" }, contentType = { "book" }) { book ->
                val itemKey = "book:${book.id}"
                val slotKey = "shelf:$sectionKey:${book.id}"
                val tilt = remember(rowState, itemKey) { ShelfTilt(rowState, itemKey, swayValue) }
                val drop = remember(book.id) { Animatable(if (arrivals.consume(book.id)) -dropPx else 0f) }
                LaunchedEffect(drop) {
                    if (drop.value != 0f) drop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f))
                }
                Column(
                    modifier = Modifier
                        .animateItem()
                        .graphicsLayer { translationY = drop.value }
                ) {
                    BookOnShelf(
                        book = book,
                        width = size.width,
                        height = size.height,
                        slotKey = slotKey,
                        tilt = tilt,
                        showNewDot = showNewDot,
                        dimmed = dimFinished && book.isFinished,
                        onClick = { onOpen(book, slotKey) },
                        onLongClick = { onLongPress(book) }
                    )
                    if (captions) {
                        Spacer(Modifier.height(CaptionGap))
                        BookCaption(book, size.width)
                    }
                }
            }
            if (more > 0) {
                item(key = "more", contentType = "more") {
                    MoreBooksFanCard(
                        books = books.drop(SHELF_ROW_MAX_BOOKS).take(3),
                        remaining = more,
                        size = size,
                        onClick = onShowAll,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/**
 * Which books a shelf row has already shown, so only books added later play
 * the drop-in (the first composition and restored rows animate nothing).
 * Plain sets, not snapshot state: they never trigger recomposition.
 */
internal class ShelfArrivals(known: Collection<Long>? = null) {
    private val seen = HashSet<Long>(known.orEmpty())
    private val pending = HashSet<Long>()
    private var initialized = known != null

    /** Records [ids]; unseen ones are queued for the drop when [animate]. */
    fun update(ids: List<Long>, animate: Boolean) {
        if (!initialized) {
            seen += ids
            initialized = true
            return
        }
        for (id in ids) {
            if (seen.add(id) && animate) pending += id
        }
    }

    /** True once for a queued book (its item then starts above the plank). */
    fun consume(id: Long): Boolean = pending.remove(id)

    companion object {
        val Saver: Saver<ShelfArrivals, LongArray> = Saver(
            save = { it.seen.toLongArray() },
            restore = { ShelfArrivals(it.toList()) }
        )
    }
}

/**
 * One row of the «Шкаф» grid (spec §4.1): up to `columns` books on a plank,
 * captions always on. Slot keys are `"$slotPrefix:<id>"`; a null prefix
 * registers no transition slots (rows inside sheets, which live in another
 * window).
 */
@Composable
fun BookcaseRow(
    rowSeed: String,
    books: List<ShelfBookUi>,
    size: ShelfBookSize,
    slotPrefix: String?,
    onOpen: (book: ShelfBookUi, slotKey: String?) -> Unit,
    onLongPress: (ShelfBookUi) -> Unit,
    modifier: Modifier = Modifier,
    showNewDot: Boolean = false
) {
    val colors = Lumina.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shelfPlank(colors, ShelfTopPadding + size.height, rowSeed)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = ShelfTopPadding, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            books.forEach { book ->
                key(book.id) {
                    val slotKey = slotPrefix?.let { "$it:${book.id}" }
                    Column {
                        BookOnShelf(
                            book = book,
                            width = size.width,
                            height = size.height,
                            slotKey = slotKey,
                            showNewDot = showNewDot,
                            onClick = { onOpen(book, slotKey) },
                            onLongClick = { onLongPress(book) }
                        )
                        Spacer(Modifier.height(CaptionGap))
                        BookCaption(book, size.width)
                    }
                }
            }
        }
    }
}

/** «Ещё N»: three mini covers fanned at −8°/0°/8° over a label; opens the shelf grid. */
@Composable
fun MoreBooksFanCard(
    books: List<ShelfBookUi>,
    remaining: Int,
    size: ShelfBookSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = "Ещё $remaining"
    val miniWidth = size.width * 0.52f
    val miniHeight = size.height * 0.52f
    Box(
        modifier = modifier
            .size(size.width, size.height)
            .background(Lumina.colors.surfaceSunken, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, показать все книги полки" },
        contentAlignment = Alignment.Center
    ) {
        val angles = listOf(-8f, 0f, 8f)
        books.take(3).forEachIndexed { index, book ->
            BookCover(
                model = book.cover,
                width = miniWidth,
                modifier = Modifier
                    .padding(bottom = 22.dp)
                    .size(miniWidth, miniHeight)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationZ = angles.getOrElse(index) { 0f }
                        translationX = (index - 1) * 10.dp.toPx()
                    }
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

/**
 * Three dashed ghost books on a plank, one leaning 8°: the empty shelf
 * (spec §4.1 `EmptyShelf`) and empty custom shelves.
 */
@Composable
fun GhostBooksRow(
    size: ShelfBookSize,
    modifier: Modifier = Modifier,
    seed: String = "ghost",
    count: Int = 3
) {
    val colors = Lumina.colors
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .shelfPlank(colors, ShelfTopPadding + size.height, seed)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = ShelfTopPadding, bottom = ShelfPlankMetrics.Total),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(count) { index ->
                Spacer(
                    modifier = Modifier
                        .size(size.width, size.height)
                        .graphicsLayer {
                            if (index == count - 1) {
                                transformOrigin = TransformOrigin(1f, 1f)
                                rotationZ = 8f
                            }
                        }
                        .drawBehind {
                            drawRoundRect(
                                color = outline,
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                )
                            )
                        }
                )
            }
        }
    }
}
