package com.lumina.reader.core.tts

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsFocusPolicyTest {

    @Test
    fun transientLossPausesOnlyActivePlayback() {
        assertEquals(
            TtsFocusAction.PAUSE_TRANSIENT,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, TtsStatus.PLAYING, false)
        )
        assertEquals(
            TtsFocusAction.PAUSE_TRANSIENT,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, TtsStatus.PREPARING, false)
        )
        assertEquals(
            TtsFocusAction.NONE,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, TtsStatus.PAUSED, false)
        )
    }

    @Test
    fun gainResumesOnlyAfterFocusPause() {
        assertEquals(
            TtsFocusAction.RESUME,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_GAIN, TtsStatus.PAUSED, true)
        )
        assertEquals(
            TtsFocusAction.NONE,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_GAIN, TtsStatus.PAUSED, false)
        )
        assertEquals(
            TtsFocusAction.NONE,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_GAIN, TtsStatus.IDLE, true)
        )
    }

    @Test
    fun permanentLossPausesAndForgetsAutoResume() {
        assertEquals(
            TtsFocusAction.PAUSE_PERMANENT,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS, TtsStatus.PLAYING, false)
        )
        assertEquals(
            TtsFocusAction.PAUSE_PERMANENT,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS, TtsStatus.PAUSED, true)
        )
        assertEquals(
            TtsFocusAction.NONE,
            TtsFocusPolicy.decide(AudioManager.AUDIOFOCUS_LOSS, TtsStatus.IDLE, false)
        )
    }

    @Test
    fun legacyStatusMapping() {
        assertEquals(TtsState.IDLE, TtsStatus.IDLE.toLegacy())
        assertEquals(TtsState.PLAYING, TtsStatus.PREPARING.toLegacy())
        assertEquals(TtsState.PLAYING, TtsStatus.PLAYING.toLegacy())
        assertEquals(TtsState.PAUSED, TtsStatus.PAUSED.toLegacy())
        assertEquals(TtsState.PAUSED, TtsStatus.ERROR.toLegacy())
    }
}
