package com.lumina.reader.ui.reader

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.effectiveTheme
import com.lumina.reader.core.model.toggledDayNight
import com.lumina.reader.core.tts.TtsStatus
import com.lumina.reader.ui.reader.chrome.BookmarkRibbon
import com.lumina.reader.ui.reader.chrome.ReaderBottomBar
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderMaterialTheme
import com.lumina.reader.ui.reader.chrome.ReaderTopBar
import com.lumina.reader.ui.reader.chrome.ReturnToPageChip
import com.lumina.reader.ui.reader.chrome.ScrubberLabel
import com.lumina.reader.ui.reader.chrome.ScrubberModel
import com.lumina.reader.ui.reader.chrome.chapterAtFraction
import com.lumina.reader.ui.reader.chrome.chapterStartFractions
import com.lumina.reader.ui.reader.chrome.formatPercentLabel
import com.lumina.reader.ui.reader.chrome.rememberReaderChromeColors
import com.lumina.reader.ui.reader.chrome.scrubberStateDescription
import com.lumina.reader.ui.reader.navigation.BookNavigationSheet
import com.lumina.reader.ui.reader.navigation.quoteShareText
import com.lumina.reader.ui.reader.pageturn.isCurlSupported
import com.lumina.reader.ui.reader.search.InBookSearchPanel
import com.lumina.reader.ui.reader.search.SearchNavigatorCapsule
import com.lumina.reader.ui.reader.selection.AskAiSheet
import com.lumina.reader.ui.reader.selection.NoteEditorSheet
import com.lumina.reader.ui.reader.settings.findActivity
import com.lumina.reader.ui.reader.tts.TtsMiniPlayer
import com.lumina.reader.ui.reader.tts.TtsSheet
import com.lumina.reader.ui.reader.tts.isSpeaking
import com.lumina.reader.ui.reader.tts.nextTtsSpeed
import com.lumina.reader.ui.theme.LuminaHaptics
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val storedSettings by viewModel.settings.collectAsState()
    val loadedSettings = storedSettings
    if (loadedSettings == null) {
        // Settings are read in a few milliseconds; drawing with defaults first
        // would paginate twice and flash the wrong theme.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    } else {
        ReaderScreenContent(
            viewModel = viewModel,
            storedSettings = loadedSettings,
            onBack = onBack
        )
    }
}

/** A note being written: for a new selection or for a saved highlight. */
private sealed interface NoteRequest {
    val quote: String

    data class New(override val quote: String, val location: SelectionLocation, val colorHex: String) : NoteRequest

    data class Existing(val highlight: ReadingHighlight) : NoteRequest {
        override val quote: String get() = highlight.selectedText
    }
}

/** Where «Вернуться» leads after a jump. */
private data class ReturnTarget(val position: ReaderPosition, val label: String)

/** Height kept free for the page footer when the chrome is hidden. */
private val FooterClearance = 64.dp

