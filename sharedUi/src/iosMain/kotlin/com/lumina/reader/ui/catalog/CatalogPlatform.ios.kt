package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.BookDownloader
import com.lumina.reader.core.library.IosLibrary
import com.lumina.reader.core.preferences.CatalogPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/** The iPhone needs no Context: the catalogue services are built on first use. */
internal actual fun defaultCatalogServices(): CatalogServices = CatalogServices(
    catalogPreferences = CatalogPreferences(),
    downloads = IosCatalogDownloads.queue
)

/**
 * The iPhone's catalogue downloads, one queue per process (two queues would
 * show two different sets of downloads). It shares IosLibrary's import
 * pipeline, which adds one book at a time, with the document picker.
 *
 * Public so the AI assistant and the library (forgetting a deleted book's
 * download) can reach the same queue once they are common too.
 */
object IosCatalogDownloads {
    val queue: CatalogDownloadQueue by lazy {
        CatalogDownloadQueue(
            pipeline = IosLibrary.importPipeline,
            downloader = BookDownloader(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            keepRunning = { name, work -> runAsBackgroundTask(name, work) }
        )
    }
}

/**
 * Runs [work] as a UIKit background task, so a book the user started to
 * download keeps coming when they switch to another app: iOS grants about
 * half a minute, enough for most books. When that time is up the task is
 * ended (iOS kills an app that overruns it); the download then continues only
 * once the app is back in the foreground, or fails with a network error.
 *
 * Every UIKit call and every use of `task` happens on the main thread, where
 * the expiration handler runs too, so no lock is needed. The task is begun
 * inside the `try`: a download cancelled while the begin call is on its way
 * back from the main thread still ends it (ending an unbegun task is a no-op).
 */
private suspend fun runAsBackgroundTask(name: String, work: suspend () -> Unit) {
    var task = UIBackgroundTaskInvalid
    val end = {
        if (task != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
    }
    try {
        withContext(Dispatchers.Main) {
            task = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) { end() }
        }
        work()
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { end() }
    }
}
