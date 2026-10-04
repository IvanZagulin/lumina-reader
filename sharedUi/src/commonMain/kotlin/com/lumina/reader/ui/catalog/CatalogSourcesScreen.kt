package com.lumina.reader.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.ui.theme.LegacyM3Defaults
import com.lumina.reader.ui.theme.LuminaDimens

/** Manage OPDS catalogues: show/hide, add, edit, delete, test the connection. */
@Composable
fun CatalogSourcesScreen(
    viewModel: CatalogSourcesViewModel,
    onBack: () -> Unit
) {
    val catalogs by viewModel.catalogs.collectAsState()
    val checks by viewModel.checks.collectAsState()
    val form by viewModel.form.collectAsState()
    var catalogToDelete by remember { mutableStateOf<OpdsCatalogConfig?>(null) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CatalogTopBar(title = "Все каталоги", subtitle = "Выключенные не участвуют в поиске", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LuminaDimens.ScreenGutter,
                end = LuminaDimens.ScreenGutter,
                top = 8.dp,
                bottom = bottom
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hint", contentType = "hint") {
                Text(
                    text = "Встроенные каталоги можно скрыть, свои — изменить или удалить.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(catalogs, key = { it.id }, contentType = { "catalog" }) { catalog ->
                CatalogSourceRow(
                    catalog = catalog,
                    check = checks[catalog.id],
                    onEnabledChange = { enabled -> viewModel.setEnabled(catalog, enabled) },
                    onCheck = { viewModel.checkConnection(catalog) },
                    onEdit = { viewModel.startEditing(catalog) },
                    onDelete = { catalogToDelete = catalog },
                    modifier = Modifier.animateItem()
                )
            }
            item(key = "add", contentType = "add") {
                AddCatalogCard(onClick = viewModel::startAdding)
            }
            item(key = "proxy", contentType = "proxy") {
                val proxy by viewModel.proxy.collectAsState()
                ProxyCard(saved = proxy, onSave = viewModel::saveProxy)
            }
        }
    }

    form?.let { current ->
        CatalogEditorSheet(
            form = current,
            onChange = viewModel::updateForm,
            onCheck = viewModel::checkForm,
            onSave = viewModel::saveForm,
            onDismiss = viewModel::dismissForm
        )
    }

    catalogToDelete?.let { catalog ->
        DeleteCatalogDialog(
            catalog = catalog,
            onConfirm = {
                viewModel.delete(catalog)
                catalogToDelete = null
            },
            onDismiss = { catalogToDelete = null }
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    HairlineCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CatalogMonogram(name = catalog.name)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = catalog.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = catalog.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = listOfNotNull(
                                if (catalog.builtIn) "встроенный" else "свой",
                                if (!catalog.enabled) "скрыт" else null
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.tertiary
                        )
                        if (catalog.hasCredentials) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Вход по логину",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                Switch(
                    checked = catalog.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics {
                        contentDescription = "Показывать «${catalog.name}»"
                        stateDescription = if (catalog.enabled) "включён" else "скрыт"
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCheck,
                    enabled = check != ConnectionCheck.Running,
                    colors = LegacyM3Defaults.textButtonColors()
                ) {
                    Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Проверить")
                }
                Spacer(Modifier.weight(1f))
                if (!catalog.builtIn) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Изменить «${catalog.name}»")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Удалить «${catalog.name}»", tint = colors.error)
                    }
                }
            }
            check?.let { CheckResultLine(it, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) }
        }
    }
}
