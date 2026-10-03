package com.lumina.reader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.ui.reader.chrome.ReaderPageHeader
import com.lumina.reader.ui.reader.selection.ParagraphMarks
import com.lumina.reader.ui.reader.selection.paragraphMarks
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether scrolling currently counts as reading (a jump does not, a drag does). */
private class CountingSwitch(var enabled: Boolean)

/**
 * Continuous vertical reading of one chapter, with buttons into the
 * neighbouring chapters at both ends. The position is the first visible line.
 */
@Composable
internal fun ScrollChapterViewer(
    chapterIndex: Int,
    chapter: Chapter,
    parsedBook: ParsedBook,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    imageCache: ReaderImageCache,
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    extras: ReaderPageExtras,
    selection: ReaderSelectionState,
    navigationOwner: Any,
    callbacks: ReaderViewerCallbacks,
    modifier: Modifier = Modifier
) {
    // A new chapter gets a fresh list state, opened at its own position.
    key(chapterIndex) {
        ScrollChapterList(
            chapterIndex = chapterIndex,
            chapter = chapter,
            parsedBook = parsedBook,
            settings = settings,
            typography = typography,
            colors = colors,
            decorations = decorations,
            imageCache = imageCache,
            navigationRequest = navigationRequest,
            positionProvider = positionProvider,
            extras = extras,
            selection = selection,
            navigationOwner = navigationOwner,
            callbacks = callbacks,
            modifier = modifier
        )
    }
}

@Composable
private fun ScrollChapterList(
    chapterIndex: Int,
    chapter: Chapter,
    parsedBook: ParsedBook,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    imageCache: ReaderImageCache,
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    extras: ReaderPageExtras,
    selection: ReaderSelectionState,
    navigationOwner: Any,
    callbacks: ReaderViewerCallbacks,
    modifier: Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val totalChapters = parsedBook.chapters.size
    val scrollItems = remember(chapter.paragraphs.size, totalChapters) {
        ScrollItems(
            hasPrevious = chapterIndex > 0,
            paragraphCount = chapter.paragraphs.size,
            hasNext = chapterIndex < totalChapters - 1
        )
    }
    val chapterTitle = remember(chapter.title, chapterIndex) { displayChapterTitle(chapter.title, chapterIndex) }
    val initialRequest = remember { navigationRequest?.takeIf { it.chapterIndex == chapterIndex } }
    val initialAnchor = remember {
        initialRequest?.anchor
            ?: positionProvider().takeIf { it.chapterIndex == chapterIndex }?.anchor
            ?: TextAnchor.START
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollItems.itemIndexFor(initialAnchor))
    // Layouts of the paragraphs on screen, to turn scroll offsets into characters.
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    val counting = remember { CountingSwitch(enabled = initialRequest?.countAsReading == true) }
    val paragraphSpacing = with(density) { paragraphSpacingPx(settings, density).toDp() }

    LaunchedEffect(Unit) {
        if (initialAnchor.charOffset > 0) scrollToAnchor(listState, scrollItems, layouts, initialAnchor)
        initialRequest?.let { callbacks.onNavigationHandled(it.id) }
    }

    // A jump inside this chapter (search, bookmark, table of contents).
    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.chapterIndex != chapterIndex || request.id == initialRequest?.id) return@LaunchedEffect
        counting.enabled = request.countAsReading
        scrollToAnchor(listState, scrollItems, layouts, request.anchor)
        callbacks.onNavigationHandled(request.id)
    }

    // Only scrolling by hand counts as reading.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) counting.enabled = true
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { visibleRangeOf(listState, scrollItems, layouts, chapterIndex) }
            .distinctUntilChanged()
            .collect { range ->
                if (range != null) callbacks.onVisibleRangeChanged(range, counting.enabled)
            }
    }

    DisposableEffect(settings.volumeKeyNavigation, listState) {
        if (settings.volumeKeyNavigation) {
            ReaderPageNavigation.register(navigationOwner) { direction ->
                coroutineScope.launch {
                    counting.enabled = true
                    val viewport = listState.layoutInfo.viewportSize.height
                        .takeIf { it > 0 }
                        ?.times(0.88f)
                        ?: 900f
                    val delta = if (direction == PageTurnDirection.NEXT) viewport else -viewport
                    val consumed = listState.scrollBy(delta)
                    if (abs(consumed) < 1f) {
                        if (direction == PageTurnDirection.NEXT) callbacks.onNextChapter()
                        else callbacks.onPreviousChapter()
                    }
                }
                true
            }
        }
        onDispose { ReaderPageNavigation.unregister(navigationOwner) }
    }

    DisposableEffect(selection, listState) {
        val provider: () -> VisibleText? = {
            VisibleText(
                chapterIndex = chapterIndex,
                paragraphs = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    scrollItems.paragraphForItem(info.index)?.let { paragraph ->
                        VisibleParagraphText(
                            paragraphIndex = paragraph,
                            text = paragraphPlainText(chapter.paragraphs.getOrElse(paragraph) { "" })
                        )
                    }
                }
            )
        }
        selection.visibleTextProvider = provider
        onDispose {
            if (selection.visibleTextProvider === provider) selection.visibleTextProvider = null
        }
    }

    val pageColor = settings.theme.bgComposeColor
    // The status bar stays visible while reading: an opaque band covers it
    // and the sticky chapter title, then the text fades in below.
    val topBandPx = WindowInsets.statusBars.getTop(density) + with(density) { 28.dp.roundToPx() }
    val topPadding = with(density) { topBandPx.toDp() } + 16.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Показать меню") {
                        callbacks.onToggleControls()
                        true
                    }
                )
            }
            .trackSelectionGestures(selection)
            .pointerInput(selection) {
                detectTapGestures { offset ->
                    if (selection.dismissOnTap()) return@detectTapGestures
                    if (offset.x in (size.width * 0.25f)..(size.width * 0.75f)) {
                        callbacks.onToggleControls()
                    }
                }
            }
    ) {
        key(selection.resetKey) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = settings.horizontalPaddingDp.dp)
                        .edgeFades(pageColor, topBandPx.toFloat()),
                    contentPadding = PaddingValues(top = topPadding, bottom = 70.dp)
                ) {
                    if (scrollItems.hasPrevious) {
                        item(key = "previous_chapter") {
                            ChapterLinkItem(
                                text = "← Предыдущая глава",
                                settings = settings,
                                onClick = callbacks.onPreviousChapter
                            )
                        }
                    }
                    item(key = "title") {
                        BasicText(
                            text = chapterTitle,
                            style = chapterTitleStyle(typography, colors.text),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 20.dp)
                        )
                    }
                    items(
                        count = chapter.paragraphs.size,
                        key = { index -> "p_$index" },
                        contentType = { "paragraph" }
                    ) { index ->
                        ScrollParagraph(
                            raw = chapter.paragraphs[index],
                            paragraphIndex = index,
                            parsedBook = parsedBook,
                            typography = typography,
                            colors = colors,
                            decorations = decorations,
                            ttsSentence = extras.ttsSentence?.rangeFor(chapterIndex, index),
                            extras = extras,
                            imageCache = imageCache,
                            paragraphSpacing = paragraphSpacing,
                            onNoteClick = callbacks.onNoteClick,
                            onHighlightClick = callbacks.onHighlightClick,
                            onLayout = { paragraph, layout -> layouts[paragraph] = layout },
                            onDisposed = { paragraph -> layouts.remove(paragraph) }
                        )
                    }
                    item(key = "chapter_divider") {
                        ChapterDivider(color = settings.theme.secondaryTextComposeColor)
                    }
                    item(key = "chapter_end") {
                        ChapterEndItem(
                            hasNext = scrollItems.hasNext,
                            nextTitle = parsedBook.chapters.getOrNull(chapterIndex + 1)
                                ?.let { displayChapterTitle(it.title, chapterIndex + 1) }
                                .orEmpty(),
                            settings = settings,
                            onNextChapter = callbacks.onNextChapter
                        )
                    }
                }
            }
        }
        // §9.5: the chapter title stays readable over the fading top edge.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = settings.horizontalPaddingDp.dp)
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ReaderPageHeader(title = chapterTitle, color = settings.theme.secondaryTextComposeColor)
        }
    }
}

