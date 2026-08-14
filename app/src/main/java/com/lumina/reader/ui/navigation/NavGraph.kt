package com.lumina.reader.ui.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.reader.ReaderScreen
import com.lumina.reader.ui.reader.ReaderViewModel
import com.lumina.reader.ui.reader.ReaderViewModelFactory
import com.lumina.reader.ui.stats.StatsScreen
import com.lumina.reader.ui.stats.StatsViewModel

@Composable
fun LuminaNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Library.route,
    onCheckForUpdates: () -> Unit = {},
    isCheckingForUpdates: Boolean = false
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Library.route) {
            val libraryViewModel: LibraryViewModel = viewModel()
            LibraryScreen(
                viewModel = libraryViewModel,
                onBookClick = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onStatsClick = {
                    navController.navigate(Screen.Stats.route)
                },
                onCatalogClick = {
                    navController.navigate(Screen.Catalog.route)
                },
                onAiChatClick = {
                    navController.navigate(Screen.AiChat.route)
                },
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
            StatsScreen(
                viewModel = statsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Catalog.route) { backStackEntry ->
            val catalogViewModel: com.lumina.reader.ui.catalog.CatalogViewModel = viewModel()
            val parentEntry = androidx.compose.runtime.remember(backStackEntry) {
                navController.getBackStackEntry(Screen.Library.route)
            }
            val libraryViewModel: LibraryViewModel = viewModel(parentEntry)
            
            com.lumina.reader.ui.catalog.CatalogScreen(
                viewModel = catalogViewModel,
                onBack = { navController.popBackStack() },
                onDownloadBook = { url, format, title ->
                    libraryViewModel.downloadAndImportBook(url, format, title)
                }
            )
        }

        composable(Screen.AiChat.route) { backStackEntry ->
            val aiChatViewModel: com.lumina.reader.ui.chat.AiChatViewModel = viewModel()
            val parentEntry = androidx.compose.runtime.remember(backStackEntry) {
                navController.getBackStackEntry(Screen.Library.route)
            }
            val libraryViewModel: LibraryViewModel = viewModel(parentEntry)
            val statsViewModel: com.lumina.reader.ui.stats.StatsViewModel = viewModel()

            androidx.compose.runtime.LaunchedEffect(Unit) {
                val libraryBooks = libraryViewModel.books.value
                val libraryContext = libraryBooks.joinToString("\n") { "- ${it.title} (${it.author}) [Коллекция: ${it.collection}, Серия: ${it.seriesName}]" }
                
                val stats = statsViewModel.uiState.value
                val statsContext = """
                    Прочитано слов: ${stats.allTime.wordsRead}
                    Прочитано страниц: ${stats.allTime.estimatedPages}
                    Средний темп: ${stats.averageWordsPerMinute} сл/мин
                    Дней активного чтения: ${stats.activeReadingDays}
                """.trimIndent()
                
                aiChatViewModel.setContext(libraryContext, statsContext)
            }

            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            com.lumina.reader.ui.chat.AiChatScreen(
                viewModel = aiChatViewModel,
                onBack = { navController.popBackStack() },
                onDownloadAction = { query ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val client = com.lumina.reader.core.network.OpdsClient()
                            val books = client.searchBooks(query)
                            val book = books.firstOrNull { it.downloadUrlFb2 != null || it.downloadUrlEpub != null }
                            if (book != null) {
                                val format = if (book.downloadUrlFb2 != null) com.lumina.reader.core.model.BookFormat.FB2_ZIP else com.lumina.reader.core.model.BookFormat.EPUB
                                val url = book.downloadUrlFb2 ?: book.downloadUrlEpub!!
                                libraryViewModel.downloadAndImportBook(url, format, book.title)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onOrganizeAction = { seriesName, books ->
                    coroutineScope.launch {
                        // DOWNLOAD actions are emitted before ORGANIZE. Wait for
                        // their imports to appear so new books join the series too.
                        withTimeoutOrNull(60_000) {
                            libraryViewModel.books.first { library ->
                                books.all { wantedTitle ->
                                    library.any { it.title.contains(wantedTitle, ignoreCase = true) }
                                }
                            }
                        }

                        val library = libraryViewModel.books.value
                        books.forEachIndexed { index, bookTitle ->
                            val matchedBook = library.firstOrNull {
                                it.title.contains(bookTitle, ignoreCase = true)
                            }
                            if (matchedBook != null) {
                                libraryViewModel.updateBookOrganization(
                                    book = matchedBook,
                                    collection = matchedBook.collection,
                                    seriesName = seriesName,
                                    seriesOrder = index + 1
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}
