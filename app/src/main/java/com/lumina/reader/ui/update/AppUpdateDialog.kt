package com.lumina.reader.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.update.AppRelease
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: AppUpdateDialogState?,
    onDismiss: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onRetryInstall: () -> Unit
) {
    when (state) {
        null -> Unit
        AppUpdateDialogState.Checking -> CheckingDialog(onDismiss)
        is AppUpdateDialogState.Available -> AvailableDialog(
            release = state.release,
            onDismiss = onDismiss,
            onDownload = { onDownload(state.release) }
        )

        is AppUpdateDialogState.Downloading -> DownloadingDialog(
            state = state,
            onCancel = onCancelDownload
        )

        is AppUpdateDialogState.Installing -> ProgressMessageDialog(
            title = "Обновление готово",
            message = "Открываем системный установщик…",
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) }
        )

        is AppUpdateDialogState.AwaitingInstallPermission -> ProgressMessageDialog(
            title = "Разрешите установку",
            message = "Включите установку из этого источника в открывшихся настройках. После этого установка продолжится автоматически.",
            icon = { Icon(Icons.Default.Security, contentDescription = null) }
        )

        is AppUpdateDialogState.UpToDate -> UpToDateDialog(
            currentVersion = state.currentVersion,
            onDismiss = onDismiss
        )

        is AppUpdateDialogState.Error -> ErrorDialog(
            state = state,
            onDismiss = onDismiss,
            onRetry = if (state.retryInstall) onRetryInstall else onRetryCheck
        )
    }
}

@Composable
private fun CheckingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp) },
        title = { Text("Ищем обновления") },
        text = { Text("Проверяем свежий релиз Lumina Reader на GitHub…") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Скрыть") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun AvailableDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
        title = { Text("Доступно обновление") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = release.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("v${release.displayVersion}") }
                    )
                }
                if (release.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = release.notes.take(MAX_NOTES_LENGTH),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 190.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Новая версия готова к установке.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (release.apkSizeBytes > 0L) {
                    Text(
                        text = "Размер: ${release.apkSizeBytes.toReadableSize()}",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text("Скачать и установить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun DownloadingDialog(
    state: AppUpdateDialogState.Downloading,
    onCancel: () -> Unit
) {
    val hasKnownSize = state.totalBytes > 0L
    val progress = if (hasKnownSize) {
        (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
        title = { Text("Загружаем обновление") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasKnownSize) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${state.downloadedBytes.toReadableSize()} из ${state.totalBytes.toReadableSize()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Загружено ${state.downloadedBytes.toReadableSize()}",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Можно отменить загрузку — книги и прогресс чтения не затрагиваются.",
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCancel) { Text("Отменить") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun ProgressMessageDialog(
    title: String,
    message: String,
    icon: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        icon = icon,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun UpToDateDialog(currentVersion: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Всё актуально") },
        text = { Text("У вас уже установлена свежая версия Lumina Reader — $currentVersion.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отлично") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun ErrorDialog(
    state: AppUpdateDialogState.Error,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Обновление не завершено") },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(if (state.retryInstall) "Повторить установку" else "Проверить снова")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

private fun Long.toReadableSize(): String {
    if (this < 1024L) return "$this Б"
    val kilobytes = this / 1024.0
    if (kilobytes < 1024.0) return String.format(Locale.US, "%.1f КБ", kilobytes)
    return String.format(Locale.US, "%.1f МБ", kilobytes / 1024.0)
}

private const val MAX_NOTES_LENGTH = 1_500
