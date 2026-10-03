package com.lumina.reader.ui.reader.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.ui.components.rememberSwipeReleaseGate
import com.lumina.reader.ui.components.swipeReleaseGate
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors

/**
 * «Закладки» (§7.4): chapter and relative date, the snippet in Literata;
 * swipe left to delete (the sheet offers «Вернуть»).
 */
@Composable
internal fun BookmarkList(
    bookmarks: List<Bookmark>,
    colors: ReaderChromeColors,
    onClick: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bookmarks.isEmpty()) {
        NavigationEmptyState(
            text = "Закладок пока нет. Нажмите на уголок страницы, чтобы добавить",
            colors = colors,
            modifier = modifier
        )
        return
    }
    val now = remember { System.currentTimeMillis() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bookmarks, key = { "bookmark_${it.id}" }) { bookmark ->
            val swipeGate = rememberSwipeReleaseGate()
            // Deprecated in Material3 1.4 but still honoured; the gate keeps the
            // delete on release (see SwipeReleaseGate).
            @Suppress("DEPRECATION")
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart && !swipeGate.isPointerDown) {
                        onDelete(bookmark)
                        true
                    } else {
                        false
                    }
                }
            )
            SwipeToDismissBox(
                state = state,
                modifier = Modifier.swipeReleaseGate(swipeGate),
                enableDismissFromStartToEnd = false,
                backgroundContent = { DeleteBackground(colors) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .background(colors.content.copy(alpha = 0.04f))
                        .clickable { onClick(bookmark) }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Удалить закладку") {
                                    onDelete(bookmark)
                                    true
                                }
                            )
                        }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bookmark.chapterTitle,
                            fontSize = 12.sp,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = relativeDateLabel(bookmark.createdAt, now),
                            fontSize = 12.sp,
                            color = colors.muted
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bookmark.snippet,
                        fontFamily = ReaderFonts.Literata.family,
                        fontSize = 14.sp,
                        color = colors.content,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeleteBackground(colors: ReaderChromeColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.error.copy(alpha = 0.16f))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить", tint = colors.error)
    }
}
