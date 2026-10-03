package com.lumina.reader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.opds.OpdsCatalogConfig

/** Manage OPDS catalogues: enable/disable, add, edit, delete, test the connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSourcesScreen(
    viewModel: CatalogSourcesViewModel,
    onBack: () -> Unit
) {
    val catalogs by viewModel.catalogs.collectAsState()
    val checks by viewModel.checks.collectAsState()
    val form by viewModel.form.collectAsState()
    var catalogToDelete by remember { mutableStateOf<OpdsCatalogConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Каталоги OPDS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startAdding,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Добавить каталог") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "hint") {
                Text(
                    text = "Выключенные каталоги не участвуют в поиске. Встроенные каталоги можно выключить, свои — изменить или удалить.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(catalogs, key = { it.id }) { catalog ->
                CatalogSourceRow(
                    catalog = catalog,
                    check = checks[catalog.id],
                    onEnabledChange = { enabled -> viewModel.setEnabled(catalog, enabled) },
                    onCheck = { viewModel.checkConnection(catalog) },
                    onEdit = { viewModel.startEditing(catalog) },
                    onDelete = { catalogToDelete = catalog }
                )
            }
        }
    }

    form?.let { current ->
        CatalogFormDialog(
            form = current,
            onChange = viewModel::updateForm,
            onCheck = viewModel::checkForm,
            onSave = viewModel::saveForm,
            onDismiss = viewModel::dismissForm
        )
    }

    catalogToDelete?.let { catalog ->
        AlertDialog(
            onDismissRequest = { catalogToDelete = null },
            title = { Text("Удалить каталог?") },
            text = { Text("Каталог «${catalog.name}» будет удалён из списка. Скачанные книги останутся в библиотеке.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(catalog)
                    catalogToDelete = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { catalogToDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun CatalogSourceRow(
    catalog: OpdsCatalogConfig,
    check: ConnectionCheck?,
    onEnabledChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = catalog.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = catalog.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val badges = listOfNotNull(
                        if (catalog.builtIn) "встроенный" else "свой",
                        if (catalog.hasCredentials) "с логином" else null,
                        if (!catalog.enabled) "выключен" else null
                    )
                    Text(
                        text = badges.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(checked = catalog.enabled, onCheckedChange = onEnabledChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCheck, enabled = check != ConnectionCheck.Running) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Проверить")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (!catalog.builtIn) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            check?.let { ConnectionCheckLine(it) }
        }
    }
}

@Composable
private fun ConnectionCheckLine(check: ConnectionCheck) {
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (check) {
            ConnectionCheck.Running -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Проверяем соединение…", style = MaterialTheme.typography.bodySmall)
            }
            is ConnectionCheck.Success -> Text(
                text = check.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            is ConnectionCheck.Failure -> Text(
                text = check.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CatalogFormDialog(
    form: CatalogForm,
    onChange: ((CatalogForm) -> CatalogForm) -> Unit,
    onCheck: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.editingId == null) "Добавить каталог" else "Изменить каталог") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { value -> onChange { it.copy(name = value) } },
                    label = { Text("Название") },
                    placeholder = { Text("Например, Моя библиотека") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.url,
                    onValueChange = { value -> onChange { it.copy(url = value) } },
                    label = { Text("Адрес OPDS") },
                    placeholder = { Text("https://example.org/opds") },
                    singleLine = true,
                    isError = form.url.isNotBlank() && form.normalizedUrl == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { value -> onChange { it.copy(username = value) } },
                    label = { Text("Логин (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { value -> onChange { it.copy(password = value) } },
                    label = { Text("Пароль (необязательно)") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onCheck, enabled = form.canSave && form.check != ConnectionCheck.Running) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Проверить соединение")
                }
                form.check?.let { ConnectionCheckLine(it) }
                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = form.canSave) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
