package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.preferences.CatalogPreferences
import com.lumina.reader.platform.PlatformLock
import kotlinx.coroutines.flow.StateFlow
import com.lumina.reader.core.network.NetworkProxy

/**
 * What the catalogue screens, the downloads sheet and the download island need
 * from the app's download service, keyed by [DownloadRequest.key] (the URL).
 *
 * Android backs it with BookImporter in :app, so the catalogue, the island,
 * the AI assistant and the system notifications keep sharing one set of
 * downloads; the iPhone with [CatalogDownloadQueue].
 */
interface CatalogDownloads {
    /** Download state per URL. */
    val downloads: StateFlow<Map<String, DownloadState>>

    /**
     * Starts downloading [request] in the background unless the same URL is
     * already queued or running. Returns false when nothing was started.
     */
    fun download(request: DownloadRequest): Boolean

    /** Starts a finished or failed download again; false when its request is unknown. */
    fun retry(key: String): Boolean

    /** Cancels a queued or running download; its state disappears. */
    fun cancel(key: String)

    /** Forgets a finished or failed download (the lists' «✕», «Очистить готовые»). */
    fun dismiss(key: String)
}

/**
 * Everything the catalogue view models and the download UI need, built once
 * per process and reached as [CatalogServicesHolder.services]. Like
 * AppServices.library it exists because the platforms build the same objects
 * differently: Android needs a Context for the preference file and for the
 * importer (which lives in :app), iOS needs nothing.
 *
 * The OPDS repository is deliberately not here: each view model creates its
 * own, as it always has, so the search templates a repository caches per
 * catalogue live only as long as the screen (an edited catalogue address is
 * picked up the next time the catalogue opens).
 */
class CatalogServices(
    val catalogPreferences: CatalogPreferences,
    val downloads: CatalogDownloads
) {
    init {
        // Everything that talks to a catalogue is built from these, so the stored proxy
        // is followed from the first use on.
        NetworkProxy.bind(catalogPreferences)
    }
}

/**
 * The catalogue services of this process.
 *
 * Android's `LuminaApp.onCreate` installs a Context-backed set, the way it
 * installs AppServices.library; iOS builds them on first use
 * ([defaultCatalogServices]). A separate holder rather than a member of
 * AppServices, so the catalogue does not touch the library's service file.
 */
object CatalogServicesHolder {
    // The view models, the island and the sheet may ask from different
    // threads; two sets would mean two download queues on iOS.
    private val lock = PlatformLock()
    private var factory: (() -> CatalogServices)? = null
    private var installed: CatalogServices? = null

    /**
     * Registers how to build the services; [factory] runs on first use, not
     * here: the preference file and the importer must not be opened before a
     * screen asks for them (see AppServices.install).
     */
    fun install(factory: () -> CatalogServices) {
        lock.withLock { this.factory = factory }
    }

    val services: CatalogServices
        get() = lock.withLock {
            installed ?: (factory?.invoke() ?: defaultCatalogServices()).also { installed = it }
        }
}

/** The services when nothing was installed: built from scratch on iOS, an error on Android. */
internal expect fun defaultCatalogServices(): CatalogServices
