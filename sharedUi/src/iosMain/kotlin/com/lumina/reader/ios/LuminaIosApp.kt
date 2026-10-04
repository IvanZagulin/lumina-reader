package com.lumina.reader.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.reader.core.library.AppMessageAction
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.reader.ReaderScreen
import com.lumina.reader.ui.reader.ReaderViewModel
import com.lumina.reader.ui.reader.selection.AskAiMessage
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaReaderTheme

/**
 * The iPhone app: the shared library and reader screens over the services iOS
 * builds from IosLibrary.
 *
 * Navigation is one piece of state, the open book. The library keeps its
 * scroll position while a book is open (SaveableStateHolder), and every opened
 * book gets its own ViewModelStore, cleared when the book closes — what a
 * NavBackStackEntry does on Android. Without it each book read would stay in
 * memory, parsed text included, for as long as the app runs.
 *
 * Books come in through the Files picker and «Открыть в Lumina»; results and
 * errors arrive as [AppMessages] and show in a snackbar, as on Android.
 * Catalogues and settings are no-ops until their screens move.
 */
@Composable
fun LuminaIosApp() {
    LuminaReaderTheme {
        var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
        val library: LibraryViewModel = viewModel { LibraryViewModel() }
        val pickBooks = rememberBookPicker()
        val snackbar = remember { SnackbarHostState() }
        val screens = rememberSaveableStateHolder()
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
        Box(modifier = Modifier.fillMaxSize().background(Lumina.colors.wall)) {
            val bookId = openBookId
            if (bookId == null) {
                screens.SaveableStateProvider("library") {
                    LibraryScreen(
                        viewModel = library,
                        onOpenBook = { id -> openBookId = id },
                        onImportBook = pickBooks,
                        onOpenCatalogs = {},
                        onSearchCatalogs = {},
                        onOpenSettings = {}
                    )
                }
                // Android adds books from the dock's «+»; the dock moves in stage 8d.
                FloatingActionButton(
                    onClick = pickBooks,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(20.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Добавить книгу")
                }
            } else {
                BookScope(bookId) {
                    val reader: ReaderViewModel = viewModel { ReaderViewModel(bookId) }
                    ReaderScreen(
                        viewModel = reader,
                        onBack = { openBookId = null },
                        askAi = askAiUnavailable
                    )
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
            )
        }
    }
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

/** The assistant needs the network client, which comes to the iPhone with the catalogues. */
private val askAiUnavailable: suspend (List<AskAiMessage>) -> String = {
    throw UnsupportedOperationException("ИИ-помощник появится на iPhone в одном из следующих обновлений")
}
