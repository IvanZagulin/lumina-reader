package com.lumina.reader.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.opds.OpdsEntry

/**
 * A book of a feed (spec §7.9 «OpdsEntryRow», min 132dp): 72×108 cover,
 * title, author (opens the author's feed when there is a link), series,
 * two lines of description and one download chip per format.
 */
@Composable
internal fun OpdsEntryRow(
    publication: OpdsEntry.Publication,
    authHeaders: Map<String, String>,
    chips: FormatChips,
    callbacks: ChipCallbacks,
    onClick: () -> Unit,
    onOpenAuthor: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clickable(role = Role.Button, onClickLabel = "Подробнее о книге", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        CatalogCover(
            url = publication.thumbnailUrl,
            authHeaders = authHeaders,
            title = publication.title,
            author = publication.authors.firstOrNull().orEmpty(),
            width = 72.dp,
            height = 108.dp,
            checked = chips.checked
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = publication.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (publication.authors.isNotEmpty()) {
                val authorModifier = if (onOpenAuthor != null) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "Все книги автора", onClick = onOpenAuthor)
                        .padding(vertical = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                }
                Text(
                    text = publication.authorLine,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = authorModifier
                )
            }
            publication.series?.let { series ->
                Text(
                    text = CatalogUi.seriesLine(series.name, series.index),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (publication.summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = publication.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            FormatChipRow(publication = publication, chips = chips, callbacks = callbacks)
        }
    }
}

/** The chips of [chips] in a scrollable row, or why there is nothing to download. */
@Composable
internal fun FormatChipRow(
    publication: OpdsEntry.Publication,
    chips: FormatChips,
    callbacks: ChipCallbacks,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    if (chips.items.isEmpty()) {
        val offered = publication.unsupportedFormats.joinToString(", ")
        Text(
            text = if (offered.isEmpty()) "Нет файлов для скачивания" else "Формат не поддерживается: $offered",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chips.items.forEach { (acquisition, state) ->
                DownloadProgressChip(
                    acquisition = acquisition,
                    state = state,
                    isBest = acquisition.url == chips.bestUrl,
                    callbacks = callbacks
                )
            }
        }
        chips.failure?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
