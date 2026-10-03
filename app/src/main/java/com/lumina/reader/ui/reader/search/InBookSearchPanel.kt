package com.lumina.reader.ui.reader.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.MIN_SEARCH_QUERY_LENGTH
import com.lumina.reader.ui.reader.SearchResult
import com.lumina.reader.ui.reader.SearchState
import com.lumina.reader.ui.reader.chrome.ReaderChromeCapsule
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaDimens

/** Results of one chapter, in book order. */
internal data class SearchChapterGroup(
    val chapterIndex: Int,
    val chapterTitle: String,
    val results: List<SearchResult>
)

/** Groups consecutive results by chapter (the search returns them in book order). */
internal fun groupSearchResults(results: List<SearchResult>): List<SearchChapterGroup> {
    val groups = ArrayList<SearchChapterGroup>()
    var start = 0
    while (start < results.size) {
        val chapter = results[start].chapterIndex
        var end = start
        while (end < results.size && results[end].chapterIndex == chapter) end++
        groups += SearchChapterGroup(chapter, results[start].chapterTitle, results.subList(start, end).toList())
        start = end
    }
    return groups
}

/** Russian plural: [one] for 1, 21…; [few] for 2–4, 22…; [many] otherwise. */
internal fun russianPlural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** «37 совпадений в 9 главах»; «первые 500 …» when the search stopped early. */
internal fun searchSummary(results: List<SearchResult>, truncated: Boolean): String {
    val chapters = results.map { it.chapterIndex }.distinct().size
    val matches = results.size
    val matchWord = russianPlural(matches, "совпадение", "совпадения", "совпадений")
    val chapterWord = if (chapters == 1) "главе" else "главах"
    val prefix = if (truncated) "Первые " else ""
    return "$prefix$matches $matchWord в $chapters $chapterWord"
}

/**
 * In-book search (§7.8): a full-screen panel in the page colour with a top
 * capsule (back · field · clear), a summary and results grouped under sticky
 * chapter headers, the match in bold on an accent tint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InBookSearchPanel(
    state: SearchState,
    colors: ReaderChromeColors,
    onQueryChange: (String) -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The field keeps its own text so typing never waits for the view model.
    var query by rememberSaveable { mutableStateOf(state.query) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    val groups = remember(state.results) { groupSearchResults(state.results) }
    Surface(
        color = colors.page,
        contentColor = colors.content,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            ReaderChromeCapsule(
                colors = colors,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LuminaDimens.ChromeMargin, vertical = 8.dp)
                    .heightIn(min = LuminaDimens.CapsuleHeight)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Закрыть поиск")
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text("Поиск по книге", color = colors.muted, style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { value ->
                                query = value
                                onQueryChange(value)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.content),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onQueryChange(query) }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                onQueryChange("")
                            }
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                        }
                    }
                }
            }

            val queryLongEnough = query.trim().length >= MIN_SEARCH_QUERY_LENGTH
            val searching = queryLongEnough && (state.isSearching || state.query != query)
            if (searching) {
                LinearProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.content.copy(alpha = 0.08f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
            }
            val summary = when {
                !queryLongEnough -> "Введите от $MIN_SEARCH_QUERY_LENGTH символов"
                searching && state.results.isEmpty() -> "Ищу…"
                state.results.isEmpty() -> "Ничего не найдено"
                else -> searchSummary(state.results, state.truncated)
            }
            Text(
                text = summary,
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                groups.forEach { group ->
                    stickyHeader(key = "chapter_${group.chapterIndex}") {
                        ChapterHeader(group, colors)
                    }
                    items(
                        group.results,
                        key = { "r_${it.chapterIndex}_${it.paragraphIndex}_${it.matchStart}" },
                        contentType = { "result" }
                    ) { result ->
                        ResultRow(result, colors, onClick = { onResultClick(result) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterHeader(group: SearchChapterGroup, colors: ReaderChromeColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.page)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.chapterTitle,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = group.results.size.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ResultRow(result: SearchResult, colors: ReaderChromeColors, onClick: () -> Unit) {
    val snippet = remember(result, colors) {
        buildAnnotatedString {
            val start = result.snippetMatchStart.coerceIn(0, result.snippet.length)
            val end = result.snippetMatchEnd.coerceIn(start, result.snippet.length)
            append(result.snippet.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = colors.accent.copy(alpha = 0.20f))) {
                append(result.snippet.substring(start, end))
            }
            append(result.snippet.substring(end))
        }
    }
    Text(
        text = snippet,
        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        color = colors.content,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    )
}