@Composable
private fun ReaderScreenContent(
    viewModel: ReaderViewModel,
    storedSettings: ReaderSettings,
    onBack: () -> Unit
) {
    val systemInDarkMode = isSystemInDarkTheme()
    val effectiveTheme = storedSettings.effectiveTheme(systemInDarkMode)
    val settings = remember(storedSettings, effectiveTheme) { storedSettings.copy(theme = effectiveTheme) }
    // «Авто» keeps the drawn theme in ReaderSettings.theme, which the library
    // reads to paint the page of the opening transition.
    LaunchedEffect(storedSettings.themeMode, effectiveTheme, storedSettings.theme) {
        if (storedSettings.themeMode == ReaderThemeMode.SYSTEM && storedSettings.theme != effectiveTheme) {
            viewModel.updateSettings { current ->
                if (current.themeMode == ReaderThemeMode.SYSTEM) current.copy(theme = effectiveTheme) else current
            }
        }
    }
    val chrome = rememberReaderChromeColors(settings.theme)
    val reducedMotion = rememberReducedMotion()
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val curlSupported = remember(context) { isCurlSupported(context) }

    val book by viewModel.book.collectAsState()
    val parsedBook by viewModel.parsedBook.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val navigationRequest by viewModel.navigationRequest.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val isPageBookmarked by viewModel.isCurrentPageBookmarked.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val searchMatch by viewModel.activeSearchMatch.collectAsState()
    val minutesLeft by viewModel.minutesLeftInChapter.collectAsState()
    val pdfDocument by viewModel.pdfDocument.collectAsState()
    val textInfo by viewModel.textInfo.collectAsState()

    var showControls by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var navigationTab by remember { mutableStateOf<Int?>(null) }
    var showTtsSheet by remember { mutableStateOf(false) }
    var showBookInfo by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var noteRequest by remember { mutableStateOf<NoteRequest?>(null) }
    var askAiQuote by remember { mutableStateOf<String?>(null) }
    var returnTarget by remember { mutableStateOf<ReturnTarget?>(null) }
    var returnToken by remember { mutableIntStateOf(0) }
    var returnVisible by remember { mutableStateOf(false) }

    val isPdf = book?.format == BookFormat.PDF
    val ttsHere = viewModel.isTtsActiveHere(ttsState)
    val ttsSentence = ttsState.sentenceRange
        ?.takeIf { ttsHere && ttsState.status != TtsStatus.ERROR }
        ?.let { range -> TtsSentenceMark(ttsState.chapterIndex, ttsState.paragraphIndex, range.first, range.last + 1) }
    val readAloudDriving = ttsHere && ttsState.isSpeaking

    // A jump remembers where the reader was, for «Вернуться» (§4.2).
    fun rememberReturnPoint() {
        returnTarget = ReturnTarget(
            position = viewModel.position.value,
            label = "Вернуться на ${formatPercentLabel(viewModel.progressPercent.value)}"
        )
        returnVisible = true
        returnToken++
    }

    fun jumpTo(chapterIndex: Int, paragraphIndex: Int, charOffset: Int) {
        rememberReturnPoint()
        viewModel.goToPosition(chapterIndex, paragraphIndex, charOffset)
    }

    fun toggleBookmark() {
        if (!isPageBookmarked) LuminaHaptics.confirm(view)
        viewModel.toggleBookmarkAtCurrentPosition()
    }

    // The chip stays for five seconds after each jump (§8 #28).
    LaunchedEffect(returnToken) {
        if (returnToken > 0) {
            delay(5_000)
            returnVisible = false
        }
    }

    // Keep screen on management
    DisposableEffect(settings.keepScreenOn) {
        val window = context.findActivity()?.window
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Keep the Android status bar visible in reading mode so the clock,
    // battery level and system indicators stay available. Only the navigation
    // bar remains immersive while the reader controls are hidden.
    DisposableEffect(showControls, settings.theme) {
        val window = context.findActivity()?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.isAppearanceLightStatusBars = !settings.theme.isDark
            insetsController.show(WindowInsetsCompat.Type.statusBars())
            if (showControls) {
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose {
            val currentWindow = context.findActivity()?.window
            if (currentWindow != null) {
                WindowCompat.getInsetsController(currentWindow, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val isReaderReady = !isLoading && book != null && parsedBook != null
    DisposableEffect(lifecycleOwner, isReaderReady) {
        if (isReaderReady && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startSession()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (isReaderReady) viewModel.startSession()
                Lifecycle.Event.ON_STOP -> viewModel.saveSessionData()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.saveSessionData()
        }
    }

    // The search panel is this screen's own overlay: Back closes it (and only then).
    BackHandler(enabled = searchOpen) { searchOpen = false }

    val selectionActions = remember(viewModel) {
        ReaderSelectionActions(
            onHighlight = { text, location, colorHex ->
                viewModel.addHighlight(
                    chapterIndex = location.chapterIndex,
                    paragraphIndex = location.paragraphIndex,
                    startOffset = location.startOffset,
                    endOffset = location.endOffset,
                    text = text,
                    colorHex = colorHex
                )
            },
            onNote = { text, location, colorHex -> noteRequest = NoteRequest.New(text, location, colorHex) },
            onShare = { text ->
                val currentBook = viewModel.book.value
                shareText(context, quoteShareText(text, currentBook?.title.orEmpty(), currentBook?.author.orEmpty()))
            },
            onAskAi = { text -> askAiQuote = text },
            onFind = { text ->
                viewModel.onSearchQueryChange(text.trim().replace(Regex("\\s+"), " ").take(MAX_FIND_QUERY_LENGTH))
                showControls = false
                searchOpen = true
            },
            onRecolorHighlight = { id, colorHex ->
                viewModel.highlights.value.firstOrNull { it.id == id }
                    ?.let { viewModel.updateHighlightColor(it, colorHex) }
            },
            onEditHighlightNote = { id ->
                viewModel.highlights.value.firstOrNull { it.id == id }
                    ?.let { noteRequest = NoteRequest.Existing(it) }
            },
            onDeleteHighlight = { id ->
                viewModel.highlights.value.firstOrNull { it.id == id }?.let(viewModel::deleteHighlight)
            }
        )
    }

    val pageColor by animateColorAsState(
        targetValue = settings.theme.bgComposeColor,
        animationSpec = tween(if (reducedMotion) 0 else 300),
        label = "readerPage"
    )

    ReaderMaterialTheme(chrome) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageColor)
        ) {
            val currentBook = book
            val currentParsedBook = parsedBook
            // Text info is published with the book; waiting for it avoids
            // paginating once without the book language and once with it.
            val textInfoPending = currentParsedBook != null &&
                textInfo.chapterLengths.size != currentParsedBook.chapters.size
            val contentReady = !isLoading && !textInfoPending && currentBook != null &&
                currentParsedBook != null && currentChapterIndex in currentParsedBook.chapters.indices
            when {
                isLoading || textInfoPending -> ReaderLoading(chrome)

                contentReady && currentBook != null && currentParsedBook != null -> ReaderContent(
                    book = currentBook,
                    parsedBook = currentParsedBook,
                    chapterIndex = currentChapterIndex,
                    settings = settings,
                    chromeColors = chrome,
                    navigationRequest = navigationRequest,
                    positionProvider = { viewModel.position.value },
                    textInfo = textInfo,
                    highlights = highlights,
                    searchMatch = searchMatch,
                    ttsSentence = ttsSentence,
                    readAloudDriving = readAloudDriving,
                    reducedMotion = reducedMotion,
                    curlSupported = curlSupported,
                    minutesLeftInChapter = minutesLeft,
                    pageCache = viewModel.pageCache,
                    imageCache = viewModel.imageCache,
                    pdfDocument = pdfDocument,
                    onNavigationHandled = viewModel::onNavigationHandled,
                    onVisibleRangeChanged = viewModel::onVisibleRangeChanged,
                    onPageProgressChanged = viewModel::onPageProgressChanged,
                    onNextChapter = viewModel::nextChapter,
                    onPreviousChapter = viewModel::previousChapter,
                    onJumpToPosition = { chapterIndex, paragraphIndex, charOffset ->
                        jumpTo(chapterIndex, paragraphIndex, charOffset)
                    },
                    onToggleControls = { showControls = !showControls },
                    onToggleProgressDisplay = {
                        viewModel.updateSettings {
                            it.copy(showBookPagesInFooter = !it.showBookPagesInFooter)
                        }
                    },
                    selectionActions = selectionActions
                )

                else -> ReaderLoadError(
                    message = loadError ?: "Не удалось подготовить текст книги",
                    colors = chrome,
                    onBack = onBack
                )
            }

            if (contentReady) {
                // §4.2 BookmarkRibbon: visible with the chrome hidden; the
                // 48×48dp corner under the status bar toggles the bookmark.
                BookmarkRibbon(
                    visible = isPageBookmarked,
                    color = chrome.accent,
                    reducedMotion = reducedMotion,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 20.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { toggleBookmark() }
                        )
                        .semantics {
                            contentDescription = if (isPageBookmarked) "Удалить закладку" else "Добавить закладку"
                        }
                )
            }

            AnimatedVisibility(
                visible = showControls && !searchOpen,
                enter = fadeIn(tween(200, easing = LuminaMotion.EmphasizedDecelerate)) +
                    slideInVertically(tween(200, easing = LuminaMotion.EmphasizedDecelerate)) {
                        -with(density) { 16.dp.roundToPx() }
                    },
                exit = fadeOut(tween(150, easing = LuminaMotion.EmphasizedAccelerate)) +
                    slideOutVertically(tween(150, easing = LuminaMotion.EmphasizedAccelerate)) {
                        -with(density) { 16.dp.roundToPx() }
                    },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    title = book?.title.orEmpty(),
                    subtitle = currentParsedBook?.chapters?.getOrNull(currentChapterIndex)
                        ?.let { chapter ->
                            if (isPdf) {
                                "Страница ${currentChapterIndex + 1} из ${currentParsedBook.chapters.size}"
                            } else {
                                displayChapterTitle(chapter.title, currentChapterIndex)
                            }
                        }
                        .orEmpty(),
                    isBookmarked = isPageBookmarked,
                    bookmarkEnabled = parsedBook != null,
                    keepScreenOn = settings.keepScreenOn,
                    colors = chrome,
                    onBack = onBack,
                    onToggleBookmark = { toggleBookmark() },
                    onToggleKeepScreenOn = {
                        viewModel.updateSettings { it.copy(keepScreenOn = !it.keepScreenOn) }
                    },
                    onShowInfo = { showBookInfo = true },
                    onShare = {
                        val current = book
                        if (current != null) {
                            val author = current.author.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                            shareText(context, "Читаю «${current.title}»$author")
                        }
                    }
                )
            }

            // The floating stack above the bottom bar, or above the footer
            // when the chrome is hidden; it moves with settle().
            var barHeightPx by remember { mutableIntStateOf(0) }
            val gapPx = with(density) { 8.dp.roundToPx() }
            val footerPx = with(density) {
                (if (settings.isContinuousScroll && !isPdf) 24.dp else FooterClearance).roundToPx()
            }
            val liftTarget = if (showControls && !searchOpen) barHeightPx + gapPx else footerPx
            val lift by animateIntAsState(
                targetValue = liftTarget,
                animationSpec = if (reducedMotion) tween(0) else LuminaMotion.settle(),
                label = "floatingLift"
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .offset { IntOffset(0, -lift) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = returnVisible && returnTarget != null && !searchOpen,
                    enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { with(density) { 8.dp.roundToPx() } },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { with(density) { 8.dp.roundToPx() } }
                ) {
                    ReturnToPageChip(
                        label = returnTarget?.label.orEmpty(),
                        colors = chrome,
                        onClick = {
                            returnVisible = false
                            returnTarget?.let { back ->
                                viewModel.goToPosition(
                                    back.position.chapterIndex,
                                    back.position.paragraphIndex,
                                    back.position.charOffset
                                )
                            }
                        }
                    )
                }
                val showNavigator = searchMatch != null && !searchOpen && searchState.results.isNotEmpty()
                if (showNavigator) {
                    SearchNavigatorCapsule(
                        index = viewModel.activeSearchResultIndex(),
                        total = searchState.results.size,
                        colors = chrome,
                        onPrevious = { viewModel.openAdjacentSearchResult(-1) },
                        onNext = { viewModel.openAdjacentSearchResult(1) },
                        onClose = { viewModel.clearSearchMatch() },
                        onOpenResults = {
                            showControls = false
                            searchOpen = true
                        }
                    )
                }
                AnimatedVisibility(
                    visible = ttsHere && !searchOpen,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { with(density) { 24.dp.roundToPx() } },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { with(density) { 24.dp.roundToPx() } }
                ) {
                    TtsMiniPlayer(
                        state = ttsState,
                        paragraphCount = currentParsedBook?.chapters?.getOrNull(ttsState.chapterIndex)?.paragraphs?.size ?: 0,
                        colors = chrome,
                        reducedMotion = reducedMotion,
                        onTogglePlay = viewModel::toggleTts,
                        onNext = viewModel::ttsNextParagraph,
                        onStop = viewModel::stopTts,
                        onCycleSpeed = { viewModel.setTtsSpeed(nextTtsSpeed(ttsState.speechRate)) },
                        onOpenPlayer = { showTtsSheet = true }
                    )
                }
            }

            AnimatedVisibility(
                visible = showControls && !searchOpen,
                enter = fadeIn(tween(200, delayMillis = 40, easing = LuminaMotion.EmphasizedDecelerate)) +
                    slideInVertically(tween(200, delayMillis = 40, easing = LuminaMotion.EmphasizedDecelerate)) {
                        with(density) { 24.dp.roundToPx() }
                    },
                exit = fadeOut(tween(150, easing = LuminaMotion.EmphasizedAccelerate)) +
                    slideOutVertically(tween(150, easing = LuminaMotion.EmphasizedAccelerate)) {
                        with(density) { 24.dp.roundToPx() }
                    },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val progressPercent by viewModel.progressPercent.collectAsState()
                val chapters = currentParsedBook?.chapters.orEmpty()
                val starts = remember(textInfo) { chapterStartFractions(textInfo.chapterLengths) }
                val fraction = (progressPercent / 100f).coerceIn(0f, 1f)
                val scrubber = if (chapters.isEmpty()) {
                    null
                } else {
                    ScrubberModel(
                        value = fraction,
                        chapterStarts = starts,
                        chapterLabel = if (isPdf) "Стр. ${currentChapterIndex + 1}" else "Гл. ${currentChapterIndex + 1}",
                        percentLabel = "${progressPercent.toInt()}%",
                        stateDescription = scrubberStateDescription(fraction, currentChapterIndex + 1),
                        labelFor = { f ->
                            val index = chapterAtFraction(starts, f)
                            val title = chapters.getOrNull(index)?.let { displayChapterTitle(it.title, index) }.orEmpty()
                            ScrubberLabel(
                                title = if (isPdf) "Страница ${index + 1}" else "Глава ${index + 1} · $title",
                                detail = formatPercentLabel(f * 100f)
                            )
                        },
                        onJump = { f ->
                            rememberReturnPoint()
                            viewModel.goToBookFraction(f)
                        }
                    )
                }
                ReaderBottomBar(
                    colors = chrome,
                    scrubber = scrubber,
                    isTtsPlaying = readAloudDriving,
                    showTextActions = !isPdf,
                    onOpenNavigation = { navigationTab = 0 },
                    onOpenSearch = {
                        showControls = false
                        searchOpen = true
                    },
                    onListen = viewModel::toggleTts,
                    onToggleTheme = { viewModel.updateSettings { it.toggledDayNight(systemInDarkMode) } },
                    onLongPressTheme = { showSettingsSheet = true },
                    onOpenSettings = { showSettingsSheet = true },
                    modifier = Modifier.onSizeChanged { barHeightPx = it.height }
                )
            }

            AnimatedVisibility(
                visible = searchOpen,
                enter = slideInVertically(tween(280, easing = LuminaMotion.EmphasizedDecelerate)) { -it } +
                    fadeIn(tween(280)),
                exit = slideOutVertically(tween(200, easing = LuminaMotion.EmphasizedAccelerate)) { -it } +
                    fadeOut(tween(200))
            ) {
                InBookSearchPanel(
                    state = searchState,
                    colors = chrome,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onResultClick = { result ->
                        searchOpen = false
                        showControls = false
                        rememberReturnPoint()
                        viewModel.openSearchResult(result)
                    },
                    onClose = { searchOpen = false }
                )
            }
        }

        if (showSettingsSheet) {
            ReaderSettingsSheet(
                settings = storedSettings,
                colors = chrome,
                reducedMotion = reducedMotion,
                curlSupported = curlSupported,
                onSettingsChanged = viewModel::updateSettings,
                onSettingsChangedDebounced = viewModel::updateSettingsDebounced,
                onDismiss = { showSettingsSheet = false }
            )
        }

        val tab = navigationTab
        val navigationBook = parsedBook
        if (tab != null && navigationBook != null) {
            val position by viewModel.position.collectAsState()
            val progressPercent by viewModel.progressPercent.collectAsState()
            val minutesLeftInBook by viewModel.minutesLeftInBook.collectAsState()
            BookNavigationSheet(
                book = book,
                parsedBook = navigationBook,
                position = position,
                progressPercent = progressPercent,
                minutesLeftInBook = minutesLeftInBook,
                bookmarks = bookmarks,
                highlights = highlights,
                colors = chrome,
                reducedMotion = reducedMotion,
                initialTab = tab,
                onNavigate = { chapterIndex, paragraphIndex, charOffset ->
                    navigationTab = null
                    showControls = false
                    jumpTo(chapterIndex, paragraphIndex, charOffset)
                },
                onDeleteBookmark = viewModel::deleteBookmark,
                onRestoreBookmark = viewModel::restoreBookmark,
                onDeleteHighlight = viewModel::deleteHighlight,
                onRestoreHighlight = viewModel::restoreHighlight,
                onEditHighlight = { highlight -> noteRequest = NoteRequest.Existing(highlight) },
                onShareText = { text -> shareText(context, text) },
                onDismiss = { navigationTab = null }
            )
        }

        if (showTtsSheet && ttsHere) {
            TtsSheet(
                state = ttsState,
                colors = chrome,
                onTogglePlay = viewModel::toggleTts,
                onPrevious = viewModel::ttsPreviousParagraph,
                onNext = viewModel::ttsNextParagraph,
                onStop = {
                    showTtsSheet = false
                    viewModel.stopTts()
                },
                onSpeedChange = viewModel::setTtsSpeed,
                onPitchChange = viewModel::setTtsPitch,
                onSleepTimer = viewModel::setTtsSleepTimer,
                onStopAtChapterEnd = viewModel::setTtsStopAtChapterEnd,
                onDismiss = { showTtsSheet = false }
            )
        } else if (showTtsSheet) {
            LaunchedEffect(Unit) { showTtsSheet = false }
        }

        noteRequest?.let { request ->
            NoteEditorSheet(
                quote = request.quote,
                initialNote = (request as? NoteRequest.Existing)?.highlight?.note.orEmpty(),
                initialColorHex = when (request) {
                    is NoteRequest.New -> request.colorHex
                    is NoteRequest.Existing -> request.highlight.colorHex
                },
                colors = chrome,
                onSave = { note, colorHex ->
                    when (request) {
                        is NoteRequest.New -> viewModel.addHighlight(
                            chapterIndex = request.location.chapterIndex,
                            paragraphIndex = request.location.paragraphIndex,
                            startOffset = request.location.startOffset,
                            endOffset = request.location.endOffset,
                            text = request.quote,
                            colorHex = colorHex,
                            note = note
                        )
                        is NoteRequest.Existing -> viewModel.updateHighlight(request.highlight, colorHex, note)
                    }
                    noteRequest = null
                },
                onDeleteHighlight = (request as? NoteRequest.Existing)?.let { existing ->
                    {
                        viewModel.deleteHighlight(existing.highlight)
                        noteRequest = null
                    }
                },
                onDismiss = { noteRequest = null }
            )
        }

        askAiQuote?.let { quote ->
            AskAiSheet(
                quote = quote,
                bookTitle = book?.title.orEmpty(),
                author = book?.author.orEmpty(),
                colors = chrome,
                onDismiss = { askAiQuote = null }
            )
        }

        val infoBook = book
        if (showBookInfo && infoBook != null) {
            val progressPercent by viewModel.progressPercent.collectAsState()
            BookInfoDialog(
                book = infoBook,
                chapterCount = parsedBook?.chapters?.size ?: 0,
                progressPercent = progressPercent,
                colors = chrome,
                onDismiss = { showBookInfo = false }
            )
        }
    }
}

/** Longest query «Найти» puts into the search field. */
private const val MAX_FIND_QUERY_LENGTH = 60

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось поделиться", Toast.LENGTH_SHORT).show()
    }
}

