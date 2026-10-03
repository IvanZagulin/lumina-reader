package com.lumina.reader.ui.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.download.formatByteSize
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion

/** What a format's download control does. */
internal class ChipCallbacks(
    val onDownload: (OpdsAcquisition) -> Unit,
    val onCancel: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onOpen: (Long) -> Unit
)

private enum class ChipKind { IDLE, QUEUED, RUNNING, IMPORTING, DONE, IN_LIBRARY, FAILED }

private val ChipState.kind: ChipKind
    get() = when (this) {
        ChipState.Idle -> ChipKind.IDLE
        ChipState.Queued -> ChipKind.QUEUED
        is ChipState.Running -> ChipKind.RUNNING
        ChipState.Importing -> ChipKind.IMPORTING
        is ChipState.Done -> ChipKind.DONE
        is ChipState.InLibrary -> ChipKind.IN_LIBRARY
        is ChipState.Failed -> ChipKind.FAILED
    }

/**
 * The per-format download control (spec §7.9 «DownloadProgressChip»):
 * «FB2 ↓» → «В очереди» → ring + «37%» → «Обработка…» → «Открыть», or
 * «Повторить» with a shake on failure. 36dp tall with a 48dp touch target;
 * [large] is the 48dp full-width button of the details sheet
 * («Скачать FB2 · 1,2 МБ»).
 */
