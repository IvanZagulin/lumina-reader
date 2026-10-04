package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.Book
import com.lumina.reader.ui.components.screenWidthDp
import com.lumina.reader.ui.components.BookcaseRow
import com.lumina.reader.ui.components.ShelfBookSize
import com.lumina.reader.ui.components.ShelfBookUi
import com.lumina.reader.ui.components.bookcaseLayout
import com.lumina.reader.ui.components.rememberShelfBookSize
import com.lumina.reader.ui.components.toShelfBookUi
import kotlinx.coroutines.launch

/**
 * «Все →»: every book of a shelf as a bookcase grid in a full-height sheet.
 * Tapping a book closes the sheet first; the transition then starts from the
 * book's slot on the shelf if it is visible, otherwise from the centre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfDetailSheet(
    title: String,
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onLongPress: (Book) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val preferred = rememberShelfBookSize()
    val screenWidth = screenWidthDp().toFloat()
    val (columns, coverWidth) = remember(screenWidth, preferred) {
        bookcaseLayout(screenWidth, preferred.width.value)
    }
    val size = remember(coverWidth) { ShelfBookSize(coverWidth.dp, (coverWidth * 1.5f).dp) }
    val rows = remember(books, columns) { books.map { it.toShelfBookUi() }.chunked(columns) }
    val byId = remember(books) { books.associateBy(Book::id) }

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .semantics { heading() }
            )
            Text(
                text = "${books.size} ${russianPlural(books.size, "книга", "книги", "книг")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(rows, key = { index, _ -> "row:$index" }) { index, row ->
                    BookcaseRow(
                        rowSeed = "$title:$index",
                        books = row,
                        size = size,
                        slotPrefix = null,
                        onOpen = { book: ShelfBookUi, _: String? ->
                            byId[book.id]?.let { found -> closeThen { onOpen(found) } }
                        },
                        onLongPress = { book -> byId[book.id]?.let(onLongPress) }
                    )
                }
            }
        }
    }
}
