package com.lumina.reader.ui.reader.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderInverseCapsule
import com.lumina.reader.ui.theme.LuminaShape

/** «3 из 37»; «— из 37» when the current match is not among the results. */
internal fun searchPositionLabel(index: Int, total: Int): String =
    if (index in 0 until total) "${index + 1} из $total" else "— из $total"

/**
 * After jumping to a search result (§7.8): «‹ 3 из 37 › ✕» in inverse
 * colours above the footer, stepping through the matches.
 */
@Composable
internal fun SearchNavigatorCapsule(
    index: Int,
    total: Int,
    colors: ReaderChromeColors,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onOpenResults: () -> Unit,
    modifier: Modifier = Modifier
) {
    ReaderInverseCapsule(colors = colors, modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, enabled = index > 0) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Предыдущее совпадение",
                    tint = colors.inverseContent.copy(alpha = if (index > 0) 1f else 0.4f)
                )
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(LuminaShape.Pill)
                    .clickable(onClickLabel = "Все результаты", onClick = onOpenResults)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = searchPositionLabel(index, total),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = colors.inverseContent
                )
            }
            IconButton(onClick = onNext, enabled = index < total - 1) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Следующее совпадение",
                    tint = colors.inverseContent.copy(alpha = if (index < total - 1) 1f else 0.4f)
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Закрыть поиск", tint = colors.inverseContent)
            }
        }
    }
}
