package com.lumina.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.ui.PlatformBackHandler
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderPageFooter
import com.lumina.reader.ui.reader.footnote.FootnotePopup
import com.lumina.reader.ui.reader.selection.MarkColors
import com.lumina.reader.ui.reader.selection.SelectionMenu
import com.lumina.reader.ui.reader.selection.SelectionMenuAction
import com.lumina.reader.ui.theme.HighlightPalette

/**
 * The reading surface: picks the PDF, scrolling or paged viewer and hosts
 * what all of them share (text selection and its menu, the highlight menu,
 * footnotes, the search-match flash).
 *
 * The view model drives navigation through [navigationRequest]; viewers
 * acknowledge it with [onNavigationHandled] and report what is on screen with
 * [onVisibleRangeChanged] (countWords is false for jumps).
 */
@Composable
internal fun ReaderContent(
    book: Book,
    parsedBook: ParsedBook,
    chapterIndex: Int,
    settings: ReaderSettings,
    chromeColors: ReaderChromeColors,
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    textInfo: BookTextInfo,
    highlights: List<ReadingHighlight>,
    searchMatch: SearchMatch?,
    ttsSentence: TtsSentenceMark?,
    readAloudDriving: Boolean,
    reducedMotion: Boolean,
    curlSupported: Boolean,
    minutesLeftInChapter: Int?,
    pageCache: ChapterPageCache,
    imageCache: ReaderImageCache,
    pdfDocument: PdfDocumentRenderer?,
    onNavigationHandled: (requestId: Long) -> Unit,
    onVisibleRangeChanged: (range: VisibleRange, countWords: Boolean) -> Unit,
    onPageProgressChanged: (chapterIndex: Int, percent: Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onJumpToPosition: (chapterIndex: Int, paragraphIndex: Int, charOffset: Int) -> Unit,
    onToggleControls: () -> Unit,
    onToggleProgressDisplay: () -> Unit,
    selectionActions: ReaderSelectionActions,
    modifier: Modifier = Modifier
) {
    val chapter = parsedBook.chapters.getOrNull(chapterIndex) ?: return
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val platformTextToolbar = LocalTextToolbar.current
    val clipboard = rememberSelectionClipboard()
    val selection = remember(platformTextToolbar, clipboard) {
        ReaderSelectionState(platformTextToolbar, clipboard)
    }
    val navigationOwner = remember { Any() }
    val chapterLengths = textInfo.chapterLengths
    val localeTag = textInfo.localeTag
    val fonts = ReaderFonts
    val typography = remember(settings, localeTag, fonts) { settings.toTypography(localeTag, fonts) }
    val theme = settings.theme
    val colors = remember(theme) {
        ReaderTextColors(
            text = theme.textComposeColor,
            noteRef = theme.accentComposeColor,
            searchMatch = theme.accentComposeColor,
            ttsSentence = theme.accentComposeColor
        )
    }
    val decorations = remember(chapterIndex, highlights, searchMatch) {
        ChapterDecorations.build(chapterIndex, highlights, searchMatch)
    }

    // §8 #41: the match the reader jumped to flashes twice, then stays tinted.
    val searchFlash = remember { Animatable(SEARCH_MATCH_ALPHA) }
    LaunchedEffect(searchMatch, reducedMotion) {
        if (searchMatch == null || reducedMotion) {
            searchFlash.snapTo(SEARCH_MATCH_ALPHA)
        } else {
            searchFlash.snapTo(0.4f)
            searchFlash.animateTo(
                targetValue = SEARCH_MATCH_ALPHA,
                animationSpec = keyframes {
                    durationMillis = 600
                    0.2f at 150
                    0.4f at 300
                    0.2f at 450
                }
            )
        }
    }
    val searchAlpha: () -> Float = remember { { searchFlash.value } }
    val extras = remember(ttsSentence, readAloudDriving, theme, minutesLeftInChapter, reducedMotion, curlSupported) {
        ReaderPageExtras(
            ttsSentence = ttsSentence,
            readAloudDriving = readAloudDriving,
            markColors = MarkColors(theme.isDark, theme.accentComposeColor),
            searchAlpha = searchAlpha,
            minutesLeft = minutesLeftInChapter,
            reducedMotion = reducedMotion,
            curlSupported = curlSupported
        )
    }

    var openNote by remember { mutableStateOf<OpenNote?>(null) }
    var highlightMenu by remember { mutableStateOf<HighlightMenu?>(null) }
    val lastDown = remember { OffsetHolder() }

    val latestToggleControls by rememberUpdatedState(onToggleControls)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val latestNavigationHandled by rememberUpdatedState(onNavigationHandled)
    val latestVisibleRange by rememberUpdatedState(onVisibleRangeChanged)
    val latestPageProgress by rememberUpdatedState(onPageProgressChanged)
    val latestJump by rememberUpdatedState(onJumpToPosition)
    val latestToggleProgress by rememberUpdatedState(onToggleProgressDisplay)
    val callbacks = remember {
        ReaderViewerCallbacks(
            onToggleControls = { latestToggleControls() },
            onNextChapter = { latestNextChapter() },
            onPreviousChapter = { latestPreviousChapter() },
            onNavigationHandled = { id -> latestNavigationHandled(id) },
            onVisibleRangeChanged = { range, countWords -> latestVisibleRange(range, countWords) },
            onPageProgressChanged = { index, percent -> latestPageProgress(index, percent) },
            onJumpToPosition = { index, paragraph, offset -> latestJump(index, paragraph, offset) },
            onToggleProgressDisplay = { latestToggleProgress() },
            onNoteClick = { noteId -> openNote = OpenNote(noteId, lastDown.value) },
            onHighlightClick = { id ->
                if (selection.hasSelection) selection.clear()
                highlightMenu = HighlightMenu(id, lastDown.value)
            }
        )
    }
    val rootCoordinates = remember { CoordinatesHolder() }

    // The selection and the highlight menu are overlays of this screen: Back closes them.
    PlatformBackHandler(enabled = highlightMenu != null || selection.hasSelection) {
        if (highlightMenu != null) highlightMenu = null else selection.clear()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bgComposeColor)
            .onGloballyPositioned { rootCoordinates.value = it }
            .pointerInput(Unit) {
                // Where the last touch went down: footnote and highlight menus open there.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastDown.value = down.position
                }
            }
    ) {
        CompositionLocalProvider(LocalTextToolbar provides selection) {
            when {
                book.format == BookFormat.PDF -> PdfChapterContent(
                    chapterIndex = chapterIndex,
                    pageIndex = chapter.pdfPageNumber,
                    totalPages = parsedBook.chapters.size.coerceAtLeast(1),
                    settings = settings,
                    pdfDocument = pdfDocument,
                    navigationRequest = navigationRequest,
                    navigationOwner = navigationOwner,
                    callbacks = callbacks
                )
                settings.isContinuousScroll -> ScrollChapterViewer(
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
                    callbacks = callbacks
                )
                else -> PagedChapterViewer(
                    chapterIndex = chapterIndex,
                    chapter = chapter,
                    parsedBook = parsedBook,
                    settings = settings,
                    typography = typography,
                    colors = colors,
                    decorations = decorations,
                    pageCache = pageCache,
                    imageCache = imageCache,
                    chapterLengths = chapterLengths,
                    navigationRequest = navigationRequest,
                    positionProvider = positionProvider,
                    extras = extras,
                    selection = selection,
                    navigationOwner = navigationOwner,
                    callbacks = callbacks
                )
            }
        }

        val menuAnchor = selection.menuAnchor
        if (menuAnchor != null) {
            val local = toLocal(menuAnchor, rootCoordinates.value)
            fun withCaptured(action: (text: String, location: SelectionLocation?) -> Unit) {
                val captured = selection.captureSelection()
                if (captured == null) {
                    showSelectionError()
                } else {
                    action(captured.text, captured.location)
                }
            }
            SelectionMenu(
                anchor = local,
                colors = chromeColors,
                selectedColorHex = null,
                noteLabel = "Заметка",
                reducedMotion = reducedMotion,
                onColor = { swatch ->
                    withCaptured { text, location ->
                        if (location == null) showLocateError()
                        else selectionActions.onHighlight(text, location, swatch.hex)
                    }
                },
                onNote = {
                    withCaptured { text, location ->
                        if (location == null) showLocateError()
                        else selectionActions.onNote(text, location, HighlightPalette.Yellow.hex)
                    }
                },
                actions = listOf(
                    SelectionMenuAction("Копировать") { selection.copySelection() },
                    SelectionMenuAction("Поделиться") {
                        withCaptured { text, _ -> selectionActions.onShare(text) }
                    },
                    SelectionMenuAction("✦ Спросить ИИ") {
                        withCaptured { text, _ -> selectionActions.onAskAi(text) }
                    },
                    SelectionMenuAction("Найти") {
                        withCaptured { text, _ -> selectionActions.onFind(text) }
                    }
                )
            )
        }

        val menu = highlightMenu
        val menuHighlight = menu?.let { open -> highlights.firstOrNull { it.id == open.highlightId } }
        if (menu != null && menuHighlight != null) {
            val touchSlop = with(density) { 12.dp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { highlightMenu = null } }
            )
            SelectionMenu(
                anchor = Rect(
                    left = menu.at.x - touchSlop,
                    top = menu.at.y - touchSlop,
                    right = menu.at.x + touchSlop,
                    bottom = menu.at.y + touchSlop
                ),
                colors = chromeColors,
                selectedColorHex = menuHighlight.colorHex,
                noteLabel = if (menuHighlight.note.isNullOrBlank()) "Заметка" else "Изменить заметку",
                reducedMotion = reducedMotion,
                onColor = { swatch ->
                    highlightMenu = null
                    selectionActions.onRecolorHighlight(menuHighlight.id, swatch.hex)
                },
                onNote = {
                    highlightMenu = null
                    selectionActions.onEditHighlightNote(menuHighlight.id)
                },
                actions = listOf(
                    SelectionMenuAction("Удалить", isDestructive = true) {
                        highlightMenu = null
                        selectionActions.onDeleteHighlight(menuHighlight.id)
                    },
                    SelectionMenuAction("Копировать") {
                        highlightMenu = null
                        clipboardManager.setText(AnnotatedString(menuHighlight.selectedText))
                    },
                    SelectionMenuAction("Поделиться") {
                        highlightMenu = null
                        selectionActions.onShare(menuHighlight.selectedText)
                    },
                    SelectionMenuAction("✦ Спросить ИИ") {
                        highlightMenu = null
                        selectionActions.onAskAi(menuHighlight.selectedText)
                    }
                )
            )
        } else if (menu != null) {
            // The highlight was deleted meanwhile.
            LaunchedEffect(menu) { highlightMenu = null }
        }

        val note = openNote
        if (note != null) {
            FootnotePopup(
                noteId = note.noteId,
                noteNumber = footnoteNumber(note.noteId),
                text = parsedBook.footnotes[note.noteId],
                anchor = note.at,
                colors = chromeColors,
                typography = typography,
                reducedMotion = reducedMotion,
                onNoteClick = { nested -> openNote = OpenNote(nested, note.at) },
                onDismiss = { openNote = null }
            )
        }
    }

    // A chapter change drops menus that pointed into the old chapter.
    DisposableEffect(chapterIndex) {
        onDispose {
            highlightMenu = null
            openNote = null
        }
    }
}

