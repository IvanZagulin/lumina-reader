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
    onDismiss: () -> Unit
) {
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
                    "Своих полок пока нет.",
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
fun RenameShelfDialog(shelfName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(shelfName) { mutableStateOf(shelfName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать полку") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название полки") },
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
