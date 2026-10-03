package com.lumina.reader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.formatByteSize
import com.lumina.reader.core.opds.OpdsLink

/** Book details: cover, metadata, every format with its own download state, related feeds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicationDetailsSheet(
    selected: SelectedPublication,
    downloads: Map<String, DownloadState>,
    actions: DownloadActions,
    onOpenLink: (OpdsLink) -> Unit,
    onDismiss: () -> Unit
) {
    val publication = selected.publication
    val coverUrl = publication.coverUrl ?: publication.thumbnailUrl
    val authHeaders = remember(selected.catalog, coverUrl) { selected.catalog.authHeadersFor(coverUrl) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row {
                CoverThumbnail(
                    url = coverUrl,
                    authHeaders = authHeaders,
                    width = 108.dp,
                    height = 160.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = publication.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (publication.authors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = publication.authorLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    publication.series?.let { series ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = seriesLabel(series.name, series.index),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    val facts = listOfNotNull(
                        publication.issued?.take(4),
                        publication.language?.uppercase(),
                        publication.sizeBytes?.let(::formatByteSize),
                        selected.catalog.name
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = facts.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (publication.categories.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = publication.categories.take(4).joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("Скачать", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            if (publication.acquisitions.isEmpty()) {
                DownloadControls(publication = publication, download = null, actions = actions, compact = false)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    publication.acquisitions.forEach { acquisition ->
                        val state = downloads[acquisition.url]
                        val single = publication.copy(acquisitions = listOf(acquisition))
                        Row {
                            Text(
                                text = acquisition.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .width(52.dp)
                                    .padding(top = 8.dp)
                            )
                            DownloadControls(
                                publication = single,
                                download = state?.let { PublicationDownload(acquisition, it) },
                                actions = actions,
                                compact = false,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (publication.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text("Описание", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = publication.summary, style = MaterialTheme.typography.bodyMedium)
            }

            if (publication.relatedLinks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                publication.relatedLinks.forEach { link ->
                    TextButton(onClick = { onOpenLink(link) }) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = link.title ?: "Связанный раздел",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