/**
 * §9.5: 16dp fades in the page colour at the top and bottom edges, plus an
 * opaque band behind the status bar and the sticky title.
 */
private fun Modifier.edgeFades(pageColor: Color, band: Float): Modifier = drawWithCache {
    val fade = 16.dp.toPx()
    val top = Brush.verticalGradient(
        0f to pageColor,
        (band / (band + fade)) to pageColor,
        1f to Color.Transparent,
        startY = 0f,
        endY = band + fade
    )
    val bottom = Brush.verticalGradient(
        0f to Color.Transparent,
        1f to pageColor,
        startY = size.height - fade,
        endY = size.height
    )
    onDrawWithContent {
        drawContent()
        drawRect(top, size = Size(size.width, band + fade))
        drawRect(bottom, topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade))
    }
}

/** «❦» between the end of a chapter and the way on (§9.5). */
@Composable
private fun ChapterDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "❦", color = color.copy(alpha = 0.7f), fontSize = 20.sp)
    }
}

@Composable
private fun ScrollParagraph(
    raw: String,
    paragraphIndex: Int,
    parsedBook: ParsedBook,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    ttsSentence: OffsetRange?,
    extras: ReaderPageExtras,
    imageCache: ReaderImageCache,
    paragraphSpacing: Dp,
    onNoteClick: (String) -> Unit,
    onHighlightClick: (Long) -> Unit,
    onLayout: (Int, TextLayoutResult) -> Unit,
    onDisposed: (Int) -> Unit
) {
    val imageId = ParagraphMarkup.imageId(raw)
    when {
        imageId != null -> BookImageInline(
            imageId = imageId,
            bytes = parsedBook.images[imageId],
            cache = imageCache,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        raw.isBlank() -> {
            // A stanza break or blank line: the same gap as in the paged reader.
            val density = LocalDensity.current
            val gap = with(density) { blankParagraphGapPx(typography, density).toDp() }
            Spacer(modifier = Modifier.height(gap))
        }
        else -> {
            val density = LocalDensity.current
            val highlights = decorations.highlightsFor(paragraphIndex)
            val searchMatch = decorations.searchMatchFor(paragraphIndex)
            val rendered = remember(raw, typography, colors, highlights) {
                renderParagraph(raw, typography, colors, highlights, onNoteClick, onHighlightClick)
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val marks = remember(highlights, searchMatch, ttsSentence) {
                if (highlights.isEmpty() && searchMatch == null && ttsSentence == null) {
                    ParagraphMarks.None
                } else {
                    ParagraphMarks(highlights, searchMatch, ttsSentence)
                }
            }
            val margins = remember(rendered.blockStyle, typography, density) {
                blockMarginsPx(rendered, typography, density)
            }
            DisposableEffect(paragraphIndex) {
                onDispose { onDisposed(paragraphIndex) }
            }
            BasicText(
                text = rendered.text,
                style = rendered.style,
                onTextLayout = { result ->
                    layout = result
                    onLayout(paragraphIndex, result)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = with(density) { margins.start.toDp() },
                        end = with(density) { margins.end.toDp() },
                        bottom = paragraphSpacing
                    )
                    .paragraphMarks(marks, extras.markColors, { layout }, extras.searchAlpha)
            )
        }
    }
}

@Composable
private fun ChapterLinkItem(
    text: String,
    settings: ReaderSettings,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick) {
            Text(text = text, color = settings.theme.secondaryTextComposeColor)
        }
    }
}

