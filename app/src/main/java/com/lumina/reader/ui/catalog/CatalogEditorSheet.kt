package com.lumina.reader.ui.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion

private val FieldShape = RoundedCornerShape(16.dp)

/**
 * Add / edit an OPDS catalogue (spec §7.9 «CatalogEditorSheet»): name,
 * address with «Вставить», «Требуется вход» with login and password,
 * «Проверить» and «Сохранить». Built-in catalogues are never edited here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogEditorSheet(
    form: CatalogForm,
    onChange: ((CatalogForm) -> CatalogForm) -> Unit,
    onCheck: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val reducedMotion = rememberReducedMotion()
    val colors = MaterialTheme.colorScheme
    var passwordVisible by remember { mutableStateOf(false) }
    var requiresLogin by rememberSaveable(form.editingId) {
        mutableStateOf(form.username.isNotBlank() || form.password.isNotEmpty())
    }
    val urlInvalid = form.url.isNotBlank() && form.normalizedUrl == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LuminaShape.Sheet,
        containerColor = colors.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (form.editingId == null) "Новый каталог" else "Изменить каталог",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> onChange { it.copy(name = value) } },
                label = { Text("Название") },
                placeholder = { Text("Например, Моя библиотека") },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.url,
                onValueChange = { value -> onChange { it.copy(url = value) } },
                label = { Text("Адрес OPDS") },
                placeholder = { Text("https://example.org/opds") },
                singleLine = true,
                isError = urlInvalid,
                supportingText = if (urlInvalid) {
                    { Text("Введите адрес, например https://example.org/opds") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = if (form.url.isEmpty()) {
                    {
                        AssistChip(
                            onClick = {
                                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                if (pasted.isNotEmpty()) onChange { it.copy(url = pasted) }
                            },
                            label = { Text("Вставить") },
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = LuminaShape.Pill,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                } else {
                    null
                },
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Требуется вход", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(
                        text = "Логин и пароль отправляются только на адрес каталога",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = requiresLogin,
                    onCheckedChange = { enabled ->
                        requiresLogin = enabled
                        if (!enabled) onChange { it.copy(username = "", password = "") }
                    }
                )
            }
            AnimatedVisibility(
                visible = requiresLogin,
                enter = if (reducedMotion) fadeIn(snap()) else expandVertically() + fadeIn(),
                exit = if (reducedMotion) fadeOut(snap()) else shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.username,
                        onValueChange = { value -> onChange { it.copy(username = value) } },
                        label = { Text("Логин") },
                        singleLine = true,
                        shape = FieldShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = { value -> onChange { it.copy(password = value) } },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                                )
                            }
                        },
                        shape = FieldShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            form.check?.let { check -> CheckResultLine(check) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val running = form.check == ConnectionCheck.Running
                OutlinedButton(
                    onClick = onCheck,
                    enabled = form.canSave && !running,
                    shape = LuminaShape.Pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    AnimatedContent(
                        targetState = running,
                        transitionSpec = {
                            if (reducedMotion) fadeIn(snap()) togetherWith fadeOut(snap())
                            else fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        },
                        label = "checkButton"
                    ) { isRunning ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Проверяем…")
                            } else {
                                Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Проверить")
                            }
                        }
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = form.canSave,
                    shape = LuminaShape.Pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Сохранить")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** «✓ …» in secondary or the error text, announced politely to TalkBack. */
@Composable
internal fun CheckResultLine(check: ConnectionCheck, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (check) {
        ConnectionCheck.Running -> "Проверяем соединение…" to colors.onSurfaceVariant
        is ConnectionCheck.Success -> "✓ ${check.message}" to colors.secondary
        is ConnectionCheck.Failure -> check.message to colors.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
