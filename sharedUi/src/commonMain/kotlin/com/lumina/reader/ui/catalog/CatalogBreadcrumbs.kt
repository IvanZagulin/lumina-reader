package com.lumina.reader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaShape

/**
 * «Флибуста › Авторы › Б» (spec §7.9): the last chip is filled ink, tapping
 * an earlier one returns to that level.
 */
@Composable
fun CatalogBreadcrumbs(
    crumbs: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (crumbs.size < 2) return
    val listState = rememberLazyListState()
    LaunchedEffect(crumbs.size) { listState.scrollToItem(crumbs.lastIndex) }
    val colors = MaterialTheme.colorScheme
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.first }) { index, (pageId, title) ->
            val isLast = index == crumbs.lastIndex
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            if (isLast) {
                Surface(
                    shape = LuminaShape.Pill,
                    color = colors.onBackground,
                    contentColor = colors.background,
                    modifier = Modifier.semantics { stateDescription = "Текущий раздел" }
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            } else {
                Surface(
                    onClick = { onSelect(pageId) },
                    shape = LuminaShape.Pill,
                    color = sunkenColor(),
                    contentColor = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "Вернуться в «$title»" }
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
