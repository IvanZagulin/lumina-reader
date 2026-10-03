package com.lumina.reader.ui.reader

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.model.effectiveTheme
import com.lumina.reader.core.tts.TtsState

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

/** A selection waiting for its note text. */
private data class PendingNote(
    val text: String,
    val location: SelectionLocation
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreenContent(
    viewModel: ReaderViewModel,
    storedSettings: ReaderSettings,
    onBack: () -> Unit
) {
    val systemInDarkMode = isSystemInDarkTheme()
    val settings = remember(storedSettings, systemInDarkMode) {
        storedSettings.copy(theme = storedSettings.effectiveTheme(systemInDarkMode))
    }
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
    var showTocSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var pendingNote by remember { mutableStateOf<PendingNote?>(null) }

    val context = LocalContext.current
    val view = LocalView.current
    val isPdf = book?.format == BookFormat.PDF

    // Keep screen on management
    DisposableEffect(settings.keepScreenOn) {
        val window = (context as? Activity)?.window
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
        val window = (context as? Activity)?.window
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
            val currentWindow = (context as? Activity)?.window
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

    Scaffold(
        containerColor = settings.theme.bgComposeColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            val currentBook = book
            val currentParsedBook = parsedBook
            // Text info is published with the book; waiting for it avoids
            // paginating once without the book language and once with it.
            val textInfoPending = currentParsedBook != null &&
                textInfo.chapterLengths.size != currentParsedBook.chapters.size
            when {
                isLoading || textInfoPending -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                currentBook != null && currentParsedBook != null &&
                    currentChapterIndex in currentParsedBook.chapters.indices -> ReaderContent(
                    book = currentBook,
                    parsedBook = currentParsedBook,
                    chapterIndex = currentChapterIndex,
                    settings = settings,
                    navigationRequest = navigationRequest,
                    positionProvider = { viewModel.position.value },
                    textInfo = textInfo,
                    highlights = highlights,
                    searchMatch = searchMatch,
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
                        viewModel.goToPosition(chapterIndex, paragraphIndex, charOffset)
                    },
                    onToggleControls = { showControls = !showControls },
                    onToggleProgressDisplay = {
                        viewModel.updateSettings {
                            it.copy(showBookPagesInFooter = !it.showBookPagesInFooter)
                        }
                    },
                    onTextSelected = { text, location, intent ->
                        if (location == null) {
                            Toast.makeText(
                                context,
                                "Не удалось найти выделенный текст на странице",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            when (intent) {
                                SelectionIntent.HIGHLIGHT -> {
                                    viewModel.addHighlight(
                                        chapterIndex = location.chapterIndex,
                                        paragraphIndex = location.paragraphIndex,
                                        startOffset = location.startOffset,
                                        endOffset = location.endOffset,
                                        text = text
                                    )
                                }
                                SelectionIntent.NOTE -> {
                                    pendingNote = PendingNote(text, location)
                                }
                            }
                        }
                    }
                )

                else -> ReaderLoadError(
                    message = loadError ?: "Не удалось подготовить текст книги",
                    textColor = settings.theme.textComposeColor,
                    onBack = onBack
                )
            }

            // Top Bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = settings.theme.surfaceComposeColor.copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Назад",
                                tint = settings.theme.textComposeColor
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = book?.title ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = settings.theme.textComposeColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val totalChapters = parsedBook?.chapters?.size ?: 1
                            Text(
                                text = if (isPdf) {
                                    "Страница ${currentChapterIndex + 1} из $totalChapters"
                                } else {
                                    "Глава ${currentChapterIndex + 1} из $totalChapters"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = settings.theme.secondaryTextComposeColor
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleBookmarkAtCurrentPosition() },
                            enabled = parsedBook != null
                        ) {
                            Icon(
                                imageVector = if (isPageBookmarked) {
                                    Icons.Default.Bookmark
                                } else {
                                    Icons.Default.BookmarkBorder
                                },
                                contentDescription = if (isPageBookmarked) {
                                    "Убрать закладку"
                                } else {
                                    "Добавить закладку"
                                },
                                tint = if (isPageBookmarked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    settings.theme.textComposeColor
                                }
                            )
                        }

                        if (!isPdf) {
                            IconButton(onClick = { viewModel.toggleTts() }) {
                                Icon(
                                    imageVector = if (ttsState == TtsState.PLAYING) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = if (ttsState == TtsState.PLAYING) {
                                        "Приостановить чтение вслух"
                                    } else {
                                        "Читать вслух"
                                    },
                                    tint = settings.theme.textComposeColor
                                )
                            }

                            IconButton(onClick = { showSearchDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Поиск по книге",
                                    tint = settings.theme.textComposeColor
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = settings.theme.surfaceComposeColor.copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilledTonalButton(
                            onClick = { showTocSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Оглавление", color = MaterialTheme.colorScheme.primary)
                        }

                        FilledTonalButton(
                            onClick = { showSettingsSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatSize,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Шрифт и вид", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showSearchDialog) {
        ReaderSearchDialog(
            state = searchState,
            onQueryChange = viewModel::onSearchQueryChange,
            onResultClick = { result ->
                showSearchDialog = false
                showControls = false
                viewModel.openSearchResult(result)
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    pendingNote?.let { note ->
        HighlightNoteDialog(
            selectedText = note.text,
            onSave = { noteText ->
                viewModel.addHighlight(
                    chapterIndex = note.location.chapterIndex,
                    paragraphIndex = note.location.paragraphIndex,
                    startOffset = note.location.startOffset,
                    endOffset = note.location.endOffset,
                    text = note.text,
                    note = noteText
                )
                pendingNote = null
            },
            onDismiss = { pendingNote = null }
        )
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(
            settings = storedSettings,
            onSettingsChanged = { transform -> viewModel.updateSettings(transform) },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showTocSheet) {
        // Books without a table of contents still list their chapters.
        val tocList = remember(parsedBook) {
            val currentBook = parsedBook
            when {
                currentBook == null -> emptyList()
                currentBook.tableOfContents.isNotEmpty() -> currentBook.tableOfContents
                else -> currentBook.chapters.mapIndexed { index, chapter ->
                    TocItem(
                        id = "chapter_$index",
                        title = displayChapterTitle(chapter.title, index),
                        chapterIndex = index
                    )
                }
            }
        }
        TableOfContentsSheet(
            tocList = tocList,
            bookmarks = bookmarks,
            highlights = highlights,
            currentChapterIndex = currentChapterIndex,
            onTocItemClick = { item -> viewModel.goToPosition(item.chapterIndex, item.paragraphIndex) },
            onBookmarkClick = { bookmark -> viewModel.goToBookmark(bookmark) },
            onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark) },
            onHighlightClick = { highlight ->
                viewModel.goToPosition(highlight.chapterIndex, highlight.paragraphIndex, highlight.startOffset)
            },
            onDeleteHighlight = { highlight -> viewModel.deleteHighlight(highlight) },
            onDismiss = { showTocSheet = false }
        )
    }
}

/** In-book search; results come from the view model, the match is shown in bold. */
@Composable
private fun ReaderSearchDialog(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    // The field keeps its own text so typing never waits for the view model.
    var query by remember { mutableStateOf(state.query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поиск в книге") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        onQueryChange(value)
                    },
                    placeholder = { Text("Введите слово...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                val queryLongEnough = query.trim().length >= MIN_SEARCH_QUERY_LENGTH
                when {
                    !queryLongEnough -> Text(
                        text = "Введите от $MIN_SEARCH_QUERY_LENGTH символов для поиска",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (state.isSearching || state.query != query) && state.results.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    state.results.isEmpty() -> Text(
                        text = "Ничего не найдено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        Text(
                            text = if (state.truncated) {
                                "Показаны первые ${state.results.size} совпадений"
                            } else {
                                "Найдено: ${state.results.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.results) { result ->
                                SearchResultRow(result = result, onClick = { onResultClick(result) })
                            }
                        }
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

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit
) {
    val snippet = remember(result) {
        buildAnnotatedString {
            val start = result.snippetMatchStart.coerceIn(0, result.snippet.length)
            val end = result.snippetMatchEnd.coerceIn(start, result.snippet.length)
            append(result.snippet.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(result.snippet.substring(start, end))
            }
            append(result.snippet.substring(end))
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = result.chapterTitle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = snippet,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HighlightNoteDialog(
    selectedText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметка") },
        text = {
            Column {
                Text(
                    text = "«${selectedText.trim()}»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Ваша мысль об этом месте") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun ReaderLoadError(
    message: String,
    textColor: Color,
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
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onBack) {
            Text("Вернуться в библиотеку")
        }
    }
}
