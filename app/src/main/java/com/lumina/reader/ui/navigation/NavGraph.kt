package com.lumina.reader.ui.navigation

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.model.effectiveTheme
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.ui.catalog.CatalogScreen
import com.lumina.reader.ui.catalog.CatalogSourcesScreen
import com.lumina.reader.ui.catalog.CatalogSourcesViewModel
import com.lumina.reader.ui.catalog.CatalogViewModel
import com.lumina.reader.ui.chat.AiChatScreen
import com.lumina.reader.ui.chat.AiChatViewModel
import com.lumina.reader.ui.components.newlyShelvedKeys
import com.lumina.reader.ui.downloads.DownloadIsland
import com.lumina.reader.ui.downloads.DownloadsSheet
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.reader.ReaderScreen
import com.lumina.reader.ui.reader.ReaderViewModel
import com.lumina.reader.ui.reader.ReaderViewModelFactory
import com.lumina.reader.ui.shell.AppSettingsSheet
import com.lumina.reader.ui.shell.DockDestination
import com.lumina.reader.ui.shell.LocalAppSnackbar
import com.lumina.reader.ui.shell.LocalDockScroll
import com.lumina.reader.ui.shell.LuminaDock
import com.lumina.reader.ui.shell.LuminaSnackbarHost
import com.lumina.reader.ui.shell.rememberDockScrollState
import com.lumina.reader.ui.stats.StatsScreenWithAchievements
import com.lumina.reader.ui.stats.StatsViewModel
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.rememberReducedMotion
import com.lumina.reader.ui.transition.BookTransitionOverlay
import com.lumina.reader.ui.transition.BookTransitionState
import com.lumina.reader.ui.transition.LocalBookTransition
import com.lumina.reader.ui.transition.ReaderTransitionHost
import com.lumina.reader.ui.transition.rememberBookTransitionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** MIME types offered by «Добавить книгу из файла» (unchanged from the old library FAB). */
private val BookMimeTypes = arrayOf(
    "application/pdf",
    "application/epub+zip",
    "application/x-fictionbook+xml",
    "application/x-fictionbook",
    "text/plain",
    "application/zip",
    "*/*"
)

/** Space the floating dock takes above the navigation bar (64 dp capsule + 12 dp margin + 12 dp gap). */
private val DockReserveHeight = LuminaDimens.DockHeight + 24.dp

/**
 * Opens [bookId] in the reader unless exactly that book is already on top.
 * Safe to call before the graph is ready (the request is then ignored).
 */
fun NavHostController.openBook(bookId: Long) {
    if (bookId <= 0) return
    val current = currentBackStackEntry
    if (current != null &&
        current.destination.route == Screen.Reader.route &&
        current.arguments?.getLong("bookId") == bookId
    ) {
        return
    }
    runCatching { navigate(Screen.Reader.createRoute(bookId)) }
}

/**
 * Switches dock tabs (spec §3.2): the library stays at the root of the back
 * stack, other tabs keep their state while hidden.
 */
