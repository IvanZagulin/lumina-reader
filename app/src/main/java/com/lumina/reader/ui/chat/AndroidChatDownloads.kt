package com.lumina.reader.ui.chat

import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.library.ChatDownloads
import com.lumina.reader.core.model.BookFormat
import kotlinx.coroutines.flow.StateFlow

/**
 * [ChatDownloads] backed by this app's [BookImporter], so the assistant's
 * downloads are the catalogue's: the same queue, notifications, snackbars and
 * downloads sheet. Stays in :app with the importer.
 */
class AndroidChatDownloads(private val importer: BookImporter) : ChatDownloads {

    override val downloads: StateFlow<Map<String, DownloadState>>
        get() = importer.downloads

    override suspend fun downloadAndAwait(
        url: String,
        title: String,
        author: String,
        formatHint: BookFormat?,
        headers: Map<String, String>,
        mirrorBaseUrls: List<String>
    ): DownloadState = importer.downloadAndAwait(
        DownloadRequest(
            url = url,
            title = title,
            author = author,
            formatHint = formatHint,
            headers = headers,
            mirrorBaseUrls = mirrorBaseUrls
        )
    )

    override fun cancel(key: String) {
        importer.cancel(key)
    }

    override fun retry(key: String): Boolean = importer.retry(key)

    override fun dismiss(key: String) {
        importer.dismiss(key)
    }
}
