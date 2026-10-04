package com.lumina.reader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.network.ProxySettings
import com.lumina.reader.core.network.ProxyType
import com.lumina.reader.ui.theme.LegacyM3Defaults

/**
 * The proxy for catalogues and book downloads, typed in once on this device. Only
 * their traffic uses it. A blocked catalogue (Flibusta without a VPN) opens through
 * a proxy that is not blocked; everything else in the app goes the usual way.
 */
@Composable
fun ProxyCard(
    saved: ProxySettings,
    onSave: (ProxySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by rememberSaveable(saved) { mutableStateOf(saved.enabled) }
    var type by rememberSaveable(saved) { mutableStateOf(saved.type) }
    var host by rememberSaveable(saved) { mutableStateOf(saved.host) }
    var port by rememberSaveable(saved) { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var username by rememberSaveable(saved) { mutableStateOf(saved.username) }
    var password by rememberSaveable(saved) { mutableStateOf(saved.password) }

    val draft = ProxySettings(
        enabled = enabled,
        type = type,
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        username = username,
        password = password
    )
    val complete = !draft.enabled || draft.usable
    val changed = draft != saved

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Прокси для каталогов", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Через него идут только каталоги и скачивание книг. Нужен, когда каталог (например, Флибуста) заблокирован.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(if (option == ProxyType.HTTP) "HTTP" else "SOCKS5") }
                        )
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Адрес") },
                    placeholder = { Text("например, 203.0.113.5") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                    label = { Text("Порт") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Логин (если нужен)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == ProxyType.SOCKS5 && username.isNotEmpty()) {
                    Text(
                        "На iPhone SOCKS5 с логином не поддерживается системой: используйте HTTP-прокси или SOCKS5 без пароля.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = { onSave(draft) },
                enabled = changed && complete,
                colors = LegacyM3Defaults.buttonColors(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (draft.enabled) "Сохранить прокси" else "Сохранить")
            }
        }
    }
}
