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

/**
 * Holds the media audio focus request used while reading aloud.
 *
 * [hasFocus] is true while we may speak. [registered] stays true after a
 * transient loss, so the system still sends us AUDIOFOCUS_GAIN when the
 * call or notification is over.
 */
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

    private var registered = false

    /**
     * Asks for focus unless it is already held. Returns true when focus is held
     * after the call. Also used after a transient loss, when the user resumes
     * by hand: during a phone call the request is refused.
     */
    fun request(): Boolean {
        if (hasFocus) return true
        val manager = audioManager
        if (manager == null) {
            hasFocus = true
            return true
        }
        val granted = try {
            AudioManagerCompat.requestAudioFocus(manager, request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            // Never block speech because the audio service misbehaves.
            true
        }
        hasFocus = granted
        if (granted) registered = true
        return granted
    }

    /** Gives focus back to the system (pause by the user, stop, error, permanent loss). */
    fun abandon() {
        hasFocus = false
        if (!registered) return
        registered = false
        val manager = audioManager ?: return
        try {
            AudioManagerCompat.abandonAudioFocusRequest(manager, request)
        } catch (e: Exception) {
            // Nothing to recover.
        }
    }

    /** Focus was taken for a while; stay registered to hear about the gain. */
    fun onTransientLoss() {
        hasFocus = false
    }

    /** The system gave focus back after a transient loss. */
    fun onGain() {
        if (registered) hasFocus = true
    }
}
