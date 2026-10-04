package com.lumina.reader.ui.shell

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.library.AppServices
import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.platform.AppInfo
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion
import com.lumina.reader.ui.transition.OpenAnimation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «20:00» */
fun formatReminderTime(hour: Int, minute: Int): String =
    "${hour.coerceIn(0, 23).toString().padStart(2, '0')}:${minute.coerceIn(0, 59).toString().padStart(2, '0')}"

/**
 * App settings (spec §3, §4.1 `AppSettingsSheet`): the book-open animation,
 * shelf captions, the daily reading reminder, the update check and the
 * version.
 *
 * @param onCheckForUpdates «Проверить обновления». The update is Android's APK
 *   self-update; an iPhone gets new versions through its store, so the iOS
 *   shell passes null and the row is not shown.
 * @param reminders the daily reading reminder; null hides its section
 *   (previews). By default the platform's own, from [ShellServices].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(
    onDismiss: () -> Unit,
    onCheckForUpdates: (() -> Unit)?,
    isCheckingForUpdates: Boolean,
    reminders: ReminderControl? = ShellServices.reminders
) {
    val preferences = remember { AppServices.library.uiPreferences }
    val openAnimation by preferences.openAnimation.collectAsState()
    val captions by preferences.shelfCaptions.collectAsState()
    val reminderFlow = remember(reminders) { reminders?.settings ?: emptyFlow() }
    val reminder by reminderFlow.collectAsState(initial = null)
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    var pickingTime by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            SectionLabel("Анимация открытия книги")
            val options = OpenAnimation.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = openAnimation == option,
                        onClick = { preferences.setOpenAnimation(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = { Text(option.title) }
                    )
                }
            }
            if (reducedMotion) {
                Text(
                    text = "В системе отключены анимации — книги открываются без перелистывания обложки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "Подписи под книгами",
                subtitle = "Название и автор под обложками на полках",
                checked = captions,
                onCheckedChange = preferences::setShelfCaptions
            )

            if (reminders != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                SectionLabel("Напоминание о чтении")
                val current = reminder ?: ReminderSettings()
                SwitchRow(
                    title = "Ежедневное напоминание",
                    subtitle = "Уведомление, если сегодня вы ещё не читали",
                    checked = current.enabled,
                    enabled = reminder != null,
                    onCheckedChange = { enabled ->
                        // Finishes (save + reschedule) even if the sheet is closed right away.
                        scope.launch { withContext(NonCancellable) { reminders.setEnabled(enabled) } }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(
                            enabled = reminder != null && current.enabled,
                            role = Role.Button,
                            onClickLabel = "Изменить время напоминания"
                        ) { pickingTime = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Время",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        color = if (current.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = formatReminderTime(current.hour, current.minute),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            if (onCheckForUpdates != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = !isCheckingForUpdates, role = Role.Button, onClick = onCheckForUpdates),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text("Проверить обновления", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (isCheckingForUpdates) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "О приложении · версия ${AppInfo.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (pickingTime && reminders != null) {
        val current = reminder ?: ReminderSettings()
        val state = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Время напоминания") },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickingTime = false
                        scope.launch {
                            withContext(NonCancellable) { reminders.setTime(state.hour, state.minute) }
                        }
                    }
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
