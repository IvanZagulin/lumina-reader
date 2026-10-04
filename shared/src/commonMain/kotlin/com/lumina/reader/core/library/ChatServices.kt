package com.lumina.reader.core.library

import com.lumina.reader.core.database.ReadingStatsDao
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.preferences.CatalogPreferences
import kotlinx.coroutines.flow.StateFlow
import com.lumina.reader.core.network.NetworkProxy

/**
 * The catalogue downloads as the assistant's chat uses them: it starts a
 * download for a book it found and waits for the outcome, and its download
 * cards show the live state with cancel / retry / dismiss.
 *
 * Android backs it with `BookImporter` (the same downloads, notifications and
 * snackbars as the catalogue screen). The request is passed field by field
 * because `DownloadRequest` still lives in :app next to its OkHttp downloader;
 * this interface must compile in :shared before that moves.
 */
interface ChatDownloads {
    /** Download state per key (the acquisition URL), as the downloads sheet shows it. */
    val downloads: StateFlow<Map<String, DownloadState>>

    /**
     * Starts (or joins) the download of [url] and waits until it is completed
     * or failed; a download the user cancelled ends as [DownloadState.Failed].
     * [headers] carry the catalogue's credentials, [mirrorBaseUrls] the other
     * domains of the same site to try when [url] fails.
     */
    suspend fun downloadAndAwait(
        url: String,
        title: String,
        author: String,
        formatHint: BookFormat?,
        headers: Map<String, String>,
        mirrorBaseUrls: List<String>
    ): DownloadState

    /** Cancels a queued or running download; its state disappears. */
    fun cancel(key: String)

    /** Starts a failed download again; false when it is unknown or already running. */
    fun retry(key: String): Boolean

    /** Forgets a finished download (its card goes away). */
    fun dismiss(key: String)
}

/**
 * What the assistant's chat needs besides [AppServices.library]: the enabled
 * catalogues to search, the reading statistics it describes to the model, and
 * the downloads it starts. Each of them is built differently per platform
 * (Android needs a `Context`, the importer lives in :app), so the shared view
 * model cannot open them itself.
 */
class ChatServices(
    val catalogPreferences: CatalogPreferences,
    val readingStatsDao: ReadingStatsDao,
    val downloads: ChatDownloads
) {
    init {
        NetworkProxy.bind(catalogPreferences)
    }
}

/**
 * The chat's services of this process, kept apart from [AppServices] so the
 * library and reader bundles do not grow a dependency on catalogue downloads.
 *
 * Android's `LuminaApp.onCreate` installs a Context-backed set, as it does for
 * [AppServices.install]. iOS builds a default set lazily on first use; its
 * downloads only report failure, because the iPhone's catalogue queue lives in
 * :sharedUi, out of this module's reach, so the iOS entry should install a set
 * over that queue.
 */
object ChatServicesHolder {
    private var factory: (() -> ChatServices)? = null
    private var installed: ChatServices? = null

    /**
     * Registers how to build the services; [factory] runs when the chat first
     * opens, not here, so start-up does not open the catalogue preferences or
     * create the importer.
     */
    fun install(factory: () -> ChatServices) {
        this.factory = factory
    }

    val services: ChatServices
        get() = installed ?: (factory?.invoke() ?: defaultChatServices()).also { installed = it }
}

internal expect fun defaultChatServices(): ChatServices
