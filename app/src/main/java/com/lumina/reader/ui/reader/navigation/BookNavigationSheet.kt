package com.lumina.reader.ui.reader.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.ui.reader.ReaderPosition
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderModalSheet
import com.lumina.reader.ui.reader.chrome.formatPercentLabel
import com.lumina.reader.ui.reader.displayChapterTitle
import com.lumina.reader.ui.reader.formatTimeLeft
import com.lumina.reader.ui.theme.LuminaShape
import kotlinx.coroutines.launch
import java.io.File

/** Contents of a book: its own table of contents, or one entry per chapter. */
internal fun navigationTocItems(book: ParsedBook): List<TocItem> =
    book.tableOfContents.ifEmpty {
        book.chapters.mapIndexed { index, chapter ->
            TocItem(id = "chapter_$index", title = displayChapterTitle(chapter.title, index), chapterIndex = index)
        }
    }

/**
 * «Навигация по книге» (§7.4): a tall sheet in reader colours with the book
 * header and the tabs «Оглавление», «Закладки», «Цитаты». Deleting a
 * bookmark or quote offers «Вернуть» in the sheet's own snackbar.
 */
@Composable
internal fun BookNavigationSheet(
    book: Book?,
    parsedBook: ParsedBook,
    position: ReaderPosition,
    progressPercent: Float,
    minutesLeftInBook: Int?,
    bookmarks: List<Bookmark>,
    highlights: List<ReadingHighlight>,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    onNavigate: (chapterIndex: Int, paragraphIndex: Int, charOffset: Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onRestoreBookmark: (Bookmark) -> Unit,
    onDeleteHighlight: (ReadingHighlight) -> Unit,
    onRestoreHighlight: (ReadingHighlight) -> Unit,
    onEditHighlight: (ReadingHighlight) -> Unit,
    onShareText: (String) -> Unit,
    onDismiss: () -> Unit,
    initialTab: Int = 0
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tocItems = remember(parsedBook) { navigationTocItems(parsedBook) }
    val currentToc = remember(tocItems, position) {
        currentTocIndex(tocItems, position.chapterIndex, position.paragraphIndex)
    }
    val title = book?.title ?: parsedBook.title
    val author = book?.author ?: parsedBook.author
    val chapterTitle: (Int) -> String = { index ->
        displayChapterTitle(parsedBook.chapters.getOrNull(index)?.title.orEmpty(), index)
    }

    ReaderModalSheet(colors = colors, onDismiss = onDismiss, scrimAlpha = 0.32f) {
        Box(modifier = Modifier.fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                NavigationHeader(
                    title = title,
                    coverPath = book?.coverPath,
                    progressPercent = progressPercent,
                    minutesLeft = minutesLeftInBook,
                    colors = colors
                )
                Spacer(modifier = Modifier.height(12.dp))
                PillTabs(
                    titles = listOf(
                        "Оглавление",
                        tabTitle("Закладки", bookmarks.size),
                        tabTitle("Цитаты", highlights.size)
                    ),
                    selected = tab,
                    colors = colors,
                    onSelect = { tab = it }
                )
                Spacer(modifier = Modifier.height(4.dp))
                when (tab) {
                    0 -> if (tocItems.isEmpty()) {
                        NavigationEmptyState("Оглавление отсутствует", colors)
                    } else {
                        TocList(
                            items = tocItems,
                            currentIndex = currentToc,
                            colors = colors,
                            reducedMotion = reducedMotion,
                            onItemClick = { item -> onNavigate(item.chapterIndex, item.paragraphIndex, 0) }
                        )
                    }
                    1 -> BookmarkList(
                        bookmarks = bookmarks,
                        colors = colors,
                        onClick = { bookmark ->
                            onNavigate(bookmark.chapterIndex, bookmark.paragraphIndex, bookmark.charOffset)
                        },
                        onDelete = { bookmark ->
                            onDeleteBookmark(bookmark)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    message = "Закладка удалена",
                                    actionLabel = "Вернуть",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) onRestoreBookmark(bookmark)
                            }
                        }
                    )
                    else -> Column {
                        if (highlights.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        onShareText(highlightsMarkdown(title, author, highlights, chapterTitle))
                                    }
                                ) {
                                    Text("Экспорт в Markdown", color = colors.accent)
                                }
                            }
                        }
                        QuoteList(
                            highlights = highlights,
                            colors = colors,
                            chapterTitle = chapterTitle,
                            onClick = { highlight ->
                                onNavigate(highlight.chapterIndex, highlight.paragraphIndex, highlight.startOffset)
                            },
                            onShare = { highlight ->
                                onShareText(quoteShareText(highlight.selectedText, title, author))
                            },
                            onEdit = onEditHighlight,
                            onDelete = { highlight ->
                                onDeleteHighlight(highlight)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = "Цитата удалена",
                                        actionLabel = "Вернуть",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) onRestoreHighlight(highlight)
                                }
                            }
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun NavigationHeader(
    title: String,
    coverPath: String?,
    progressPercent: Float,
    minutesLeft: Int?,
    colors: ReaderChromeColors
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 60.dp)
                .clip(LuminaShape.Book)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            val cover = coverPath?.let(::File)?.takeIf { it.isFile }
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 40.dp, height = 60.dp)
                )
            } else {
                Text(
                    text = title.trim().take(1).uppercase(),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    fontSize = 18.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val left = formatTimeLeft(minutesLeft)
            Text(
                text = formatPercentLabel(progressPercent) + (left?.let { " · осталось ≈ $it" } ?: ""),
                fontSize = 12.sp,
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            )
        }
    }
}

/** Pill tabs «Оглавление · Закладки 4 · Цитаты 12». */
@Composable
private fun PillTabs(
    titles: List<String>,
    selected: Int,
    colors: ReaderChromeColors,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 40.dp)
                    .clip(LuminaShape.Pill)
                    .background(if (isSelected) colors.content else colors.content.copy(alpha = 0.06f))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.surface else colors.content,
                    maxLines = 1
                )
            }
        }
    }
}
