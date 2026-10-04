package com.lumina.reader.ui.reader

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.ui.reader.chrome.ReaderPageFooter
import com.lumina.reader.ui.reader.chrome.ReaderPageHeader
import com.lumina.reader.ui.reader.chrome.timeLeftLabel
import com.lumina.reader.ui.reader.pageturn.CurlState
import com.lumina.reader.ui.reader.pageturn.curlDragGestures
import com.lumina.reader.ui.reader.pageturn.curlPage
import com.lumina.reader.ui.reader.pageturn.effectivePageTurn
import com.lumina.reader.ui.reader.pageturn.flipPage
import com.lumina.reader.ui.reader.pageturn.plainPage
import com.lumina.reader.ui.reader.pageturn.slidePage
import com.lumina.reader.ui.reader.pageturn.tapTurnSpec
import com.lumina.reader.ui.reader.selection.ParagraphMarks
import com.lumina.reader.ui.reader.selection.paragraphMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fixed chrome around the page body; every page has exactly the same body
 * size. The header leaves room for the status bar, which stays visible while
 * reading, and the footer keeps clear of the gesture navigation area.
 * Change these only together with [PageLayoutSpec] (the body size).
 */
private val PageHeaderHeight = 68.dp
private val PageFooterHeight = 60.dp
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
    extras: ReaderPageExtras,
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
    val spacingPx = paragraphSpacingPx(settings, density)
    val titleSpacingPx = with(density) { TitleSpacing.roundToPx() }
    val spec: PageLayoutSpec? = if (bodySize.width > 0 && bodySize.height > 0) {
        PageLayoutSpec(
            contentWidthPx = bodySize.width,
            contentHeightPx = bodySize.height,
            paragraphSpacingPx = spacingPx,
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
                    // §7.1 loading: a thin accent line instead of a spinner.
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(96.dp)
                            .height(2.dp),
                        color = settings.theme.accentComposeColor,
                        trackColor = settings.theme.textComposeColor.copy(alpha = 0.08f)
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
                    extras = extras,
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
    extras: ReaderPageExtras,
    selection: ReaderSelectionState,
    navigationOwner: Any,
    onBodySize: (IntSize) -> Unit,
    callbacks: ReaderViewerCallbacks
) {
    val scope = rememberCoroutineScope()
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
    val curl = remember { CurlState() }
    val turnStyle = effectivePageTurn(
        selected = settings.pageTurnAnimation,
        curlSupported = extras.curlSupported,
        readAloudDriving = extras.readAloudDriving
    )
    val latestTurnStyle by rememberUpdatedState(turnStyle)
    val latestReducedMotion by rememberUpdatedState(extras.reducedMotion)
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

    // A jump inside this chapter (search, bookmark, table of contents, read-aloud).
    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.chapterIndex != chapterIndex || request.id == initialRequest?.id) return@LaunchedEffect
        val target = chapterPages.pageIndexFor(request.anchor)
        val pagerPage = pagerLayout.pagerPageForContent(target)
        when {
            pagerState.settledPage == pagerPage && !pagerState.isScrollInProgress -> {
                gate.clear()
                reportPage(target, request.countAsReading)
            }
            request.animate && pagerPage == pagerState.settledPage + 1 && !curl.isActive -> {
                // Read-aloud reached the next page: turn it like the reader would.
                gate.expect(pagerPage, request.countAsReading)
                turnRequests.tryEmit(PageTurnDirection.NEXT)
            }
            else -> {
                gate.expect(pagerPage, request.countAsReading)
                pagerState.scrollToPage(pagerPage)
            }
        }
        callbacks.onNavigationHandled(request.id)
    }

    LaunchedEffect(pagerState, pagerLayout) {
        var requestedPage = pagerState.currentPage
        var pageAnimationJob: Job? = null
        turnRequests.collect { direction ->
            if (latestTurnStyle == PageTurnAnimation.CURL && !latestReducedMotion) {
                // Curl turns run one after another; each ends settled on its page.
                pageAnimationJob?.join()
                val current = pagerState.settledPage
                val target = if (direction == PageTurnDirection.NEXT) current + 1 else current - 1
                if (target in 0 until pagerLayout.pageCount && !curl.isActive) {
                    curl.scriptedTurn(current, direction == PageTurnDirection.NEXT) { page ->
                        pagerState.scrollToPage(page)
                    }
                }
                requestedPage = pagerState.settledPage
                return@collect
            }
            // A real swipe may have moved the pager since the previous
            // tap or volume-key request.
            if (pageAnimationJob?.isActive != true) {
                requestedPage = pagerState.settledPage
            }
            val target = if (direction == PageTurnDirection.NEXT) requestedPage + 1 else requestedPage - 1
            if (target in 0 until pagerLayout.pageCount) {
                requestedPage = target
                val spec = tapTurnSpec(latestTurnStyle, latestReducedMotion)
                // Interruptible: a rapid second tap heads for the new target.
                pageAnimationJob?.cancel()
                pageAnimationJob = launch {
                    if (spec == null) {
                        pagerState.scrollToPage(target)
                    } else {
                        pagerState.animateScrollToPage(page = target, animationSpec = spec)
                    }
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

    val tapZones = settings.tapZones
    val tapZonesInverted = settings.tapZonesInverted
    val isCurl = turnStyle == PageTurnAnimation.CURL
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                curl.pageWidth = size.width.toFloat()
                curl.pageHeight = size.height.toFloat()
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Следующая страница") {
                        turnRequests.tryEmit(PageTurnDirection.NEXT)
                    },
                    CustomAccessibilityAction("Предыдущая страница") {
                        turnRequests.tryEmit(PageTurnDirection.PREVIOUS)
                    },
                    CustomAccessibilityAction("Показать меню") {
                        callbacks.onToggleControls()
                        true
                    }
                )
            }
            .trackSelectionGestures(selection)
            .then(
                if (isCurl) {
                    Modifier.curlDragGestures(
                        curl = curl,
                        pagerState = pagerState,
                        pageCount = pagerLayout.pageCount,
                        isSelectionActive = { selection.hasSelection },
                        scope = scope,
                        onCommit = { page -> pagerState.scrollToPage(page) }
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(turnRequests, selection, tapZones, tapZonesInverted) {
                detectTapGestures { offset ->
                    if (selection.dismissOnTap()) return@detectTapGestures
                    when (
                        tapZoneAction(
                            x = offset.x,
                            y = offset.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            zones = tapZones,
                            inverted = tapZonesInverted
                        )
                    ) {
                        TapAction.PREVIOUS -> turnRequests.tryEmit(PageTurnDirection.PREVIOUS)
                        TapAction.NEXT -> turnRequests.tryEmit(PageTurnDirection.NEXT)
                        TapAction.MENU -> callbacks.onToggleControls()
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !isCurl
        ) { pagerPage ->
            ReaderPageContent(
                pagerPage = pagerPage,
                pagerState = pagerState,
                turnStyle = turnStyle,
                curl = curl,
                pagerLayout = pagerLayout,
                chapterIndex = chapterIndex,
                chapter = chapter,
                chapterTitle = chapterTitle,
                parsedBook = parsedBook,
                pages = pages,
                spec = spec,
                settings = settings,
                typography = typography,
                colors = colors,
                decorations = decorations,
                imageCache = imageCache,
                chapterLengths = chapterLengths,
                bookPageMap = bookPageMap,
                extras = extras,
                selection = selection,
                onBodySize = onBodySize,
                callbacks = callbacks,
                onFooterLongPress = {
                    if (bookPageMap != null) {
                        showPageJumpDialog = true
                    } else {
                        AppMessages.post("Страницы книги ещё считаются, попробуйте через пару секунд")
                    }
                }
            )
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
 * One page of the pager (§9 prerequisite 1): the page-turn modifier of the
 * current style, then either a chapter boundary page or header, body and
 * footer. Pages are opaque because the styles stack them.
 */
@Composable
private fun ReaderPageContent(
    pagerPage: Int,
    pagerState: PagerState,
    turnStyle: PageTurnAnimation,
    curl: CurlState,
    pagerLayout: ChapterPagerLayout,
    chapterIndex: Int,
    chapter: Chapter,
    chapterTitle: String,
    parsedBook: ParsedBook,
    pages: List<ReaderPage>,
    spec: PageLayoutSpec,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    imageCache: ReaderImageCache,
    chapterLengths: IntArray,
    bookPageMap: BookPageMap?,
    extras: ReaderPageExtras,
    selection: ReaderSelectionState,
    onBodySize: (IntSize) -> Unit,
    callbacks: ReaderViewerCallbacks,
    onFooterLongPress: () -> Unit
) {
    val pageColor = settings.theme.bgComposeColor
    val pageModifier = when (turnStyle) {
        PageTurnAnimation.SLIDE -> Modifier.slidePage(pagerState, pagerPage, pageColor, settings.theme.isDark)
        PageTurnAnimation.FLIP -> Modifier.flipPage(pagerState, pagerPage, pageColor)
        PageTurnAnimation.CURL -> {
            val layer = rememberGraphicsLayer()
            Modifier.curlPage(pagerState, pagerPage, curl, layer, pageColor)
        }
        PageTurnAnimation.NONE -> Modifier.plainPage(pagerPage, pageColor)
    }
    val contentPage = pagerLayout.contentPageForPager(pagerPage)
    Box(modifier = pageModifier.fillMaxSize()) {
        if (contentPage == null) {
            val isPrevious = pagerLayout.boundaryDirectionFor(pagerPage) == PageTurnDirection.PREVIOUS
            val adjacentIndex = if (isPrevious) chapterIndex - 1 else chapterIndex + 1
            ChapterBoundaryPage(
                isPrevious = isPrevious,
                chapterTitle = parsedBook.chapters.getOrNull(adjacentIndex)
                    ?.let { displayChapterTitle(it.title, adjacentIndex) }
                    .orEmpty(),
                settings = settings,
                typography = typography,
                colors = colors,
                spec = spec,
                onBodySize = onBodySize
            )
        } else {
            val page = pages[contentPage]
            val theme = settings.theme
            PageFrame(
                settings = settings,
                header = chapterTitle,
                onBodySize = onBodySize,
                footer = {
                    ReaderPageFooter(
                        position = bookPosition(
                            chapterIndex = chapterIndex,
                            localPage = contentPage,
                            chapterPageCount = pages.size,
                            chapterLengths = chapterLengths,
                            pageMap = bookPageMap
                        ),
                        showPages = settings.showBookPagesInFooter,
                        timeLeft = if (settings.showTimeLeft) timeLeftLabel(extras.minutesLeft) else null,
                        showProgressLine = settings.showProgressLine,
                        mutedColor = theme.secondaryTextComposeColor,
                        textColor = theme.textComposeColor,
                        accentColor = theme.accentComposeColor,
                        onToggle = callbacks.onToggleProgressDisplay,
                        onLongPress = onFooterLongPress
                    )
                }
            ) {
                key(selection.resetKey) {
                    SelectionContainer {
                        PageBody(
                            page = page,
                            chapterIndex = chapterIndex,
                            chapter = chapter,
                            chapterTitle = chapterTitle,
                            parsedBook = parsedBook,
                            spec = spec,
                            typography = typography,
                            colors = colors,
                            decorations = decorations,
                            extras = extras,
                            imageCache = imageCache,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
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
                .padding(bottom = 10.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            ReaderPageHeader(
                title = header,
                color = settings.theme.secondaryTextComposeColor
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
                .height(PageFooterHeight)
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            footer()
        }
    }
}

@Composable
private fun PageBody(
    page: ReaderPage,
    chapterIndex: Int,
    chapter: Chapter,
    chapterTitle: String,
    parsedBook: ParsedBook,
    spec: PageLayoutSpec,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    decorations: ChapterDecorations,
    extras: ReaderPageExtras,
    imageCache: ReaderImageCache,
    callbacks: ReaderViewerCallbacks
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
                            ttsSentence = extras.ttsSentence?.rangeFor(chapterIndex, block.paragraphIndex),
                            extras = extras,
                            onNoteClick = callbacks.onNoteClick,
                            onHighlightClick = callbacks.onHighlightClick
                        )
                        if (block.endsParagraph && spec.paragraphSpacingPx > 0) {
                            Spacer(
                                modifier = Modifier.height(with(density) { spec.paragraphSpacingPx.toDp() })
                            )
                        }
                    }
                    is PageBlock.Gap -> Spacer(
                        modifier = Modifier.height(with(density) { block.heightPx.toDp() })
                    )
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
 * Highlights, the search match and the spoken sentence are drawn behind the
 * text from its layout.
 */
@Composable
private fun ParagraphSlice(
    raw: String,
    slice: PageBlock.TextSlice,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    highlights: List<HighlightSpan>,
    searchMatch: OffsetRange?,
    ttsSentence: OffsetRange?,
    extras: ReaderPageExtras,
    onNoteClick: (String) -> Unit,
    onHighlightClick: (Long) -> Unit
) {
    val density = LocalDensity.current
    val rendered = remember(raw, typography, colors, highlights) {
        renderParagraph(raw, typography, colors, highlights, onNoteClick, onHighlightClick)
    }
    val margins = remember(rendered.blockStyle, typography, density) {
        blockMarginsPx(rendered, typography, density)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val marks = remember(highlights, searchMatch, ttsSentence) {
        if (highlights.isEmpty() && searchMatch == null && ttsSentence == null) {
            ParagraphMarks.None
        } else {
            ParagraphMarks(highlights, searchMatch, ttsSentence)
        }
    }
    BasicText(
        text = rendered.text,
        style = rendered.style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = with(density) { margins.start.toDp() },
                end = with(density) { margins.end.toDp() }
            )
            .clipToBounds()
            .lineWindow(topPx = slice.topPx, heightPx = slice.heightPx)
            .paragraphMarks(marks, extras.markColors, { layout }, extras.searchAlpha)
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

/**
 * The page between two chapters. Leading into the next chapter it looks like
 * that chapter's first page (same header, the title where the title will
 * be), so settling on it and switching chapters is seamless.
 */
@Composable
private fun ChapterBoundaryPage(
    isPrevious: Boolean,
    chapterTitle: String,
    settings: ReaderSettings,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    spec: PageLayoutSpec,
    onBodySize: (IntSize) -> Unit
) {
    val density = LocalDensity.current
    PageFrame(
        settings = settings,
        header = chapterTitle,
        onBodySize = onBodySize,
        footer = {}
    ) {
        if (isPrevious) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Предыдущая глава",
                    style = MaterialTheme.typography.labelLarge,
                    color = settings.theme.secondaryTextComposeColor
                )
                if (chapterTitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = chapterTitle,
                        style = chapterTitleStyle(typography, colors.text),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                BasicText(
                    text = AnnotatedString(chapterTitle),
                    style = chapterTitleStyle(typography, colors.text),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(with(density) { spec.titleSpacingPx.toDp() }))
            }
        }
    }
}
