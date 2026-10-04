package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.BookImporter
import kotlinx.coroutines.flow.StateFlow

/**
 * [CatalogDownloads] backed by this app's [BookImporter], so the catalogue,
 * the downloads sheet, the island, the AI assistant and the system
 * notifications keep sharing one set of downloads, exactly as before the
 * catalogue screens became common code.
 *
 * Stays in :app with the importer (like AndroidLibraryImports); installed by
 * `LuminaApp.onCreate` through [CatalogServicesHolder.install].
 */
class AndroidCatalogDownloads(private val importer: BookImporter) : CatalogDownloads {

    override val downloads: StateFlow<Map<String, DownloadState>>
        get() = importer.downloads

    override fun download(request: DownloadRequest): Boolean = importer.download(request)

    override fun retry(key: String): Boolean = importer.retry(key)

    override fun cancel(key: String) {
        importer.cancel(key)
    }

    override fun dismiss(key: String) {
        importer.dismiss(key)
    }
}
