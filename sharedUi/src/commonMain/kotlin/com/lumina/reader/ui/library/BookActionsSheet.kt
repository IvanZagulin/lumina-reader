package com.lumina.reader.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Book
import com.lumina.reader.ui.components.BookOnShelf
import com.lumina.reader.ui.components.toShelfBookUi
import com.lumina.reader.ui.theme.LegacyM3Defaults
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape
import kotlinx.coroutines.launch

/** Pages of the book sheet: actions, the two organisation sections and the delete confirmation. */
enum class BookSheetPage { MAIN, MOVE_TO_SHELF, SERIES, CONFIRM_DELETE }

/**
 * Long-press sheet of a book (spec §4.1 `BookActionsSheet`): header with the
 * book, description, a 4-column action grid and in-sheet sections for «На
 * полку…», «Серия…» and the delete confirmation (they replace the old
 * AlertDialogs and keep their fields and logic).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookActionsSheet(
    book: Book,
    collections: List<String>,
    seriesNames: List<String>,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSaveOrganization: (BookOrganization) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    initialPage: BookSheetPage = BookSheetPage.MAIN
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(book.id) { mutableStateOf(initialPage) }

    /** Hides the sheet, then runs [action] (so the reader opens over the shelf, not the sheet). */
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "book-sheet"
            ) { current ->
                when (current) {
                    BookSheetPage.MAIN -> BookSheetMain(
                        book = book,
                        onRead = { closeThen(onRead) },
                        onToggleFavorite = onToggleFavorite,
                        onToggleCompleted = onToggleCompleted,
                        onMoveToShelf = { page = BookSheetPage.MOVE_TO_SHELF },
                        onSeries = { page = BookSheetPage.SERIES },
                        onShare = onShare,
                        onDelete = { page = BookSheetPage.CONFIRM_DELETE }
                    )
                    BookSheetPage.MOVE_TO_SHELF -> MoveToShelfSection(
                        book = book,
                        collections = collections,
                        seriesNames = seriesNames,
                        onSave = { organization -> closeThen { onSaveOrganization(organization) } },
                        onCancel = { page = BookSheetPage.MAIN }
                    )
                    BookSheetPage.SERIES -> SeriesSection(
                        book = book,
                        collections = collections,
                        seriesNames = seriesNames,
                        onSave = { organization -> closeThen { onSaveOrganization(organization) } },
                        onCancel = { page = BookSheetPage.MAIN }
                    )
                    BookSheetPage.CONFIRM_DELETE -> DeleteConfirmation(
                        title = book.title,
                        onConfirm = { closeThen(onDelete) },
                        onCancel = { page = BookSheetPage.MAIN }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSheetMain(
    book: Book,
    onRead: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompleted: () -> Unit,
    onMoveToShelf: () -> Unit,
    onSeries: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val shelfBook = remember(book) { book.toShelfBookUi() }
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                BookOnShelf(book = shelfBook, width = 72.dp, height = 108.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.seriesName.isNotBlank()) {
                    Text(
                        text = buildString {
                            append(book.seriesName)
                            if (book.seriesOrder > 0) append(" · №").append(book.seriesOrder)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { shelfBook.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (shelfBook.isFinished) Lumina.colors.success else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (shelfBook.isFinished) "Прочитано" else "${book.currentProgressPercent.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (book.description.isNotBlank()) {
            var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
            Text(
                text = book.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis
            )
            if (!expanded) {
                TextButton(onClick = { expanded = true }) { Text("Ещё") }
            }
        }
        Spacer(Modifier.height(12.dp))
        val actions = listOf(
            SheetAction("Читать", Icons.AutoMirrored.Rounded.MenuBook, onClick = onRead),
            SheetAction(
                if (book.isFavorite) "Из избранного" else "В избранное",
                if (book.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tint = if (book.isFavorite) Lumina.colors.favorite else null,
                onClick = onToggleFavorite
            ),
            SheetAction(
                if (book.isDone()) "Не прочитано" else "Прочитано",
                if (book.isDone()) Icons.Rounded.RemoveDone else Icons.Rounded.TaskAlt,
                onClick = onToggleCompleted
            ),
            SheetAction("На полку…", Icons.AutoMirrored.Rounded.DriveFileMove, onClick = onMoveToShelf),
            SheetAction("Серия…", Icons.Rounded.CollectionsBookmark, onClick = onSeries),
            SheetAction("Поделиться", Icons.Rounded.Share, onClick = onShare),
            SheetAction("Удалить", Icons.Rounded.Delete, tint = MaterialTheme.colorScheme.error, onClick = onDelete)
        )
        actions.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    ActionTile(action, Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class SheetAction(
    val label: String,
    val icon: ImageVector,
    val tint: Color? = null,
    val onClick: () -> Unit
)

@Composable
private fun ActionTile(action: SheetAction, modifier: Modifier = Modifier) {
    val color = action.tint ?: MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = action.label,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            color = if (action.tint == MaterialTheme.colorScheme.error) action.tint else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun DeleteConfirmation(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "Удалить «$title» из библиотеки?",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Файл книги, закладки и цитаты будут удалены.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = LegacyM3Defaults.outlinedButtonColors(),
                border = LegacyM3Defaults.outlinedButtonBorder()
            ) { Text("Отмена") }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("Удалить") }
        }
    }
}
