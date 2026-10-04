package com.lumina.reader.core.tts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lumina.reader.MainActivity
import com.lumina.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Foreground service that keeps read-aloud alive in the background. It owns
 * the media session (lock screen, headset and Bluetooth controls) and the
 * media-style notification, and stops itself when playback goes IDLE or
 * stays paused for [PAUSE_TIMEOUT_MS].
 *
 * Started by [TtsController]; it never drives playback on its own.
 */
class TtsPlaybackService : Service() {

    private data class NotificationModel(
        val status: TtsStatus,
        val bookId: Long?,
        val bookTitle: String,
        val chapterTitle: String,
        val errorMessage: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSessionCompat? = null
    private var noisyReceiverRegistered = false
    private var stopping = false
    private var pauseTimeout: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) TtsController.pause()
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            TtsController.resume()
        }

        override fun onPause() {
            TtsController.pause()
        }

        override fun onStop() {
            TtsController.stop()
        }

        override fun onSkipToNext() {
            TtsController.nextParagraph()
        }

        override fun onSkipToPrevious() {
            TtsController.previousParagraph()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == CUSTOM_ACTION_STOP) TtsController.stop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TtsController.init(this)
        createChannel(this)

        val session = MediaSessionCompat(this, MEDIA_SESSION_TAG)
        session.setCallback(sessionCallback)
        session.isActive = true
        mediaSession = session

        // startForegroundService() requires startForeground() promptly, even if
        // playback already stopped in the meantime.
        val initial = modelOf(TtsController.state.value)
        promoteToForeground(initial)

        try {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register noisy receiver", e)
        }

        scope.launch {
            TtsController.state
                .map { modelOf(it) }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> TtsController.togglePlayPause()
            ACTION_NEXT -> TtsController.nextParagraph()
            ACTION_PREVIOUS -> TtsController.previousParagraph()
            ACTION_STOP -> TtsController.stop()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps an active reading going, but a paused or
        // failed session would otherwise leave a notification nobody asked for.
        val status = TtsController.state.value.status
        if (status != TtsStatus.PLAYING && status != TtsStatus.PREPARING) TtsController.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (e: Exception) {
                // Already gone.
            }
            noisyReceiverRegistered = false
        }
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TtsController.onServiceDestroyed()
        super.onDestroy()
    }

    // ---- Rendering --------------------------------------------------------------

    private fun modelOf(state: TtsPlaybackState) = NotificationModel(
        status = state.status,
        bookId = state.bookId,
        bookTitle = state.bookTitle,
        chapterTitle = state.chapterTitle,
        errorMessage = state.errorMessage
    )

    private fun render(model: NotificationModel) {
        if (model.status == TtsStatus.IDLE) {
            stopPlayback()
            return
        }
        if (stopping) return
        schedulePauseTimeout(model.status)
        updateSession(model)
        postNotification(buildNotification(model))
    }

    /** A pause nobody comes back to ends the session, like a stop from the notification. */
    private fun schedulePauseTimeout(status: TtsStatus) {
        if (status != TtsStatus.PAUSED) {
            pauseTimeout?.cancel()
            pauseTimeout = null
            return
        }
        if (pauseTimeout?.isActive == true) return
        pauseTimeout = scope.launch {
            delay(PAUSE_TIMEOUT_MS)
            if (TtsController.state.value.status == TtsStatus.PAUSED) TtsController.stop()
        }
    }

    private fun stopPlayback() {
        if (stopping) return
        stopping = true
        mediaSession?.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(model: NotificationModel) {
        updateSession(model)
        val notification = buildNotification(model)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14+ requires the type to match the manifest declaration.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException on Android 12+.
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun postNotification(notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the permission the notification is hidden anyway; playback keeps working.
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification update refused", e)
        }
    }

    private fun updateSession(model: NotificationModel) {
        val session = mediaSession ?: return
        val playing = model.status == TtsStatus.PLAYING || model.status == TtsStatus.PREPARING
        val playbackState = when (model.status) {
            TtsStatus.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TtsStatus.PREPARING -> PlaybackStateCompat.STATE_BUFFERING
            TtsStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TtsStatus.ERROR -> PlaybackStateCompat.STATE_ERROR
            TtsStatus.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                playbackState,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    CUSTOM_ACTION_STOP,
                    "Остановить",
                    android.R.drawable.ic_menu_close_clear_cancel
                ).build()
            )
        if (model.status == TtsStatus.ERROR) {
            builder.setErrorMessage(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                model.errorMessage ?: "Ошибка чтения вслух"
            )
        }
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleOf(model))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, model.chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, model.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titleOf(model))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, model.chapterTitle)
                .build()
        )
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val playing = model.status == TtsStatus.PLAYING || model.status == TtsStatus.PREPARING
        val text = when (model.status) {
            TtsStatus.ERROR -> model.errorMessage ?: "Ошибка чтения вслух"
            TtsStatus.PREPARING -> "Подготовка синтеза речи…"
            else -> model.chapterTitle
        }
        val playPause = if (playing) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Пауза", serviceIntent(ACTION_PLAY_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Слушать", serviceIntent(ACTION_PLAY_PAUSE))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titleOf(model))
            .setContentText(text)
            .setContentIntent(contentIntent(model.bookId))
            .setDeleteIntent(serviceIntent(ACTION_STOP))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Назад", serviceIntent(ACTION_PREVIOUS))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "Дальше", serviceIntent(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", serviceIntent(ACTION_STOP))
        val style = MediaNotificationCompat.MediaStyle().setShowActionsInCompactView(1, 2, 3)
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        builder.setStyle(style)
        return builder.build()
    }

    private fun titleOf(model: NotificationModel): String = model.bookTitle.ifBlank { CHANNEL_NAME }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun contentIntent(bookId: Long?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (bookId != null) putExtra(TtsController.EXTRA_OPEN_BOOK_ID, bookId)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "tts_playback"
        const val CHANNEL_NAME = "Чтение вслух"
        const val NOTIFICATION_ID = 7301

        const val ACTION_PLAY_PAUSE = "com.lumina.reader.tts.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.lumina.reader.tts.action.NEXT"
        const val ACTION_PREVIOUS = "com.lumina.reader.tts.action.PREVIOUS"
        const val ACTION_STOP = "com.lumina.reader.tts.action.STOP"

        private const val CUSTOM_ACTION_STOP = "com.lumina.reader.tts.custom.STOP"
        private const val MEDIA_SESSION_TAG = "LuminaTts"
        private const val REQUEST_CONTENT = 7302
        private const val TAG = "TtsPlaybackService"
        private const val PAUSE_TIMEOUT_MS = 30L * 60 * 1000

        internal fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Управление чтением книги вслух"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }
}
