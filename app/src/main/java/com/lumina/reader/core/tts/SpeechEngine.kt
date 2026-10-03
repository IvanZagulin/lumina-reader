package com.lumina.reader.core.tts

import java.util.Locale

/** Result of asking the engine for a voice. */
internal enum class TtsLanguageSupport { AVAILABLE, MISSING_DATA, NOT_SUPPORTED }

/**
 * The parts of a speech engine [TtsPlayer] needs. The Android implementation
 * is [AndroidSpeechEngine]; tests use a fake.
 *
 * Methods other than [shutdown] are only called after
 * [SpeechEngineCallbacks.onInit] reported success.
 */
internal interface SpeechEngine {
    /** Replaces whatever is being spoken. Returns false if the engine rejected the request. */
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)
    fun setLanguage(locale: Locale): TtsLanguageSupport
    fun shutdown()
}

/** Engine events; implementations must deliver them on the player's (main) thread. */
internal interface SpeechEngineCallbacks {
    fun onInit(success: Boolean)
    fun onUtteranceDone(utteranceId: String)
    fun onUtteranceError(utteranceId: String)
}

internal fun interface SpeechEngineFactory {
    fun create(callbacks: SpeechEngineCallbacks): SpeechEngine
}
