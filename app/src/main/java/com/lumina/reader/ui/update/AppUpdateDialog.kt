package com.lumina.reader.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.BuildConfig
import com.lumina.reader.core.download.formatByteSize
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.update.AppRelease
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion

/**
 * The app update flow as a bottom sheet (spec §7.11). The state machine and
 * the parameters are unchanged: Checking → Available → Downloading (the
 * «Обновить» pill morphs into progress) → Installing / AwaitingInstallPermission,
 * or Error. «У вас последняя версия» is a snackbar instead of a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateDialog(
    state: AppUpdateDialogState?,
    onDismiss: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onRetryInstall: () -> Unit,
    /**
     * "Открыть настройки" in the install-permission step. Defaults to
     * [onRetryInstall], which re-sends the downloaded APK to the Activity and
     * makes it open the "install unknown apps" settings again.
     */
    onOpenInstallSettings: (() -> Unit)? = null
) {
    if (state is AppUpdateDialogState.UpToDate) {
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        LaunchedEffect(state) {
            AppMessages.post("У вас последняя версия — ${state.currentVersion}")
            currentOnDismiss()
        }
        return
    }
    if (state == null) return

    val currentState by rememberUpdatedState(state)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // The download cannot be dismissed by a swipe, a scrim tap or back: only «Отмена».
        confirmValueChange = { value -> value != SheetValue.Hidden || currentState !is AppUpdateDialogState.Downloading }
    )
    val reducedMotion = rememberReducedMotion()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it.stepKey() },
            transitionSpec = {
                if (reducedMotion) {
                    (fadeIn(snap()) togetherWith fadeOut(snap())).using(SizeTransform(clip = false) { _, _ -> snap() })
                } else {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(300) })
                }
            },
            label = "updateStep"
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
            ) {
                when (step) {
                    AppUpdateDialogState.Checking -> CheckingStep(onDismiss, reducedMotion)
                    is AppUpdateDialogState.Available -> ReleaseStep(
                        release = step.release,
                        downloading = null,
                        onDownload = { onDownload(step.release) },
                        onCancel = onCancelDownload,
                        onLater = onDismiss,
                        reducedMotion = reducedMotion
                    )
                    is AppUpdateDialogState.Downloading -> ReleaseStep(
                        release = step.release,
                        downloading = step,
                        onDownload = {},
                        onCancel = onCancelDownload,
                        onLater = onDismiss,
                        reducedMotion = reducedMotion
                    )
                    is AppUpdateDialogState.Installing -> MessageStep(
                        title = "Обновление готово",
                        message = "Открываем системный установщик…",
                        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        showProgress = true,
                        reducedMotion = reducedMotion,
                        primaryLabel = null,
                        onPrimary = {},
                        secondaryLabel = "Скрыть",
                        onSecondary = onDismiss
                    )
                    is AppUpdateDialogState.AwaitingInstallPermission -> MessageStep(
                        title = "Разрешите установку",
                        message = "Чтобы установить обновление, включите «Разрешить установку из этого источника» " +
                            "для Lumina Reader в системных настройках и вернитесь в приложение — " +
                            "установка продолжится автоматически.",
                        icon = { Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        showProgress = false,
                        primaryLabel = "Открыть настройки",
                        onPrimary = onOpenInstallSettings ?: onRetryInstall,
                        secondaryLabel = "Отмена",
                        onSecondary = onDismiss
                    )
                    is AppUpdateDialogState.Error -> MessageStep(
                        title = "Обновление не завершено",
                        message = step.message,
                        icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        showProgress = false,
                        primaryLabel = if (step.retryInstall) "Повторить установку" else "Проверить снова",
                        onPrimary = if (step.retryInstall) onRetryInstall else onRetryCheck,
                        secondaryLabel = "Закрыть",
                        onSecondary = onDismiss
                    )
                    is AppUpdateDialogState.UpToDate -> Unit
                }
            }
        }
    }
}