@Composable
internal fun DownloadProgressChip(
    acquisition: OpdsAcquisition,
    state: ChipState,
    isBest: Boolean,
    callbacks: ChipCallbacks,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotion()
    val label = acquisition.label

    // Failure shake (±6dp, 300ms) only on the transition into Failed.
    val shake = remember { Animatable(0f) }
    val isFailed = state is ChipState.Failed
    var wasFailed by remember { mutableStateOf(isFailed) }
    LaunchedEffect(isFailed) {
        if (isFailed && !wasFailed && !reducedMotion) {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 300
                    -6f at 40
                    6f at 100
                    -5f at 160
                    4f at 220
                    -2f at 270
                }
            )
        }
        wasFailed = isFailed
    }

    val container: Color
    val content: Color
    val border: BorderStroke?
    when (state) {
        ChipState.Idle -> if (isBest) {
            container = colors.secondaryContainer
            content = colors.onSecondaryContainer
            border = null
        } else {
            container = Color.Transparent
            content = colors.onSurface
            border = BorderStroke(1.dp, colors.outline)
        }
        ChipState.Queued, is ChipState.Running, ChipState.Importing -> {
            container = colors.surfaceContainerHigh
            content = colors.onSurface
            border = null
        }
        is ChipState.Done -> {
            container = colors.secondary
            content = colors.onSecondary
            border = null
        }
        is ChipState.InLibrary -> {
            container = colors.secondaryContainer
            content = colors.onSecondaryContainer
            border = null
        }
        is ChipState.Failed -> {
            container = Color.Transparent
            content = colors.error
            border = BorderStroke(1.dp, colors.error)
        }
    }

    val onClick: (() -> Unit)? = when (state) {
        ChipState.Idle -> ({ callbacks.onDownload(acquisition) })
        ChipState.Queued, is ChipState.Running -> ({ callbacks.onCancel(acquisition.url) })
        ChipState.Importing -> null
        is ChipState.Done -> ({ callbacks.onOpen(state.bookId) })
        is ChipState.InLibrary -> ({ callbacks.onOpen(state.bookId) })
        is ChipState.Failed -> ({ callbacks.onRetry(acquisition.url) })
    }
    val description = when (state) {
        ChipState.Idle -> "Скачать $label"
        ChipState.Queued -> "$label в очереди, нажмите для отмены"
        is ChipState.Running -> state.percent?.let { "Загрузка $it%, нажмите для отмены" }
            ?: "Загрузка, нажмите для отмены"
        ChipState.Importing -> "Обработка книги"
        is ChipState.Done -> "Открыть книгу"
        is ChipState.InLibrary -> "Книга в библиотеке, открыть"
        is ChipState.Failed -> "Не удалось скачать $label: ${state.message}. Повторить"
    }

    val chipModifier = modifier
        .graphicsLayer { translationX = shake.value * density }
        .then(if (large) Modifier.fillMaxWidth() else Modifier)
        .semantics(mergeDescendants = true) {
            contentDescription = description
            role = Role.Button
        }
    val minHeight = if (large) 48.dp else 36.dp
    val body: @Composable () -> Unit = {
        AnimatedContent(
            targetState = state,
            contentKey = { it.kind },
            transitionSpec = {
                if (reducedMotion) {
                    (fadeIn(snap()) togetherWith fadeOut(snap())).using(SizeTransform(clip = false) { _, _ -> snap() })
                } else {
                    (fadeIn(tween(240)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(240) })
                }
            },
            contentAlignment = Alignment.Center,
            label = "downloadChip"
        ) { target ->
            Row(
                modifier = Modifier
                    .heightIn(min = minHeight)
                    .padding(horizontal = if (large) 20.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (large) Arrangement.Center else Arrangement.Start
            ) {
                ChipBody(target = target, acquisition = acquisition, large = large, reducedMotion = reducedMotion, tint = content)
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = chipModifier,
            shape = LuminaShape.Pill,
            color = container,
            contentColor = content,
            border = border,
            content = body
        )
    } else {
        Surface(
            modifier = chipModifier,
            shape = LuminaShape.Pill,
            color = container,
            contentColor = content,
            border = border,
            content = body
        )
    }
}

@Composable
private fun ChipBody(
    target: ChipState,
    acquisition: OpdsAcquisition,
    large: Boolean,
    reducedMotion: Boolean,
    tint: Color
) {
    val textStyle = MaterialTheme.typography.labelLarge
    val iconSize = if (large) 20.dp else 16.dp
    val label = acquisition.label
    when (target) {
        ChipState.Idle -> {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            val size = acquisition.sizeBytes
            Text(
                text = when {
                    large && size != null -> "Скачать $label · ${formatByteSize(size)}"
                    large -> "Скачать $label"
                    else -> label
                },
                style = textStyle,
                maxLines = 1
            )
        }
        ChipState.Queued -> {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            Text("В очереди", style = textStyle, maxLines = 1)
        }
        is ChipState.Running -> {
            val fraction = target.fraction
            if (fraction != null) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    trackColor = tint.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round
                )
            } else {
                IndeterminateRing(tint = MaterialTheme.colorScheme.primary, track = tint.copy(alpha = 0.18f), reducedMotion = reducedMotion)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = target.percent?.let { "$it%" } ?: "Загрузка",
                style = textStyle.copy(fontFeatureSettings = "tnum"),
                maxLines = 1
            )
            if (large) {
                Spacer(Modifier.width(6.dp))
                Text("· Отмена", style = textStyle, maxLines = 1)
            }
        }
        ChipState.Importing -> {
            IndeterminateRing(tint = MaterialTheme.colorScheme.primary, track = tint.copy(alpha = 0.18f), reducedMotion = reducedMotion)
            Spacer(Modifier.width(8.dp))
            Text("Обработка…", style = textStyle, maxLines = 1)
        }
        is ChipState.Done -> {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            Text(if (large) "✓ На полке · Открыть" else "Открыть", style = textStyle, maxLines = 1)
        }
        is ChipState.InLibrary -> {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            Text("В библиотеке · Открыть", style = textStyle, maxLines = 1)
        }
        is ChipState.Failed -> {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            Text(if (large) "Повторить · $label" else "Повторить", style = textStyle, maxLines = 1)
        }
    }
}

@Composable
private fun IndeterminateRing(tint: Color, track: Color, reducedMotion: Boolean) {
    if (reducedMotion) {
        CircularProgressIndicator(
            progress = { 0.25f },
            modifier = Modifier.size(16.dp),
            color = tint,
            strokeWidth = 2.dp,
            trackColor = track,
            strokeCap = StrokeCap.Round
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = tint,
            strokeWidth = 2.dp,
            trackColor = track,
            strokeCap = StrokeCap.Round
        )
    }
}
