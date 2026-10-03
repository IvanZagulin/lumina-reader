package com.lumina.reader.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * [SpeechEngine] over the platform [TextToSpeech]. Every callback is re-posted
 * to the main thread, where [TtsPlayer] lives.
 */
internal class AndroidSpeechEngine(
    context: Context,
    private val callbacks: SpeechEngineCallbacks
) : SpeechEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var released = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val success = status == TextToSpeech.SUCCESS
        mainHandler.post { if (!released) callbacks.onInit(success) }
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                mainHandler.post { if (!released) callbacks.onUtteranceDone(id) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val id = utteranceId ?: return
                mainHandler.post { if (!released) callbacks.onUtteranceError(id) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val id = utteranceId ?: return
                mainHandler.post { if (!released) callbacks.onUtteranceError(id) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // Only happens after our own stop(); the player already moved on.
            }
        })
    }

    override fun speak(text: String, utteranceId: String): Boolean =
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() {
        tts.stop()
    }

    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch)
    }

    override fun setLanguage(locale: Locale): TtsLanguageSupport {
        val result = tts.setLanguage(locale)
        return when {
            result == TextToSpeech.LANG_MISSING_DATA -> TtsLanguageSupport.MISSING_DATA
            result == TextToSpeech.LANG_NOT_SUPPORTED -> TtsLanguageSupport.NOT_SUPPORTED
            result >= TextToSpeech.LANG_AVAILABLE -> TtsLanguageSupport.AVAILABLE
            else -> TtsLanguageSupport.NOT_SUPPORTED
        }
    }

    override fun shutdown() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            tts.stop()
        } finally {
            tts.shutdown()
        }
    }
}
