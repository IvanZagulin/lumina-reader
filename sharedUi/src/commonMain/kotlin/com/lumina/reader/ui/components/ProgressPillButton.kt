package com.lumina.reader.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberLuminaHaptics
import com.lumina.reader.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/** UI states of a download chip (spec §7.9). */
sealed interface DownloadChipState {
    data object Idle : DownloadChipState
    data object Queued : DownloadChipState

    /** [fraction] is null without Content-Length (indeterminate ring). */
    data class Running(val fraction: Float?) : DownloadChipState
    data object Importing : DownloadChipState
    data class Done(val bookId: Long) : DownloadChipState

    /** The book was already on the shelf (duplicate). */
    data class InLibrary(val bookId: Long) : DownloadChipState
    data class Failed(val message: String) : DownloadChipState
}

/** Thin mapper from the importer's per-URL [DownloadState]. */
fun DownloadState?.toChipState(): DownloadChipState = when (this) {
    null -> DownloadChipState.Idle
    DownloadState.Queued -> DownloadChipState.Queued
    is DownloadState.Running -> if (isImporting) DownloadChipState.Importing else DownloadChipState.Running(fraction)
    is DownloadState.Completed -> if (alreadyInLibrary) {
        DownloadChipState.InLibrary(bookId)
    } else {
        DownloadChipState.Done(bookId)
    }
    is DownloadState.Failed -> DownloadChipState.Failed(message)
}

/**
 * Download keys that turned into a new library book between [previous] and
 * [current] (duplicates excluded): drives the dock's «Полка +N» badge.
 */
fun newlyShelvedKeys(
    previous: Map<String, DownloadState>,
    current: Map<String, DownloadState>
): Set<String> = current.filter { (key, state) ->
    state is DownloadState.Completed && !state.alreadyInLibrary && previous[key] !is DownloadState.Completed
}.keys

/**
 * The download chip of catalogue rows and sheets (spec §7.9): 36 dp tall
 * (48 dp touch), content morphing over 240 ms; a failure shakes ±6 dp with a
 * reject haptic, success confirms. [formatLabel] is e.g. «FB2»; [best] fills
 * the idle chip tonally.
 */
@Composable
fun DownloadProgressChip(
    state: DownloadChipState,
    formatLabel: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    best: Boolean = false
) {
    val haptics = rememberLuminaHaptics()
    val reducedMotion = rememberReducedMotion()
    val shake = remember { Animatable(1f) }
    val failed = state is DownloadChipState.Failed
    val done = state is DownloadChipState.Done
    LaunchedEffect(failed) {
        if (failed) {
            haptics.reject()
            if (!reducedMotion) {
                shake.snapTo(0f)
                shake.animateTo(1f, tween(300, easing = LinearEasing))
            }
        }
    }
    LaunchedEffect(done) {
        if (done) haptics.confirm()
    }

    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    val border: BorderStroke?
    when (state) {
        DownloadChipState.Idle -> {
            container = if (best) scheme.secondaryContainer else Color.Transparent
            content = if (best) scheme.onSecondaryContainer else scheme.primary
            border = if (best) null else BorderStroke(1.dp, scheme.outline)
        }
        is DownloadChipState.Done -> {
            container = scheme.secondary
            content = scheme.onSecondary
            border = null
        }
        is DownloadChipState.InLibrary -> {
            container = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
            border = null
        }
        is DownloadChipState.Failed -> {
            container = Color.Transparent
            content = scheme.error
            border = BorderStroke(1.dp, scheme.error)
        }
        else -> {
            container = scheme.surfaceVariant
            content = scheme.onSurfaceVariant
            border = null
        }
    }
    val description = when (state) {
        DownloadChipState.Idle -> "Скачать $formatLabel"
        DownloadChipState.Queued -> "$formatLabel в очереди, нажмите для отмены"
        is DownloadChipState.Running ->
            state.fraction?.let { "Загрузка ${(it * 100).toInt()}%, нажмите для отмены" } ?: "Загрузка, нажмите для отмены"
        DownloadChipState.Importing -> "Книга обрабатывается"
        is DownloadChipState.Done, is DownloadChipState.InLibrary -> "Открыть книгу"
        is DownloadChipState.Failed -> "Не удалось скачать $formatLabel, повторить"
    }
    val onClick: () -> Unit = when (state) {
        DownloadChipState.Idle -> onStart
        DownloadChipState.Queued, is DownloadChipState.Running -> onCancel
        DownloadChipState.Importing -> ({})
        is DownloadChipState.Done -> ({ onOpen(state.bookId) })
        is DownloadChipState.InLibrary -> ({ onOpen(state.bookId) })
        is DownloadChipState.Failed -> onRetry
    }

    Column(modifier = modifier) {
        Surface(
            onClick = onClick,
            enabled = state != DownloadChipState.Importing,
            shape = LuminaShape.Pill,
            color = container,
            contentColor = content,
            border = border,
            modifier = Modifier
                .height(36.dp)
                .graphicsLayer {
                    val p = shake.value
                    translationX = if (p < 1f) 6.dp.toPx() * sin(p * 4f * PI.toFloat()) * (1f - p) else 0f
                }
                .semantics { contentDescription = description }
        ) {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(240)) },
                label = "download-chip",
                modifier = Modifier.animateContentSize()
            ) { current ->
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    ChipContent(current, formatLabel, content)
                }
            }
        }
        if (state is DownloadChipState.Failed && state.message.isNotBlank()) {
            Text(
                text = state.message,
                color = scheme.error,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun ChipContent(state: DownloadChipState, formatLabel: String, tint: Color) {
    val label = MaterialTheme.typography.labelLarge
    when (state) {
        DownloadChipState.Idle -> {
            Text(formatLabel, style = label)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DownloadChipState.Queued -> {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("В очереди", style = label)
        }
        is DownloadChipState.Running -> {
            val fraction = state.fraction
            if (fraction != null) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(16.dp),
                    color = tint,
                    strokeWidth = 2.dp,
                    trackColor = tint.copy(alpha = 0.2f)
                )
                Spacer(Modifier.width(6.dp))
                Text("${(fraction * 100).toInt()}%", style = label)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Загрузка", style = label)
            }
        }
        DownloadChipState.Importing -> {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Обработка…", style = label)
        }
        is DownloadChipState.Done -> {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Открыть", style = label)
        }
        is DownloadChipState.InLibrary -> {
            Text("В библиотеке · Открыть", style = label)
        }
        is DownloadChipState.Failed -> {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Повторить", style = label)
        }
    }
}
