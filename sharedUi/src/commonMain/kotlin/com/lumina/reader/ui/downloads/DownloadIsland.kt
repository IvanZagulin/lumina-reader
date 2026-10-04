package com.lumina.reader.ui.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.catalog.CatalogServicesHolder
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.rememberLuminaHaptics
import com.lumina.reader.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * True where the island should shrink to a ring-only chip (the reader route,
 * spec §7.10). The shell provides it around [DownloadIsland]; default false.
 */
val LocalDownloadIslandCompact = staticCompositionLocalOf { false }

/** Gap between the status bar and the island: clears a 56 dp header row. */
private val IslandTopOffset = 64.dp

private const val SUCCESS_VISIBLE_MS = 4_000L
private const val SUCCESS_VISIBLE_COMPACT_MS = 3_000L
private const val FAILURE_VISIBLE_MS = 6_000L
private val IslandShape = RoundedCornerShape(28.dp)
private val IslandShadowColor = Color(0x4D2A1608)

/** What the island currently shows. */
@Immutable
private sealed interface IslandContent {
    data class Progress(val progress: IslandProgress) : IslandContent
    data class Announce(val announcement: IslandAnnouncement) : IslandContent
}

private enum class IslandKind { PROGRESS, SUCCESS, FAILURE }

private val IslandContent?.kind: IslandKind?
    get() = when (this) {
        null -> null
        is IslandContent.Progress -> IslandKind.PROGRESS
        is IslandContent.Announce ->
            if (announcement is IslandAnnouncement.Failure) IslandKind.FAILURE else IslandKind.SUCCESS
    }

/**
 * Global download feedback (spec §7.10): a compact inverse capsule while
 * books download, a «✓ На полке · Открыть» card when one finishes and an error
 * card on failure. Tapping it opens the downloads sheet ([onOpenDownloads]).
 * Reads its state from the app-wide [com.lumina.reader.ui.catalog.CatalogDownloads]
 * (Android's BookImporter), so it works on every screen.
 * It applies the status-bar inset itself (+64dp, below the screen header); the caller only aligns it.
 */
@Composable
fun DownloadIsland(
    onOpenBook: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberLuminaHaptics()
    val bookDownloads = remember { CatalogServicesHolder.services.downloads }
    val downloads by bookDownloads.downloads.collectAsState()
    val meta by DownloadMetaRegistry.meta.collectAsState()
    val reducedMotion = rememberReducedMotion()
    val compact = LocalDownloadIslandCompact.current

    var previous by remember { mutableStateOf(downloads) }
    var batch by remember { mutableStateOf(DownloadUiMapper.nextBatch(emptySet(), downloads)) }
    var announcement by remember { mutableStateOf<IslandAnnouncement?>(null) }

    LaunchedEffect(downloads) {
        val next = DownloadUiMapper.announcement(previous, downloads, batch, meta)
        previous = downloads
        batch = DownloadUiMapper.nextBatch(batch, downloads)
        if (next == null) return@LaunchedEffect
        announcement = next
        if (next is IslandAnnouncement.Failure) haptics.reject() else haptics.confirm()
    }
    LaunchedEffect(announcement, compact) {
        val shown = announcement ?: return@LaunchedEffect
        delay(
            when {
                shown is IslandAnnouncement.Failure -> FAILURE_VISIBLE_MS
                compact -> SUCCESS_VISIBLE_COMPACT_MS
                else -> SUCCESS_VISIBLE_MS
            }
        )
        if (announcement == shown) announcement = null
    }

    val rows = remember(downloads, meta) { DownloadUiMapper.rows(downloads, meta) }
    val progress = remember(rows, batch) { DownloadUiMapper.progress(rows, batch) }
    val content: IslandContent? = announcement?.let { IslandContent.Announce(it) }
        ?: progress?.let { IslandContent.Progress(it) }

    val density = LocalDensity.current
    val dismissThreshold = with(density) { 32.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtMost(0f) }

    AnimatedContent(
        targetState = content,
        contentKey = { it.kind },
        transitionSpec = {
            val transform = when {
                reducedMotion -> EnterTransition.None togetherWith ExitTransition.None
                initialState == null ->
                    (scaleIn(LuminaMotion.snappy(), initialScale = 0.8f) + fadeIn(LuminaMotion.snappy())) togetherWith
                        fadeOut(snap())
                targetState == null ->
                    fadeIn(snap()) togetherWith (scaleOut(tween(180), targetScale = 0.8f) + fadeOut(tween(180)))
                else -> fadeIn(tween(200, delayMillis = 100)) togetherWith fadeOut(tween(100))
            }
            transform.using(
                SizeTransform(clip = false) { _, _ ->
                    if (reducedMotion) snap() else tween(300, easing = LuminaMotion.Emphasized)
                }
            )
        },
        contentAlignment = Alignment.TopCenter,
        label = "downloadIsland",
        modifier = modifier
            .statusBarsPadding()
            // Below the 56 dp screen headers, so their search, ⋯ and «+» buttons
            // stay reachable while a download runs.
            .padding(top = IslandTopOffset, start = 16.dp, end = 16.dp)
            .graphicsLayer {
                translationY = dragY
                alpha = (1f + dragY / (dismissThreshold * 3f)).coerceIn(0f, 1f)
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = announcement != null,
                onDragStopped = {
                    if (dragY < -dismissThreshold) announcement = null
                    dragY = 0f
                }
            )
    ) { target ->
        when (target) {
            null -> Box(Modifier)
            is IslandContent.Progress -> if (compact) {
                CompactRing(progress = target.progress, reducedMotion = reducedMotion, onClick = onOpenDownloads)
            } else {
                ProgressCapsule(progress = target.progress, reducedMotion = reducedMotion, onClick = onOpenDownloads)
            }
            is IslandContent.Announce -> when (val shown = target.announcement) {
                is IslandAnnouncement.Success -> SuccessCard(
                    row = shown.row,
                    headline = shown.headline,
                    onClick = onOpenDownloads,
                    onOpen = { bookId ->
                        announcement = null
                        onOpenBook(bookId)
                    }
                )
                is IslandAnnouncement.Failure -> FailureCard(
                    row = shown.row,
                    onClick = onOpenDownloads,
                    onRetry = {
                        announcement = null
                        bookDownloads.retry(shown.row.key)
                    }
                )
            }
        }
    }
}

