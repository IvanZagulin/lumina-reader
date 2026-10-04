package com.lumina.reader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.catalog.CatalogServicesHolder
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion
import com.lumina.reader.ui.components.absorbListOverscroll

/**
 * Every download task (spec §7.10): progress, cancel, retry, open, and
 * «Очистить готовые» to forget finished ones. Reads the app-wide
 * [com.lumina.reader.ui.catalog.CatalogDownloads] (Android's BookImporter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    onDismiss: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val bookDownloads = remember { CatalogServicesHolder.services.downloads }
    val downloads by bookDownloads.downloads.collectAsState()
    val meta by DownloadMetaRegistry.meta.collectAsState()
    val rows = remember(downloads, meta) { DownloadUiMapper.rows(downloads, meta) }
    val reducedMotion = rememberReducedMotion()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = remember(bookDownloads, onOpenBook, onDismiss) {
        DownloadRowActions(
            onCancel = bookDownloads::cancel,
            onRetry = { key -> bookDownloads.retry(key) },
            onDismiss = bookDownloads::dismiss,
            onOpen = { bookId ->
                onOpenBook(bookId)
                onDismiss()
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.absorbListOverscroll().fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp)
                    .heightIn(min = LuminaDimens.TouchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Загрузки",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (rows.any { it.isFinished && it.bookId != null }) {
                    TextButton(onClick = {
                        rows.filter { it.isFinished && it.bookId != null }.forEach { bookDownloads.dismiss(it.key) }
                    }) {
                        Text("Очистить готовые")
                    }
                }
            }
            val activeCount = rows.count { it.isActive }
            Text(
                text = when {
                    rows.isEmpty() -> "Книги из каталогов появятся здесь, пока скачиваются"
                    activeCount > 0 -> "Скачивается: $activeCount ${pluralBooks(activeCount)}"
                    else -> "Все загрузки завершены"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (rows.isEmpty()) {
                EmptyDownloads()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, bottom = 24.dp)
                ) {
                    items(rows, key = { it.key }, contentType = { "download" }) { row ->
                        Column(modifier = Modifier.animateItem()) {
                            DownloadTaskRow(row = row, actions = actions, reducedMotion = reducedMotion)
                            HorizontalDivider(
                                thickness = LuminaDimens.Hairline,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloads() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = "Загрузок пока нет",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Найдите книгу в каталоге и нажмите «Скачать»",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
