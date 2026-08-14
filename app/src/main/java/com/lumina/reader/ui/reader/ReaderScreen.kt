package com.lumina.reader.ui.reader

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumina.reader.core.tts.TtsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val book by viewModel.book.collectAsState()
    val parsedBook by viewModel.parsedBook.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val currentParagraphIndex by viewModel.currentParagraphIndex.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showControls by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var inBookSearchQuery by remember { mutableStateOf("") }

    val context = LocalContext.current
    val view = LocalView.current

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

    // Immersive system bars toggle
    DisposableEffect(showControls) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showControls) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val window = (context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
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
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val currentBook = book
                val currentChapter = parsedBook?.chapters?.getOrNull(currentChapterIndex)
                if (currentBook != null && currentChapter != null) {
                    ReaderContent(
                        book = currentBook,
                        parsedBook = parsedBook,
                        chapter = currentChapter,
                        initialParagraphIndex = currentParagraphIndex,
                        settings = settings,
                        onToggleControls = { showControls = !showControls },
                        onNextChapter = { viewModel.nextChapter() },
                        onPreviousChapter = { viewModel.previousChapter() },
                        onParagraphVisible = { pIndex -> viewModel.onParagraphVisible(pIndex) },
                        onParagraphFragmentVisible = { pIndex, fragmentIndex, text ->
                            viewModel.onParagraphFragmentVisible(pIndex, fragmentIndex, text)
                        }
                    )
                }
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
                            val totalCh = parsedBook?.chapters?.size ?: 1
                            Text(
                                text = "Глава ${currentChapterIndex + 1} из $totalCh",
                                style = MaterialTheme.typography.labelSmall,
                                color = settings.theme.secondaryTextComposeColor
                            )
                        }

                        // Search in book
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

            // Bottom Bar (One UI ergonomic layout)
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        // Action Buttons (Table of Contents & Settings)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
    }

    // Search In Book Dialog
    if (showSearchDialog) {
        val allChapters = parsedBook?.chapters ?: emptyList()
        val searchResults = remember(inBookSearchQuery, allChapters) {
            if (inBookSearchQuery.length >= 2) {
                allChapters.mapIndexedNotNull { chIndex, ch ->
                    val matchingParagraphs = ch.paragraphs.mapIndexedNotNull { pIdx, p ->
                        if (p.contains(inBookSearchQuery, ignoreCase = true)) {
                            pIdx to p
                        } else null
                    }
                    if (matchingParagraphs.isNotEmpty()) {
                        chIndex to (ch.title to matchingParagraphs)
                    } else null
                }
            } else emptyList()
        }

        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Поиск в книге") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = inBookSearchQuery,
                        onValueChange = { inBookSearchQuery = it },
                        placeholder = { Text("Введите слово...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (searchResults.isEmpty()) {
                        Text(
                            text = if (inBookSearchQuery.length >= 2) "Ничего не найдено" else "Введите от 2-х символов для поиска",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchResults.forEach { (chIdx, pair) ->
                                val (chTitle, matches) = pair
                                item {
                                    Text(
                                        text = chTitle,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 13.sp
                                    )
                                }
                                itemsIndexed(matches) { _, (pIdx, snippet) ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.goToChapter(chIdx)
                                                viewModel.onParagraphVisible(pIdx)
                                                showSearchDialog = false
                                            }
                                    ) {
                                        Text(
                                            text = snippet.take(150),
                                            modifier = Modifier.padding(8.dp),
                                            fontSize = 12.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    // Sheets
    if (showSettingsSheet) {
        ReaderSettingsSheet(
            settings = settings,
            onSettingsChanged = { transform -> viewModel.updateSettings(transform) },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showTocSheet) {
        TableOfContentsSheet(
            tocList = parsedBook?.tableOfContents ?: emptyList(),
            bookmarks = bookmarks,
            currentChapterIndex = currentChapterIndex,
            onChapterClick = { chIndex -> viewModel.goToChapter(chIndex) },
            onBookmarkClick = { chIndex, pIndex ->
                viewModel.goToChapter(chIndex)
                viewModel.onParagraphVisible(pIndex)
            },
            onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark) },
            onDismiss = { showTocSheet = false }
        )
    }
}