/** Alpha of the search match once its flash is over. */
private const val SEARCH_MATCH_ALPHA = 0.35f

private data class OpenNote(val noteId: String, val at: Offset)

private data class HighlightMenu(val highlightId: Long, val at: Offset)

/** Last touch position, read on demand (not snapshot state). */
private class OffsetHolder {
    var value: Offset = Offset.Zero
}

/** «12» for a footnote id like "note_12"; empty when the id has no number. */
internal fun footnoteNumber(noteId: String): String = noteId.takeLastWhile { it.isDigit() }

private fun showSelectionError() {
    AppMessages.post("Не удалось прочитать выделенный текст", isError = true)
}

private fun showLocateError() {
    AppMessages.post("Не удалось найти выделенный текст на странице", isError = true)
}

/** Converts a rectangle in root coordinates into [coordinates]' local space. */
private fun toLocal(rect: Rect, coordinates: LayoutCoordinates?): Rect {
    val attached = coordinates?.takeIf { it.isAttached } ?: return rect
    val root = attached.findRootCoordinates()
    val topLeft = attached.localPositionOf(root, rect.topLeft)
    val bottomRight = attached.localPositionOf(root, rect.bottomRight)
    return Rect(topLeft, bottomRight)
}

/** Layout coordinates kept outside snapshot state; they are read on demand. */
private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

