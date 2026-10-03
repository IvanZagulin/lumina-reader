package com.lumina.reader.core.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lumina.reader.MainActivity

/**
 * System notifications for book downloads: an ongoing progress notification
 * while transferring, then "Книга добавлена" (tap opens the book) or an error.
 * Without the POST_NOTIFICATIONS permission everything is skipped silently.
 */
class DownloadNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun showProgress(key: String, title: String, bytesRead: Long, totalBytes: Long?) {
        val builder = baseBuilder(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(
                if (bytesRead <= 0) "Подключение…" else "Загрузка · ${describeProgress(bytesRead, totalBytes)}"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (totalBytes != null && totalBytes > 0) {
            val percent = ((bytesRead.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(notificationId(key), builder.build())
    }

    fun showImporting(key: String, title: String) {
        val builder = baseBuilder(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText("Добавляем в библиотеку…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notify(notificationId(key), builder.build())
    }

    fun showCompleted(key: String, title: String, bookId: Long, alreadyInLibrary: Boolean) {
        val id = notificationId(key)
        val builder = baseBuilder(if (alreadyInLibrary) "Книга уже в библиотеке" else "Книга добавлена: $title")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentText(if (alreadyInLibrary) "«$title» — нажмите, чтобы открыть" else "Нажмите, чтобы начать чтение")
            .setContentIntent(openBookIntent(bookId, id))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(id, builder.build())
    }

    fun showFailed(key: String, title: String, message: String) {
        val builder = baseBuilder("Не удалось скачать «$title»")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent(notificationId(key)))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(notificationId(key), builder.build())
    }

    fun cancel(key: String) {
        try {
            manager.cancel(notificationId(key))
        } catch (e: Exception) {
            // Nothing to cancel.
        }
    }

    /**
     * Removes progress notifications left behind by a process that died in the
     * middle of a download. Finished «Книга добавлена» notifications stay.
     */
    fun clearStaleProgress() {
        val system = appContext.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            system.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }
                .forEach { system.cancel(it.tag, it.id) }
        }
    }

    private fun baseBuilder(title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setShowWhen(true)

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun openBookIntent(bookId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_BOOK
            putExtra(EXTRA_OPEN_BOOK_ID, bookId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ход загрузки книг из каталогов и уведомления о готовности"
                setSound(null, null)
            }
            appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val CHANNEL_NAME = "Загрузки книг"

        /** Long extra on the MainActivity intent: open this book in the reader. Shared with TTS. */
        const val EXTRA_OPEN_BOOK_ID = "open_book_id"
        const val ACTION_OPEN_BOOK = "com.lumina.reader.action.OPEN_BOOK"

        /** Stable id per download key; distinct from the reminder (701) and TTS ids. */
        fun notificationId(key: String): Int = 20_000 + (key.hashCode() and 0x7FFFFFFF) % 1_000_000
    }
}
