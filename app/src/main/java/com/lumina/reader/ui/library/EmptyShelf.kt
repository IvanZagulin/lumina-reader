package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.components.GhostBooksRow
import com.lumina.reader.ui.components.rememberShelfBookSize

/** Which empty state to show (spec §4.1 `EmptyShelf`). */
enum class EmptyShelfVariant { NO_BOOKS, ALL_READ, NO_RESULTS, EMPTY_FILTER }

/**
 * A plank with three dashed ghost books and a message: an empty library,
 * «всё прочитано», a search without results (with «Искать в каталогах») or an
 * empty filter (with «Сбросить фильтр»).
 */
@Composable
fun EmptyShelf(
    variant: EmptyShelfVariant,
    modifier: Modifier = Modifier,
    query: String = "",
    onAddFile: () -> Unit = {},
    onOpenCatalogs: () -> Unit = {},
    onSearchCatalogs: (String) -> Unit = {},
    onResetFilter: () -> Unit = {}
) {
    val size = rememberShelfBookSize()
    val (title, body) = when (variant) {
        EmptyShelfVariant.NO_BOOKS ->
            "Полка ждёт первую книгу" to "Добавьте EPUB, FB2, PDF или TXT — или найдите книгу в каталоге"
        EmptyShelfVariant.ALL_READ ->
            "Всё прочитано — время для новой книги" to "Добавьте файл или загляните в каталоги"
        EmptyShelfVariant.NO_RESULTS ->
            "Ничего не нашлось по «${query.trim()}»" to "Проверьте написание или поищите книгу в каталогах"
        EmptyShelfVariant.EMPTY_FILTER ->
            "Здесь пока пусто" to "В этом разделе нет книг"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GhostBooksRow(size = size, seed = "empty-${variant.name}")
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(20.dp))
        when (variant) {
            EmptyShelfVariant.NO_BOOKS, EmptyShelfVariant.ALL_READ -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Button(onClick = onAddFile) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить файл")
                    }
                    OutlinedButton(onClick = onOpenCatalogs) {
                        Text("Открыть каталоги")
                    }
                }
            }
            EmptyShelfVariant.NO_RESULTS -> {
                FilledTonalButton(onClick = { onSearchCatalogs(query.trim()) }) {
                    Icon(Icons.Rounded.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Искать в каталогах")
                }
            }
            EmptyShelfVariant.EMPTY_FILTER -> {
                OutlinedButton(onClick = onResetFilter) {
                    Icon(Icons.Rounded.FilterListOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Сбросить фильтр")
                }
            }
        }
    }
}