@Composable
private fun PdfChapterContent(
    chapterIndex: Int,
    pageIndex: Int,
    totalPages: Int,
    settings: ReaderSettings,
    pdfDocument: PdfDocumentRenderer?,
    navigationRequest: NavigationRequest?,
    navigationOwner: Any,
    callbacks: ReaderViewerCallbacks
) {
    // Every PDF page is its own chapter, so the position is exact.
    val pdfPage = chapterIndex + 1
    val pdfPosition = BookPosition(
        pageNumber = pdfPage,
        totalPages = totalPages,
        percent = pdfPage * 100f / totalPages,
        isExact = true
    )
    var showJumpDialog by remember { mutableStateOf(false) }

    DisposableEffect(settings.volumeKeyNavigation) {
        if (settings.volumeKeyNavigation) {
            ReaderPageNavigation.register(navigationOwner) { direction ->
                if (direction == PageTurnDirection.NEXT) callbacks.onNextChapter()
                else callbacks.onPreviousChapter()
                true
            }
        }
        onDispose { ReaderPageNavigation.unregister(navigationOwner) }
    }

    LaunchedEffect(chapterIndex, navigationRequest?.id) {
        callbacks.onVisibleRangeChanged(
            VisibleRange(chapterIndex, TextAnchor.START, TextAnchor.CHAPTER_END),
            false
        )
        callbacks.onPageProgressChanged(chapterIndex, pdfPosition.percent)
        navigationRequest
            ?.takeIf { it.chapterIndex == chapterIndex }
            ?.let { callbacks.onNavigationHandled(it.id) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PdfPageViewer(
            document = pdfDocument,
            pageIndex = pageIndex,
            settings = settings,
            onToggleControls = callbacks.onToggleControls,
            onNextPage = callbacks.onNextChapter,
            onPreviousPage = callbacks.onPreviousChapter,
            modifier = Modifier.fillMaxSize()
        )
        val theme = settings.theme
        ReaderPageFooter(
            position = pdfPosition,
            showPages = settings.showBookPagesInFooter,
            timeLeft = null,
            showProgressLine = settings.showProgressLine,
            mutedColor = theme.secondaryTextComposeColor,
            textColor = theme.textComposeColor,
            accentColor = theme.accentComposeColor,
            onToggle = callbacks.onToggleProgressDisplay,
            onLongPress = { showJumpDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        )
    }

    if (showJumpDialog) {
        BookJumpDialog(
            position = pdfPosition,
            byPages = settings.showBookPagesInFooter,
            onDismiss = { showJumpDialog = false },
            onJump = { targetPage ->
                showJumpDialog = false
                callbacks.onJumpToPosition(targetPage, 0, 0)
            }
        )
    }
}
