package com.lumina.reader.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.describeProgress
import com.lumina.reader.core.download.formatByteSize
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsUrls

/** Callbacks of a publication's download controls. */
data class DownloadActions(
    val onDownload: (OpdsAcquisition) -> Unit,
    val onOpenBook: (Long) -> Unit,
    val onRetry: (String) -> Unit,
    val onCancel: (String) -> Unit
)

@Composable
fun CatalogCard(
    catalog: OpdsCatalogConfig,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = catalog.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = OpdsUrls.host(catalog.url) ?: catalog.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (catalog.hasCredentials) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "С логином",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A folder of the catalogue (authors, series, genres, new books...). */
@Composable
fun NavigationRow(
    entry: OpdsEntry.Navigation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A book in a feed: cover, title, authors, series and its download controls. */
@Composable
fun PublicationRow(
    publication: OpdsEntry.Publication,
    authHeaders: Map<String, String>,
    download: PublicationDownload?,
    actions: DownloadActions,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            CoverThumbnail(
                url = publication.thumbnailUrl,
                authHeaders = authHeaders,
                width = 56.dp,
                height = 82.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = publication.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (publication.authors.isNotEmpty()) {
                    Text(
                        text = publication.authorLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                publication.series?.let { series ->
                    Text(
                        text = seriesLabel(series.name, series.index),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                DownloadControls(
                    publication = publication,
                    download = download,
                    actions = actions,
                    compact = true
                )
            }
        }
    }
}

/**
 * Format buttons before a download, then progress, then "Открыть" or
 * "Повторить". [compact] shows short labels for list rows.
 */
@Composable
fun DownloadControls(
    publication: OpdsEntry.Publication,
    download: PublicationDownload?,
    actions: DownloadActions,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    if (download == null) {
        FormatButtons(publication = publication, onDownload = actions.onDownload, compact = compact, modifier = modifier)
        return
    }
    when (val state = download.state) {
        DownloadState.Queued -> ProgressLine(
            label = "В очереди…",
            fraction = null,
            onCancel = { actions.onCancel(download.acquisition.url) },
            modifier = modifier
        )
        is DownloadState.Running -> ProgressLine(
            label = if (state.isImporting) {
                "Добавляем в библиотеку…"
            } else if (state.bytesRead <= 0) {
                "Подключение…"
            } else {
                describeProgress(state.bytesRead, state.totalBytes)
            },
            fraction = if (state.isImporting) null else state.fraction,
            onCancel = if (state.isImporting) null else ({ actions.onCancel(download.acquisition.url) }),
            modifier = modifier
        )
        is DownloadState.Completed -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { actions.onOpenBook(state.bookId) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Открыть")
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (state.alreadyInLibrary) "Уже в библиотеке" else "В библиотеке · ${download.acquisition.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is DownloadState.Failed -> Column(modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (compact) 2 else 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = { actions.onRetry(download.acquisition.url) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Повторить")
            }
        }
    }
}

@Composable
private fun FormatButtons(
    publication: OpdsEntry.Publication,
    onDownload: (OpdsAcquisition) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    if (publication.acquisitions.isEmpty()) {
        val offered = publication.unsupportedFormats.joinToString(", ")
        Text(
            text = if (offered.isEmpty()) "Нет файлов для скачивания" else "Формат не поддерживается: $offered",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        publication.acquisitions.forEach { acquisition ->
            FilledTonalButton(
                onClick = { onDownload(acquisition) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                val size = acquisition.sizeBytes
                Text(
                    text = if (!compact && size != null) "${acquisition.label} · ${formatByteSize(size)}" else acquisition.label,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ProgressLine(
    label: String,
    fraction: Float?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Отменить загрузку")
            }
        }
    }
}

/** Cover from the catalogue (with its credentials), or a neutral placeholder. */
@Composable
fun CoverThumbnail(
    url: String?,
    authHeaders: Map<String, String>,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Book,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(width / 2)
        )
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, authHeaders) {
                ImageRequest.Builder(context)
                    .data(url)
                    .apply { authHeaders.forEach { (name, value) -> addHeader(name, value) } }
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Triggers [onLoadMore] when it comes into view; shows progress or a retry button. */
@Composable
fun LoadMoreFooter(
    itemCount: Int,
    isLoading: Boolean,
    error: String?,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(itemCount) {
        if (error == null) onLoadMore()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onLoadMore) { Text("Загрузить ещё") }
            }
            else -> TextButton(onClick = onLoadMore) { Text("Загрузить ещё") }
        }
    }
}

@Composable
fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun MessagePanel(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isError) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

fun seriesLabel(name: String, index: Int?): String =
    if (index != null && index > 0) "Серия «$name» · книга $index" else "Серия «$name»"
