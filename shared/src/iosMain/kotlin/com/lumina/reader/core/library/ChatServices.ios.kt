package com.lumina.reader.core.library

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.preferences.CatalogPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun defaultChatServices(): ChatServices = ChatServices(
    catalogPreferences = CatalogPreferences(),
    readingStatsDao = AppDatabase.getDatabase().readingStatsDao(),
    downloads = IosChatDownloads
)

/**
 * The fallback when nothing installed better chat downloads: this module
 * (:shared) cannot reach the iPhone's catalogue download queue, which lives in
 * :sharedUi (`IosCatalogDownloads.queue`, behind `CatalogServicesHolder`). The
 * assistant still searches the catalogues, and a [DOWNLOAD] command ends as a
 * failure the chat reports in words, instead of a card that never moves.
 *
 * To download for real, the iOS entry in :sharedUi installs a [ChatServices]
 * whose downloads adapt `CatalogServicesHolder.services.downloads` (with
 * [ChatServicesHolder.install]) before the chat first opens; this default is
 * then never built.
 */
private object IosChatDownloads : ChatDownloads {
    override val downloads: StateFlow<Map<String, DownloadState>> =
        MutableStateFlow<Map<String, DownloadState>>(emptyMap()).asStateFlow()

    override suspend fun downloadAndAwait(
        url: String,
        title: String,
        author: String,
        formatHint: BookFormat?,
        headers: Map<String, String>,
        mirrorBaseUrls: List<String>
    ): DownloadState = DownloadState.Failed("Скачивание из каталогов на iPhone пока недоступно")

    override fun cancel(key: String) = Unit

    override fun retry(key: String): Boolean = false

    override fun dismiss(key: String) = Unit
}
