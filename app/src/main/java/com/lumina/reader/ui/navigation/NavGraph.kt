package com.lumina.reader.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.ui.catalog.CatalogScreen
import com.lumina.reader.ui.catalog.CatalogSourcesScreen
import com.lumina.reader.ui.catalog.CatalogSourcesViewModel
import com.lumina.reader.ui.catalog.CatalogViewModel
import com.lumina.reader.ui.chat.AiChatScreen
import com.lumina.reader.ui.chat.AiChatViewModel
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.reader.ReaderScreen
import com.lumina.reader.ui.reader.ReaderViewModel
import com.lumina.reader.ui.reader.ReaderViewModelFactory
import com.lumina.reader.ui.stats.StatsScreenWithAchievements
import com.lumina.reader.ui.stats.StatsViewModel

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
 * The app's navigation graph. The snackbar host sits above every screen so
 * results of background work (downloads, imports) are visible wherever the
 * user is; [snackbarHostState] is driven by the activity, which shows
 * [AppMessages] only while it is started.
 */
@Composable
fun LuminaNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Library.route,
    onCheckForUpdates: () -> Unit = {},
    isCheckingForUpdates: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // Notification taps, "Открыть с помощью" and snackbar actions open books here.
    LaunchedEffect(navController) {
        AppMessages.openBookRequests.collect { bookId -> navController.openBook(bookId) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            composable(Screen.Library.route) {
                val libraryViewModel: LibraryViewModel = viewModel()
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onBookClick = { bookId -> navController.openBook(bookId) },
                    onStatsClick = { navController.navigate(Screen.Stats.route) },
                    onCatalogClick = { navController.navigate(Screen.Catalog.route) },
                    onAiChatClick = { navController.navigate(Screen.AiChat.route) },
                    onCheckForUpdates = onCheckForUpdates,
                    isCheckingForUpdates = isCheckingForUpdates
                )
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L
                val readerViewModel: ReaderViewModel = viewModel(
                    key = "reader_$bookId",
                    factory = ReaderViewModelFactory(application, bookId)
                )
                ReaderScreen(
                    viewModel = readerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Stats.route) {
                val statsViewModel: StatsViewModel = viewModel()
                StatsScreenWithAchievements(
                    viewModel = statsViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Catalog.route) {
                val catalogViewModel: CatalogViewModel = viewModel()
                CatalogScreen(
                    viewModel = catalogViewModel,
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
                AiChatScreen(
                    viewModel = aiChatViewModel,
                    onBack = { navController.popBackStack() },
                    onDownloadAction = {},
                    onOrganizeAction = { _, _ -> }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}
