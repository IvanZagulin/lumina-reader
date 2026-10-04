package com.lumina.reader.core.library

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.preferences.get
import com.lumina.reader.core.tts.IosReadAloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal actual fun defaultLibraryServices(): LibraryServices = LibraryServices(
    bookDao = AppDatabase.getDatabase().bookDao(),
    repository = IosLibrary.repository,
    libraryPreferences = LibraryPreferences(),
    uiPreferences = AppUiPreferences.get(),
    imports = IosLibraryImports
)

internal actual fun defaultReaderServices(): ReaderServices {
    val db = AppDatabase.getDatabase()
    return ReaderServices(
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        statsDao = db.readingStatsDao(),
        preferences = ReaderPreferences(),
        readAloud = IosReadAloud
    )
}

/**
 * The iPhone's import service: the shared pipeline plus an app-wide scope.
 * Catalogue downloads and their notifications are still Android-only, so
 * [forgetBook] has nothing to forget yet.
 */
object IosLibraryImports : LibraryImports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val activeImports: StateFlow<Int>
        get() = IosLibrary.importPipeline.activeImports

    override fun seedWelcomeBookIfNeeded() {
        scope.launch { IosLibrary.importPipeline.seedWelcomeBookIfNeeded() }
    }

    override fun forgetBook(bookId: Long) {
        // No download bookkeeping on iOS yet.
    }

    override fun launchInBackground(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}
