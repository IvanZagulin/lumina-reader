package com.lumina.reader.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.reader.core.library.AppMessageAction
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaReaderTheme

/**
 * The iPhone app: the shared library screen, driven by the shared view model
 * over the services iOS builds from IosLibrary.
 *
 * Books come in through the Files picker; results and errors arrive as
 * [AppMessages] and show in a snackbar, as on Android. The reader is the next
 * stage, so opening a book says so instead of doing nothing; catalogues and
 * settings are no-ops until their screens move.
 */
@Composable
fun LuminaIosApp() {
    LuminaReaderTheme {
        var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
        val library: LibraryViewModel = viewModel { LibraryViewModel() }
        val pickBooks = rememberBookPicker()
        val snackbar = remember { SnackbarHostState() }
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
            LibraryScreen(
                viewModel = library,
                onOpenBook = { bookId -> openBookId = bookId },
                onImportBook = pickBooks,
                onOpenCatalogs = {},
                onSearchCatalogs = {},
                onOpenSettings = {}
            )
            if (openBookId == null) {
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
                NotPortedYet(
                    text = "Чтение книг на iPhone появится в следующем обновлении.",
                    onBack = { openBookId = null }
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
            )
        }
    }
}

@Composable
private fun NotPortedYet(text: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Lumina.colors.wall)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(onClick = onBack) { Text("Назад к полкам") }
        }
    }
}
