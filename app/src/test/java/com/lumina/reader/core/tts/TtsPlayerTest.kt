package com.lumina.reader.core.tts

import com.lumina.reader.core.model.ParagraphMarkup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlayerTest {

    private class FakeEngine(val callbacks: SpeechEngineCallbacks) : SpeechEngine {
        val spoken = mutableListOf<Pair<String, String>>()
        val languages = mutableListOf<Locale>()
        var support: (Locale) -> TtsLanguageSupport = { TtsLanguageSupport.AVAILABLE }
        var stopCalls = 0
        var lastRate = 1f
        var lastPitch = 1f
        var isShutdown = false

        val lastText: String get() = spoken.last().second
        val lastId: String get() = spoken.last().first

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += utteranceId to text
            return true
        }

        override fun stop() {
            stopCalls++
        }

        override fun setSpeechRate(rate: Float) {
            lastRate = rate
        }

        override fun setPitch(pitch: Float) {
            lastPitch = pitch
        }

        override fun setLanguage(locale: Locale): TtsLanguageSupport {
            languages += locale
            return support(locale)
        }

        override fun shutdown() {
            isShutdown = true
        }

        fun finishCurrent() = callbacks.onUtteranceDone(lastId)
    }

    private class Book(vararg chapters: TtsChapter) : TtsChapterSource {
        val list = chapters.toList()
        val requested = mutableListOf<Int>()

        override suspend fun chapter(index: Int): TtsChapter? {
            requested += index
            return list.getOrNull(index)
        }
    }

    private val engines = mutableListOf<FakeEngine>()
    private val engine: FakeEngine get() = engines.last()

    private val ch0 = TtsChapter(0, "Глава 1", listOf("", "[IMG:a]", "Раз. Два.", "Три."))
    private val ch1 = TtsChapter(1, "Глава 2", listOf("Четыре."))
    private val ch2 = TtsChapter(2, "Пусто", listOf("[IMG:b]", ""))
    private val ch3 = TtsChapter(3, "Глава 4", listOf("Пять."))

    private fun TestScope.newPlayer(): TtsPlayer = TtsPlayer(
        scope = backgroundScope,
        engineFactory = SpeechEngineFactory { callbacks -> FakeEngine(callbacks).also { engines += it } },
        clock = { testScheduler.currentTime }
    )

    private fun TtsPlayer.startReady(chapter: TtsChapter = ch0, start: Int = 0, book: Book = Book(ch0, ch1)) {
        start(7L, "Книга", chapter, start, book)
        engine.callbacks.onInit(true)
    }

    @Test
    fun queuesUntilEngineIsInitialised() = runTest {
        val player = newPlayer()
        player.start(7L, "Книга", ch0, 0, Book(ch0))
        assertEquals(TtsStatus.PREPARING, player.state.value.status)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(2, player.state.value.paragraphIndex)

        engine.callbacks.onInit(true)
        val state = player.state.value
        assertEquals(TtsStatus.PLAYING, state.status)
        assertEquals("Раз.", engine.lastText)
        assertEquals(0..3, state.sentenceRange)
        assertEquals(7L, state.bookId)
        assertEquals("Книга", state.bookTitle)
        assertEquals("Глава 1", state.chapterTitle)
    }

    @Test
    fun startParagraphIsAnOriginalIndex() = runTest {
        val player = newPlayer()
        player.startReady(start = 3)
        assertEquals("Три.", engine.lastText)
        assertEquals(3, player.state.value.paragraphIndex)
    }

    @Test
    fun speaksSentenceBySentenceAcrossChaptersUntilTheEndOfTheBook() = runTest {
        val player = newPlayer()
        val book = Book(ch0, ch1, ch2, ch3)
        player.startReady(book = book)

        assertEquals("Раз.", engine.lastText)
        engine.finishCurrent()
        assertEquals("Два.", engine.lastText)
        assertEquals(2, player.state.value.paragraphIndex)
        assertEquals(5..8, player.state.value.sentenceRange)
        engine.finishCurrent()
        assertEquals("Три.", engine.lastText)
        assertEquals(3, player.state.value.paragraphIndex)

        engine.finishCurrent()
        runCurrent()
        assertEquals("Четыре.", engine.lastText)
        assertEquals(1, player.state.value.chapterIndex)
        assertEquals("Глава 2", player.state.value.chapterTitle)
        assertEquals(0, player.state.value.paragraphIndex)

        engine.finishCurrent()
        runCurrent()
        // Chapter 2 has nothing to read and is skipped.
        assertEquals("Пять.", engine.lastText)
        assertEquals(3, player.state.value.chapterIndex)
        assertEquals(listOf(1, 2, 3), book.requested)

        engine.finishCurrent()
        runCurrent()
        assertEquals(TtsStatus.IDLE, player.state.value.status)
        assertNull(player.state.value.bookId)
    }

    @Test
    fun pauseRemembersSentenceAndIgnoresStaleCallbacks() = runTest {
        val player = newPlayer()
        player.startReady()
        engine.finishCurrent()
        assertEquals("Два.", engine.lastText)
        val staleId = engine.lastId
        val spokenBefore = engine.spoken.size

        player.pause()
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertTrue(engine.stopCalls > 0)

        engine.callbacks.onUtteranceDone(staleId)
        assertEquals(spokenBefore, engine.spoken.size)
        assertEquals(TtsStatus.PAUSED, player.state.value.status)

        player.resume()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
        assertEquals("Два.", engine.lastText)
        assertEquals(spokenBefore + 1, engine.spoken.size)
    }

    @Test
    fun togglePlayPauseSwitchesStates() = runTest {
        val player = newPlayer()
        player.togglePlayPause()
        assertEquals(TtsStatus.IDLE, player.state.value.status)
        player.startReady()
        player.togglePlayPause()
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        player.togglePlayPause()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
    }

    @Test
    fun stopAtChapterEndPausesThenResumeContinuesWithNextChapter() = runTest {
        val player = newPlayer()
        val book = Book(ch0, ch1)
        player.setStopAtChapterEnd(true)
        player.startReady(start = 3, book = book)
        engine.finishCurrent()
        runCurrent()
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertTrue(book.requested.isEmpty())

        player.resume()
        runCurrent()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
        assertEquals("Четыре.", engine.lastText)
        assertEquals(1, player.state.value.chapterIndex)
    }

    @Test
    fun missingVoiceFallsBackToDefaultWithMessage() = runTest {
        val player = newPlayer()
        player.start(7L, "Книга", ch0, 0, Book(ch0))
        engine.support = { if (it == TtsLanguageDetector.RUSSIAN) TtsLanguageSupport.MISSING_DATA else TtsLanguageSupport.AVAILABLE }
        engine.callbacks.onInit(true)

        val state = player.state.value
        assertEquals(TtsStatus.PLAYING, state.status)
        assertEquals("Раз.", engine.lastText)
        assertTrue(state.errorMessage!!.startsWith("Нет голоса для языка"))
        assertEquals(listOf(TtsLanguageDetector.RUSSIAN, Locale.getDefault()), engine.languages)
    }

    @Test
    fun englishChapterSelectsEnglishVoice() = runTest {
        val player = newPlayer()
        val english = TtsChapter(0, "One", listOf("Hello there. How are you?"))
        player.startReady(chapter = english, book = Book(english))
        assertEquals(TtsLanguageDetector.ENGLISH, engine.languages.first())
        assertEquals("Hello there.", engine.lastText)
        assertNull(player.state.value.errorMessage)
    }

    @Test
    fun rateAndPitchApplyImmediatelyAndRespeakTheSentence() = runTest {
        val player = newPlayer()
        player.setSpeechRate(2f)
        player.startReady()
        assertEquals(2f, engine.lastRate)

        engine.finishCurrent()
        val spoken = engine.spoken.size
        player.setSpeechRate(1.5f)
        assertEquals(1.5f, engine.lastRate)
        assertEquals(1.5f, player.state.value.speechRate)
        assertEquals(spoken + 1, engine.spoken.size)
        assertEquals("Два.", engine.lastText)

        player.setPitch(0.8f)
        assertEquals(0.8f, engine.lastPitch)
        assertEquals(spoken + 2, engine.spoken.size)
        assertEquals("Два.", engine.lastText)

        player.setSpeechRate(100f)
        assertEquals(TtsPlayer.MAX_RATE, player.state.value.speechRate)
    }

    @Test
    fun sleepTimerPausesWhenItExpires() = runTest {
        val player = newPlayer()
        player.startReady()
        val now = testScheduler.currentTime
        player.setSleepTimer(10)
        assertEquals(now + 10 * 60_000L, player.state.value.sleepTimerEndsAt)

        advanceTimeBy(10 * 60_000L - 1)
        runCurrent()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertNull(player.state.value.sleepTimerEndsAt)
    }

    @Test
    fun cancelledSleepTimerDoesNothing() = runTest {
        val player = newPlayer()
        player.startReady()
        player.setSleepTimer(5)
        player.setSleepTimer(null)
        assertNull(player.state.value.sleepTimerEndsAt)
        advanceTimeBy(6 * 60_000L)
        runCurrent()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
    }

    @Test
    fun engineIsReleasedAfterBeingIdleForAMinute() = runTest {
        val player = newPlayer()
        player.setSpeechRate(1.25f)
        player.startReady()
        player.stop()
        val state = player.state.value
        assertEquals(TtsStatus.IDLE, state.status)
        assertEquals(1.25f, state.speechRate)
        assertNull(state.sentenceRange)
        assertFalse(engine.isShutdown)

        advanceTimeBy(59_000)
        runCurrent()
        assertFalse(engine.isShutdown)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(engine.isShutdown)

        player.start(7L, "Книга", ch0, 0, Book(ch0))
        assertEquals(2, engines.size)
        assertEquals(TtsStatus.PREPARING, player.state.value.status)
    }

    @Test
    fun restartingWithinTheIdleWindowReusesTheEngine() = runTest {
        val player = newPlayer()
        player.startReady()
        player.stop()
        advanceTimeBy(30_000)
        player.start(7L, "Книга", ch0, 0, Book(ch0))
        assertEquals(1, engines.size)
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(engine.isShutdown)
    }

    @Test
    fun engineInitFailureReportsErrorAndResumeRetries() = runTest {
        val player = newPlayer()
        player.start(7L, "Книга", ch0, 0, Book(ch0))
        engine.callbacks.onInit(false)
        assertEquals(TtsStatus.ERROR, player.state.value.status)
        assertEquals(TtsPlayer.MESSAGE_ENGINE_UNAVAILABLE, player.state.value.errorMessage)
        assertTrue(engines.first().isShutdown)

        player.resume()
        assertEquals(2, engines.size)
        assertEquals(TtsStatus.PREPARING, player.state.value.status)
        engine.callbacks.onInit(true)
        assertEquals("Раз.", engine.lastText)
        assertNull(player.state.value.errorMessage)
    }

    @Test
    fun repeatedUtteranceErrorsStopWithError() = runTest {
        val player = newPlayer()
        player.startReady()
        engine.callbacks.onUtteranceError(engine.lastId)
        assertEquals("Два.", engine.lastText)
        engine.callbacks.onUtteranceError(engine.lastId)
        assertEquals("Три.", engine.lastText)
        engine.callbacks.onUtteranceError(engine.lastId)
        assertEquals(TtsStatus.ERROR, player.state.value.status)
        assertEquals(TtsPlayer.MESSAGE_SPEECH_ERROR, player.state.value.errorMessage)
    }

    @Test
    fun skipAndParagraphNavigation() = runTest {
        val player = newPlayer()
        player.startReady(book = Book(ch0, ch1))

        player.skipToParagraph(3)
        assertEquals("Три.", engine.lastText)
        assertEquals(3, player.state.value.paragraphIndex)

        player.previousParagraph()
        assertEquals("Раз.", engine.lastText)
        assertEquals(2, player.state.value.paragraphIndex)

        player.nextParagraph()
        assertEquals("Три.", engine.lastText)

        player.nextParagraph()
        runCurrent()
        assertEquals("Четыре.", engine.lastText)
        assertEquals(1, player.state.value.chapterIndex)
    }

    @Test
    fun navigationWhilePausedOnlyMovesThePosition() = runTest {
        val player = newPlayer()
        player.startReady()
        player.pause()
        val spoken = engine.spoken.size
        player.skipToParagraph(3)
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertEquals(3, player.state.value.paragraphIndex)
        assertEquals(spoken, engine.spoken.size)

        player.resume()
        assertEquals("Три.", engine.lastText)
    }

    @Test
    fun releaseEngineWhilePlayingPauses() = runTest {
        val player = newPlayer()
        player.startReady()
        player.releaseEngine()
        assertTrue(engine.isShutdown)
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        player.resume()
        assertEquals(2, engines.size)
        assertEquals(TtsStatus.PREPARING, player.state.value.status)
    }

    @Test
    fun observerPausingDuringResumeIsNotOverridden() = runTest {
        val player = newPlayer()
        player.startReady()
        player.pause()
        val spoken = engine.spoken.size

        var armed = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect {
                // Synchronous observer, like a Main.immediate collector in the app.
                if (armed && it.status == TtsStatus.PLAYING) {
                    armed = false
                    player.pause()
                }
            }
        }

        player.resume()
        assertFalse(armed)
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertEquals(spoken, engine.spoken.size)

        player.resume()
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
        assertEquals(spoken + 1, engine.spoken.size)
    }

    @Test
    fun pauseWhileChoosingTheVoiceSpeaksNothing() = runTest {
        val player = newPlayer()
        player.start(7L, "Книга", ch0, 0, Book(ch0))
        var pauseOnLanguage = true
        engine.support = {
            if (pauseOnLanguage) {
                pauseOnLanguage = false
                player.pause()
            }
            TtsLanguageSupport.AVAILABLE
        }
        engine.callbacks.onInit(true)
        assertEquals(TtsStatus.PAUSED, player.state.value.status)
        assertTrue(engine.spoken.isEmpty())

        player.resume()
        assertEquals("Раз.", engine.lastText)
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
    }

    @Test
    fun footnoteLabelsAreNotSpoken() = runTest {
        val player = newPlayer()
        val paragraph = "Конец." + ParagraphMarkup.noteRef("1", "n1") + " Новая фраза."
        val chapter = TtsChapter(0, "Сноски", listOf(paragraph))
        player.startReady(chapter = chapter, book = Book(chapter))
        assertEquals("Конец.", engine.lastText)
        engine.finishCurrent()
        assertEquals("Новая фраза.", engine.lastText)
        val plain = ParagraphMarkup.plainText(paragraph)
        val range = player.state.value.sentenceRange!!
        assertEquals("Новая фраза.", plain.substring(range.first, range.last + 1))
    }

    @Test
    fun synchronousInitFromFactoryIsHandled() = runTest {
        val player = TtsPlayer(
            scope = backgroundScope,
            engineFactory = SpeechEngineFactory { callbacks ->
                FakeEngine(callbacks).also {
                    engines += it
                    callbacks.onInit(true)
                }
            }
        )
        player.start(1L, "Книга", ch0, 0, Book(ch0))
        assertEquals(TtsStatus.PLAYING, player.state.value.status)
        assertEquals(1, engine.spoken.size)
        assertEquals("Раз.", engine.lastText)
    }
}
