package com.lumina.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.Book
import com.lumina.reader.ui.components.BookCover
import com.lumina.reader.ui.components.rememberSwipeReleaseGate
import com.lumina.reader.ui.components.shelfBookDescription
import com.lumina.reader.ui.components.swipeReleaseGate
import com.lumina.reader.ui.components.toCoverModel
import com.lumina.reader.ui.components.toShelfBookUi
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.rememberLuminaHaptics
import com.lumina.reader.ui.transition.LocalBookSlotHost
import com.lumina.reader.ui.transition.bookSlot

/**
 * A row of the «Список» view (spec §4.1 `BookListRow`, replaces the old
 * BookItemCard): a 48×72 cover, title, author, a 3 dp progress bar and ⋯.
 * Swiping right moves the book to a shelf, swiping left deletes it (both ask
 * first, in the book sheet).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookListRow(
    book: Book,
    slotKey: String,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onMoveToShelf: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = LocalBookSlotHost.current
    val haptics = rememberLuminaHaptics()
    val cover = remember(book) { book.toCoverModel() }
    val description = remember(book) { shelfBookDescription(book.toShelfBookUi()) }
    val swipeGate = rememberSwipeReleaseGate()
    // confirmValueChange is deprecated in Material3 1.4 but still honoured; the
    // gate keeps the actions on release (see SwipeReleaseGate).
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.25f },
        confirmValueChange = { value ->
            if (!swipeGate.isPointerDown) {
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> onMoveToShelf()
                    SwipeToDismissBoxValue.EndToStart -> onDelete()
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
            // The row stays; the action is confirmed in the sheet.
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth().swipeReleaseGate(swipeGate),
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(Lumina.colors.wall)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = "Действия с книгой",
                    onLongClick = {
                        haptics.longPress()
                        onMore()
                    },
                    onClick = {
                        haptics.tick()
                        onOpen()
                    }
                )
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                model = cover,
                width = 48.dp,
                modifier = Modifier
                    .size(48.dp, 72.dp)
                    .bookSlot(transition, slotKey, book.id)
                    .graphicsLayer { alpha = if (transition?.hiddenBookId == book.id) 0f else 1f }
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (book.isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Lumina.colors.favorite,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (book.currentProgressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (book.isDone()) Lumina.colors.success else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (book.isDone()) "Прочитано" else "${book.currentProgressPercent.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = "Действия с книгой",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) {
        Box(Modifier.fillMaxSize())
        return
    }
    val move = direction == SwipeToDismissBoxValue.StartToEnd
    val container = if (move) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (move) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = 24.dp),
        contentAlignment = if (move) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (move) Icons.AutoMirrored.Rounded.DriveFileMove else Icons.Rounded.Delete,
                contentDescription = null,
                tint = content
            )
            Text(
                text = if (move) "На полку" else "Удалить",
                color = content,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
