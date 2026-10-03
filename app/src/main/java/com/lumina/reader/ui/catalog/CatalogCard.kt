package com.lumina.reader.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsUrls
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape

/** Menu actions of a catalogue card. */
class CatalogCardActions(
    val onCheck: (OpdsCatalogConfig) -> Unit,
    val onEdit: (OpdsCatalogConfig) -> Unit,
    val onHide: (OpdsCatalogConfig) -> Unit,
    val onDelete: (OpdsCatalogConfig) -> Unit
)

/**
 * A catalogue on the home screen (spec §7.9): monogram tile, name, host,
 * lock for catalogues with a login, a dot with the last check result and
 * a ⋯ menu («Проверить», «Изменить»/«Удалить», built-ins «Скрыть»).
 */
@Composable
internal fun CatalogCard(
    catalog: OpdsCatalogConfig,
    check: ConnectionCheck?,
    onClick: () -> Unit,
    actions: CatalogCardActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    HairlineCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = 88.dp)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CatalogMonogram(name = catalog.name)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = catalog.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (catalog.builtIn) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = colors.tertiaryContainer,
                            contentColor = colors.onTertiaryContainer,
                            shape = LuminaShape.Pill
                        ) {
                            Text(
                                text = "встроенный",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = OpdsUrls.host(catalog.url) ?: catalog.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (catalog.hasCredentials) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Вход по логину",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (check != null) {
                        Spacer(Modifier.width(8.dp))
                        CheckDot(check)
                    }
                }
                if (check is ConnectionCheck.Failure) {
                    Text(
                        text = check.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Действия с каталогом «${catalog.name}»",
                        tint = colors.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Проверить соединение") },
                        leadingIcon = { Icon(Icons.Rounded.NetworkCheck, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onCheck(catalog)
                        }
                    )
                    if (catalog.builtIn) {
                        DropdownMenuItem(
                            text = { Text("Скрыть") },
                            leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onHide(catalog)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Изменить") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onEdit(catalog)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = colors.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = colors.error) },
                            onClick = {
                                menuOpen = false
                                actions.onDelete(catalog)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 48dp tile with the catalogue's first letter on its cloth colour. */
@Composable
internal fun CatalogMonogram(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(LuminaShape.Tile)
            .background(clothColor(name)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = CatalogUi.monogram(name),
            color = onClothColor(name),
            fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp
        )
    }
}

@Composable
private fun CheckDot(check: ConnectionCheck) {
    when (check) {
        ConnectionCheck.Running -> CircularProgressIndicator(
            modifier = Modifier
                .size(10.dp)
                .semantics { contentDescription = "Проверяем соединение" },
            strokeWidth = 1.5.dp
        )
        is ConnectionCheck.Success -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(successColor())
                .semantics { contentDescription = "Каталог доступен" }
        )
        is ConnectionCheck.Failure -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .semantics { contentDescription = "Каталог не ответил" }
        )
    }
}

/** Dashed «+ Добавить каталог» card. */
@Composable
internal fun AddCatalogCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(LuminaShape.Card)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(20.dp.toPx() - stroke / 2),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                    )
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Добавить каталог",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** `success` from the extended palette, or `secondary` (same hue family) when it is not provided. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun successColor(): androidx.compose.ui.graphics.Color {
    val extended = Lumina.colors
    return if (extended.isDark == catalogIsDark()) extended.success else MaterialTheme.colorScheme.secondary
}
