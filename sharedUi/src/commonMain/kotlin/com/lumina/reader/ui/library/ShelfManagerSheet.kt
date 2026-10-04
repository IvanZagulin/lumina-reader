package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.ui.theme.LegacyM3Defaults

/** A user shelf with its book count, as listed in the manager. */
data class ShelfEntry(val name: String, val bookCount: Int)

/**
 * «Управление полками» (spec §4.1): the user's shelves with counts, rename (✎)
 * and delete (🗑, books go back to the main shelf), and «+ Новая полка».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfManagerSheet(
    shelves: List<ShelfEntry>,
    onCreate: (String) -> Unit,
    onRename: (old: String, new: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The series of the library: they are shelves on the main screen too, built from the books' series. */
    series: List<ShelfEntry> = emptyList(),
    onRenameSeries: (old: String, new: String) -> Unit = { _, _ -> },
    onDisbandSeries: (String) -> Unit = {}
) {
    var renamingSeries by rememberSaveable { mutableStateOf<String?>(null) }
    var disbanding by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text("Управление полками", style = MaterialTheme.typography.headlineMedium)
            Text(
                "«${LibraryPreferences.MAIN_SHELF}» полка есть всегда; книги удалённой полки возвращаются на неё.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            if (shelves.isEmpty()) {
                Text(
                    if (series.isEmpty()) "Своих полок пока нет." else "Своих полок пока нет; серии — ниже.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            shelves.forEach { shelf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            shelf.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${shelf.bookCount} ${russianPlural(shelf.bookCount, "книга", "книги", "книг")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { renaming = shelf.name }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Переименовать полку «${shelf.name}»")
                    }
                    IconButton(onClick = { deleting = shelf.name }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить полку «${shelf.name}»",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (series.isNotEmpty()) {
                Text(
                    "Серии",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Text(
                    "Серия собирается из книг, у которых она указана. Можно переименовать её или расформировать: книги останутся в библиотеке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                series.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${entry.bookCount} ${russianPlural(entry.bookCount, "книга", "книги", "книг")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { renamingSeries = entry.name }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Переименовать серию «${entry.name}»")
                        }
                        IconButton(onClick = { disbanding = entry.name }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Расформировать серию «${entry.name}»",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Новая полка") },
                    placeholder = { Text("Например, Фантастика") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    colors = LegacyM3Defaults.buttonColors()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Создать")
                }
            }
        }
    }

    renamingSeries?.let { name ->
        RenameShelfDialog(
            shelfName = name,
            title = "Переименовать серию",
            label = "Название серии",
            onRename = { target ->
                onRenameSeries(name, target)
                renamingSeries = null
            },
            onDismiss = { renamingSeries = null }
        )
    }
    disbanding?.let { name ->
        AlertDialog(
            onDismissRequest = { disbanding = null },
            title = { Text("Расформировать серию «$name»?") },
            text = { Text("Книги останутся в библиотеке, но больше не будут собраны в серию.") },
            confirmButton = {
                TextButton(onClick = {
                    onDisbandSeries(name)
                    disbanding = null
                }) { Text("Расформировать", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { disbanding = null }) { Text("Отмена") } }
        )
    }
    renaming?.let { name ->
        RenameShelfDialog(
            shelfName = name,
            onRename = { target ->
                onRename(name, target)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { name ->
        DeleteShelfDialog(
            shelfName = name,
            onDelete = {
                onDelete(name)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

/** Rename a user shelf. */
@Composable
fun RenameShelfDialog(
    shelfName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Переименовать полку",
    label: String = "Название полки"
) {
    var name by rememberSaveable(shelfName) { mutableStateOf(shelfName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank() && name.trim() != shelfName,
                colors = LegacyM3Defaults.textButtonColors()
            ) { Text("Переименовать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Delete a user shelf; its books return to the main shelf. */
@Composable
fun DeleteShelfDialog(shelfName: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить полку «$shelfName»?") },
        text = { Text("Книги вернутся на «Основную полку».") },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
