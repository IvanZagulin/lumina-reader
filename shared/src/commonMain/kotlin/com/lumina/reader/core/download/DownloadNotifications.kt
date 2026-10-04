package com.lumina.reader.core.download

/**
 * System notifications about book downloads, keyed by [DownloadRequest.key].
 *
 * Android's `DownloadNotifier` (in :app: it needs a Context, the notification
 * channel and MainActivity for the tap) posts an ongoing progress
 * notification, then "Книга добавлена" (tap opens the book) or an error.
 * The iPhone shows progress inside the app only (`IosDownloadNotifications` is
 * a no-op there), so a download engine in common code can always report to one.
 */
interface DownloadNotifications {
    /** Transfer progress; [totalBytes] is null when the size is unknown. */
    fun showProgress(key: String, title: String, bytesRead: Long, totalBytes: Long?)

    /** The file arrived and is being added to the library. */
    fun showImporting(key: String, title: String)

    /** The book is in the library ([alreadyInLibrary]: it was a duplicate). */
    fun showCompleted(key: String, title: String, bookId: Long, alreadyInLibrary: Boolean)

    /** The download failed with the user-facing [message]. */
    fun showFailed(key: String, title: String, message: String)

    /** Removes the notification of a cancelled download. */
    fun cancel(key: String)

    /**
     * Removes progress notifications left behind by a process that died in the
     * middle of a download; called once when the download service starts.
     */
    fun clearStaleProgress()
}
