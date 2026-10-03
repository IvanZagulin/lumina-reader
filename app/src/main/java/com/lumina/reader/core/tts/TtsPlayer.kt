package com.lumina.reader.core.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Platform-free playback state machine behind [TtsController].
 *
 * Not thread-safe: every method and every engine callback must run on the
 * thread of [scope] (the main thread in the app, the test dispatcher in tests).
 *
 * Playback goes sentence by sentence through a [TtsQueue]; the engine always
 * speaks exactly one utterance, so pausing remembers the sentence and resuming
 * repeats it from its start.
 */
internal class TtsPlayer(
    private val scope: CoroutineScope,
    private val engineFactory: SpeechEngineFactory,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idleReleaseDelayMillis: Long = IDLE_RELEASE_DELAY_MILLIS
) {
    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private var engine: SpeechEngine? = null
    private var engineReady = false
    private var engineToken = 0
    private var creatingEngine = false
    private var earlyInitResult: Boolean? = null
    private var appliedLocale: Locale? = null
    private var languageWarning: String? = null

    private var source: TtsChapterSource? = null
    private var queue: TtsQueue? = null
    private var chapterLocale: Locale = TtsLanguageDetector.RUSSIAN
    private var activeUtteranceId: String? = null
    private var utteranceCounter = 0L
    private var consecutiveErrors = 0

    private var chapterLoadJob: Job? = null
    private var chapterLoadToken = 0
    private var sleepJob: Job? = null
    private var releaseJob: Job? = null

    private val status: TtsStatus
        get() = _state.value.status

    private val isPlayingOrPreparing: Boolean
        get() = status == TtsStatus.PLAYING || status == TtsStatus.PREPARING

    fun start(
        bookId: Long?,
        bookTitle: String,
        chapter: TtsChapter,
        startParagraph: Int,
        source: TtsChapterSource
    ) {
        cancelChapterLoad()
        releaseJob?.cancel()
        releaseJob = null
        silence()
        this.source = source
        consecutiveErrors = 0
        appliedLocale = null
        languageWarning = null
        setState {
            it.copy(
                status = TtsStatus.PLAYING,
                bookId = bookId,
                bookTitle = bookTitle,
                errorMessage = null
            )
        }
        installChapter(chapter, startParagraph)
        speakCurrent()
    }

    fun pause() {
        if (!isPlayingOrPreparing) return
        silence()
        setState { it.copy(status = TtsStatus.PAUSED) }
    }

    fun resume() {
        val current = status
        if (current != TtsStatus.PAUSED && current != TtsStatus.ERROR) return
        val q = queue ?: return
        consecutiveErrors = 0
        setState {
            it.copy(
                status = TtsStatus.PLAYING,
                errorMessage = if (current == TtsStatus.ERROR) null else it.errorMessage
            )
        }
        if (chapterLoadJob?.isActive == true) return
        if (q.isFinished) {
            moveToNextChapter()
            return
        }
        speakCurrent()
    }

    fun togglePlayPause() {
        when (status) {
            TtsStatus.PLAYING, TtsStatus.PREPARING -> pause()
            TtsStatus.PAUSED, TtsStatus.ERROR -> resume()
            TtsStatus.IDLE -> Unit
        }
    }

    fun stop() {
        cancelChapterLoad()
        sleepJob?.cancel()
        sleepJob = null
        silence()
        source = null
        queue = null
        consecutiveErrors = 0
        languageWarning = null
        val previous = _state.value
        _state.value = TtsPlaybackState(
            speechRate = previous.speechRate,
            pitch = previous.pitch,
            stopAtChapterEnd = previous.stopAtChapterEnd
        )
        scheduleRelease()
    }

    fun skipToParagraph(index: Int) {
        val q = queue ?: return
        val wasActive = isPlayingOrPreparing
        cancelChapterLoad()
        silence()
        val segment = q.seekToParagraph(index)
        if (segment == null) {
            setState { it.copy(paragraphIndex = index.coerceAtLeast(0), sentenceRange = null) }
            if (wasActive) moveToNextChapter()
            return
        }
        publishPosition(segment)
        if (wasActive) speakCurrent()
    }

    fun nextParagraph() {
        val q = queue ?: return
        if (chapterLoadJob?.isActive == true) return
        val wasActive = isPlayingOrPreparing
        silence()
        val segment = q.nextParagraph()
        if (segment == null) {
            // Explicit request: go to the next chapter even with stopAtChapterEnd.
            moveToNextChapter()
            return
        }
        publishPosition(segment)
        if (wasActive) speakCurrent()
    }

    fun previousParagraph() {
        val q = queue ?: return
        val wasActive = isPlayingOrPreparing
        cancelChapterLoad()
        silence()
        val segment = q.previousParagraph()
        if (segment != null) publishPosition(segment)
        if (wasActive) speakCurrent()
    }

    fun setSpeechRate(rate: Float) {
        if (rate.isNaN()) return
        val value = rate.coerceIn(MIN_RATE, MAX_RATE)
        if (value == _state.value.speechRate) return
        setState { it.copy(speechRate = value) }
        if (engineReady) engine?.let { runSafely { it.setSpeechRate(value) } }
        respeakIfPlaying()
    }

    fun setPitch(pitch: Float) {
        if (pitch.isNaN()) return
        val value = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        if (value == _state.value.pitch) return
        setState { it.copy(pitch = value) }
        if (engineReady) engine?.let { runSafely { it.setPitch(value) } }
        respeakIfPlaying()
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            setState { it.copy(sleepTimerEndsAt = null) }
            return
        }
        val duration = minutes * MILLIS_PER_MINUTE
        setState { it.copy(sleepTimerEndsAt = clock() + duration) }
        sleepJob = scope.launch {
            delay(duration)
            sleepJob = null
            setState { it.copy(sleepTimerEndsAt = null) }
            pause()
        }
    }

    fun setStopAtChapterEnd(enabled: Boolean) {
        setState { it.copy(stopAtChapterEnd = enabled) }
    }

    /** Frees the engine now. Playback that was running is paused. */
    fun releaseEngine() {
        releaseJob?.cancel()
        releaseJob = null
        val current = engine ?: return
        if (isPlayingOrPreparing) {
            silence()
            setState { it.copy(status = TtsStatus.PAUSED) }
        }
        engine = null
        engineReady = false
        engineToken++
        appliedLocale = null
        activeUtteranceId = null
        runSafely { current.shutdown() }
    }

    // ---- Internals ----------------------------------------------------------

    private fun installChapter(chapter: TtsChapter, startParagraph: Int) {
        val q = TtsQueue(chapter.paragraphs)
        val segment = q.seekToParagraph(startParagraph)
        queue = q
        chapterLocale = TtsLanguageDetector.detect(chapter.paragraphs)
        setState {
            it.copy(
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                paragraphIndex = segment?.paragraphIndex ?: startParagraph.coerceAtLeast(0),
                sentenceRange = segment?.range
            )
        }
    }

    private fun publishPosition(segment: TtsSegment) {
        setState { it.copy(paragraphIndex = segment.paragraphIndex, sentenceRange = segment.range) }
    }

    /** Speaks the queue's current sentence, handling chapter end and engine start-up. */
    private fun speakCurrent() {
        val q = queue ?: return
        val segment = q.current()
        if (segment == null) {
            onChapterFinished()
            return
        }
        publishPosition(segment)
        if (!ensureEngine()) {
            if (status != TtsStatus.ERROR) setState { it.copy(status = TtsStatus.PREPARING) }
            return
        }
        val current = engine ?: return
        applyLocale(current)
        utteranceCounter++
        val id = "lumina-tts-$utteranceCounter"
        activeUtteranceId = id
        setState { it.copy(status = TtsStatus.PLAYING) }
        val accepted = try {
            current.speak(segment.text, id)
        } catch (e: Exception) {
            false
        }
        if (!accepted) onUtteranceFailed(id)
    }

    /** Creates the engine when needed. Returns true when it is ready to speak. */
    private fun ensureEngine(): Boolean {
        if (engine != null) return engineReady
        val token = ++engineToken
        engineReady = false
        appliedLocale = null
        creatingEngine = true
        earlyInitResult = null
        val created = try {
            engineFactory.create(Callbacks(token))
        } catch (e: Exception) {
            null
        } finally {
            creatingEngine = false
        }
        if (created == null) {
            earlyInitResult = null
            fail(MESSAGE_ENGINE_UNAVAILABLE)
            return false
        }
        engine = created
        // An engine may report its init result before create() returns.
        val early = earlyInitResult
        earlyInitResult = null
        if (early != null) onEngineInit(token, early, resumePlayback = false)
        return engineReady
    }

    private fun onEngineInit(token: Int, success: Boolean, resumePlayback: Boolean = true) {
        if (token != engineToken) return
        if (creatingEngine) {
            earlyInitResult = success
            return
        }
        if (!success) {
            val failed = engine
            engine = null
            engineReady = false
            engineToken++
            if (failed != null) runSafely { failed.shutdown() }
            if (isPlayingOrPreparing) fail(MESSAGE_ENGINE_UNAVAILABLE)
            return
        }
        val current = engine ?: return
        engineReady = true
        val snapshot = _state.value
        runSafely { current.setSpeechRate(snapshot.speechRate) }
        runSafely { current.setPitch(snapshot.pitch) }
        if (resumePlayback && status == TtsStatus.PREPARING && chapterLoadJob?.isActive != true) {
            speakCurrent()
        }
    }

    private fun applyLocale(current: SpeechEngine) {
        val wanted = chapterLocale
        if (appliedLocale == wanted) return
        appliedLocale = wanted
        val support = try {
            current.setLanguage(wanted)
        } catch (e: Exception) {
            TtsLanguageSupport.NOT_SUPPORTED
        }
        if (support == TtsLanguageSupport.AVAILABLE) {
            val warning = languageWarning
            if (warning != null && _state.value.errorMessage == warning) {
                setState { it.copy(errorMessage = null) }
            }
            languageWarning = null
            return
        }
        runSafely { current.setLanguage(Locale.getDefault()) }
        val message = missingVoiceMessage(wanted)
        languageWarning = message
        setState { it.copy(errorMessage = message) }
    }

    private fun onUtteranceDone(utteranceId: String) {
        if (utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        consecutiveErrors = 0
        if (status != TtsStatus.PLAYING) return
        queue?.advance()
        speakCurrent()
    }

    private fun onUtteranceFailed(utteranceId: String) {
        if (utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            fail(MESSAGE_SPEECH_ERROR)
            return
        }
        if (status != TtsStatus.PLAYING) return
        queue?.advance()
        speakCurrent()
    }

    private fun onChapterFinished() {
        activeUtteranceId = null
        if (_state.value.stopAtChapterEnd) {
            setState { it.copy(status = TtsStatus.PAUSED, sentenceRange = null) }
            return
        }
        moveToNextChapter()
    }

    /**
     * Loads the chapter after the current one (skipping chapters with nothing
     * to say). Playback continues afterwards only if the status is still
     * PLAYING/PREPARING when the chapter arrives.
     */
    private fun moveToNextChapter() {
        val src = source
        if (src == null) {
            stop()
            return
        }
        cancelChapterLoad()
        val token = chapterLoadToken
        val firstIndex = _state.value.chapterIndex + 1
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var index = firstIndex
            var skipped = 0
            while (true) {
                val next: TtsChapter?
                try {
                    next = src.chapter(index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token == chapterLoadToken) fail(MESSAGE_CHAPTER_LOAD_FAILED)
                    return@launch
                }
                if (token != chapterLoadToken) return@launch
                if (next == null) {
                    stop()
                    return@launch
                }
                installChapter(next, 0)
                if (queue?.current() != null) break
                skipped++
                if (skipped >= MAX_EMPTY_CHAPTERS) {
                    stop()
                    return@launch
                }
                index++
            }
            if (isPlayingOrPreparing) speakCurrent()
        }
        chapterLoadJob = job
        job.start()
    }

    private fun cancelChapterLoad() {
        chapterLoadToken++
        chapterLoadJob?.cancel()
        chapterLoadJob = null
    }

    private fun respeakIfPlaying() {
        if (status == TtsStatus.PLAYING && activeUtteranceId != null) {
            silence()
            speakCurrent()
        }
    }

    private fun silence() {
        activeUtteranceId = null
        if (engineReady) engine?.let { runSafely { it.stop() } }
    }

    private fun fail(message: String) {
        silence()
        setState { it.copy(status = TtsStatus.ERROR, errorMessage = message) }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        if (engine == null) {
            releaseJob = null
            return
        }
        releaseJob = scope.launch {
            delay(idleReleaseDelayMillis)
            if (status == TtsStatus.IDLE) releaseEngine()
        }
    }

    private inline fun setState(transform: (TtsPlaybackState) -> TtsPlaybackState) {
        _state.value = transform(_state.value)
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Engine binder calls can fail when the TTS service dies; playback logic must survive.
        }
    }

    private inner class Callbacks(private val token: Int) : SpeechEngineCallbacks {
        override fun onInit(success: Boolean) {
            onEngineInit(token, success)
        }

        override fun onUtteranceDone(utteranceId: String) {
            if (token == engineToken) this@TtsPlayer.onUtteranceDone(utteranceId)
        }

        override fun onUtteranceError(utteranceId: String) {
            if (token == engineToken) onUtteranceFailed(utteranceId)
        }
    }

    companion object {
        const val IDLE_RELEASE_DELAY_MILLIS = 60_000L
        const val MIN_RATE = 0.25f
        const val MAX_RATE = 4f
        const val MIN_PITCH = 0.25f
        const val MAX_PITCH = 4f
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val MAX_EMPTY_CHAPTERS = 50
        private const val MILLIS_PER_MINUTE = 60_000L

        const val MESSAGE_ENGINE_UNAVAILABLE = "Синтез речи недоступен на этом устройстве"
        const val MESSAGE_SPEECH_ERROR = "Ошибка синтеза речи"
        const val MESSAGE_CHAPTER_LOAD_FAILED = "Не удалось загрузить следующую главу"

        private val RUSSIAN_DISPLAY: Locale = Locale.forLanguageTag("ru")

        fun missingVoiceMessage(locale: Locale): String {
            val name = locale.getDisplayLanguage(RUSSIAN_DISPLAY).ifBlank { locale.language }
            return "Нет голоса для языка «$name». Используется голос по умолчанию"
        }
    }
}
