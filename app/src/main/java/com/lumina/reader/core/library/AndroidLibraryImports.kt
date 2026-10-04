package com.lumina.reader.core.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * [LibraryImports] backed by this app's [BookImporter], which adds catalogue
 * downloads and their notifications on top of the shared import pipeline.
 */
class AndroidLibraryImports(private val importer: BookImporter) : LibraryImports {

    override val activeImports: StateFlow<Int>
        get() = importer.activeImports

    override fun seedWelcomeBookIfNeeded() {
        importer.seedWelcomeBookIfNeeded()
    }

    override fun forgetBook(bookId: Long) {
        importer.forgetBook(bookId)
    }

    override fun launchInBackground(block: suspend CoroutineScope.() -> Unit) {
        importer.launchInBackground(block)
    }
}
