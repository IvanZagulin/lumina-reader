package com.lumina.reader.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.reader.ui.theme.LuminaShape

/** Spine-side-tight book shape at thumbnail size (same proportions as LuminaShape.Book). */
internal val ThumbShape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 4.dp, bottomEnd = 4.dp)

/**
 * A tiny cover for download rows and the island: the catalogue thumbnail
 * (with its credentials) over a format plate that stays visible while the
 * image loads or when there is none.
 */
@Composable
internal fun DownloadCover(
    coverUrl: String?,
    coverHeaders: Map<String, String>,
    formatLabel: String?,
    width: Dp,
    height: Dp,
    plateColor: Color,
    onPlateColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(ThumbShape)
            .background(plateColor),
        contentAlignment = Alignment.Center
    ) {
        if (formatLabel != null && width >= 28.dp) {
            Text(
                text = formatLabel,
                style = MaterialTheme.typography.labelSmall,
                color = onPlateColor,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = onPlateColor,
                modifier = Modifier.size(width * 0.6f)
            )
        }
        if (coverUrl != null) {
            val context = LocalContext.current
            val request = remember(coverUrl, coverHeaders) {
                ImageRequest.Builder(context)
                    .data(coverUrl)
                    .apply { coverHeaders.forEach { (name, value) -> addHeader(name, value) } }
                    .size(120, 180)
                    .crossfade(false)
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

/** Determinate ring, or an indeterminate one (static under reduced motion). */
@Composable
internal fun DownloadRing(
    fraction: Float?,
    size: Dp,
    color: Color,
    trackColor: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val stroke = if (size <= 20.dp) 2.dp else 2.5.dp
    if (fraction != null || reducedMotion) {
        val value = fraction ?: 0.25f
        CircularProgressIndicator(
            progress = { value },
            modifier = modifier.size(size),
            color = color,
            strokeWidth = stroke,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            color = color,
            strokeWidth = stroke,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round
        )
    }
}

/** What a row's buttons do. */
internal class DownloadRowActions(
    val onCancel: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onDismiss: (String) -> Unit,
    val onOpen: (Long) -> Unit
)

/**
 * One task in the downloads sheet or the catalogue home: cover, title,
 * status and progress, with cancel / retry / open.
 */
@Composable
internal fun DownloadTaskRow(
    row: DownloadRowUi,
    actions: DownloadRowActions,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 64.dp else 76.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownloadCover(
            coverUrl = row.coverUrl,
            coverHeaders = row.coverHeaders,
            formatLabel = row.formatLabel,
            width = if (compact) 32.dp else 40.dp,
            height = if (compact) 48.dp else 60.dp,
            plateColor = colors.secondaryContainer,
            onPlateColor = colors.onSecondaryContainer
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (row.author.isNotBlank() && !compact) {
                Text(
                    text = row.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            if (row.isActive) {
                val fraction = row.fraction
                val barModifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(LuminaShape.Pill)
                if (fraction != null || reducedMotion) {
                    LinearProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = barModifier,
                        color = colors.primary,
                        trackColor = colors.surfaceVariant,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        color = colors.primary,
                        trackColor = colors.surfaceVariant,
                        gapSize = 0.dp
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = row.statusText,
                style = MaterialTheme.typography.labelMedium,
                color = when (row.phase) {
                    DownloadPhase.FAILED -> colors.error
                    DownloadPhase.DONE, DownloadPhase.IN_LIBRARY -> colors.secondary
                    else -> colors.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        when (row.phase) {
            DownloadPhase.QUEUED, DownloadPhase.RUNNING -> IconButton(onClick = { actions.onCancel(row.key) }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Отменить загрузку «${row.title}»",
                    tint = colors.onSurfaceVariant
                )
            }
            DownloadPhase.IMPORTING -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                DownloadRing(
                    fraction = null,
                    size = 20.dp,
                    color = colors.primary,
                    trackColor = colors.surfaceVariant,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.semantics { contentDescription = "Обработка" }
                )
            }
            DownloadPhase.DONE, DownloadPhase.IN_LIBRARY -> {
                val bookId = row.bookId
                if (bookId != null) {
                    Button(
                        onClick = { actions.onOpen(bookId) },
                        shape = LuminaShape.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.secondary,
                            contentColor = colors.onSecondary
                        ),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .semantics { contentDescription = "Открыть книгу «${row.title}»" }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Открыть", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            DownloadPhase.FAILED -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = { actions.onRetry(row.key) },
                    shape = LuminaShape.Pill,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.error),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Повторить", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { actions.onDismiss(row.key) }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Убрать «${row.title}» из списка",
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}
