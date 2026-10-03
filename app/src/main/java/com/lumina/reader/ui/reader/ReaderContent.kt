package com.lumina.reader.ui.reader

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingHighlight
import kotlin.math.roundToInt

/**
 * The reading surface: picks the PDF, scrolling or paged viewer and hosts
 * what all of them share (text selection, footnotes).
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
    navigationRequest: NavigationRequest?,
    positionProvider: () -> ReaderPosition,
    highlights: List<ReadingHighlight>,
    searchMatch: SearchMatch?,
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
    onTextSelected: (selectedText: String, location: SelectionLocation?, intent: SelectionIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val chapter = parsedBook.chapters.getOrNull(chapterIndex) ?: return
    val context = LocalContext.current
    val platformTextToolbar = LocalTextToolbar.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val selection = remember(platformTextToolbar, clipboard) {
        ReaderSelectionState(platformTextToolbar, clipboard)
    }
    val navigationOwner = remember { Any() }
    val chapterLengths = remember(parsedBook) { chapterTextLengths(parsedBook.chapters) }
    val localeTag = remember(parsedBook) { detectTextLocaleTag(bookTextSample(parsedBook)) }
    val typography = remember(settings, localeTag) { settings.toTypography(localeTag) }
    val accent = MaterialTheme.colorScheme.primary
    val colors = remember(settings.theme, accent) {
        ReaderTextColors(
            text = settings.theme.textComposeColor,
            noteRef = accent,
            searchMatch = accent.copy(alpha = 0.40f)
        )
    }
    val decorations = remember(chapterIndex, highlights, searchMatch) {
        ChapterDecorations.build(chapterIndex, highlights, searchMatch)
    }

    var openNoteId by remember { mutableStateOf<String?>(null) }

    val latestToggleControls by rememberUpdatedState(onToggleControls)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val latestNavigationHandled by rememberUpdatedState(onNavigationHandled)
    val latestVisibleRange by rememberUpdatedState(onVisibleRangeChanged)
    val latestPageProgress by rememberUpdatedState(onPageProgressChanged)
    val latestJump by rememberUpdatedState(onJumpToPosition)
    val latestToggleProgress by rememberUpdatedState(onToggleProgressDisplay)
    val latestTextSelected by rememberUpdatedState(onTextSelected)
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
            onNoteClick = { noteId -> openNoteId = noteId }
        )
    }
    val rootCoordinates = remember { CoordinatesHolder() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(settings.theme.bgComposeColor)
            .onGloballyPositioned { rootCoordinates.value = it }
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
                    minutesLeft = minutesLeftInChapter,
                    selection = selection,
                    navigationOwner = navigationOwner,
                    callbacks = callbacks
                )
            }
        }

        val menuAnchor = selection.menuAnchor
        if (menuAnchor != null) {
            SelectionActionBar(
                anchor = menuAnchor,
                coordinates = rootCoordinates.value,
                settings = settings,
                onCopy = { selection.copySelection() },
                onHighlight = {
                    selection.captureSelection()?.let { captured ->
                        latestTextSelected(captured.text, captured.location, SelectionIntent.HIGHLIGHT)
                    }
                },
                onNote = {
                    selection.captureSelection()?.let { captured ->
                        latestTextSelected(captured.text, captured.location, SelectionIntent.NOTE)
                    }
                }
            )
        }
    }

    val noteId = openNoteId
    if (noteId != null) {
        FootnoteDialog(
            noteId = noteId,
            footnotes = parsedBook.footnotes,
            typography = typography,
            onNoteClick = { nested -> openNoteId = nested },
            onDismiss = { openNoteId = null }
        )
    }
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
        ReaderProgressFooter(
            chapterLabel = null,
            position = pdfPosition,
            showPages = settings.showBookPagesInFooter,
            settings = settings,
            minutesLeft = null,
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

/**
 * Actions for selected text, shown above the selection (or below it when
 * there is no room above).
 */
@Composable
private fun SelectionActionBar(
    anchor: Rect,
    coordinates: LayoutCoordinates?,
    settings: ReaderSettings,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit
) {
    val density = LocalDensity.current
    val attached = coordinates?.takeIf { it.isAttached }
    val root = attached?.findRootCoordinates()
    val top = if (attached != null && root != null) {
        attached.localPositionOf(root, anchor.topLeft).y
    } else {
        anchor.top
    }
    val bottom = if (attached != null && root != null) {
        attached.localPositionOf(root, anchor.bottomLeft).y
    } else {
        anchor.bottom
    }
    val containerHeight = attached?.size?.height?.toFloat() ?: Float.MAX_VALUE
    val barHeight = with(density) { 48.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val topLimit = with(density) { 40.dp.toPx() }
    val above = top - barHeight - margin
    val y = (if (above >= topLimit) above else bottom + margin)
        .coerceIn(0f, (containerHeight - barHeight).coerceAtLeast(0f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, y.roundToInt()) },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = settings.theme.surfaceComposeColor,
            shadowElevation = 6.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = onCopy) {
                    Text("Копировать", color = settings.theme.textComposeColor)
                }
                TextButton(onClick = onHighlight) {
                    Text("Выделить", color = settings.theme.textComposeColor)
                }
                TextButton(onClick = onNote) {
                    Text("Заметка", color = settings.theme.textComposeColor)
                }
            }
        }
    }
}

/** The text of a footnote, rendered with the same markup rules as the book. */
@Composable
private fun FootnoteDialog(
    noteId: String,
    footnotes: Map<String, String>,
    typography: ReaderTypography,
    onNoteClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val text = footnotes[noteId]
    val textColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val noteTypography = remember(typography) {
        typography.copy(
            fontSizeSp = (typography.fontSizeSp - 2).coerceAtLeast(12),
            firstLineIndentEm = 0f,
            bionic = false
        )
    }
    val colors = remember(textColor, accent) {
        ReaderTextColors(text = textColor, noteRef = accent, searchMatch = Color.Transparent)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Примечание") },
        text = {
            if (text == null) {
                Text("Текст примечания не найден")
            } else {
                val paragraphs = remember(text) { text.split('\n').filter { it.isNotBlank() } }
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    paragraphs.forEachIndexed { index, raw ->
                        val rendered = remember(raw, noteTypography, colors) {
                            renderParagraph(raw, noteTypography, colors, onNoteClick = onNoteClick)
                        }
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        BasicText(text = rendered.text, style = rendered.style)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
