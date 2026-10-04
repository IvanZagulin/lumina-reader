package com.lumina.reader.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.formatByteSize
import com.lumina.reader.core.opds.OpdsLink
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.components.absorbListOverscroll

/**
 * Book details (spec §7.9 «OpdsBookDetailsSheet»): 120×180 cover on a lamp
 * glow, title, author, series, «Язык · Год · Размер», 48dp format buttons
 * with the chip state machine, the full description and related feeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsBookDetailsSheet(
    selected: SelectedPublication,
    downloads: Map<String, DownloadState>,
    libraryBookId: Long?,
    callbacks: ChipCallbacks,
    onOpenLink: (OpdsLink) -> Unit,
    onDismiss: () -> Unit
) {
    val publication = selected.publication
    val coverUrl = publication.coverUrl ?: publication.thumbnailUrl
    val authHeaders = remember(selected.catalog, coverUrl) { selected.catalog.authHeadersFor(coverUrl) }
    val chips = remember(publication, downloads, libraryBookId) { FormatChips.of(publication, downloads, libraryBookId) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    val glow = if (Lumina.colors.isDark == catalogIsDark()) Lumina.colors.lampGlow else colors.primary.copy(alpha = 0.10f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LuminaShape.Sheet,
        containerColor = colors.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.absorbListOverscroll()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(212.dp)
                    .drawWithCache {
                        val brush = Brush.radialGradient(
                            colors = listOf(glow, Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.45f),
                            radius = size.minDimension * 0.9f
                        )
                        onDrawBehind { drawRect(brush = brush) }
                    }
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                CatalogCover(
                    url = coverUrl,
                    authHeaders = authHeaders,
                    title = publication.title,
                    author = publication.authors.firstOrNull().orEmpty(),
                    width = 120.dp,
                    height = 180.dp,
                    checked = chips.checked,
                    large = true
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = publication.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                if (publication.authors.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = publication.authorLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.primary,
                        textAlign = TextAlign.Center
                    )
                }
                publication.series?.let { series ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Серия · " + CatalogUi.seriesLine(series.name, series.index),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.tertiary,
                        textAlign = TextAlign.Center
                    )
                }
                val meta = CatalogUi.metaParts(
                    language = publication.language,
                    issued = publication.issued,
                    sizeLabel = publication.sizeBytes?.let(::formatByteSize)
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                }
                Text(
                    text = selected.catalog.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chips.items.isEmpty()) {
                    FormatChipRow(publication = publication, chips = chips, callbacks = callbacks)
                } else {
                    chips.items.forEach { (acquisition, state) ->
                        DownloadProgressChip(
                            acquisition = acquisition,
                            state = state,
                            isBest = acquisition.url == chips.bestUrl,
                            callbacks = callbacks,
                            large = true
                        )
                        if (state is ChipState.Failed) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.error,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }

            if (publication.summary.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Описание",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .semantics { heading() }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = publication.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            if (publication.categories.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = publication.categories.take(6).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            if (publication.relatedLinks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(
                    thickness = LuminaDimens.Hairline,
                    color = colors.outlineVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(4.dp))
                publication.relatedLinks.forEach { link ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LuminaDimens.TouchTarget)
                            .clickable(role = Role.Button) { onOpenLink(link) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = link.title ?: "Связанный раздел",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