/** Available and Downloading share one step, so the pill can morph into the progress bar. */
private fun AppUpdateDialogState.stepKey(): String = when (this) {
    AppUpdateDialogState.Checking -> "checking"
    is AppUpdateDialogState.Available, is AppUpdateDialogState.Downloading -> "release"
    is AppUpdateDialogState.Installing -> "installing"
    is AppUpdateDialogState.AwaitingInstallPermission -> "permission"
    is AppUpdateDialogState.UpToDate -> "uptodate"
    is AppUpdateDialogState.Error -> "error"
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CheckingStep(onDismiss: () -> Unit, reducedMotion: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // No infinite animation under reduced motion (spec §11): a static arc instead.
        if (reducedMotion) {
            CircularProgressIndicator(progress = { 0.25f }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        }
        Spacer(Modifier.width(14.dp))
        SheetTitle("Ищем обновления")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Проверяем свежий релиз Lumina Reader на GitHub…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Скрыть") }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReleaseStep(
    release: AppRelease,
    downloading: AppUpdateDialogState.Downloading?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLater: () -> Unit,
    reducedMotion: Boolean
) {
    val colors = MaterialTheme.colorScheme
    SheetTitle("Доступно обновление ${release.displayVersion}")
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = LuminaShape.Pill,
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer
    ) {
        Text(
            text = "${BuildConfig.VERSION_NAME} → ${release.displayVersion}",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
    if (release.title.isNotBlank() && release.title != release.tagName) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = release.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(10.dp))
    Changelog(notes = release.notes)
    Spacer(Modifier.height(18.dp))

    // The 48dp «Обновить» pill morphs into the progress bar while downloading.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(if (reducedMotion) snap() else tween(300))
    ) {
        if (downloading == null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onDownload,
                    shape = LuminaShape.Pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (release.apkSizeBytes > 0L) "Обновить · ${formatByteSize(release.apkSizeBytes)}" else "Обновить",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                TextButton(onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Позже")
                }
            }
        } else {
            DownloadProgress(state = downloading, onCancel = onCancel, reducedMotion = reducedMotion)
        }
    }
}

@Composable
private fun DownloadProgress(
    state: AppUpdateDialogState.Downloading,
    onCancel: () -> Unit,
    reducedMotion: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val known = state.totalBytes > 0L
    val fraction = if (known) (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        val barModifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(LuminaShape.Pill)
        if (known || reducedMotion) {
            LinearProgressIndicator(
                progress = { fraction },
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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (known) {
                    "${formatByteSize(state.downloadedBytes)} / ${formatByteSize(state.totalBytes)} · ${(fraction * 100).toInt()}%"
                } else {
                    "Загружено ${formatByteSize(state.downloadedBytes)}"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
        Text(
            text = "Можно отменить загрузку — книги и прогресс чтения не затрагиваются.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

/** Release notes as a bullet list: "- item" / "* item" / "• item" lines become bullets. */
@Composable
private fun Changelog(notes: String) {
    val lines = remember(notes) { changelogLines(notes) }
    if (lines.isEmpty()) {
        Text(
            text = "Новая версия готова к установке.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { (bullet, text) ->
            Row {
                if (bullet) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(16.dp)
                    )
                }
                Text(
                    text = text,
                    style = if (bullet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = if (bullet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** (isBullet, text) lines of markdown-ish release notes; headings lose their "#". */
internal fun changelogLines(notes: String): List<Pair<Boolean, String>> =
    notes.take(MAX_NOTES_LENGTH)
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            when {
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> true to line.drop(2).trim()
                line.startsWith("#") -> false to line.trimStart('#').trim()
                else -> false to line
            }
        }
        .filter { it.second.isNotEmpty() }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MessageStep(
    title: String,
    message: String,
    icon: @Composable () -> Unit,
    showProgress: Boolean,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    reducedMotion: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        SheetTitle(title, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    if (showProgress) {
        val barModifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(LuminaShape.Pill)
        if (reducedMotion) {
            LinearProgressIndicator(progress = { 1f }, modifier = barModifier, gapSize = 0.dp, drawStopIndicator = {})
        } else {
            LinearProgressIndicator(modifier = barModifier)
        }
        Spacer(Modifier.height(10.dp))
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))
    if (primaryLabel != null) {
        Button(
            onClick = onPrimary,
            shape = LuminaShape.Pill,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
    TextButton(onClick = onSecondary, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(secondaryLabel)
    }
}

/**
 * Dismissible library banner «Доступна версия 1.8.0 · Обновить» (spec §7.11)
 * for an update found in the background. Placed by the library screen.
 */
@Composable
fun AppUpdateBanner(
    release: AppRelease,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onUpdate,
        shape = LuminaShape.Card,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Доступна версия ${release.displayVersion} · Обновить",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Скрыть уведомление об обновлении")
            }
        }
    }
}

private const val MAX_NOTES_LENGTH = 1_500
