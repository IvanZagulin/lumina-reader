package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.preferences.LibrarySort
import com.lumina.reader.core.preferences.LibraryViewMode
import com.lumina.reader.ui.theme.LuminaShape

private val SortLabels = listOf(
    LibrarySort.RECENT to "Недавние",
    LibrarySort.TITLE to "Название",
    LibrarySort.AUTHOR to "Автор",
    LibrarySort.ADDED to "Добавлены",
    LibrarySort.PROGRESS to "Прогресс"
)

private val FormatLabels = listOf(
    BookFormat.EPUB to "EPUB",
    BookFormat.FB2 to "FB2",
    BookFormat.PDF to "PDF",
    BookFormat.TXT to "TXT"
)

private val ViewOptions: List<Triple<LibraryViewMode, String, ImageVector>> = listOf(
    Triple(LibraryViewMode.SHELVES, "Полки", Icons.Rounded.ViewDay),
    Triple(LibraryViewMode.BOOKCASE, "Шкаф", Icons.Rounded.GridView),
    Triple(LibraryViewMode.LIST, "Список", Icons.AutoMirrored.Rounded.ViewList)
)

/**
 * «Сортировка и вид» (spec §4.1 `LibraryViewSheet`): sort order, format filter,
 * the view (Полки · Шкаф · Список) and «Подписи под книгами».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryViewSheet(
    sort: LibrarySort,
    format: BookFormat?,
    viewMode: LibraryViewMode,
    captions: Boolean,
    onSortSelected: (LibrarySort) -> Unit,
    onFormatSelected: (BookFormat?) -> Unit,
    onViewModeSelected: (LibraryViewMode) -> Unit,
    onCaptionsChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text("Сортировка и вид", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            SheetLabel("Вид")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ViewOptions.forEachIndexed { index, (mode, label, icon) ->
                    SegmentedButton(
                        selected = viewMode == mode,
                        onClick = { onViewModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ViewOptions.size),
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            SheetLabel("Сортировка")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortLabels.forEach { (value, label) ->
                    FilterChip(
                        selected = sort == value,
                        onClick = { onSortSelected(value) },
                        label = { Text(label) },
                        shape = LuminaShape.Pill
                    )
                }
            }
            Text(
                text = "Порядок книг в разделах и поиске; полки упорядочены сами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            SheetLabel("Формат")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatLabels.forEach { (value, label) ->
                    FilterChip(
                        selected = format == value,
                        onClick = { onFormatSelected(value) },
                        label = { Text(label) },
                        shape = LuminaShape.Pill
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = captions, role = Role.Switch, onValueChange = onCaptionsChanged),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Подписи под книгами", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Название и автор под обложками на полках",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = captions, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
