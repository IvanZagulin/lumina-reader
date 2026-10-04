package com.lumina.reader.core.download

import com.lumina.reader.core.model.BookFormat

/**
 * What to download; [key] (the URL) identifies the download in the UI.
 *
 * Split out of BookDownloader.kt (same package, unchanged) so the shared
 * download queue, the catalogue and the assistant's chat can name a request
 * before the downloader itself moves to common code.
 */
data class DownloadRequest(
    val url: String,
    val title: String,
    val author: String = "",
    /** Format the catalogue announced; the real one is detected from the content. */
    val formatHint: BookFormat? = null,
    /** Extra headers, e.g. HTTP Basic credentials of the catalogue. */
    val headers: Map<String, String> = emptyMap(),
    /** Other domains of the same site to try when [url] fails. */
    val mirrorBaseUrls: List<String> = emptyList()
) {
    val key: String
        get() = url
}
