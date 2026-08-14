package com.lumina.reader.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsState {
    IDLE,
    PLAYING,
    PAUSED
}

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var textQueue: List<String> = emptyList()
    private var currentIndex = 0
    private var speechRate = 1.0f

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = Locale("ru", "RU")
            tts?.setSpeechRate(speechRate)
            setupListener()
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = TtsState.PLAYING
            }

            override fun onDone(utteranceId: String?) {
                currentIndex++
                if (currentIndex < textQueue.size) {
                    speakCurrent()
                } else {
                    _state.value = TtsState.IDLE
                }
            }

            override fun onError(utteranceId: String?) {
                _state.value = TtsState.IDLE
            }
        })
    }

    fun play(paragraphs: List<String>, startIndex: Int = 0) {
        if (!isInitialized) return
        textQueue = paragraphs.filter { it.isNotBlank() }
        currentIndex = startIndex.coerceIn(0, (textQueue.size - 1).coerceAtLeast(0))
        if (textQueue.isNotEmpty()) {
            _state.value = TtsState.PLAYING
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        if (currentIndex in textQueue.indices) {
            val text = textQueue[currentIndex]
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
        }
    }

    fun pause() {
        tts?.stop()
        _state.value = TtsState.PAUSED
    }

    fun resume() {
        if (currentIndex < textQueue.size) {
            _state.value = TtsState.PLAYING
            speakCurrent()
        }
    }

    fun stop() {
        tts?.stop()
        _state.value = TtsState.IDLE
        textQueue = emptyList()
        currentIndex = 0
    }

    fun setRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(rate)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