@Composable
private fun IslandSurface(
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    clickLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = color,
        contentColor = contentColor,
        shape = IslandShape,
        modifier = modifier
            .shadow(elevation = 8.dp, shape = IslandShape, spotColor = IslandShadowColor, ambientColor = IslandShadowColor)
            .clickable(onClickLabel = clickLabel, onClick = onClick),
        content = content
    )
}

@Composable
private fun ProgressCapsule(progress: IslandProgress, reducedMotion: Boolean, onClick: () -> Unit) {
    val capsule = Lumina.colors.inverseCapsule
    val onCapsule = Lumina.colors.onInverseCapsule
    val percent = progress.percent
    val description = buildString {
        append(progress.label)
        if (percent != null) append(", $percent%")
    }
    IslandSurface(
        color = capsule,
        contentColor = onCapsule,
        onClick = onClick,
        clickLabel = "Открыть загрузки",
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val lead = progress.lead
            if (progress.batchSize <= 1) {
                DownloadCover(
                    coverUrl = lead.coverUrl,
                    coverHeaders = lead.coverHeaders,
                    formatLabel = null,
                    width = 20.dp,
                    height = 30.dp,
                    plateColor = onCapsule.copy(alpha = 0.16f),
                    onPlateColor = onCapsule
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = progress.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Spacer(Modifier.width(10.dp))
            DownloadRing(
                fraction = progress.fraction,
                size = 20.dp,
                color = onCapsule,
                trackColor = onCapsule.copy(alpha = 0.25f),
                reducedMotion = reducedMotion
            )
            if (percent != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CompactRing(progress: IslandProgress, reducedMotion: Boolean, onClick: () -> Unit) {
    val capsule = Lumina.colors.inverseCapsule
    val onCapsule = Lumina.colors.onInverseCapsule
    val description = progress.label + (progress.percent?.let { ", $it%" } ?: "")
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        IslandSurface(color = capsule, contentColor = onCapsule, onClick = onClick, clickLabel = "Открыть загрузки") {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                DownloadRing(
                    fraction = progress.fraction,
                    size = 18.dp,
                    color = onCapsule,
                    trackColor = onCapsule.copy(alpha = 0.25f),
                    reducedMotion = reducedMotion
                )
            }
        }
    }
}

@Composable
private fun SuccessCard(row: DownloadRowUi, headline: String, onClick: () -> Unit, onOpen: (Long) -> Unit) {
    val capsule = Lumina.colors.inverseCapsule
    val onCapsule = Lumina.colors.onInverseCapsule
    IslandSurface(
        color = capsule,
        contentColor = onCapsule,
        onClick = onClick,
        clickLabel = "Открыть загрузки",
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DownloadCover(
                coverUrl = row.coverUrl,
                coverHeaders = row.coverHeaders,
                formatLabel = row.formatLabel,
                width = 28.dp,
                height = 42.dp,
                plateColor = onCapsule.copy(alpha = 0.16f),
                onPlateColor = onCapsule
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.widthIn(max = 190.dp)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = onCapsule.copy(alpha = 0.72f),
                    maxLines = 1
                )
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val bookId = row.bookId
            if (bookId != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { onOpen(bookId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    modifier = Modifier.semantics { contentDescription = "Открыть книгу «${row.title}»" }
                ) {
                    Text("Открыть", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@Composable
private fun FailureCard(row: DownloadRowUi, onClick: () -> Unit, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IslandSurface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        onClick = onClick,
        clickLabel = "Открыть загрузки",
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 14.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.widthIn(max = 200.dp)) {
                Text(
                    text = "Не удалось скачать «${row.title}»",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                row.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)
            ) {
                Text("Повторить", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
