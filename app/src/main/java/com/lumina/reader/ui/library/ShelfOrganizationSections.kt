package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.Book
import com.lumina.reader.ui.theme.LegacyM3Defaults

/** Values the two organisation sections save through `LibraryViewModel.updateBookOrganization`. */
data class BookOrganization(val collection: String, val seriesName: String, val seriesOrder: Int)

/**
 * «На полку…» (formerly the «Перенести книгу» dialog): the shelf, and when
 * needed the group / series and the book's position in it.
 */
@Composable
fun MoveToShelfSection(
    book: Book,
    collections: List<String>,
    seriesNames: List<String>,
    onSave: (BookOrganization) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shelf by rememberSaveable(book.id) { mutableStateOf(book.collection) }
    var series by rememberSaveable(book.id) { mutableStateOf(book.seriesName) }
    var order by rememberSaveable(book.id) {
        mutableStateOf(book.seriesOrder.takeIf { it > 0 }?.toString().orEmpty())
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Перенести книгу")
        Text(
            "Книга будет показана только на выбранной полке.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = shelf,
            onValueChange = { shelf = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Полка") },
            singleLine = true
        )
        SuggestionRow(collections) { shelf = it }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text("Группа / серия (необязательно)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = series,
            onValueChange = {
                series = it
                if (it.isBlank()) order = ""
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название группы") },
            placeholder = { Text("Например, Дом странных детей") },
            singleLine = true
        )
        if (seriesNames.isNotEmpty()) SuggestionRow(seriesNames, withIcon = true) { series = it }
        if (series.isNotBlank()) {
            OutlinedTextField(
                value = order,
                onValueChange = { order = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Номер книги в группе") },
                placeholder = { Text("1, 2, 3…") },
                supportingText = { Text("Нужен для правильного порядка серии") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        SectionButtons(
            confirmLabel = "Перенести",
            confirmEnabled = shelf.isNotBlank(),
            onConfirm = { onSave(BookOrganization(shelf, series, order.toIntOrNull() ?: 0)) },
            onCancel = onCancel
        )
    }
}

/**
 * «Серия…» (formerly the «Полка и серия» dialog): the regular shelf and the
 * optional series with the book's number.
 */
@Composable
fun SeriesSection(
    book: Book,
    collections: List<String>,
    seriesNames: List<String>,
    onSave: (BookOrganization) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shelf by rememberSaveable(book.id) { mutableStateOf(book.collection) }
    var series by rememberSaveable(book.id) { mutableStateOf(book.seriesName) }
    var order by rememberSaveable(book.id) {
        mutableStateOf(book.seriesOrder.takeIf { it > 0 }?.toString().orEmpty())
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Полка и серия")
        Text("Обычная полка", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = shelf,
            onValueChange = { shelf = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название полки") },
            placeholder = { Text("Например, Фантастика") },
            singleLine = true
        )
        if (collections.isNotEmpty()) SuggestionRow(collections) { shelf = it }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text("Серия книг", style = MaterialTheme.typography.titleSmall)
        Text(
            "После сохранения серия появится в библиотеке как отдельная полка.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = series,
            onValueChange = { series = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название серии") },
            placeholder = { Text("Например, Дюна") },
            trailingIcon = {
                if (series.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            series = ""
                            order = ""
                        }
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Убрать серию")
                    }
                }
            },
            singleLine = true
        )
        if (seriesNames.isNotEmpty()) SuggestionRow(seriesNames, withIcon = true) { series = it }
        OutlinedTextField(
            value = order,
            onValueChange = { value -> order = value.filter(Char::isDigit).take(6) },
            label = { Text("Номер книги в серии") },
            placeholder = { Text("1, 2, 3…") },
            supportingText = { Text("Без номера книга будет показана в конце серии") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = series.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        SectionButtons(
            confirmLabel = "Сохранить",
            confirmEnabled = shelf.isNotBlank(),
            onConfirm = { onSave(BookOrganization(shelf, series, order.toIntOrNull() ?: 0)) },
            onCancel = onCancel
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SuggestionRow(values: List<String>, withIcon: Boolean = false, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(values, key = { it.lowercase() }) { value ->
            SuggestionChip(
                onClick = { onPick(value) },
                label = { Text(value, maxLines = 1) },
                border = LegacyM3Defaults.suggestionChipBorder(),
                icon = if (withIcon) {
                    { Icon(Icons.Rounded.Bookmarks, contentDescription = null, modifier = Modifier.size(15.dp)) }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun SectionButtons(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) { Text("Отмена") }
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = LegacyM3Defaults.buttonColors()
        ) { Text(confirmLabel) }
    }
}
