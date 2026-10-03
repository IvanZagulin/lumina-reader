package com.lumina.reader.core.tts

import android.content.Context
import android.media.AudioManager
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat

/** What playback should do after an audio focus change. */
internal enum class TtsFocusAction { NONE, PAUSE_TRANSIENT, PAUSE_PERMANENT, RESUME }

/** Pure audio focus rules: pause on any loss (no ducking for speech), resume only after a transient loss. */
internal object TtsFocusPolicy {
    fun decide(focusChange: Int, status: TtsStatus, pausedByFocusLoss: Boolean): TtsFocusAction {
        val playing = status == TtsStatus.PLAYING || status == TtsStatus.PREPARING
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN ->
                if (pausedByFocusLoss && status == TtsStatus.PAUSED) TtsFocusAction.RESUME else TtsFocusAction.NONE
            AudioManager.AUDIOFOCUS_LOSS ->
                if (playing || pausedByFocusLoss) TtsFocusAction.PAUSE_PERMANENT else TtsFocusAction.NONE
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (playing) TtsFocusAction.PAUSE_TRANSIENT else TtsFocusAction.NONE
            else -> TtsFocusAction.NONE
        }
    }
}

/** Holds the media audio focus request used while reading aloud. */
internal class TtsAudioFocus(context: Context, onFocusChange: (Int) -> Unit) {
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val request: AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) })
            .build()

    var hasFocus: Boolean = false
        private set

    /** Returns true when focus is held after the call. */
    fun request(): Boolean {
        if (hasFocus) return true
        val manager = audioManager ?: return true
        hasFocus = try {
            AudioManagerCompat.requestAudioFocus(manager, request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            // Never block speech because the audio service misbehaves.
            true
        }
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        hasFocus = false
        val manager = audioManager ?: return
        try {
            AudioManagerCompat.abandonAudioFocusRequest(manager, request)
        } catch (e: Exception) {
            // Nothing to recover.
        }
    }

    /** The system took focus away permanently; there is nothing left to abandon. */
    fun onLost() {
        hasFocus = false
    }
}
