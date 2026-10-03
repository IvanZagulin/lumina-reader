package com.lumina.reader.ui.reader

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Fixed chrome around the page body; every page has exactly the same body size. */
private val PageHeaderHeight = 56.dp
private val PageFooterHeight = 48.dp
private val TitleSpacing = 12.dp

/**
 * Book-like reader: the chapter is paginated off the main thread with the
 * same text styles the pages are drawn with, then shown in a pager whose
 * first and last pages lead into the neighbouring chapters.
 */
@Composable
internal fun PagedChapterViewer(
    chapterIndex: Int,
    chapter: Chapter,
    parsedBook: ParsedBook,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    pageCache: ChapterPageCache,
    imageCache: ReaderImageCache,
    chapterLengths: IntArray,
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    minutesLeft: Int?,
    selection: ReaderSelectionState,
    navigationOwner: Any,
    callbacks: ReaderViewerCallbacks,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    val chapterTitle = remember(chapter.title, chapterIndex) { displayChapterTitle(chapter.title, chapterIndex) }

    // The page body has the same size in every chapter. Keeping it across
    // chapter changes lets a new chapter paginate at once with the real size.
    var bodySize by remember { mutableStateOf(IntSize.Zero) }
    val onBodySize: (IntSize) -> Unit = remember {
        { size -> if (size.width > 0 && size.height > 0) bodySize = size }
    }
    val paragraphSpacingPx = with(density) { settings.paragraphSpacingDp.coerceAtLeast(0).dp.roundToPx() }
    val titleSpacingPx = with(density) { TitleSpacing.roundToPx() }
    val spec: PageLayoutSpec? = if (bodySize.width > 0 && bodySize.height > 0) {
        PageLayoutSpec(
            contentWidthPx = bodySize.width,
            contentHeightPx = bodySize.height,
            paragraphSpacingPx = paragraphSpacingPx,
            titleSpacingPx = titleSpacingPx,
            density = density.density,
            fontScale = density.fontScale,
            typography = typography
        )
    } else {
        null
    }

    var chapterPages by remember(chapterIndex, spec) {
        mutableStateOf(spec?.let { pageCache.get(it, chapterIndex) })
    }
    LaunchedEffect(chapterIndex, spec) {
        val currentSpec = spec ?: return@LaunchedEffect
        if (chapterPages != null) return@LaunchedEffect
        pageCache.activate(currentSpec)
        val paginated = withContext(Dispatchers.Default) {
            // A private measurer: TextMeasurer must not be shared between threads.
            val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, 0)
            ChapterPaginator(measurer, density, currentSpec).paginate(chapter, chapterIndex) { ensureActive() }
        }
        pageCache.put(currentSpec, paginated)
        chapterPages = paginated
    }

    // Paginate the rest of the book in the background with the same routine,
    // so the footer can show real book-wide page numbers and the next chapter
    // opens instantly.
    var bookPageMap by remember(parsedBook, spec) {
        mutableStateOf(spec?.let { pageCache.bookPageMap(it, parsedBook.chapters.size) })
    }
    val currentChapterReady = chapterPages != null
    LaunchedEffect(parsedBook, spec, currentChapterReady) {
        val currentSpec = spec ?: return@LaunchedEffect
        if (!currentChapterReady || bookPageMap != null) return@LaunchedEffect
        val chapters = parsedBook.chapters
        val map = withContext(Dispatchers.Default) {
            val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, 0)
            val paginator = ChapterPaginator(measurer, density, currentSpec)
            chapters.forEachIndexed { index, bookChapter ->
                ensureActive()
                if (pageCache.get(currentSpec, index) == null) {
                    pageCache.put(currentSpec, paginator.paginate(bookChapter, index) { ensureActive() })
                }
            }
            pageCache.bookPageMap(currentSpec, chapters.size)
        }
        bookPageMap = map
    }

    Box(modifier = modifier.fillMaxSize()) {
        val pages = chapterPages
        if (spec == null || pages == null) {
            PageFrame(
                settings = settings,
                header = chapterTitle,
                onBodySize = onBodySize,
                footer = {}
            ) {
                if (spec != null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        color = settings.theme.secondaryTextComposeColor,
                        strokeWidth = 2.dp
                    )
                }
            }
        } else {
            key(chapterIndex, spec) {
                ChapterPager(
                    chapterIndex = chapterIndex,
                    chapter = chapter,
                    chapterTitle = chapterTitle,
                    parsedBook = parsedBook,
                    chapterPages = pages,
                    spec = spec,
                    settings = settings,
                    typography = typography,
                    colors = colors,
                    decorations = decorations,
                    imageCache = imageCache,
                    chapterLengths = chapterLengths,
                    bookPageMap = bookPageMap,
                    navigationRequest = navigationRequest,
                    positionProvider = positionProvider,
                    minutesLeft = minutesLeft,
                    selection = selection,
                    navigationOwner = navigationOwner,
                    onBodySize = onBodySize,
                    callbacks = callbacks
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterPager(
    chapterIndex: Int,
    chapter: Chapter,
    chapterTitle: String,
    parsedBook: ParsedBook,
    chapterPages: ChapterPages,
    spec: PageLayoutSpec,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    imageCache: ReaderImageCache,
    chapterLengths: IntArray,
    bookPageMap: BookPageMap?,
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    minutesLeft: Int?,
    selection: ReaderSelectionState,
    navigationOwner: Any,
    onBodySize: (IntSize) -> Unit,
    callbacks: ReaderViewerCallbacks
) {
    val context = LocalContext.current
    val pages = chapterPages.pages
    val totalChapters = parsedBook.chapters.size
    val pagerLayout = remember(pages.size, chapterIndex, totalChapters) {
        ChapterPagerLayout(
            contentPageCount = pages.size,
            hasPreviousChapter = chapterIndex > 0,
            hasNextChapter = chapterIndex < totalChapters - 1
        )
    }
    val gate = remember { PageReportGate() }
    val initialRequest = remember { navigationRequest?.takeIf { it.chapterIndex == chapterIndex } }
    val initialContentPage = remember {
        val target = when {
            initialRequest != null -> chapterPages.pageIndexFor(initialRequest.anchor)
            else -> positionProvider()
                .takeIf { it.chapterIndex == chapterIndex }
                ?.let { chapterPages.pageIndexFor(it.anchor) }
                ?: 0
        }
        gate.expect(pagerLayout.pagerPageForContent(target), initialRequest?.countAsReading == true)
        target
    }
    val pagerState = rememberPagerState(
        initialPage = pagerLayout.pagerPageForContent(initialContentPage),
        pageCount = { pagerLayout.pageCount }
    )
    val turnRequests = remember {
        MutableSharedFlow<PageTurnDirection>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    var showPageJumpDialog by remember { mutableStateOf(false) }
    val latestBookPageMap by rememberUpdatedState(bookPageMap)

    val reportPage: (Int, Boolean) -> Unit = { contentPage, countWords ->
        callbacks.onVisibleRangeChanged(chapterPages.visibleRange(contentPage), countWords)
        callbacks.onPageProgressChanged(
            chapterIndex,
            bookPosition(chapterIndex, contentPage, pages.size, chapterLengths, latestBookPageMap).percent
        )
    }

    LaunchedEffect(Unit) {
        initialRequest?.let { callbacks.onNavigationHandled(it.id) }
    }

    // A jump inside this chapter (search, bookmark, table of contents).
    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.chapterIndex != chapterIndex || request.id == initialRequest?.id) return@LaunchedEffect
        val target = chapterPages.pageIndexFor(request.anchor)
        val pagerPage = pagerLayout.pagerPageForContent(target)
        if (pagerState.settledPage == pagerPage && !pagerState.isScrollInProgress) {
            gate.clear()
            reportPage(target, request.countAsReading)
        } else {
            gate.expect(pagerPage, request.countAsReading)
            pagerState.scrollToPage(pagerPage)
        }
        callbacks.onNavigationHandled(request.id)
    }

    LaunchedEffect(pagerState, pagerLayout) {
        var requestedPage = pagerState.currentPage
        var pageAnimationJob: Job? = null
        turnRequests.collect { direction ->
            // A real swipe may have moved the pager since the previous
            // tap or volume-key request.
            if (pageAnimationJob?.isActive != true) {
                requestedPage = pagerState.settledPage
            }
            val target = if (direction == PageTurnDirection.NEXT) requestedPage + 1 else requestedPage - 1
            if (target in 0 until pagerLayout.pageCount) {
                requestedPage = target
                // Interruptible: a rapid second tap heads for the new target.
                pageAnimationJob?.cancel()
                pageAnimationJob = launch {
                    pagerState.animateScrollToPage(
                        page = target,
                        animationSpec = tween(durationMillis = 155, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }

    DisposableEffect(settings.volumeKeyNavigation, pagerState) {
        if (settings.volumeKeyNavigation) {
            ReaderPageNavigation.register(navigationOwner) { direction ->
                turnRequests.tryEmit(direction)
            }
        }
        onDispose { ReaderPageNavigation.unregister(navigationOwner) }
    }

    // Re-report the current page once the exact book page map arrives.
    LaunchedEffect(bookPageMap) {
        val map = bookPageMap ?: return@LaunchedEffect
        pagerLayout.contentPageForPager(pagerState.settledPage)?.let { contentPage ->
            callbacks.onPageProgressChanged(
                chapterIndex,
                bookPosition(chapterIndex, contentPage, pages.size, chapterLengths, map).percent
            )
        }
    }

    LaunchedEffect(pagerState) {
        var boundaryTransitionCommitted = false
        snapshotFlow { pagerState.settledPage }.collect { pagerPage ->
            if (boundaryTransitionCommitted) return@collect
            when (pagerLayout.boundaryDirectionFor(pagerPage)) {
                PageTurnDirection.PREVIOUS -> {
                    boundaryTransitionCommitted = true
                    callbacks.onPreviousChapter()
                }
                PageTurnDirection.NEXT -> {
                    boundaryTransitionCommitted = true
                    callbacks.onNextChapter()
                }
                null -> {
                    // A selection left on the previous page is no longer visible.
                    if (selection.hasSelection) selection.clear()
                    val contentPage = pagerLayout.contentPageForPager(pagerPage) ?: return@collect
                    reportPage(contentPage, gate.consume(pagerPage))
                }
            }
        }
    }

    DisposableEffect(selection, pagerState) {
        val provider: () -> VisibleText? = {
            pagerLayout.contentPageForPager(pagerState.settledPage)
                ?.let(pages::getOrNull)
                ?.let { page ->
                    VisibleText(
                        chapterIndex = chapterIndex,
                        paragraphs = page.blocks
                            .filterIsInstance<PageBlock.TextSlice>()
                            .map { slice ->
                                VisibleParagraphText(
                                    paragraphIndex = slice.paragraphIndex,
                                    text = paragraphPlainText(chapter.paragraphs.getOrElse(slice.paragraphIndex) { "" }),
                                    visibleStart = slice.startOffset,
                                    visibleEnd = slice.endOffset
                                )
                            }
                    )
                }
        }
        selection.visibleTextProvider = provider
        onDispose {
            if (selection.visibleTextProvider === provider) selection.visibleTextProvider = null
        }
    }

    val tapZonesInverted = settings.tapZonesInverted
    Box(
        modifier = Modifier
            .fillMaxSize()
            .trackSelectionGestures(selection)
            .pointerInput(turnRequests, selection, tapZonesInverted) {
                detectTapGestures { offset ->
                    if (selection.dismissOnTap()) return@detectTapGestures
                    val backZone = offset.x < size.width * 0.30f
                    val forwardZone = offset.x > size.width * 0.70f
                    when {
                        backZone -> turnRequests.tryEmit(
                            if (tapZonesInverted) PageTurnDirection.NEXT else PageTurnDirection.PREVIOUS
                        )
                        forwardZone -> turnRequests.tryEmit(
                            if (tapZonesInverted) PageTurnDirection.PREVIOUS else PageTurnDirection.NEXT
                        )
                        else -> callbacks.onToggleControls()
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { pagerPage ->
            val contentPage = pagerLayout.contentPageForPager(pagerPage)
            if (contentPage == null) {
                val isPrevious = pagerLayout.boundaryDirectionFor(pagerPage) == PageTurnDirection.PREVIOUS
                val adjacentIndex = if (isPrevious) chapterIndex - 1 else chapterIndex + 1
                ChapterBoundaryPage(
                    isPrevious = isPrevious,
                    chapterTitle = parsedBook.chapters.getOrNull(adjacentIndex)
                        ?.let { displayChapterTitle(it.title, adjacentIndex) }
                        .orEmpty(),
                    settings = settings
                )
            } else {
                val page = pages[contentPage]
                PageFrame(
                    settings = settings,
                    header = chapterTitle,
                    onBodySize = onBodySize,
                    modifier = Modifier.graphicsLayer {
                        // Read the scroll position here, in the layer, so a swipe
                        // does not recompose the page on every frame.
                        val pageOffset = (pagerState.currentPage - pagerPage) +
                            pagerState.currentPageOffsetFraction
                        val distance = abs(pageOffset).coerceIn(0f, 1f)
                        scaleX = 1f - 0.025f * distance
                        scaleY = 1f - 0.025f * distance
                        alpha = 1f - 0.15f * distance
                    },
                    footer = {
                        ReaderProgressFooter(
                            chapterLabel = "Глава ${chapterIndex + 1} из $totalChapters",
                            position = bookPosition(
                                chapterIndex = chapterIndex,
                                localPage = contentPage,
                                chapterPageCount = pages.size,
                                chapterLengths = chapterLengths,
                                pageMap = bookPageMap
                            ),
                            showPages = settings.showBookPagesInFooter,
                            settings = settings,
                            minutesLeft = minutesLeft,
                            onToggle = callbacks.onToggleProgressDisplay,
                            onLongPress = {
                                if (bookPageMap != null) {
                                    showPageJumpDialog = true
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Страницы книги ещё считаются, попробуйте через пару секунд",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                ) {
                    key(selection.resetKey) {
                        SelectionContainer {
                            PageBody(
                                page = page,
                                chapter = chapter,
                                chapterTitle = chapterTitle,
                                parsedBook = parsedBook,
                                spec = spec,
                                typography = typography,
                                colors = colors,
                                decorations = decorations,
                                imageCache = imageCache,
                                onNoteClick = callbacks.onNoteClick
                            )
                        }
                    }
                }
            }
        }
    }

    val pageMapForJump = bookPageMap
    if (showPageJumpDialog && pageMapForJump != null) {
        val settledContentPage = pagerLayout.contentPageForPager(pagerState.settledPage) ?: 0
        BookJumpDialog(
            position = bookPosition(
                chapterIndex = chapterIndex,
                localPage = settledContentPage,
                chapterPageCount = pages.size,
                chapterLengths = chapterLengths,
                pageMap = pageMapForJump
            ),
            byPages = settings.showBookPagesInFooter,
            onDismiss = { showPageJumpDialog = false },
            onJump = { globalPageIndex ->
                showPageJumpDialog = false
                val target = pageMapForJump.locate(globalPageIndex)
                callbacks.onJumpToPosition(target.chapterIndex, target.paragraphIndex, target.charOffset)
            }
        )
    }
}

/**
 * The chrome of one page: running header, the page body and the footer.
 * Header and footer have fixed heights so the body, whose size drives
 * pagination, is identical on every page.
 */
@Composable
private fun PageFrame(
    settings: ReaderSettings,
    header: String,
    onBodySize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
    body: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = settings.horizontalPaddingDp.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PageHeaderHeight)
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(
                text = header,
                fontSize = 11.sp,
                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged(onBodySize)
                .clipToBounds(),
            content = body
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PageFooterHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            footer()
        }
    }
}

@Composable
private fun PageBody(
    page: ReaderPage,
    chapter: Chapter,
    chapterTitle: String,
    parsedBook: ParsedBook,
    spec: PageLayoutSpec,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    imageCache: ReaderImageCache,
    onNoteClick: (String) -> Unit
) {
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (page.showsTitle) {
            // Same AnnotatedString path as the measurement in ChapterPaginator.
            BasicText(
                text = AnnotatedString(chapterTitle),
                style = chapterTitleStyle(typography, colors.text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(with(density) { spec.titleSpacingPx.toDp() }))
        }
        for (block in page.blocks) {
            key(block.paragraphIndex, block.startOffset) {
                when (block) {
                    is PageBlock.TextSlice -> {
                        ParagraphSlice(
                            raw = chapter.paragraphs.getOrElse(block.paragraphIndex) { "" },
                            slice = block,
                            typography = typography,
                            colors = colors,
                            highlights = decorations.highlightsFor(block.paragraphIndex),
                            searchMatch = decorations.searchMatchFor(block.paragraphIndex),
                            onNoteClick = onNoteClick
                        )
                        if (block.endsParagraph && spec.paragraphSpacingPx > 0) {
                            Spacer(
                                modifier = Modifier.height(with(density) { spec.paragraphSpacingPx.toDp() })
                            )
                        }
                    }
                    is PageBlock.Image -> BookImageFill(
                        imageId = block.imageId,
                        bytes = parsedBook.images[block.imageId],
                        cache = imageCache,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Lines [PageBlock.TextSlice.startLine] until [PageBlock.TextSlice.endLine]
 * of a paragraph: the whole paragraph is laid out (exactly as it was
 * measured) and shifted and clipped to the slice, so a paragraph continued
 * from the previous page keeps its justification and has no indent.
 */
@Composable
private fun ParagraphSlice(
    raw: String,
    slice: PageBlock.TextSlice,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    highlights: List<HighlightSpan>,
    searchMatch: OffsetRange?,
    onNoteClick: (String) -> Unit
) {
    val density = LocalDensity.current
    val rendered = remember(raw, typography, colors, highlights, searchMatch) {
        renderParagraph(raw, typography, colors, highlights, searchMatch, onNoteClick)
    }
    val margins = remember(rendered.blockStyle, typography, density) {
        blockMarginsPx(rendered, typography, density)
    }
    BasicText(
        text = rendered.text,
        style = rendered.style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = with(density) { margins.start.toDp() },
                end = with(density) { margins.end.toDp() }
            )
            .clipToBounds()
            .lineWindow(topPx = slice.topPx, heightPx = slice.heightPx)
    )
}

/** Shows [heightPx] of the content starting [topPx] below its top. */
private fun Modifier.lineWindow(topPx: Int, heightPx: Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val height = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) {
            placeable.place(0, -topPx)
        }
    }

@Composable
private fun ChapterBoundaryPage(
    isPrevious: Boolean,
    chapterTitle: String,
    settings: ReaderSettings
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isPrevious) "← Предыдущая глава" else "Следующая глава →",
                style = MaterialTheme.typography.titleMedium,
                color = settings.theme.secondaryTextComposeColor
            )
            if (chapterTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = settings.theme.textComposeColor,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
