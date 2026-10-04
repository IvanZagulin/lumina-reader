package com.lumina.reader.core.download

/**
 * The iPhone's [DownloadNotifications]: none. Progress, completion and errors
 * are shown in the app itself (the download island, the downloads sheet and
 * AppMessages); iOS has no ongoing progress notification like Android's, and a
 * "book added" notification would need a notification permission the app does
 * not ask for.
 */
object IosDownloadNotifications : DownloadNotifications {
    override fun showProgress(key: String, title: String, bytesRead: Long, totalBytes: Long?) = Unit

    override fun showImporting(key: String, title: String) = Unit

    override fun showCompleted(key: String, title: String, bookId: Long, alreadyInLibrary: Boolean) = Unit

    override fun showFailed(key: String, title: String, message: String) = Unit

    override fun cancel(key: String) = Unit

    override fun clearStaleProgress() = Unit
}
