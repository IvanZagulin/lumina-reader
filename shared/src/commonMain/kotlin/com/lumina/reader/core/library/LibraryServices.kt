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
    private var factory: (() -> LibraryServices)? = null
    private var installed: LibraryServices? = null

    private var readerFactory: (() -> ReaderServices)? = null
    private var installedReader: ReaderServices? = null

    /**
     * Registers how to build the services; [factory] runs on first use, not
     * here. Nothing may be built eagerly: opening the database and the
     * preference files at start-up would slow it down and would bind DataStore
     * to the files before anything else could touch them.
     */
    fun install(factory: () -> LibraryServices) {
        this.factory = factory
    }

    /**
     * Registers how to build the reader's services, lazily for the same
     * reasons as [install]; on Android building them also initialises
     * read-aloud, which used to happen when the first book opened.
     */
    fun installReader(factory: () -> ReaderServices) {
        this.readerFactory = factory
    }

    val library: LibraryServices
        get() = installed ?: (factory?.invoke() ?: defaultLibraryServices()).also { installed = it }

    val reader: ReaderServices
        get() = installedReader ?: (readerFactory?.invoke() ?: defaultReaderServices()).also { installedReader = it }
}

internal expect fun defaultLibraryServices(): LibraryServices

internal expect fun defaultReaderServices(): ReaderServices
