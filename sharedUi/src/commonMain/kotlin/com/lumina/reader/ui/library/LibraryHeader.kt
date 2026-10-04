package com.lumina.reader.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.components.SearchPill
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaMotion

/**
 * «Библиотека» header (spec §4.1 `LibraryHeader`): the title with search and ⋯
 * circles, swapped for a [SearchPill] while searching (220 ms fade + 8 dp
 * slide), and the counts subtitle.
 */
@Composable
fun LibraryHeader(
    subtitle: String,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenViewSheet: () -> Unit,
    onManageShelves: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
    isCheckingForUpdates: Boolean,
    updateAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        AnimatedContent(
            targetState = searchActive,
            transitionSpec = {
                val enter = fadeIn(tween(220, easing = LuminaMotion.Emphasized)) +
                    slideInVertically(tween(220, easing = LuminaMotion.Emphasized)) { it / 7 }
                val exit = fadeOut(tween(220, easing = LuminaMotion.Emphasized)) +
                    slideOutVertically(tween(220, easing = LuminaMotion.Emphasized)) { -it / 7 }
                enter togetherWith exit
            },
            label = "library-header"
        ) { searching ->
            if (searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 8.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCloseSearch) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Закрыть поиск")
                    }
                    SearchPill(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = "Книга, автор, серия…",
                        autoFocus = true,
                        modifier = Modifier.weight(1f),
                        trailing = {
                            IconButton(onClick = onOpenViewSheet) {
                                Icon(
                                    Icons.Rounded.Tune,
                                    contentDescription = "Сортировка и вид",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Библиотека",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    if (updateAvailable) {
                        AssistChip(
                            onClick = onCheckForUpdates,
                            label = { Text("Обновление") },
                            leadingIcon = {
                                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = null
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    HeaderCircleButton(
                        icon = Icons.Rounded.Search,
                        description = "Поиск по библиотеке",
                        onClick = onOpenSearch
                    )
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        HeaderCircleButton(
                            icon = Icons.Rounded.MoreHoriz,
                            description = "Ещё",
                            onClick = { menuOpen = true },
                            showDot = updateAvailable
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Проверить обновления") },
                                leadingIcon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                                trailingIcon = if (isCheckingForUpdates) {
                                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                                } else {
                                    null
                                },
                                enabled = !isCheckingForUpdates,
                                onClick = {
                                    menuOpen = false
                                    onCheckForUpdates()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сортировка и вид") },
                                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenViewSheet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Управление полками") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onManageShelves()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                }
                            )
                        }
                    }
                }
            }
        }
        if (!searchActive) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A 44 dp `surfaceSunken` circle inside a 48 dp touch target. */
@Composable
private fun HeaderCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    showDot: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Lumina.colors.surfaceSunken, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
        }
        if (showDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
