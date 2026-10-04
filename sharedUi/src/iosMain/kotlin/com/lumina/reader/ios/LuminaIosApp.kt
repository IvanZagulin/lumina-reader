package com.lumina.reader.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.reader.core.library.AppMessageAction
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.model.effectiveTheme
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.network.AiClient
import com.lumina.reader.core.network.AiMessage
import com.lumina.reader.ui.catalog.CatalogScreen
import com.lumina.reader.ui.catalog.CatalogSourcesScreen
import com.lumina.reader.ui.catalog.CatalogSourcesViewModel
import com.lumina.reader.ui.catalog.CatalogViewModel
import com.lumina.reader.ui.chat.AiChatScreen
import com.lumina.reader.ui.chat.AiChatViewModel
import com.lumina.reader.ui.downloads.DownloadIsland
import com.lumina.reader.ui.downloads.DownloadsSheet
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.reader.ReaderScreen
import com.lumina.reader.ui.reader.ReaderViewModel
import com.lumina.reader.ui.shell.AppSettingsSheet
import com.lumina.reader.ui.shell.DockDestination
import com.lumina.reader.ui.shell.LocalAppSnackbar
import com.lumina.reader.ui.shell.LocalDockScroll
import com.lumina.reader.ui.shell.LuminaDock
import com.lumina.reader.ui.shell.LuminaSnackbarHost
import com.lumina.reader.ui.shell.rememberDockScrollState
import com.lumina.reader.ui.stats.StatsScreenWithAchievements
import com.lumina.reader.ui.stats.StatsViewModel
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaReaderTheme
import com.lumina.reader.ui.theme.rememberReducedMotion
import com.lumina.reader.ui.transition.BookTransitionOverlay
import com.lumina.reader.ui.transition.LocalBookSlotHost
import com.lumina.reader.ui.transition.LocalBookTransition
import com.lumina.reader.ui.transition.ReaderTransitionHost
import com.lumina.reader.ui.transition.rememberBookTransitionState

/**
 * The iPhone app: the same screens as Android, over the services iOS builds
 * from IosLibrary and the holders of each feature.
 *
 * Navigation is state, not a graph: a selected dock tab and, above it, the
 * open book. Each tab keeps its scroll and input while another is shown
 * (SaveableStateHolder), and every opened book gets its own ViewModelStore,
 * cleared when it closes — what a NavBackStackEntry does on Android. Without
 * it each book read would stay in memory, parsed text included.
 *
 * The book-opening flight is Android-only for now: without a transition host
 * the shared screens simply open the book (BookSlotHost's defaults).
 */
