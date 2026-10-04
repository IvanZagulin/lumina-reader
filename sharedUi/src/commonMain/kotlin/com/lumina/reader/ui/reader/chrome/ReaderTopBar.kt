package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.theme.LuminaDimens

/**
 * The top capsule of the reader: back, book and chapter title, bookmark and
 * a menu (§4.2). 56dp tall, 12dp from the screen edges, under the status bar.
 */
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    isBookmarked: Boolean,
    bookmarkEnabled: Boolean,
    keepScreenOn: Boolean,
    colors: ReaderChromeColors,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onShowInfo: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = LuminaDimens.ChromeMargin, end = LuminaDimens.ChromeMargin, top = 8.dp)
    ) {
        ReaderChromeCapsule(
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LuminaDimens.CapsuleHeight)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onToggleBookmark, enabled = bookmarkEnabled) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Удалить закладку" else "Добавить закладку",
                        tint = if (isBookmarked) colors.accent else colors.content
                    )
                }
                ReaderOverflowMenu(
                    keepScreenOn = keepScreenOn,
                    colors = colors,
                    onToggleKeepScreenOn = onToggleKeepScreenOn,
                    onShowInfo = onShowInfo,
                    onShare = onShare
                )
            }
        }
    }
}

@Composable
private fun ReaderOverflowMenu(
    keepScreenOn: Boolean,
    colors: ReaderChromeColors,
    onToggleKeepScreenOn: () -> Unit,
    onShowInfo: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Ещё")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface
        ) {
            DropdownMenuItem(
                text = { Text("О книге", color = colors.content) },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.muted) },
                onClick = {
                    expanded = false
                    onShowInfo()
                }
            )
            DropdownMenuItem(
                text = { Text("Не гасить экран", color = colors.content) },
                trailingIcon = {
                    if (keepScreenOn) Icon(Icons.Rounded.Check, contentDescription = "Включено", tint = colors.accent)
                },
                onClick = {
                    expanded = false
                    onToggleKeepScreenOn()
                }
            )
            DropdownMenuItem(
                text = { Text("Поделиться", color = colors.content) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, tint = colors.muted) },
                onClick = {
                    expanded = false
                    onShare()
                }
            )
        }
    }
}