@Composable
private fun ChapterEndItem(
    hasNext: Boolean,
    nextTitle: String,
    settings: ReaderSettings,
    onNextChapter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasNext) {
            OutlinedButton(
                onClick = onNextChapter,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Следующая глава →",
                    color = settings.theme.textComposeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (nextTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nextTitle,
                    color = settings.theme.secondaryTextComposeColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = "Конец книги",
                color = settings.theme.secondaryTextComposeColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Scrolls so that [anchor] is at the top: first to its paragraph, then, once
 * the paragraph is laid out, down to the line that contains the character.
 */
private suspend fun scrollToAnchor(
    listState: LazyListState,
    scrollItems: ScrollItems,
    layouts: Map<Int, TextLayoutResult>,
    anchor: TextAnchor
) {
    val itemIndex = scrollItems.itemIndexFor(anchor)
    listState.scrollToItem(itemIndex)
    if (anchor.charOffset <= 0 || anchor.paragraphIndex == Int.MAX_VALUE) return
    var layout = layouts[anchor.paragraphIndex]
    var frames = 0
    while (layout == null && frames < 3) {
        withFrameNanos { }
        frames++
        layout = layouts[anchor.paragraphIndex]
    }
    val paragraphLayout = layout ?: return
    val offset = anchor.charOffset.coerceIn(0, paragraphLayout.layoutInput.text.length)
    val line = paragraphLayout.getLineForOffset(offset)
    listState.scrollToItem(itemIndex, paragraphLayout.getLineTop(line).roundToInt())
}

/**
 * What the list shows: from the first visible line (as a character anchor)
 * up to the paragraph after the last visible one.
 */
private fun visibleRangeOf(
    listState: LazyListState,
    scrollItems: ScrollItems,
    layouts: Map<Int, TextLayoutResult>,
    chapterIndex: Int
): VisibleRange? {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    var start: TextAnchor? = null
    var end: TextAnchor? = null
    for (info in visible) {
        val paragraph = scrollItems.paragraphForItem(info.index)
        if (paragraph == null) {
            if (info.index == scrollItems.endItemIndex) end = TextAnchor.CHAPTER_END
            continue
        }
        if (start == null && info.offset + info.size > 0) {
            val y = -info.offset
            val layout = layouts[paragraph]
            val offset = if (layout != null && y > 0) {
                layout.getLineStart(layout.getLineForVerticalPosition(y.toFloat()))
            } else {
                0
            }
            start = TextAnchor(paragraph, offset)
        }
        if (end != TextAnchor.CHAPTER_END) end = TextAnchor(paragraph + 1, 0)
    }
    val first = start ?: if (end == TextAnchor.CHAPTER_END && scrollItems.paragraphCount > 0) {
        TextAnchor(scrollItems.paragraphCount - 1, 0)
    } else {
        TextAnchor.START
    }
    return VisibleRange(chapterIndex, first, end ?: TextAnchor(first.paragraphIndex + 1, 0))
}
