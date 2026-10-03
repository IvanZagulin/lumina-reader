package com.lumina.reader.ui.reader.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.HighlightPalette

/**
 * «Цитаты» (§7.4): colour filters, then cards with a colour bar, the quote
 * in Literata italic (five lines, «Ещё» expands), the note and actions.
 * Tap opens the place; swipe left or long-press deletes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuoteList(
    highlights: List<ReadingHighlight>,
    colors: ReaderChromeColors,
    chapterTitle: (Int) -> String,
    onClick: (ReadingHighlight) -> Unit,
    onShare: (ReadingHighlight) -> Unit,
    onEdit: (ReadingHighlight) -> Unit,
    onDelete: (ReadingHighlight) -> Unit,
    modifier: Modifier = Modifier
) {
    if (highlights.isEmpty()) {
        NavigationEmptyState("Выделите текст, чтобы сохранить цитату", colors, modifier)
        return
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(highlights, filter) {
        val wanted = filter
        if (wanted == null) highlights
        else highlights.filter { HighlightPalette.fromHex(it.colorHex).id == wanted }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HighlightPalette.all.forEach { swatch ->
                val selected = filter == swatch.id
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Checkbox,
                            onClick = { filter = if (selected) null else swatch.id }
                        )
                        .semantics { contentDescription = "Только ${swatch.label.lowercase()}" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .then(if (selected) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
                            .padding(if (selected) 3.dp else 0.dp)
                            .clip(CircleShape)
                            .background(swatch.color)
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { "quote_${it.id}" }) { highlight ->
                val state = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDelete(highlight)
                            true
                        } else {
                            false
                        }
                    }
                )
                SwipeToDismissBox(
                    state = state,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = { DeleteBackground(colors) }
                ) {
                    QuoteCard(
                        highlight = highlight,
                        chapter = chapterTitle(highlight.chapterIndex),
                        colors = colors,
                        onClick = { onClick(highlight) },
                        onLongClick = { onDelete(highlight) },
                        onShare = { onShare(highlight) },
                        onEdit = { onEdit(highlight) },
                        onDelete = { onDelete(highlight) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuoteCard(
    highlight: ReadingHighlight,
    chapter: String,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val swatch = HighlightPalette.fromHex(highlight.colorHex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .background(colors.content.copy(alpha = 0.04f))
            .combinedClickable(
                onClickLabel = "Открыть в книге",
                onLongClickLabel = "Удалить цитату",
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(swatch.color)
        )
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Text(text = chapter, fontSize = 12.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = highlight.selectedText.trim(),
                fontFamily = ReaderFonts.Literata.family,
                fontStyle = FontStyle.Italic,
                fontSize = 15.sp,
                color = colors.content,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
            if (!expanded && highlight.selectedText.length > 240) {
                Text(
                    text = "Ещё",
                    fontSize = 13.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { expanded = true })
                        .padding(vertical = 4.dp)
                )
            }
            val note = highlight.note
            if (!note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = note, fontSize = 13.sp, color = colors.content.copy(alpha = 0.85f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = "Поделиться", tint = colors.muted)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Изменить заметку", tint = colors.muted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить", tint = colors.muted)
                }
            }
        }
    }
}
