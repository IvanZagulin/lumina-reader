package com.lumina.reader.core.library

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.LibraryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What the library screen needs from the app's import service. Android backs it
 * with `BookImporter` (downloads and notifications on top of [ImportPipeline]);
 * iOS with the pipeline alone, for now.
 */
interface LibraryImports {
    /** Number of imports of documents from other apps in progress. */
    val activeImports: StateFlow<Int>

    /** Adds the bundled welcome book the first time the library is empty. */
    fun seedWelcomeBookIfNeeded()

    /** Forgets a deleted book, so catalogues offer to download it again. */
    fun forgetBook(bookId: Long)

    /** Runs [block] in the app's scope, so leaving the screen does not cancel it. */
    fun launchInBackground(block: suspend CoroutineScope.() -> Unit)
}

/**
 * Everything the shared library screen and its view model need, built once per
 * process. It exists because the two platforms build the same objects
 * differently: Android needs a `Context` for all of them, iOS needs nothing.
 */
class LibraryServices(
    val bookDao: BookDao,
    val repository: LibraryRepository,
    val libraryPreferences: LibraryPreferences,
    val uiPreferences: AppUiPreferences,
    val imports: LibraryImports
)

/**
 * The services of this process.
 *
 * iOS builds them lazily on first use; Android's `LuminaApp.onCreate` installs
 * a Context-backed set (as it already does `AppInfo.init`), because only :app
 * owns the importer.
 */
object AppServices {
    private var installed: LibraryServices? = null

    fun install(services: LibraryServices) {
        installed = services
    }

    val library: LibraryServices
        get() = installed ?: defaultLibraryServices().also { installed = it }
}

internal expect fun defaultLibraryServices(): LibraryServices
