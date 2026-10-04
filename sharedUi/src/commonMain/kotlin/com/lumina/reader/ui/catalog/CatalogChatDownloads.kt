package com.lumina.reader.ui.catalog

import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.ChatDownloads
import com.lumina.reader.core.model.BookFormat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The assistant's downloads on top of the catalogue's queue, so a book the
 * chat downloads shows up in the same downloads sheet and island as one the
 * user started in a catalogue (two queues would show two sets of downloads).
 * Android adapts its importer to [ChatDownloads] the same way; the iPhone uses
 * this over [CatalogDownloads].
 */
class CatalogChatDownloads(private val queue: CatalogDownloads) : ChatDownloads {

    override val downloads: StateFlow<Map<String, DownloadState>>
        get() = queue.downloads

    override suspend fun downloadAndAwait(
        url: String,
        title: String,
        author: String,
        formatHint: BookFormat?,
        headers: Map<String, String>,
        mirrorBaseUrls: List<String>
    ): DownloadState {
        val request = DownloadRequest(
            url = url,
            title = title,
            author = author,
            formatHint = formatHint,
            headers = headers,
            mirrorBaseUrls = mirrorBaseUrls
        )
        val current = queue.downloads.value[request.key]
        if (current is DownloadState.Completed) return current
        queue.download(request)
        val finished = queue.downloads
            .map { it[request.key] }
            .first { it == null || it.isFinished }
        // A download that disappeared from the queue was cancelled.
        return finished ?: DownloadState.Failed("Загрузка отменена")
    }

    override fun cancel(key: String) = queue.cancel(key)

    override fun retry(key: String): Boolean = queue.retry(key)

    override fun dismiss(key: String) = queue.dismiss(key)
}
