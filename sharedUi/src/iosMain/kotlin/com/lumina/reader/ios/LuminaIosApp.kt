package com.lumina.reader.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.reader.ui.library.LibraryScreen
import com.lumina.reader.ui.library.LibraryViewModel
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaReaderTheme

/**
 * The iPhone app: the shared library screen, driven by the shared view model
 * over the services iOS builds from IosLibrary.
 *
 * What is not ported yet answers honestly instead of doing nothing: the reader
 * (next stage) and adding books from Files. Catalogues and settings are no-ops
 * until their screens move.
 */
@Composable
fun LuminaIosApp() {
    LuminaReaderTheme {
        var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
        val library: LibraryViewModel = viewModel { LibraryViewModel() }
        Box(modifier = Modifier.fillMaxSize().background(Lumina.colors.wall)) {
            LibraryScreen(
                viewModel = library,
                onOpenBook = { bookId -> openBookId = bookId },
                onImportBook = {},
                onOpenCatalogs = {},
                onSearchCatalogs = {},
                onOpenSettings = {}
            )
            if (openBookId != null) {
                NotPortedYet(
                    text = "Чтение книг на iPhone появится в следующем обновлении.",
                    onBack = { openBookId = null }
                )
            }
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
