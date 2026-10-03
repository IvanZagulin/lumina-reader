package com.lumina.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape

/** Chip labels of the library filters, mapped onto the existing [ReadingStatus] (spec §5.2). */
val LibraryFilters: List<Pair<ReadingStatus, String>> = listOf(
    ReadingStatus.COLLECTIONS to "Полки",
    ReadingStatus.READING to "Читаю",
    ReadingStatus.UNREAD to "Непрочитанные",
    ReadingStatus.FAVORITES to "Избранное",
    ReadingStatus.COMPLETED to "Прочитано",
    ReadingStatus.ALL to "Все"
)

/**
 * The sticky filter row (spec §4.1 `LibraryFilterRow`): 52 dp of chips on the
 * wall colour with an 8 dp fade below. Selected chips are filled with ink.
 */
@Composable
fun LibraryFilterRow(
    selected: ReadingStatus,
    onSelect: (ReadingStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val wall = Lumina.colors.wall
    val ink = MaterialTheme.colorScheme.onBackground
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(wall)
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(LibraryFilters, key = { it.first.name }) { (status, label) ->
                val isSelected = status == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(status) },
                    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    shape = LuminaShape.Pill,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = ink,
                        selectedLabelColor = wall
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = Color.Transparent
                    )
                )
            }
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Brush.verticalGradient(listOf(wall, wall.copy(alpha = 0f))))
        )
    }
}
