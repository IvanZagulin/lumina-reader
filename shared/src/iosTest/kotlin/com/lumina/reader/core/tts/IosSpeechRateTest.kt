package com.lumina.reader.core.tts

import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The player's speech rates on AVSpeechUtterance's scale. */
class IosSpeechRateTest {

    @Test
    fun normalSpeedIsTheSystemDefault() {
        assertEquals(AVSpeechUtteranceDefaultSpeechRate, avSpeechRate(1f))
    }

    @Test
    fun theSheetsRangeSpansTheWholeScale() {
        assertEquals(AVSpeechUtteranceDefaultSpeechRate / 2f, avSpeechRate(0.5f))
        assertEquals(AVSpeechUtteranceMaximumSpeechRate, avSpeechRate(3f))
        // The player allows up to 4×; it stays at the maximum.
        assertEquals(AVSpeechUtteranceMaximumSpeechRate, avSpeechRate(4f))
        assertEquals(AVSpeechUtteranceMinimumSpeechRate, avSpeechRate(0f))
    }

    @Test
    fun fasterIsAlwaysFaster() {
        val rates = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).map(::avSpeechRate)
        assertTrue(rates.zipWithNext().all { (slower, faster) -> slower < faster })
    }
}