/** §7.1 loading: the page colour with a 96×2dp accent line at 72 % of the height. */
@Composable
private fun ReaderLoading(colors: ReaderChromeColors) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.72f))
        LinearProgressIndicator(
            color = colors.accent,
            trackColor = colors.content.copy(alpha = 0.08f),
            modifier = Modifier
                .width(96.dp)
                .height(2.dp)
        )
        Spacer(modifier = Modifier.weight(0.28f))
    }
}

@Composable
private fun BookInfoDialog(
    book: Book,
    chapterCount: Int,
    progressPercent: Float,
    colors: ReaderChromeColors,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.content,
        textContentColor = colors.content,
        title = { Text(book.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (book.author.isNotBlank()) Text(book.author, color = colors.muted)
                val series = book.seriesName.takeIf { it.isNotBlank() }
                    ?.let { name -> if (book.seriesOrder > 0) "$name · №${book.seriesOrder}" else name }
                if (series != null) Text(series, color = colors.muted)
                Text("Формат: ${book.format.name.replace('_', '.')}")
                if (chapterCount > 0 && book.format != BookFormat.PDF) Text("Глав: $chapterCount")
                if (book.format == BookFormat.PDF && chapterCount > 0) Text("Страниц: $chapterCount")
                Text("Прочитано: ${formatPercentLabel(progressPercent)}")
                if (book.description.isNotBlank()) {
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 8
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = colors.accent) }
        }
    )
}

@Composable
private fun ReaderLoadError(
    message: String,
    colors: ReaderChromeColors,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            color = colors.content,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onBack) {
            Text("Вернуться в библиотеку", color = colors.accent)
        }
    }
}