@Composable
fun LuminaIosApp() {
    LuminaReaderTheme {
        var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
        var tabRoute by rememberSaveable { mutableStateOf(DockDestination.LIBRARY.route) }
        var managingCatalogs by rememberSaveable { mutableStateOf(false) }
        var catalogQuery by rememberSaveable { mutableStateOf<String?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDownloads by rememberSaveable { mutableStateOf(false) }
        var shelfBadge by rememberSaveable { mutableIntStateOf(0) }

        val tab = DockDestination.forRoute(tabRoute) ?: DockDestination.LIBRARY
        val library: LibraryViewModel = viewModel { LibraryViewModel() }
        val pickBooks = rememberBookPicker()
        val snackbar = remember { SnackbarHostState() }
        val screens = rememberSaveableStateHolder()
        val dockScroll = rememberDockScrollState()

        // The book-opening flight: the tab content is recorded into navLayer, which the
        // transition snapshots; the overlay with the flying cover is drawn above it.
        val density = LocalDensity.current
        val navLayer = rememberGraphicsLayer()
        val transition = rememberBookTransitionState(navLayer)
        val reducedMotion = rememberReducedMotion()
        val systemInDarkMode = isSystemInDarkTheme()
        val readerSettings by remember { ReaderPreferences().settingsFlow }.collectAsState(initial = null)
        SideEffect {
            transition.reducedMotion = reducedMotion
            transition.density = density
            // The page the book opens onto has the colour the reader will draw with.
            readerSettings?.effectiveTheme(systemInDarkMode)?.let { theme ->
                transition.paper = theme.bgComposeColor
                transition.paperInk = theme.textComposeColor
            }
        }

        fun selectTab(destination: DockDestination) {
            tabRoute = destination.route
            managingCatalogs = false
            if (destination == DockDestination.LIBRARY) shelfBadge = 0
            dockScroll.reset()
        }

        LaunchedEffect(Unit) {
            AppMessages.messages.collect { message ->
                if (!message.isFresh()) return@collect
                val result = snackbar.showSnackbar(message.text, actionLabel = message.actionLabel)
                val action = message.action
                if (result == SnackbarResult.ActionPerformed && action is AppMessageAction.OpenBook) {
                    openBookId = action.bookId
                }
            }
        }
        LaunchedEffect(Unit) {
            AppMessages.openBookRequests.collect { bookId -> openBookId = bookId }
        }

        CompositionLocalProvider(
            LocalDockScroll provides dockScroll.connection,
            LocalAppSnackbar provides snackbar,
            LocalBookTransition provides transition,
            LocalBookSlotHost provides transition
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Lumina.colors.wall)
                    .onSizeChanged { transition.rootSize = it }
            ) {
              Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        navLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(navLayer)
                    }
              ) {
                val bookId = openBookId
                if (bookId == null) {
                    screens.SaveableStateProvider(tab.route + managingCatalogs) {
                        when (tab) {
                            DockDestination.LIBRARY -> LibraryScreen(
                                viewModel = library,
                                onOpenBook = { id -> openBookId = id },
                                onImportBook = pickBooks,
                                onOpenCatalogs = { selectTab(DockDestination.CATALOG) },
                                onSearchCatalogs = { query ->
                                    if (query.isNotBlank()) {
                                        catalogQuery = query
                                        selectTab(DockDestination.CATALOG)
                                    }
                                },
                                onOpenSettings = { showSettings = true }
                            )
                            DockDestination.CATALOG ->
                                if (managingCatalogs) {
                                    val sources: CatalogSourcesViewModel = viewModel { CatalogSourcesViewModel() }
                                    CatalogSourcesScreen(viewModel = sources, onBack = { managingCatalogs = false })
                                } else {
                                    val catalog: CatalogViewModel = viewModel { CatalogViewModel() }
                                    DockScrollArea {
                                        CatalogScreen(
                                            viewModel = catalog,
                                            onBack = { selectTab(DockDestination.LIBRARY) },
                                            onOpenBook = { id -> openBookId = id },
                                            onManageCatalogs = { managingCatalogs = true },
                                            initialQuery = catalogQuery
                                        )
                                    }
                                }
                            DockDestination.ASSISTANT -> {
                                val chat: AiChatViewModel = viewModel { AiChatViewModel() }
                                DockScrollArea {
                                    AiChatScreen(viewModel = chat, onBack = { selectTab(DockDestination.LIBRARY) })
                                }
                            }
                            DockDestination.STATS -> {
                                val stats: StatsViewModel = viewModel { StatsViewModel() }
                                DockScrollArea {
                                    StatsScreenWithAchievements(
                                        viewModel = stats,
                                        onBack = { selectTab(DockDestination.LIBRARY) }
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !managingCatalogs,
                        enter = fadeIn(androidx.compose.animation.core.tween(LuminaMotion.DurationMedium)) +
                            slideInVertically(androidx.compose.animation.core.tween(LuminaMotion.DurationMedium)) { it },
                        exit = fadeOut(androidx.compose.animation.core.tween(LuminaMotion.DurationShort)) +
                            slideOutVertically(androidx.compose.animation.core.tween(LuminaMotion.DurationMedium)) { it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp)
                    ) {
                        LuminaDock(
                            selected = tab,
                            onSelect = { destination -> if (destination != tab) selectTab(destination) },
                            onAddBook = pickBooks,
                            collapse = { dockScroll.collapse.value },
                            libraryBadge = shelfBadge
                        )
                    }
                    DownloadIsland(
                        onOpenBook = { id -> openBookId = id },
                        onOpenDownloads = { showDownloads = true },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                } else {
                    BookScope(bookId) {
                        val reader: ReaderViewModel = viewModel { ReaderViewModel(bookId) }
                        val aiClient = remember { AiClient() }
                        // «Reader ready»: loading finished and two frames drawn, then the
                        // overlay fades onto the real page (as the Android nav graph does).
                        val loading by reader.isLoading.collectAsState()
                        LaunchedEffect(loading) {
                            if (!loading) {
                                withFrameNanos { }
                                withFrameNanos { }
                                transition.onReaderReady(bookId)
                            }
                        }
                        ReaderTransitionHost(bookId = bookId, onExit = { openBookId = null }) { requestClose ->
                            ReaderScreen(
                                viewModel = reader,
                                onBack = requestClose,
                                askAi = { messages ->
                                    aiClient.askAssistant(messages.map { AiMessage(role = it.role, content = it.content) }).content
                                }
                            )
                        }
                    }
                }
              }
                BookTransitionOverlay(state = transition)
                LuminaSnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = if (openBookId == null && !managingCatalogs) LuminaDimens.DockHeight + 24.dp else 12.dp)
                )
            }

            if (showDownloads) {
                DownloadsSheet(
                    onDismiss = { showDownloads = false },
                    onOpenBook = { id ->
                        showDownloads = false
                        openBookId = id
                    }
                )
            }
            if (showSettings) {
                // APK updates are Android's: iOS updates come through AltStore.
                AppSettingsSheet(
                    onDismiss = { showSettings = false },
                    onCheckForUpdates = null,
                    isCheckingForUpdates = false
                )
            }
        }
    }
}

/** Keeps a top-level screen's lists scrolling the dock, as the Android nav graph does. */
@Composable
private fun DockScrollArea(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().nestedScroll(LocalDockScroll.current)) { content() }
}

/** A ViewModelStore that lives exactly as long as [bookId] is open. */
@Composable
private fun BookScope(bookId: Long, content: @Composable () -> Unit) {
    val owner = remember(bookId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
