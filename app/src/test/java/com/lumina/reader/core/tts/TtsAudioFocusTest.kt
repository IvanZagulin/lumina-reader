package com.lumina.reader.core.tts

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsAudioFocusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun transientLossKeepsTheRequestUntilAbandoned() {
        val focus = TtsAudioFocus(context) {}
        assertFalse(focus.hasFocus)

        assertTrue(focus.request())
        assertTrue(focus.hasFocus)

        focus.onTransientLoss()
        assertFalse(focus.hasFocus)
        // Still registered, so the system's AUDIOFOCUS_GAIN restores it.
        focus.onGain()
        assertTrue(focus.hasFocus)

        focus.abandon()
        assertFalse(focus.hasFocus)
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)

        // A gain after abandoning does not pretend we hold focus.
        focus.onGain()
        assertFalse(focus.hasFocus)
    }

    @Test
    fun refusedRequestIsReportedAndNotAbandoned() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = TtsAudioFocus(context) {}

        assertFalse(focus.request())
        assertFalse(focus.hasFocus)

        focus.abandon()
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)

        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(focus.request())
        assertTrue(focus.hasFocus)
    }
}