fun NavHostController.navigateToTopLevel(route: String) {
    runCatching {
        navigate(route) {
            popUpTo(Screen.Library.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * The app shell (spec §3.1). Bottom to top: the NavHost and the dock (both
 * recorded into a layer that the book transition snapshots), the snackbar, the
 * download island, and the book-open overlay. Results of background work
 * (downloads, imports) appear on whatever screen is open; [snackbarHostState]
 * is driven by the activity, which shows [AppMessages] while it is started.
 */
@Composable
fun LuminaNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Library.route,
    onCheckForUpdates: () -> Unit = {},
    isCheckingForUpdates: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    updateAvailable: Boolean = false
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val density = LocalDensity.current
    val importer = remember(context) { BookImporter.get(context.applicationContext) }

    val navLayer = rememberGraphicsLayer()
    val transition = rememberBookTransitionState(navLayer)
    val dockScroll = rememberDockScrollState()
    val reducedMotion = rememberReducedMotion()
    val readerSettingsFlow = remember(context) { ReaderPreferences(context.applicationContext).settingsFlow }
    val readerSettings by readerSettingsFlow.collectAsState(initial = null)
    val systemInDarkMode = isSystemInDarkTheme()
    SideEffect {
        transition.reducedMotion = reducedMotion
        transition.density = density
        // The page the book opens onto has the colour the reader will draw with
        // (with «Авто» that is the day or night theme, not the stored manual one).
        readerSettings?.effectiveTheme(systemInDarkMode)?.let { theme ->
            transition.paper = theme.bgComposeColor
            transition.paperInk = theme.textComposeColor
        }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.importFromUri(uri)
    }
    val launchImport: () -> Unit = remember(importLauncher) {
        { runCatching { importLauncher.launch(BookMimeTypes) } }
    }

    // Notification taps, "Открыть с помощью" and snackbar actions open books here (plain fade).
    LaunchedEffect(navController) {
        AppMessages.openBookRequests.collect { bookId -> navController.openBook(bookId) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val dockDestination = DockDestination.forRoute(route)
    val dockVisible = dockDestination != null

    // «Полка +N»: books that arrived from downloads while another screen was open.
    var shelfBadge by rememberSaveable { mutableIntStateOf(0) }
    val currentRoute by rememberUpdatedState(route)
    LaunchedEffect(importer) {
        var previous = importer.downloads.value
        importer.downloads.collect { current ->
            val arrived = newlyShelvedKeys(previous, current).size
            previous = current
            if (arrived > 0 && currentRoute != Screen.Library.route) shelfBadge += arrived
        }
    }
    LaunchedEffect(route) {
        if (route == Screen.Library.route) shelfBadge = 0
        dockScroll.reset()
    }

    val slidePx = with(density) { 30.dp.roundToPx() }

    CompositionLocalProvider(
        LocalBookTransition provides transition,
        LocalDockScroll provides dockScroll.connection,
        LocalAppSnackbar provides snackbarHostState
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = { enterFor(transition, slidePx, pop = false) },
                    exitTransition = { exitFor(transition, slidePx, pop = false) },
                    popEnterTransition = { enterFor(transition, slidePx, pop = true) },
                    popExitTransition = { exitFor(transition, slidePx, pop = true) }
                ) {
                    composable(Screen.Library.route) {
                        val libraryViewModel: LibraryViewModel = viewModel()
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenBook = { bookId -> navController.openBook(bookId) },
                            onImportBook = launchImport,
                            onOpenCatalogs = { navController.navigateToTopLevel(Screen.Catalog.route) },
                            onSearchCatalogs = { query ->
                                if (query.isNotBlank()) {
                                    runCatching { navController.navigate(Screen.CatalogSearch.createRoute(query)) }
                                }
                            },
                            onOpenSettings = { showSettings = true },
                            onCheckForUpdates = onCheckForUpdates,
                            isCheckingForUpdates = isCheckingForUpdates,
                            updateAvailable = updateAvailable
                        )
                    }

                    composable(
                        route = Screen.Reader.route,
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                    ) { entry ->
                        val bookId = entry.arguments?.getLong("bookId") ?: 0L
                        val readerViewModel: ReaderViewModel = viewModel(
                            key = "reader_$bookId",
                            factory = ReaderViewModelFactory(application, bookId)
                        )
                        // "Reader ready" without reader cooperation (seam S1): loading finished
                        // and two frames drawn, then the overlay fades onto the real page.
                        val loading by readerViewModel.isLoading.collectAsState()
                        LaunchedEffect(loading) {
                            if (!loading) {
                                withFrameNanos { }
                                withFrameNanos { }
                                transition.onReaderReady(bookId)
                            }
                        }
                        ReaderTransitionHost(
                            bookId = bookId,
                            onExit = { navController.popBackStack() }
                        ) { requestClose ->
                            ReaderScreen(
                                viewModel = readerViewModel,
                                onBack = requestClose
                            )
                        }
                    }

                    composable(Screen.Stats.route) {
                        val statsViewModel: StatsViewModel = viewModel()
                        DockReserve {
                            StatsScreenWithAchievements(
                                viewModel = statsViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable(Screen.Catalog.route) {
                        val catalogViewModel: CatalogViewModel = viewModel()
                        DockReserve {
                            CatalogScreen(
                                viewModel = catalogViewModel,
                                onBack = { navController.popBackStack() },
                                onOpenBook = { bookId -> navController.openBook(bookId) },
                                onManageCatalogs = { navController.navigate(Screen.CatalogSources.route) }
                            )
                        }
                    }

                    composable(
                        route = Screen.CatalogSearch.route,
                        arguments = listOf(
                            navArgument(Screen.CatalogSearch.ARG_QUERY) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val query = entry.arguments?.getString(Screen.CatalogSearch.ARG_QUERY).orEmpty()
                        val searchViewModel: CatalogViewModel = viewModel()
                        var started by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(searchViewModel, query) {
                            if (!started && query.isNotBlank()) {
                                started = true
                                searchViewModel.onQueryChange(query)
                                // The enabled catalogues are read from storage right after start.
                                withTimeoutOrNull(2_000) { searchViewModel.uiState.first { it.catalogs.isNotEmpty() } }
                                searchViewModel.search()
                            }
                        }
                        CatalogScreen(
                            viewModel = searchViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenBook = { bookId -> navController.openBook(bookId) },
                            onManageCatalogs = { navController.navigate(Screen.CatalogSources.route) }
                        )
                    }

                    composable(Screen.CatalogSources.route) {
                        val sourcesViewModel: CatalogSourcesViewModel = viewModel()
                        CatalogSourcesScreen(
                            viewModel = sourcesViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.AiChat.route) {
                        // The chat runs its commands (search, download, series) itself in the
                        // app scope, so leaving the chat does not cancel them.
                        val aiChatViewModel: AiChatViewModel = viewModel()
                        DockReserve {
                            AiChatScreen(
                                viewModel = aiChatViewModel,
                                onBack = { navController.popBackStack() },
                                onDownloadAction = {},
                                onOrganizeAction = { _, _ -> }
                            )
                        }
                    }
                }

                // The dock is inside the recorded layer: it is part of the transition backdrop.
                AnimatedVisibility(
                    visible = dockVisible,
                    enter = fadeIn(tween(LuminaMotion.DurationMedium)) +
                        slideInVertically(tween(LuminaMotion.DurationMedium, easing = LuminaMotion.EmphasizedDecelerate)) { it },
                    exit = fadeOut(tween(LuminaMotion.DurationShort)) +
                        slideOutVertically(tween(LuminaMotion.DurationMedium, easing = LuminaMotion.EmphasizedAccelerate)) { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    LuminaDock(
                        selected = dockDestination,
                        onSelect = { destination ->
                            if (destination.route != route) navController.navigateToTopLevel(destination.route)
                        },
                        onAddBook = launchImport,
                        collapse = { dockScroll.collapse.value },
                        libraryBadge = shelfBadge
                    )
                }
            }

            val snackbarBottom by animateDpAsState(
                targetValue = if (dockVisible) LuminaDimens.DockHeight + 24.dp else 12.dp,
                animationSpec = tween(LuminaMotion.DurationMedium),
                label = "snackbar-offset"
            )
            LuminaSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(bottom = snackbarBottom)
            )

            DownloadIsland(
                onOpenBook = { bookId -> navController.openBook(bookId) },
                onOpenDownloads = { showDownloads = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )

            BookTransitionOverlay(state = transition)
        }

        if (showDownloads) {
            DownloadsSheet(
                onDismiss = { showDownloads = false },
                onOpenBook = { bookId ->
                    showDownloads = false
                    navController.openBook(bookId)
                }
            )
        }
        if (showSettings) {
            AppSettingsSheet(
                onDismiss = { showSettings = false },
                onCheckForUpdates = onCheckForUpdates,
                isCheckingForUpdates = isCheckingForUpdates
            )
        }
    }
}

/**
 * Keeps a top-level screen that does not know about the dock clear of it: the
 * content ends above the dock, or above the keyboard while it is open (the
 * dock then stays under the keyboard). Computed from insets in layout only.
 */
@Composable
private fun DockReserve(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .add(WindowInsets(bottom = DockReserveHeight))
                    .union(WindowInsets.ime)
            )
    ) {
        content()
    }
}

private fun isTopLevel(route: String?): Boolean = route != null && route in Screen.TopLevelRoutes

private fun isReader(route: String?): Boolean = route == Screen.Reader.route

/** NavHost enter transitions (spec §3.2, §6.2): none under the book overlay, fade for the reader. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enterFor(
    transition: BookTransitionState,
    slidePx: Int,
    pop: Boolean
): EnterTransition {
    if (transition.suppressNavAnimation) return EnterTransition.None
    val from = initialState.destination.route
    val to = targetState.destination.route
    return when {
        isReader(to) || isReader(from) -> fadeIn(tween(220))
        isTopLevel(from) && isTopLevel(to) ->
            fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)
        else -> {
            val direction = if (pop) -1 else 1
            slideInHorizontally(tween(240, easing = LuminaMotion.Emphasized)) { direction * slidePx } +
                fadeIn(tween(240))
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exitFor(
    transition: BookTransitionState,
    slidePx: Int,
    pop: Boolean
): ExitTransition {
    if (transition.suppressNavAnimation) return ExitTransition.None
    val from = initialState.destination.route
    val to = targetState.destination.route
    return when {
        isReader(to) || isReader(from) -> fadeOut(tween(200))
        isTopLevel(from) && isTopLevel(to) -> fadeOut(tween(90))
        else -> {
            val direction = if (pop) 1 else -1
            slideOutHorizontally(tween(240, easing = LuminaMotion.Emphasized)) { direction * slidePx } +
                fadeOut(tween(240))
        }
    }
}
